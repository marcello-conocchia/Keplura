<script setup lang="ts">
import { sourcePageLabel } from "../source-preview";
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { useAuthStore } from "../auth-store";
import {
  CHAT_PROMPT_MAX_LENGTH,
  createConversation,
  deleteConversation,
  fetchTranscript,
  serverUrl,
  streamChatTurn,
  type ChatCitation,
  type ChatMessageDto,
} from "../api";
import { rateAnswer } from "../api-manage";
import { desktopFetch } from "../transport";
import { DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuTrigger } from "reka-ui";
import Button from "./ui/Button.vue";
import SourceFreshness from "./ui/SourceFreshness.vue";
import AppIcon from "./ui/AppIcon.vue";
import StatusPill from "./ui/StatusPill.vue";
import MarkdownAnswer from "./ui/MarkdownAnswer.vue";
import { copyText } from "./ui/clipboard";
import SourcePreview from "./SourcePreview.vue";
import TemplateMenu from "./chat/TemplateMenu.vue";
import ChatContextBar from "./chat/ChatContextBar.vue";
import { webSearchStatus } from "../productivity-api";
import type { SourceSelection } from "../source-preview";
import { tr, dateLocale, serverText } from "../i18n";

/**
 * Chat panel (M4b; restyled UI-POLISH-1 card §4; DESIGN.md §5 Chat + mockup
 * panel 4; CHAT-POLISH card §1–§6). Conversations are personal threads inside
 * the selected space; an optional KB turns the thread into a grounded RAG
 * chat with clickable citations. Everything is real data through api.ts only
 * — server-side persistence and the gateway stream, no mock replies.
 *
 * CHAT-POLISH (presentation-only — api.ts/stores/transport untouched):
 *  §1 assistant answers render as SANITISED Markdown (MarkdownAnswer.vue:
 *     marked + DOMPurify, no raw HTML/styles, http(s)/mailto-only links);
 *     fenced code blocks get an attached Copy button (delegated click).
 *  §2 per-message actions: copy full text (user + assistant), retry =
 *     re-send the LAST user message through the EXISTING send path (a new
 *     user turn + new assistant turn — no server edit endpoints, which is
 *     why there is no message EDIT: the transcript is append-only).
 *  §3 sources are quiet chips as their own row; click expands a detail line
 *     carrying the score (never inline in the reading flow) and the only
 *     document action that exists server-side: the download URL (GET,
 *     session cookie via top-level navigation — no new endpoints).
 *  §4 user block keeps the 7 % tint + gains a "Du" marker; the model tag is
 *     a small badge at the top-right of the AI answer; "fertig" stays a
 *     dezent pill.
 *  §5 empty state: examples are real bordered chips that fill the composer
 *     and focus it; the conversation column collapses at 0 threads (CSS
 *     class on the grid only — the aside markup/testid stay attached).
 *  §6 streaming renders PLAIN text with a pulsing caret while tokens
 *     arrive and switches to Markdown only on the final (server-persisted)
 *     re-read. Deliberate: re-running marked+DOMPurify per delta would
 *     replace the whole block on every token (visible flicker, lost text
 *     selection, racy code-block decoration) — the card allows this
 *     fallback explicitly, this comment is the documentation.
 *
 * Honesty rule: the transcript shown after every turn is the SERVER's
 * persisted rows (re-read), never a client-side guess — an aborted or failed
 * turn displays its real persisted status.
 */
const auth = useAuthStore();

// Conversation LIST state lives in the auth store (tenant teardown via
// dropExpiredSession → clearKnowledge, M3e pattern). Open transcript and
// streaming stay view-local: they are re-readable server-side at any time.
const activeId = ref<number | null>(null);
const messages = ref<ChatMessageDto[]>([]);
const prompt = ref("");
const composerKb = ref<number | null>(null);
const contextBusy = ref(false);
const listError = ref("");

const conversations = computed(() => auth.conversations);

/** Optimistic streaming state for the answer currently being generated. */
const streaming = ref(false);
const streamedText = ref("");
const streamCitations = ref<ChatCitation[]>([]);
const notice = ref("");
/** Neutral hint line (link refusal); NOT the error-styled notice (§1 links). */
const linkHint = ref("");
let linkHintTimer: ReturnType<typeof setTimeout> | null = null;

let stopController: AbortController | null = null;
const transcriptEl = ref<HTMLElement | null>(null);
const composerEl = ref<HTMLTextAreaElement | null>(null);

const active = computed(
  () => conversations.value.find((c) => c.id === activeId.value) ?? null,
);
watch([() => active.value?.knowledgeBaseId, () => auth.selectedKbId, activeId], () => {
  composerKb.value = activeId.value !== null ? active.value?.knowledgeBaseId ?? null : null;
}, {immediate: true});
async function changeKnowledgeContext() {
  if (streaming.value || contextBusy.value || activeId.value === null) return;
  if (composerKb.value === (active.value?.knowledgeBaseId ?? null)) return;
  const draft=prompt.value;
  contextBusy.value=true;
  try {
    if (!(await newConversation(composerKb.value!==null))) composerKb.value=active.value?.knowledgeBaseId ?? null;
  } finally { prompt.value=draft; contextBusy.value=false; }
  await nextTick(); composerEl.value?.focus();
}
const canSend = computed(
  () =>
    prompt.value.trim() !== "" &&
    !streaming.value &&
    !contextBusy.value &&
    auth.selectedOrgId !== null && auth.selectedSpaceId !== null,
);

/** True when the active conversation is a grounded (KB) chat — the composer
 *  context line and empty-state copy read this, never a raw kb id (card §4). */
const activeHasKb = computed(
  () => (active.value?.knowledgeBaseId ?? null) !== null,
);

/** Human label of the knowledge selection the composer will use (name, not
 *  "KB-ID"): the active RAG conversation's kb, else the panel-selected one. */
const knowledgeLabel = computed(() => auth.kbs.find(k => k.id === composerKb.value)?.name ?? null);

function resizeComposer() {
  const input = composerEl.value;
  if (!input) return;
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight, 200)}px`;
}
watch(prompt, () => void nextTick(resizeComposer));
function composerKeydown(event: KeyboardEvent) {
  if (event.key !== "Enter" || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey || event.isComposing) return;
  event.preventDefault();
  void send();
}

function scoped(): { orgId: number; spaceId: number } | null {
  if (auth.selectedOrgId === null || auth.selectedSpaceId === null) {
    return null;
  }
  return { orgId: auth.selectedOrgId, spaceId: auth.selectedSpaceId };
}

async function loadList(keepActive = true) {
  const s = scoped();
  if (!s) {
    resetTranscript();
    return;
  }
  const outcome = await auth.loadConversations();
  if (outcome.kind === "ok") {
    listError.value = "";
    if (
      !keepActive ||
      activeId.value === null ||
      !conversations.value.some((c) => c.id === activeId.value)
    ) {
      activeId.value =
        conversations.value.length > 0 ? conversations.value[0].id : null;
    }
  } else if (outcome.kind === "expired") {
    // the store already dropped the session (dropExpiredSession clears the
    // conversation list too — M3e pattern teardown)
    activeId.value = null;
    resetTranscript();
  } else {
    listError.value = tr("Unterhaltungen konnten nicht geladen werden.");
  }
}

function resetTranscript() {
  messages.value = [];
  streamedText.value = "";
  streamCitations.value = [];
}

async function openConversation(id: number | null) {
  if (stopController) return; // never switch mid-stream: sending blocks first
  activeId.value = id;
  resetTranscript();
  if (id === null) return;
  const s = scoped();
  if (!s) return;
  const outcome = await fetchTranscript(s.orgId, id);
  if (outcome.kind === "ok") {
    messages.value = outcome.transcript.messages;
    await scrollToBottom();
  } else if (outcome.kind === "gone") {
    // deleted elsewhere / server-side prune: drop it from the list honestly
    await loadList(false);
  } else if (outcome.kind === "expired") {
    // M3e teardown pattern: a 401 IS the session dying — drop it so the app
    // renders the login view, a notice alone would strand a dead session.
    auth.dropExpiredSession();
    activeId.value = null;
    resetTranscript();
  } else {
    notice.value = tr("Unterhaltung konnte nicht geladen werden.");
  }
}

// REDESIGN v3: Verlauf lebt in der Hauptsidebar — Auswahl/Löschen kommen als Anfragen.
watch(activeId, (id) => {
  auth.activeConversationId = id;
}, { immediate: true });
watch(
  () => auth.conversationRequest,
  (req) => {
    if (!req) return;
    if (req.kind === "open") void openConversation(req.id);
    else void removeConversation(req.id);
  },
);

// Ein neuer Chat startet neutral. Wissen wird bei Bedarf in der Eingabe ergänzt.
watch(
  () => auth.newChatRequest,
  () => {
    if (!streaming.value && !contextBusy.value) void newConversation(false);
  },
);

/** Websuche (CHAT-02): Schalter nur, wenn der Server sie eingerichtet hat. */
const webAvailable = ref(false);
const webOn = ref(false);
watch(() => auth.selectedOrgId, async (o) => {
  webAvailable.value = false;
  if (o === null) return;
  const r = await webSearchStatus(o);
  webAvailable.value = r.kind === "ok" && r.value.enabled;
}, { immediate: true });

/** Datei/Wissensbasis vor der ersten Frage: Unterhaltung bei Bedarf anlegen. */
async function ensureConversation(): Promise<number | null> {
  if (activeId.value !== null) return activeId.value;
  if (contextBusy.value) return null;
  contextBusy.value = true;
  try {
    return (await newConversation(composerKb.value !== null)) ? activeId.value : null;
  } finally {
    contextBusy.value = false;
  }
}

async function newConversation(withKb: boolean): Promise<boolean> {
  const s = scoped();
  if (!s) return false;
  notice.value = "";
  const kbId = withKb ? composerKb.value : null;
  const outcome = await createConversation(s.orgId, s.spaceId, kbId);
  if (outcome.kind === "created") {
    auth.rememberConversation(outcome.conversation);
    activeId.value = outcome.conversation.id;
    resetTranscript();
    return true;
  } else if (outcome.kind === "parent-gone") {
    notice.value = tr("Bereich nicht verfügbar — bitte Organisation/Bereich wählen.");
    await loadList(false);
  } else if (outcome.kind === "expired") {
    auth.dropExpiredSession(); // -> login view (M3e pattern, see openConversation)
    activeId.value = null;
    resetTranscript();
  } else {
    notice.value = tr("Unterhaltung konnte nicht angelegt werden.");
  }
  return false;
}

async function removeConversation(id: number) {
  const s = scoped();
  if (!s) return;
  const outcome = await deleteConversation(s.orgId, id);
  if (outcome === "expired") {
    auth.dropExpiredSession(); // -> login view (M3e pattern, see openConversation)
    return;
  }
  if (outcome === "deleted" || outcome === "gone") {
    auth.forgetConversation(id);
  }
  if (id === activeId.value) {
    activeId.value =
      conversations.value.length > 0 ? conversations.value[0].id : null;
    if (activeId.value !== null) await openConversation(activeId.value);
    else resetTranscript();
  }
}

async function send() {
  const s = scoped();
  if (!s || !canSend.value) return;
  const text = prompt.value.trim().slice(0, CHAT_PROMPT_MAX_LENGTH);
  if (activeId.value === null) {
    contextBusy.value=true;
    try { if (!(await newConversation(composerKb.value!==null))) return; }
    finally { contextBusy.value=false; }
  }
  if (activeId.value === null) return;
  const conversationId = activeId.value;
  prompt.value = "";
  notice.value = "";
  streaming.value = true;
  streamedText.value = "";
  streamCitations.value = [];
  stopController = new AbortController();

  // Optimistic echo of the user row: the server already commits it before
  // the upstream call, but the honest re-read only happens when the turn
  // ends — without this the prompt would visually vanish for the duration
  // of a slow stream. Placeholder id/status: replaced by the re-read.
  messages.value = [
    ...messages.value,
    {
      id: -Date.now(),
      role: "user",
      content: text,
      model: "",
      citations: [],
      status: "complete",
      createdAt: new Date().toISOString(),
    },
  ];
  void scrollToBottom();

  const outcome = await streamChatTurn(
    s.orgId,
    conversationId,
    text,
    (event) => {
      if (event.kind === "delta") {
        streamedText.value += event.text;
        void scrollToBottom();
      } else if (event.kind === "citations") {
        streamCitations.value = event.citations;
      }
    },
    stopController.signal,
    webOn.value && webAvailable.value,
  );
  stopController = null;
  streaming.value = false;

  switch (outcome.kind) {
    case "not-configured":
      notice.value =
        tr("Es ist noch kein KI-Modell eingerichtet. Bitte wende dich an deine Administration.");
      break;
    case "rate-limited":
      notice.value =
        tr("Gerade sind zu viele Anfragen unterwegs. Bitte versuche es gleich noch einmal") +
        (outcome.retryAfterSeconds ? tr(" (ca. {0}s warten).", outcome.retryAfterSeconds) : ".");
      break;
    case "unauthorized":
      // 401 mid-turn: the session died — same teardown as every expired
      // outcome (M3e); the login view replaces this one, no notice needed.
      auth.dropExpiredSession();
      return;
    case "gone":
      notice.value = tr("Diese Unterhaltung gibt es nicht mehr.");
      await loadList(false);
      return;
    case "stopped":
      notice.value = tr("Antwort angehalten. Der bisherige Text bleibt gespeichert.");
      break;
    case "error":
      notice.value = tr("Die Antwort konnte nicht erstellt werden. Bitte versuche es erneut.");
      break;
    case "closed":
      break;
  }
  // The honest transcript: always re-read what the SERVER persisted, never
  // keep the optimistic render as truth. A stopped/errored stream races the
  // server's late persist: the relay only notices the dead connection on its
  // NEXT upstream write, so the aborted row can land after the abort is
  // already visible client-side. Those outcomes settle with a short poll.
  await readBackTranscript(
    s.orgId,
    conversationId,
    outcome.kind === "stopped" || outcome.kind === "error",
  );
  await loadList(true); // titles/active order may have changed server-side
  await scrollToBottom();
}

/** How long to wait for a late-persisted terminal row after stop/error. */
const TRANSCRIPT_SETTLE_MS = 600;
const TRANSCRIPT_SETTLE_ATTEMPTS = 10;

/**
 * Re-read the persisted transcript into the view. When {@code settle} is
 * true, keep re-reading while the transcript still ends on the bare user
 * row — the assistant row for an aborted/failed turn is written the moment
 * the server's next relay write breaks, usually within one token interval.
 * Bounded: if it never lands (fetch died before reaching the server, say),
 * the view honestly shows exactly what the server has.
 */
async function readBackTranscript(
  orgId: number,
  conversationId: number,
  settle: boolean,
): Promise<void> {
  for (let attempt = 0; attempt < (settle ? TRANSCRIPT_SETTLE_ATTEMPTS : 1); attempt++) {
    const read = await fetchTranscript(orgId, conversationId);
    if (read.kind === "ok") {
      messages.value = read.transcript.messages;
      streamedText.value = "";
      streamCitations.value = [];
      const last = messages.value[messages.value.length - 1];
      if (!last || last.role !== "user") return; // terminal row landed
    } else if (read.kind === "gone") {
      return; // list reload right after this drops it from the UI
    } else if (read.kind === "expired") {
      auth.dropExpiredSession(); // -> login view (M3e pattern)
      return;
    }
    if (!settle) return;
    await new Promise((r) => setTimeout(r, TRANSCRIPT_SETTLE_MS));
  }
}

function stop() {
  stopController?.abort();
}

// --- CHAT-POLISH §2: message actions ----------------------------------------

/** Key of the message/copy control that succeeded (shows ✓ + data-copied=1). */
const copiedKey = ref<string | null>(null);
let copiedTimer: ReturnType<typeof setTimeout> | null = null;

async function copyWithFeedback(key: string, text: string) {
  const ok = await copyText(text);
  if (ok) {
    copiedKey.value = key;
    if (copiedTimer) clearTimeout(copiedTimer);
    copiedTimer = setTimeout(() => {
      copiedKey.value = null;
    }, 1600);
  }
  return ok;
}

function copyAssistant(m: ChatMessageDto) {
  return copyWithFeedback(`a:${m.id}`, m.content);
}

function copyUser(m: ChatMessageDto) {
  return copyWithFeedback(`u:${m.id}`, m.content);
}

/**
 * Retry (card §2): re-send the LAST user message through the SAME send path.
 * The server is append-only — this deliberately creates a NEW user turn and a
 * NEW assistant turn rather than editing anything (an edit endpoint does not
 * exist; building one was out of scope for this card by the PO).
 */
function retryLastAssistant() {
  if (streaming.value || activeId.value === null) return;
  const lastUser = [...messages.value].reverse().find((m) => m.role === "user");
  if (!lastUser) return;
  prompt.value = lastUser.content;
  void send();
}

/** True for the LAST assistant row only — retry lives there, older answers
 *  keep just the copy action (re-trying an older question would replay the
 *  wrong prompt). */
function isLastAssistant(index: number): boolean {
  if (messages.value[index]?.role !== "assistant") return false;
  for (let i = messages.value.length - 1; i > index; i--) {
    if (messages.value[i].role === "assistant") return false;
  }
  return true;
}

// --- CHAT-POLISH §3: sources -------------------------------------------------

const previewSource = ref<SourceSelection | null>(null);

function citationKey(m: ChatMessageDto, c: ChatCitation): string {
  return `${m.id}:${c.documentId ?? "x"}:${c.chunkIdx}`;
}

function openSource(c: ChatCitation) {
  const s = scoped();
  const kbId = active.value?.knowledgeBaseId ?? null;
  if (!s || kbId === null || c.documentId === null) {
    notice.value = tr("Das Quelldokument ist nicht mehr verfügbar.");
    return;
  }
  previewSource.value = {...s, kbId, documentId:c.documentId, chunkIdx:c.chunkIdx,
    filename:c.filename, sourceUpdatedAt:c.sourceUpdatedAt, startOffset:c.startOffset, pageNumber:c.pageNumber};
}
watch([() => auth.selectedOrgId, () => auth.selectedSpaceId, activeId], () => { previewSource.value = null; });
function previewExpired() { previewSource.value = null; void auth.restoreSession(); }

// --- CHAT-POLISH §1/§6: markdown post-processing in the transcript -----------

/**
 * Attach a Copy button to every rendered fenced code block. Runs after the
 * transcript re-renders (watch flush:post) — decoration by DOM query, never
 * by splicing HTML strings (that would reopen the injection surface). v-html
 * replaces innerHTML on change, so re-decoration is cheap and idempotent.
 */
function decorateCodeBlocks() {
  const root = transcriptEl.value;
  if (!root) return;
  for (const pre of root.querySelectorAll<HTMLElement>("pre[data-code]")) {
    if (pre.hasAttribute("data-decorated")) continue;
    pre.setAttribute("data-decorated", "");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "code-copy";
    btn.setAttribute("data-copy-code", "");
    btn.setAttribute("data-testid", "chat-code-copy");
    btn.setAttribute("aria-label", tr("Code kopieren"));
    btn.textContent = tr("Kopieren");
    pre.appendChild(btn);
  }
}

/** One delegated click listener for the whole transcript: link refusal +
 *  code-block copy (buttons live inside v-html where @click can't bind). */
async function onTranscriptClick(event: MouseEvent) {
  const target = event.target instanceof Element ? event.target : null;
  if (!target) return;

  const link = target.closest<HTMLAnchorElement>("a[data-inapp-link]");
  if (link) {
    // No native opener path exists in this app (Tauri capability has no
    // shell/open scope, ADR-0005 pins all I/O through desktopFetch), so
    // rendered links never navigate the webview — card §1: preventDefault +
    // honest hint instead.
    event.preventDefault();
    showLinkHint(link.getAttribute("href") ?? "");
    return;
  }

  const btn = target.closest<HTMLButtonElement>('button[data-copy-code]');
  if (btn) {
    const code = btn.closest("pre")?.querySelector("code")?.textContent ?? "";
    const ok = await copyText(code);
    btn.setAttribute("data-copied", ok ? "1" : "0");
    btn.textContent = ok ? tr("Kopiert") : tr("Kopieren");
    setTimeout(() => {
      btn.removeAttribute("data-copied");
      btn.textContent = tr("Kopieren");
    }, 1600);
  }
}

function showLinkHint(href: string) {
  linkHint.value = href
    ? tr("Links öffnen hier keinen Browser — Adresse: {0}", href)
    : tr("Links öffnen hier keinen Browser.");
  if (linkHintTimer) clearTimeout(linkHintTimer);
  linkHintTimer = setTimeout(() => {
    linkHint.value = "";
  }, 5000);
}

async function scrollToBottom() {
  await nextTick();
  const el = transcriptEl.value;
  if (el) el.scrollTop = el.scrollHeight;
}

/* UI-POLISH-1 card §4 + CHAT-POLISH §5: at most a few example prompts on the
 * empty state, only when a knowledge base is actually selected (the examples
 * promise sourced answers). They are REAL buttons with border/hover now;
 * clicking starts a Wissens-Chat through the SAME create path as the header
 * button (no new backend behaviour), prefills the composer and focuses it —
 * the user still presses Senden; nothing here streams or sends on its own. */
const examplePrompts = [
  tr("Hilf mir, eine Aufgabe zu planen"),
  tr("Formuliere eine freundliche E-Mail"),
] as const;

async function useExample(text: string) {
  if (streaming.value) return;
  if (activeId.value === null) {
    const created = await newConversation(composerKb.value !== null);
    if (!created) return; // honest: notice + list state already explain
  }
  prompt.value = text;
  await nextTick();
  composerEl.value?.focus();
}

/* REDESIGN v3: Verlauf nach Zeit gruppiert (Heute / Gestern / Letzte 7 Tage / Älter). */
const groupedConversations = computed(() => {
  const now = new Date();
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const day = 86400000;
  const buckets: { label: string; items: typeof conversations.value }[] = [
    { label: tr("Heute"), items: [] },
    { label: tr("Gestern"), items: [] },
    { label: tr("Letzte 7 Tage"), items: [] },
    { label: tr("Älter"), items: [] },
  ];
  for (const c of conversations.value) {
    const t = new Date(c.updatedAt).getTime();
    const i = t >= startToday ? 0 : t >= startToday - day ? 1 : t >= startToday - 7 * day ? 2 : 3;
    buckets[i].items.push(c);
  }
  return buckets.filter((b) => b.items.length);
});

function formatClock(iso: string): string {
  try {
    return new Intl.DateTimeFormat(dateLocale(), { hour: "2-digit", minute: "2-digit" }).format(new Date(iso));
  } catch {
    return "";
  }
}

const greeting = computed(() => {
  const h = new Date().getHours();
  const first = (auth.user?.displayName ?? "").split(/\s+/)[0];
  const base = h < 11 ? tr("Guten Morgen") : h < 18 ? tr("Guten Tag") : tr("Guten Abend");
  return first ? `${base}, ${first}` : base;
});

/* Vorlage einfügen: an den vorhandenen Entwurf anhängen, Fokus in die Eingabe. */
async function insertTemplate(text: string) {
  prompt.value = prompt.value.trim() ? `${prompt.value.trimEnd()}\n\n${text}` : text;
  await nextTick();
  composerEl.value?.focus();
}

/* Antwort exportieren: Word/PDF vom Server, „Für E-Mail kopieren“ mit Formatierung. */
async function exportAnswer(m: ChatMessageDto, format: "docx" | "pdf") {
  const s = scoped();
  if (!s || activeId.value === null) return;
  try {
    const res = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${s.orgId}/chat/conversations/${activeId.value}/messages/${m.id}/export?format=${format}`,
    );
    if (!res.ok) throw new Error(String(res.status));
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = tr("Keplura-Antwort.{0}", format);
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  } catch {
    notice.value = tr("Der Export hat nicht geklappt. Bitte versuche es erneut.");
  }
}

async function copyForEmail(m: ChatMessageDto) {
  const rendered = document.querySelector(`[data-msg-id="${m.id}"] .acontent`) as HTMLElement | null;
  const sources = m.citations.map((c, i) =>
    `[${i + 1}] ${c.filename}${c.pageNumber ? `, ${sourcePageLabel(c.filename)} ${c.pageNumber}` : ""}`);
  const esc = (t: string) => t.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const html = `<div style="font-family:Calibri,Arial,sans-serif;font-size:11pt">${rendered?.innerHTML ?? esc(m.content)}`
    + (sources.length ? `<p style="color:#666;font-size:9pt">Quellen:<br>${sources.map(esc).join("<br>")}</p>` : "")
    + "</div>";
  const plain = m.content + (sources.length ? tr("\n\nQuellen:\n{0}", sources.join("\n")) : "");
  try {
    if (typeof ClipboardItem !== "undefined" && navigator.clipboard?.write) {
      await navigator.clipboard.write([new ClipboardItem({
        "text/html": new Blob([html], { type: "text/html" }),
        "text/plain": new Blob([plain], { type: "text/plain" }),
      })]);
    } else {
      await copyText(plain);
    }
    copiedKey.value = `a:${m.id}`;
    setTimeout(() => { if (copiedKey.value === `a:${m.id}`) copiedKey.value = null; }, 1500);
  } catch {
    notice.value = tr("Kopieren wurde vom Browser blockiert. Bitte markiere den Text und kopiere ihn manuell.");
  }
}

/* PILOT 4e: Daumen hoch/runter — zweiter Klick auf denselben Daumen nimmt die Bewertung zurück. */
async function rate(m: ChatMessageDto, rating: "up" | "down") {
  const s = scoped();
  if (!s || activeId.value === null) return;
  const next = m.feedback === rating ? null : rating;
  const previous = m.feedback ?? null;
  m.feedback = next; // sofort sichtbar
  const r = await rateAnswer(s.orgId, activeId.value, m.id, next);
  if (r.kind !== "ok") {
    m.feedback = previous;
    notice.value = r.kind === "expired"
      ? tr("Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.")
      : tr("Die Bewertung konnte nicht gespeichert werden. Bitte versuche es erneut.");
  }
}

function formatTime(iso: string): string {
  try {
    return new Intl.DateTimeFormat(dateLocale(), {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(iso));
  } catch {
    return "";
  }
}

function statusLabel(status: ChatMessageDto["status"]): string {
  switch (status) {
    case "complete":
      return "fertig";
    case "aborted":
      return "gestoppt";
    case "failed":
      return "fehlgeschlagen";
    default:
      return tr("unvollständig");
  }
}

/** StatusPill tones are spec-limited (ok/wait/neutral); "failed" reads as
 *  an error state via the wait tone + its label — no invented colour step. */
function statusTone(status: ChatMessageDto["status"]): "ok" | "wait" | "neutral" {
  switch (status) {
    case "complete":
      return "ok";
    case "aborted":
      return "neutral";
    default:
      return "wait";
  }
}

// Re-run code-block decoration whenever persisted messages change (final
// Markdown renders land here, never during the plain-text stream phase).
watch(messages, () => {
  void nextTick(decorateCodeBlocks);
});

watch(
  () => auth.selectedSpaceId,
  () => {
    // conversations are space-scoped personal threads — re-point on switch
    stop();
    void loadList(false);
    if (activeId.value !== null) void openConversation(activeId.value);
  },
);

onMounted(() => {
  void loadList(false);
  if (activeId.value !== null) void openConversation(activeId.value);
  void nextTick(decorateCodeBlocks);
});

onUnmounted(() => {
  stop();
  if (copiedTimer) clearTimeout(copiedTimer);
  if (linkHintTimer) clearTimeout(linkHintTimer);
});
</script>

<template>
  <section
    class="chat-view flex min-w-0 flex-1 flex-col overflow-hidden bg-content"
    :class="{'chat-source-open': previewSource !== null}"
    data-testid="chat-view"
  >
    <header class="flex min-h-[48px] items-center justify-between gap-4 border-b border-separator py-[6px] pr-6 pl-6">
      <div class="min-w-0">
        <h1 class="m-0 inline text-[15px] font-semibold tracking-[-0.01em] text-ink">{{ $t("Chat") }}</h1>
        <p class="m-0 ml-3 inline text-[13px] text-ink-3">
          <template v-if="auth.selectedSpace">
            {{ auth.selectedSpace.name }}
          </template>
          <template v-else-if="auth.orgs.length === 0">{{ $t("Lege unter „Team“ zuerst eine Organisation an.") }}</template>
          <template v-else>{{ $t("Lege unter „Wissen“ zuerst einen Arbeitsbereich an.") }}</template>
        </p>
      </div>
      <div class="chat-head-actions">
        <Button
          variant="ghost"
          data-testid="chat-new-plain"
          :disabled="!scoped() || streaming"
          @click="newConversation(false)"
        >
          {{ $t("Neuer Chat") }}
        </Button>
      </div>
    </header>

    <div class="chat-split flex min-h-0 flex-1">
      <!-- CHAT-POLISH §5: at 0 conversations the whole column collapses and
           the "Noch keine Unterhaltung" line moves into the reading area's
           empty state (same text + same chat-conv-empty testid — so browser
           and packaged AT-SPI anchors keep working). The header buttons are
           always visible, so starting a chat never depends on the aside. -->
      <aside
        v-if="false"
        class="chat-list w-[232px] flex-none overflow-y-auto border-r border-separator py-[6px]"
        data-testid="chat-conv-list"
        :aria-label="$t('Unterhaltungen')"
      >
        <p v-if="listError" class="chat-list-hint">{{ listError }}</p>
        <template v-for="g in groupedConversations" :key="g.label">
          <p class="conv-group">{{ g.label }}</p>
          <div v-for="c in g.items" :key="c.id" class="conv-row">
            <button
              type="button"
              class="conv-open"
              :class="c.id === activeId ? 'conv-active' : 'conv-idle'"
              data-testid="chat-conv-item"
              :title="c.title"
              @click="openConversation(c.id)"
            >
              <span class="conv-title">{{ c.title }}</span>
              <span v-if="c.knowledgeBaseId !== null" class="conv-kb" :title="$t('Mit Wissen')" :aria-label="$t('Mit Wissen')"><AppIcon name="book" :size="13" /></span>
            </button>
            <button
              type="button"
              class="conv-del"
              data-testid="chat-conv-delete"
              :data-conv-id="c.id"
              :disabled="streaming"
              :title="$t('Unterhaltung löschen')"
              :aria-label="$t('Unterhaltung löschen')"
              @click="removeConversation(c.id)"
            >
              <AppIcon name="trash" :size="13" />
            </button>
          </div>
        </template>
      </aside>

      <div class="chat-thread flex min-w-0 flex-1 flex-col" data-testid="chat-thread">
        <div
          ref="transcriptEl"
          class="chat-transcript flex flex-1 flex-col gap-[16px] overflow-y-auto pt-[18px] pr-6 pb-[10px] pl-6"
          @click="onTranscriptClick"
        >
          <!-- UI-POLISH-1 card §4 + CHAT-POLISH §5: the empty transcript is
               the product's orientation moment. Examples are now clearly
               bordered chips (real buttons); clicking fills AND focuses the
               composer — no turn is auto-started, streaming untouched. -->
          <div v-if="messages.length === 0 && !streaming" class="empty-state mx-auto my-auto flex max-w-[480px] flex-col gap-[6px] p-6 text-center" :data-testid="activeId === null ? 'chat-no-selection' : 'chat-ready'">
            <span class="welcome-symbol" aria-hidden="true"><AppIcon name="chat" /></span>
            <p class="welcome-greeting">{{ greeting }}</p>
            <p class="es-lead m-0 text-[24px] font-semibold tracking-[-0.02em] text-ink">{{ $t("Wie kann ich dir helfen?") }}</p>
            <p class="es-sub m-0 text-[13px] leading-[1.6] text-ink-2">
              {{ $t("Für Fragen, Ideen und alles, was du vorhast.") }}
            </p>
            <p
              v-if="conversations.length === 0 && !listError"
              class="es-sub m-0 text-[13px] leading-[1.6] text-ink-2"
              data-testid="chat-conv-empty"
            >
              {{ $t("Starte einfach mit deiner ersten Nachricht.") }}
            </p>
            <div class="es-examples mt-[14px] flex flex-col items-center gap-[8px]">
              <button
                v-for="(ex, i) in examplePrompts"
                :key="i"
                type="button"
                class="es-ex"
                data-testid="chat-example"
                :disabled="streaming || !scoped()"
                @click="useExample(ex)"
              >
                {{ ex }}
              </button>
            </div>
          </div>
          <template v-for="(m, mi) in messages" :key="m.id">
            <div v-if="m.role === 'user'" class="urow urow-x flex flex-col items-end gap-[2px] self-stretch" data-testid="chat-msg-user">
              <!-- CHAT-POLISH §4: short sender marker + the block's 7 % tint
                   carry the user-vs-AI separation; content stays plain text
                   (user input is not Markdown-authored). -->
              <p class="sr-only-x">{{ $t("Du") }}</p>
              <!-- ucontent: E2E-Anker (chat_flow_e2e text-Selektor) — Name
                   bleibt byte-identisch. -->
              <p class="ucontent m-0 max-w-[min(72ch,100%)] self-end rounded-[14px] bg-msg-tint px-3 py-2 text-[14px] leading-[1.6] text-ink break-anywhere whitespace-pre-wrap">{{ m.content }}</p>
              <span class="msg-actions">
                <button
                  type="button"
                  class="msg-action"
                  data-testid="chat-copy-user"
                  :data-copied="copiedKey === `u:${m.id}` ? '1' : null"
                  :aria-label="copiedKey === `u:${m.id}` ? $t('Kopiert') : $t('Nachricht kopieren')"
                  :title="copiedKey === `u:${m.id}` ? $t('Kopiert') : $t('Kopieren')"
                  @click="copyUser(m)"
                >
                  <AppIcon name="copy" :size="13" />
                </button>
              </span>
            </div>
            <div v-else class="arow max-w-[72ch]" data-testid="chat-msg-assistant" :data-msg-id="m.id">
              <!-- CHAT-POLISH §4: model as a small top-right badge instead of
                   grey nothing in the meta row. testid chat-msg-model kept. -->

              <!-- CHAT-POLISH §1: sanitized Markdown (marked+DOMPurify) for
                   the FINAL answer; .acontent stays the E2E text anchor. -->
              <div class="acontent"><MarkdownAnswer :source="serverText(m.content)" /></div>
              <div class="ameta mt-[4px] flex items-center gap-[8px] text-[12px] text-ink-2">
                <StatusPill v-if="m.status !== 'complete'" :tone="statusTone(m.status)">{{ statusLabel(m.status) }}</StatusPill>
                <span :title="formatTime(m.createdAt)">{{ formatClock(m.createdAt) }}</span>
                <span v-if="m.model" class="msg-model-x ic-num" data-testid="chat-msg-model">{{ m.model }}</span>
                <!-- CHAT-POLISH §2: quiet hover actions (existing icon set,
                     currentColor). Retry only on the LAST answer. -->
                <span class="msg-actions">
                  <button
                    type="button"
                    class="msg-action"
                    data-testid="chat-msg-copy"
                    :data-copied="copiedKey === `a:${m.id}` ? '1' : null"
                    :aria-label="copiedKey === `a:${m.id}` ? $t('Kopiert') : $t('Antwort kopieren')"
                    :title="copiedKey === `a:${m.id}` ? $t('Kopiert') : $t('Kopieren')"
                    @click="copyAssistant(m)"
                  >
                    <AppIcon name="copy" :size="13" />
                  </button>
                  <DropdownMenuRoot v-if="m.status === 'complete'">
                    <DropdownMenuTrigger class="msg-action" data-testid="chat-msg-export" :aria-label="$t('Antwort exportieren')" :title="$t('Exportieren')">
                      <AppIcon name="download" :size="13" />
                    </DropdownMenuTrigger>
                    <DropdownMenuPortal>
                      <DropdownMenuContent class="ic-menu" :side-offset="4" align="start">
                        <DropdownMenuItem class="ic-menu-item" data-testid="chat-export-email" @select="copyForEmail(m)">
                          <AppIcon name="copy" :size="13" /><span class="ic-menu-text">{{ $t("Für E-Mail kopieren") }}</span>
                        </DropdownMenuItem>
                        <DropdownMenuItem class="ic-menu-item" data-testid="chat-export-docx" @select="exportAnswer(m, 'docx')">
                          <AppIcon name="doc" :size="13" /><span class="ic-menu-text">{{ $t("Als Word-Datei") }}</span>
                        </DropdownMenuItem>
                        <DropdownMenuItem class="ic-menu-item" data-testid="chat-export-pdf" @select="exportAnswer(m, 'pdf')">
                          <AppIcon name="doc" :size="13" /><span class="ic-menu-text">{{ $t("Als PDF") }}</span>
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenuPortal>
                  </DropdownMenuRoot>
                  <button
                    v-if="isLastAssistant(mi) && !streaming"
                    type="button"
                    class="msg-action"
                    data-testid="chat-msg-retry"
                    :aria-label="$t('Letzte Frage erneut senden')"
                    :title="$t('Neu versuchen')"
                    @click="retryLastAssistant"
                  >
                    <AppIcon name="refresh" :size="13" />
                  </button>
                  <template v-if="m.status === 'complete'">
                    <button
                      type="button"
                      class="msg-action fb"
                      :class="{ on: m.feedback === 'up' }"
                      data-testid="chat-feedback-up"
                      :aria-pressed="m.feedback === 'up'"
                      :aria-label="$t('Hilfreiche Antwort')"
                      :title="$t('Hilfreich')"
                      @click="rate(m, 'up')"
                    >
                      <AppIcon name="thumb-up" :size="13" />
                    </button>
                    <button
                      type="button"
                      class="msg-action fb"
                      :class="{ on: m.feedback === 'down' }"
                      data-testid="chat-feedback-down"
                      :aria-pressed="m.feedback === 'down'"
                      :aria-label="$t('Nicht hilfreiche Antwort')"
                      :title="$t('Nicht hilfreich')"
                      @click="rate(m, 'down')"
                    >
                      <AppIcon name="thumb-down" :size="13" />
                    </button>
                  </template>
                </span>
              </div>
              <!-- Quelle und Stand zuerst; Seiten-/Abschnittsdetails stehen in der Vorschau. -->
              <div v-if="m.citations.length" class="src-block src-x" data-testid="chat-sources">
                <p class="src-cap-x">{{ $t("Quellen") }}</p>
                <div v-for="(c, ci) in m.citations" :key="citationKey(m, c)" class="src-row">
                  <button
                    type="button"
                    class="src-chip"
                    :aria-expanded="previewSource?.documentId === c.documentId && previewSource?.chunkIdx === c.chunkIdx"
                    aria-controls="document-source-preview"
                    data-testid="chat-citation"
                    @click="openSource(c)"
                  >
                    <span class="source-reference ic-num">{{ ci + 1 }}</span>
                    <span class="source-chip-content min-w-0">
                      <span class="src-file text-ink font-medium">{{ c.filename }}</span>
                      <SourceFreshness :value="c.sourceUpdatedAt" />
                    </span>
                  </button>
                </div>
              </div>
            </div>
          </template>

          <!-- CHAT-POLISH §6: plain text + pulsing caret while tokens arrive;
               Markdown renders on the final persisted re-read only. -->
          <div v-if="streaming" class="arow max-w-[72ch]" data-testid="chat-streaming">
            <p class="acontent streamtext m-0 text-[14px] leading-[1.6] text-ink break-anywhere whitespace-pre-wrap">
              {{ streamedText }}<i class="caret" aria-hidden="true"></i>
            </p>
            <div v-if="streamCitations.length" class="src-block src-x" data-testid="chat-sources-live">
              <p class="src-cap-x">{{ $t("Quellen") }}</p>
              <div v-for="(c, ci) in streamCitations" :key="'live:' + c.documentId + ':' + c.chunkIdx" class="src-row">
                <button type="button" class="src-chip" aria-controls="document-source-preview" :aria-expanded="previewSource?.documentId === c.documentId && previewSource?.chunkIdx === c.chunkIdx" @click="openSource(c)">
                  <span class="source-reference ic-num">{{ ci + 1 }}</span>
                  <span class="source-chip-content min-w-0">
                    <span class="src-file text-ink font-medium">{{ c.filename }}</span>
                    <SourceFreshness :value="c.sourceUpdatedAt" />
                  </span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="notice" class="msg-notice" data-testid="chat-notice">{{ notice }}</div>
        <div v-if="linkHint" class="msg-notice msg-notice-info" data-testid="chat-link-hint">{{ linkHint }}</div>

        <footer class="msg-composer" :aria-label="$t('Nachricht verfassen')">
          <p id="chat-input-help" class="composer-hint">{{ $t("Enter sendet. Umschalt und Enter fügt eine neue Zeile ein.") }}
            <span v-if="activeHasKb">{{ $t("Antworten mit Quellen aus „{0}“.", knowledgeLabel) }}</span>
          </p>
          <textarea ref="composerEl" v-model="prompt" class="composer-input" rows="1" maxlength="4000"
            :placeholder="$t('Frag Keplura …')" :aria-label="$t('Nachricht an Keplura')" aria-describedby="chat-input-help"
            data-testid="chat-prompt" @keydown="composerKeydown"></textarea>
          <div class="composer-bar">
            <div class="composer-tools">
              <ChatContextBar :conversation-id="activeId" :primary-kb-id="composerKb"
                :disabled="!scoped() || streaming || contextBusy" :ensure-conversation="ensureConversation"
                @error="(m: string) => (notice = m)" />
              <TemplateMenu compact :current-prompt="prompt" :disabled="!scoped()" @insert="insertTemplate" />
              <button v-if="webAvailable" type="button" class="web-toggle" :class="{ on: webOn }" :aria-pressed="webOn"
                :disabled="streaming || contextBusy" :title="$t('Im Web suchen')" :aria-label="$t('Im Web suchen')"
                data-testid="chat-web-toggle" @click="webOn = !webOn"><AppIcon name="globe" :size="16" /><span v-if="webOn">{{ $t("Web") }}</span></button>
              <label class="composer-knowledge" :class="{ selected: composerKb !== null, disabled: streaming || contextBusy || !scoped() }"
                :title="activeId !== null ? $t('Wissensbasis wählen. Ein Wechsel öffnet einen neuen Chat und erhält deinen Entwurf.') : $t('Optional Wissen aus deinen Dokumenten verwenden')">
                <AppIcon name="book" :size="16" /><span class="composer-kb-name" :data-testid="composerKb !== null ? 'chat-ctx-kb' : undefined">{{ knowledgeLabel || $t("Wissen hinzufügen") }}</span>
                <AppIcon name="chevron" :size="13" />
                <select id="chat-knowledge-base" v-model="composerKb" :aria-label="$t('Wissensbasis auswählen')" data-testid="chat-kb-select"
                  :disabled="streaming || contextBusy || !scoped()" @change="changeKnowledgeContext">
                  <option :value="null">{{ $t("Ohne Wissensbasis") }}</option>
                  <option v-for="kb in auth.kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
                </select>
              </label>
            </div>
            <span v-if="contextBusy" role="status" class="composer-hint">{{ $t("Neuer Chat wird geöffnet …") }}</span>
            <span v-if="prompt.length > CHAT_PROMPT_MAX_LENGTH - 200" class="composer-counter ic-num">{{ prompt.length }}/{{ CHAT_PROMPT_MAX_LENGTH }}</span>
            <Button v-if="streaming" class="composer-send" variant="secondary" data-testid="chat-stop" :aria-label="$t('Antwort stoppen')" :title="$t('Antwort stoppen')" @click="stop"><AppIcon name="stop" :size="16" /></Button>
            <Button v-else class="composer-send" variant="primary" data-testid="chat-send" :disabled="!canSend" :aria-label="$t('Nachricht senden')" :title="$t('Nachricht senden')" @click="send"><AppIcon name="arrow-up" :size="20" /></Button>
          </div>
        </footer>
      </div>
    </div>
    <SourcePreview v-if="previewSource" :source="previewSource" @close="previewSource = null" @expired="previewExpired" />
  </section>
</template>

<style scoped>
.source-chip-content { display:grid;gap:2px;min-width:0; }.source-reference { flex:none;min-width:15px;margin-top:1px;color:var(--ic-text-3);font-size:12px;line-height:20px; }.src-file { overflow-wrap:anywhere; }

.chat-view { position:relative; border:0; border-radius:0; min-height:0; }
.chat-view > header { min-height:72px; padding:16px 24px; border-bottom:1px solid var(--ic-separator); flex-wrap:wrap; background:var(--ic-content); }
.chat-view > header h1 { font-size:24px; line-height:32px; letter-spacing:-.01em; }
.chat-view > header p { display:block; margin:4px 0 0; color:var(--ic-text-2); font-size:13px; }
.chat-head-actions { display:flex; gap:8px; flex-wrap:wrap; opacity:1; }
.chat-split,.chat-thread,.chat-transcript { min-height:0; }
.chat-transcript { padding:32px max(24px,calc((100% - 720px)/2)) 24px; gap:32px; }
.empty-state { max-width:560px; padding:32px 0; text-align:left; align-items:flex-start; }
.welcome-symbol { display:none; }.welcome-greeting { color:var(--ic-text-2); margin:0 0 8px; font-size:14px; }
.es-lead { font-size:24px; line-height:32px; letter-spacing:-.01em; }.es-sub { font-size:14px; line-height:22px; }
.es-examples { display:flex; flex-direction:row; flex-wrap:wrap; align-items:center; justify-content:flex-start; width:100%; gap:8px; margin-top:20px; }.es-ex { min-height:40px; padding:8px 12px; border:1px solid var(--ic-separator); border-radius:8px; background:transparent; color:var(--ic-text-2); text-align:left; font:400 13px/20px var(--ic-font); }.es-ex:hover:not(:disabled) { background:var(--ic-hover-tint); color:var(--ic-text); }
.arow { width:100%; max-width:100%; }.acontent { font-size:16px; line-height:26px; overflow-wrap:anywhere; min-width:0; }
.urow { position:relative; } /* Anker für .sr-only-x, sonst wächst .chat-view über die Transkripthöhe */
.urow .ucontent { max-width:min(65ch,100%); padding:12px 16px; border-radius:8px; background:var(--ic-surface-subtle); font-size:16px; line-height:26px; }
.ameta { flex-wrap:wrap; margin-top:12px; }.msg-actions { display:inline-flex; flex-wrap:wrap; gap:4px; opacity:1; }
.msg-action { display:inline-flex; align-items:center; justify-content:center; width:32px; height:32px; padding:6px; opacity:1; border-radius:6px; color:var(--ic-text-2); }
.msg-action:hover,.msg-action.fb.on { background:var(--ic-selection); color:var(--ic-accent); }.msg-model-x { color:var(--ic-text-2); }
.src-chip { align-items:flex-start;justify-content:flex-start;gap:10px;min-height:40px; border-radius:4px; padding:8px 0; border:0; background:transparent; color:var(--ic-text-2); max-width:100%; white-space:normal; overflow-wrap:anywhere; }
.msg-composer { flex:none; width:min(768px,calc(100% - 48px)); margin:0 auto 24px; padding:12px; border:1px solid var(--ic-input-edge); border-radius:16px; background:var(--ic-surface); }
.composer-input { display:block; min-height:48px; max-height:200px; width:100%; padding:8px 8px 12px; border:0; border-radius:6px; background:transparent; box-shadow:none; color:var(--ic-text); font:400 16px/26px var(--ic-font); resize:none; overflow-y:auto; }
.composer-input::placeholder { color:var(--ic-text-3); }
.composer-input:focus-visible { outline:2px solid var(--ic-focus-color); outline-offset:2px; box-shadow:none; }
.composer-bar { display:flex; align-items:flex-end; gap:8px; margin-top:4px; }
.composer-tools { display:flex; align-items:center; flex-wrap:wrap; gap:4px; flex:1; min-width:0; }
.composer-knowledge { position:relative; display:inline-flex; align-items:center; gap:6px; min-height:32px; max-width:220px; padding:4px 8px; border-radius:6px; color:var(--ic-text-2); font:500 13px/20px var(--ic-font); cursor:pointer; }
.composer-knowledge:hover { background:var(--ic-hover-tint); }
.composer-knowledge.selected { background:var(--ic-selection); color:var(--ic-accent); }
.composer-knowledge.disabled { opacity:.5; cursor:default; }
.composer-knowledge:focus-within { outline:2px solid var(--ic-focus-color); outline-offset:2px; }
.composer-knowledge svg { flex:none; }.composer-knowledge > svg:last-of-type { transform:rotate(90deg); }.composer-kb-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.composer-knowledge select { position:absolute; inset:0; width:100%; height:100%; opacity:0; cursor:inherit; font:400 16px var(--ic-font); }
.composer-hint { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; }
.composer-counter { align-self:center; font-size:12px; color:var(--ic-text-2); }
.composer-send { flex:none; min-width:40px; width:40px; min-height:40px; padding:8px; border-radius:50%; }
.web-toggle { display:inline-flex; align-items:center; justify-content:center; gap:6px; min-width:32px; min-height:32px; padding:6px; border:0; border-radius:6px; background:transparent; color:var(--ic-text-2); font:500 13px/20px var(--ic-font); cursor:pointer; }
.web-toggle:hover:not(:disabled) { background:var(--ic-hover-tint); }.web-toggle.on { background:var(--ic-selection); color:var(--ic-accent); }.web-toggle:disabled { color:var(--ic-disabled-text); cursor:default; }
@media(max-width:767px),(any-pointer:coarse) { .composer-knowledge,.web-toggle { min-height:44px; }.web-toggle { min-width:44px; }.composer-send { width:44px; min-width:44px; min-height:44px; } }
@media(max-width:767px) { .chat-view > header { padding:12px 16px; gap:8px; }.chat-view > header h1 { font-size:20px; line-height:28px; }.chat-transcript { padding:24px 16px; }.chat-head-actions { gap:4px; }.msg-composer { width:calc(100% - 32px); margin-bottom:16px; padding:8px; }.msg-action,.src-chip { min-height:44px; }.msg-action { width:44px; }.composer-knowledge { max-width:min(220px,100%); } }
@media(max-height:600px),(max-width:359px) { .chat-view { overflow-y:auto; overflow-x:hidden; }.chat-split { flex:none; }.chat-thread { min-width:0; } /* flex:none am Thread machte ihn in der Zeilen-Flexbox max-content breit */.chat-transcript { flex:none; overflow:visible; }.msg-composer { margin-top:16px; }.chat-view > header { position:static; } }
@media(any-pointer:coarse) { .msg-action { width:44px; height:44px; }.web-toggle,.src-chip { min-height:44px; } }
@media(min-width:1440px) { .chat-source-open { margin-right:424px; } }


</style>

<!-- UI-REFRESH-2 Phase 2: das bisherige scoped-Stylesheet ist 1:1 auf die
     Tailwind-Utilities/Theme-Tokens in styles/tailwind.css umgezogen
     (conv-*/msg-*/src-*/composer-*/es-*/caret/code-copy + core utilities).
     Die E2E-Anker-Klassen (ucontent, acontent, ameta, caret, chat-* data-
     testids) sind byte-identisch erhalten; decorateCodeBlocks haengt
     .code-copy weiter imperativ an v-html-Inhalt (Stil jetzt global —
     v-html traegt nie Scope-Attribute). Der bisherige <style scoped>-Block
     entfaellt ersatzlos — jede Regel lebt in identischen Werten unten in
     tailwind.css weiter. -->
