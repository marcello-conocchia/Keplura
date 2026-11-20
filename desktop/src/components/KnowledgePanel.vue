<script setup lang="ts">
import { sourcePageLabel } from "../source-preview";
/**
 * REDESIGN-2026-11 — Wissen-Explorer (ersetzt die lange Scroll-Seite).
 * Links: Wissensbasen des aktiven Arbeitsbereichs. Rechts: Suche im Wissen,
 * Upload (Button + Drag & Drop) und Dokumenttabelle mit Klartext-Status.
 * Zustand liegt im auth-store, Rename/Delete/Re-Index in api-manage.ts;
 * diese Datei orchestriert nur. Alle bisherigen testids bleiben erhalten.
 */
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useAuthStore } from "../auth-store";
import {
  DOCUMENT_ACCEPT_EXTENSIONS,
  KB_NAME_MAX_LENGTH,
  SEARCH_QUERY_MAX_LENGTH,
  SPACE_NAME_MAX_LENGTH,
  serverUrl,
  type KnowledgeBase,
  type KnowledgeDocument,
  type SearchHit,
} from "../api";
import { deleteDocument, deleteKnowledgeBase, manageErrorText, reindexDocument, renameKnowledgeBase } from "../api-manage";
import { desktopFetch } from "../transport";
import AppDialog from "./ui/AppDialog.vue";
import AppIcon from "./ui/AppIcon.vue";
import Button from "./ui/Button.vue";
import ConfirmDialog from "./ui/ConfirmDialog.vue";
import Input from "./ui/Input.vue";
import KbList from "./knowledge/KbList.vue";
import DocumentTable from "./knowledge/DocumentTable.vue";
import { formatSize, statusLabel } from "./knowledge/status";
import SourcePreview from "./SourcePreview.vue";
import SourceFreshness from "./ui/SourceFreshness.vue";
import type { SourceSelection } from "../source-preview";

const auth = useAuthStore();
const previewSource = ref<SourceSelection | null>(null);
function openSearchSource(hit: SearchHit) {
  if (auth.selectedOrgId === null || auth.selectedSpaceId === null || auth.selectedKbId === null) return;
  previewSource.value = {orgId:auth.selectedOrgId, spaceId:auth.selectedSpaceId, kbId:auth.selectedKbId,
    documentId:hit.documentId, chunkIdx:hit.chunkIdx, filename:hit.filename,
    sourceUpdatedAt:hit.sourceUpdatedAt, startOffset:hit.startOffset, pageNumber:hit.pageNumber};
}
watch([() => auth.selectedOrgId, () => auth.selectedSpaceId, () => auth.selectedKbId], () => { previewSource.value = null; });
function previewExpired() { previewSource.value = null; void auth.restoreSession(); }
const acceptAttr = DOCUMENT_ACCEPT_EXTENSIONS.map((e) => "." + e).join(",");
const acceptText = DOCUMENT_ACCEPT_EXTENSIONS.map((e) => e.toUpperCase()).join(", ");

const isOwner = computed(() => auth.selectedOrg?.role === "OWNER");
const canChange = (doc: KnowledgeDocument) => isOwner.value || doc.uploadedBy === auth.user?.userId;

/* ---------- Rückmeldungen ---------- */
const toast = ref("");
const toastKind = ref<"ok" | "error" | "">("");
function showToast(kind: "ok" | "error", message: string) {
  toastKind.value = kind;
  toast.value = message;
}
function clearToast() {
  toast.value = "";
  toastKind.value = "";
}

/* ---------- Arbeitsbereich / Wissensbasis anlegen ---------- */
const spaceFormOpen = ref(false);
const spaceName = ref("");
const spaceError = ref("");
const creatingSpace = ref(false);
const kbFormOpen = ref(false);
const kbName = ref("");
const kbError = ref("");
const creatingKb = ref(false);
const canCreateSpace = computed(() => spaceName.value.trim() !== "" && !creatingSpace.value && auth.selectedOrgId !== null);
const canCreateKb = computed(() => kbName.value.trim() !== "" && !creatingKb.value && auth.selectedSpaceId !== null);

function openSpaceForm() {
  spaceError.value = "";
  spaceFormOpen.value = true;
}
function openKbForm() {
  kbError.value = "";
  kbFormOpen.value = true;
}

const commonError: Record<string, string> = {
  expired: "Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.",
  error: "Das hat nicht geklappt. Bitte versuche es später erneut.",
};

async function submitCreateSpace() {
  if (!canCreateSpace.value) return;
  creatingSpace.value = true;
  spaceError.value = "";
  const outcome = await auth.createSpace(spaceName.value.trim());
  creatingSpace.value = false;
  if (outcome.kind === "created") {
    spaceName.value = "";
    spaceFormOpen.value = false;
    showToast("ok", `Arbeitsbereich „${outcome.item.name}“ angelegt.`);
    return;
  }
  spaceError.value =
    outcome.kind === "invalid-name" ? `Bitte einen Namen mit 1 bis ${SPACE_NAME_MAX_LENGTH} Zeichen eingeben.`
    : outcome.kind === "duplicate-name" ? "Einen Arbeitsbereich mit diesem Namen gibt es bereits."
    : outcome.kind === "parent-gone" ? "Die Organisation ist nicht mehr verfügbar. Die Ansicht wurde aktualisiert."
    : commonError[outcome.kind];
}

async function submitCreateKb() {
  if (!canCreateKb.value) return;
  creatingKb.value = true;
  kbError.value = "";
  const outcome = await auth.createKnowledgeBase(kbName.value.trim());
  creatingKb.value = false;
  if (outcome.kind === "created") {
    kbName.value = "";
    kbFormOpen.value = false;
    showToast("ok", `Wissensbasis „${outcome.item.name}“ angelegt.`);
    return;
  }
  kbError.value =
    outcome.kind === "invalid-name" ? `Bitte einen Namen mit 1 bis ${KB_NAME_MAX_LENGTH} Zeichen eingeben.`
    : outcome.kind === "duplicate-name" ? "Eine Wissensbasis mit diesem Namen gibt es bereits."
    : outcome.kind === "parent-gone" ? "Der Arbeitsbereich ist nicht mehr verfügbar. Die Ansicht wurde aktualisiert."
    : commonError[outcome.kind];
}

/* ---------- Wissensbasis umbenennen / löschen ---------- */
const renameTarget = ref<KnowledgeBase | null>(null);
const renameValue = ref("");
const renameError = ref("");
const renaming = ref(false);
function openRename(kb: KnowledgeBase) {
  renameTarget.value = kb;
  renameValue.value = kb.name;
  renameError.value = "";
}
async function submitRename() {
  const kb = renameTarget.value;
  if (!kb || !auth.selectedOrgId || !auth.selectedSpaceId || renameValue.value.trim() === "") return;
  renaming.value = true;
  const r = await renameKnowledgeBase(auth.selectedOrgId, auth.selectedSpaceId, kb.id, renameValue.value.trim());
  renaming.value = false;
  if (r.kind === "ok") {
    renameTarget.value = null;
    showToast("ok", "Wissensbasis umbenannt.");
    await auth.loadKnowledge();
  } else {
    renameError.value = manageErrorText(r.kind);
  }
}

type Pending = { kind: "kb"; kb: KnowledgeBase } | { kind: "doc"; doc: KnowledgeDocument };
const pendingDelete = ref<Pending | null>(null);
const deleting = ref(false);
const deleteError = ref("");
const deleteTitle = computed(() => (pendingDelete.value?.kind === "kb" ? "Wissensbasis löschen?" : "Dokument löschen?"));
const deleteMessage = computed(() => {
  const p = pendingDelete.value;
  if (!p) return "";
  return p.kind === "kb"
    ? `„${p.kb.name}“ wird mit allen Dokumenten endgültig gelöscht. Chats, die sich darauf bezogen haben, bleiben erhalten, nutzen das Wissen aber nicht mehr.`
    : `„${p.doc.filename}“ wird endgültig gelöscht und steht im Chat nicht mehr als Quelle zur Verfügung.`;
});
function askDelete(p: Pending) {
  deleteError.value = "";
  pendingDelete.value = p;
}
async function confirmDelete() {
  const p = pendingDelete.value;
  if (!p || !auth.selectedOrgId || !auth.selectedSpaceId) return;
  deleting.value = true;
  const r = p.kind === "kb"
    ? await deleteKnowledgeBase(auth.selectedOrgId, auth.selectedSpaceId, p.kb.id)
    : await deleteDocument(auth.selectedOrgId, auth.selectedSpaceId, p.doc.kbId, p.doc.id);
  deleting.value = false;
  if (r.kind === "ok" || r.kind === "not-found") {
    pendingDelete.value = null;
    showToast("ok", p.kind === "kb" ? "Wissensbasis gelöscht." : "Dokument gelöscht.");
    await auth.loadKnowledge();
  } else {
    deleteError.value = manageErrorText(r.kind);
  }
}

/* ---------- Dokumente ---------- */
const uploading = ref(false);
const uploadFileName = ref("");
const fileInputRef = ref<HTMLInputElement | null>(null);
const dragOver = ref(false);

function openFilePicker() {
  fileInputRef.value?.click();
}

async function uploadFiles(files: File[]) {
  for (const file of files) {
    await uploadOne(file);
  }
}

async function uploadOne(file: File) {
  if (auth.selectedKbId === null) return;
  uploading.value = true;
  uploadFileName.value = file.name;
  clearToast();
  const outcome = await auth.uploadDocument(file);
  uploading.value = false;
  uploadFileName.value = "";
  switch (outcome.kind) {
    case "uploaded":
      // zurück zur Liste, damit die neue Datei sichtbar ist
      query.value = "";
      auth.searchResult = null;
      showToast("ok", `„${outcome.document.filename}“ hochgeladen (${formatSize(outcome.document.sizeBytes)}) – ${statusLabel(outcome.document.indexStatus)}.`);
      break;
    case "rejected-type":
      showToast("error", `„${file.name}“ konnte nicht übernommen werden. Erlaubt sind ${acceptText}; die Datei muss lesbar und nicht verschlüsselt sein.`);
      break;
    case "too-large":
      showToast("error", `„${file.name}“ ist zu groß. Die Obergrenze liegt standardmäßig bei 20 MB.`);
      break;
    case "parser-busy":
      showToast("error", `Der Server ist gerade ausgelastet. Bitte lade „${file.name}“ gleich noch einmal hoch.`);
      break;
    case "parent-gone":
      showToast("error", "Die Wissensbasis ist nicht mehr verfügbar. Die Ansicht wurde aktualisiert.");
      break;
    default:
      showToast("error", commonError[outcome.kind] ?? commonError.error);
  }
}

async function onFileChosen(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = input.files ? Array.from(input.files) : [];
  input.value = "";
  await uploadFiles(files);
}

async function onDrop(event: DragEvent) {
  dragOver.value = false;
  const files = event.dataTransfer?.files ? Array.from(event.dataTransfer.files) : [];
  await uploadFiles(files);
}

async function download(doc: KnowledgeDocument) {
  if (!auth.selectedOrgId || !auth.selectedSpaceId) return;
  try {
    const res = await desktopFetch(
      `${serverUrl}/api/v1/orgs/${auth.selectedOrgId}/spaces/${auth.selectedSpaceId}/knowledge-bases/${doc.kbId}/documents/${doc.id}/download`,
    );
    if (!res.ok) throw new Error(String(res.status));
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = doc.filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  } catch {
    showToast("error", "Das Dokument konnte nicht geöffnet werden.");
  }
}

async function reindex(doc: KnowledgeDocument) {
  if (!auth.selectedOrgId || !auth.selectedSpaceId) return;
  const r = await reindexDocument(auth.selectedOrgId, auth.selectedSpaceId, doc.kbId, doc.id);
  if (r.kind === "ok") {
    showToast("ok", `„${doc.filename}“ wird erneut verarbeitet.`);
    await auth.loadKnowledge();
  } else {
    showToast("error", r.kind === "invalid" ? `„${doc.filename}“ konnte nicht gelesen werden.` : manageErrorText(r.kind));
  }
}

/* Async-Indexierung: solange etwas verarbeitet wird, alle 3 s nachladen. */
let pollTimer: ReturnType<typeof setInterval> | null = null;
watch(
  () => auth.documents.some((d) => d.indexStatus === "PENDING"),
  (pending) => {
    if (pending && pollTimer === null) {
      pollTimer = setInterval(() => void auth.loadKnowledge(), 3000);
    } else if (!pending && pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  if (pollTimer !== null) clearInterval(pollTimer);
});

/* ---------- Suche ---------- */
const query = ref("");
const searching = ref(false);
const canSearch = computed(() => query.value.trim() !== "" && !searching.value && auth.selectedKbId !== null);

async function submitSearch() {
  if (!canSearch.value) return;
  searching.value = true;
  clearToast();
  const outcome = await auth.searchKb(query.value.trim());
  searching.value = false;
  if (outcome.kind === "ok") return;
  showToast(
    "error",
    outcome.kind === "invalid-query" ? `Bitte einen Suchbegriff mit 1 bis ${SEARCH_QUERY_MAX_LENGTH} Zeichen eingeben.`
    : outcome.kind === "parent-gone" ? "Die Wissensbasis ist nicht mehr verfügbar. Die Ansicht wurde aktualisiert."
    : commonError[outcome.kind] ?? commonError.error,
  );
}
function clearSearch() {
  query.value = "";
  auth.searchResult = null;
}

function pickKb(kbId: number) {
  clearToast();
  query.value = "";
  auth.selectKnowledgeBase(kbId);
}

async function reloadAll() {
  clearToast();
  await auth.loadKnowledge();
}
</script>

<template>
  <section class="kx" data-testid="knowledge-section">
    <Teleport defer to="#page-actions">
      <p class="crumbs">
        <span data-testid="knowledge-org">{{ auth.selectedOrg?.name || "–" }}</span>
        <AppIcon name="chevron" :size="13" />
        <span data-testid="knowledge-space">{{ auth.selectedSpace?.name || "–" }}</span>
      </p>
      <div class="kx-bar-actions">
        <Button variant="ghost" data-testid="knowledge-space-new" :disabled="auth.selectedOrgId === null" @click="openSpaceForm">
          <AppIcon name="plus" :size="13" /> Neuer Arbeitsbereich
        </Button>

        <Button variant="icon" class="p-[6px]" data-testid="knowledge-reload" title="Aktualisieren" aria-label="Aktualisieren" @click="reloadAll">
          <AppIcon name="refresh" :size="13" />
        </Button>
      </div>
    </Teleport>

    <p v-if="auth.knowledgeError" class="note error" role="alert" data-testid="knowledge-error">{{ auth.knowledgeError }}</p>
    <p v-if="toast" class="note" :class="toastKind" role="status" data-testid="knowledge-toast">{{ toast }}</p>

    <div v-if="auth.selectedSpaceId === null && auth.selectedOrgId !== null" class="empty">
      <h2>Noch kein Arbeitsbereich</h2>
      <p>Ein Arbeitsbereich bündelt Wissen und Chats eines Teams, zum Beispiel „Vertrieb“ oder „Produktion“.</p>
      <Button variant="primary" @click="openSpaceForm">Arbeitsbereich anlegen</Button>
    </div>

    <div v-else-if="auth.selectedSpaceId !== null" class="kx-grid">
      <KbList
        :kbs="auth.kbs"
        :selected-id="auth.selectedKbId"
        :can-manage="isOwner"
        :doc-count="auth.documents.length"
        @select="pickKb"
        @create="openKbForm"
        @rename="openRename"
        @remove="(kb) => askDelete({ kind: 'kb', kb })"
      />

      <div
        class="kx-main"
        :class="{ drag: dragOver }"
        @dragover.prevent="auth.selectedKbId !== null && (dragOver = true)"
        @dragleave.self="dragOver = false"
        @drop.prevent="onDrop"
      >
        <template v-if="auth.selectedKb">
          <div class="kx-head">
            <div class="kx-heading"><h2 class="kx-title">{{ auth.selectedKb.name }}</h2><p class="kx-description"><span class="ic-num">{{auth.documents.length}}</span> {{ auth.documents.length === 1 ? 'Dokument' : 'Dokumente' }} · Dateien und Inhalte dieser Wissensbasis</p></div>
            <Button variant="primary" data-testid="knowledge-upload-button" :disabled="uploading" @click="openFilePicker"><AppIcon name="upload" :size="16" />Dateien hochladen</Button>
            <input
              id="knowledge-upload"
              ref="fileInputRef"
              data-testid="knowledge-upload-input"
              class="sr-only"
              type="file"
              multiple
              :accept="acceptAttr"
              tabindex="-1"
              aria-hidden="true"
              @change="onFileChosen"
            />
          </div>

          <form class="search" data-testid="knowledge-search-form" role="search" @submit.prevent="submitSearch">
            <div class="search-entry"><Input label="In dieser Wissensbasis suchen" label-hidden icon="search"
              id="knowledge-search-query"
              v-model="query"
              data-testid="knowledge-search-query"
              type="search"
              :maxlength="SEARCH_QUERY_MAX_LENGTH"
              :placeholder="`In „${auth.selectedKb.name}“ suchen …`"
              autocomplete="off"
            /></div>
            <Button v-if="auth.searchResult" variant="ghost" @click="clearSearch">Zurücksetzen</Button>
            <Button type="submit" variant="secondary" data-testid="knowledge-search-submit" :disabled="!canSearch">
              {{ searching ? "Suche…" : "Suchen" }}
            </Button>
          </form>

          <p v-if="uploading" class="progress" role="status" data-testid="knowledge-upload-progress">
            <span class="spinner" aria-hidden="true"></span> „{{ uploadFileName }}“ wird hochgeladen …
          </p>

          <section v-if="auth.searchResult" class="results" aria-label="Suchergebnisse">
            <p class="results-sum" data-testid="knowledge-search-summary">
              <span class="ic-num">{{ auth.searchResult.results.length }}</span>
              {{ auth.searchResult.results.length === 1 ? "Fundstelle" : "Fundstellen" }} für „{{ auth.searchResult.query }}“
            </p>
            <ol v-if="auth.searchResult.results.length" class="hits" data-testid="knowledge-result-list">
              <li v-for="(hit, i) in auth.searchResult.results" :key="i" class="hit" data-testid="knowledge-result-item">
                <p class="hit-src" data-testid="knowledge-result-source">
                  <AppIcon name="doc" :size="13" />
                  <button type="button" class="source-name" @click="openSearchSource(hit)">{{ hit.filename }}</button>
                  <span class="hit-part">{{ hit.pageNumber ? `${sourcePageLabel(hit.filename)} ${hit.pageNumber} · ` : "" }}{{ hit.sectionTitle || `Abschnitt ${hit.chunkIdx + 1}` }}</span>
                  <span data-testid="knowledge-result-score" hidden>{{ hit.score.toFixed(3) }}</span>
                </p>
                <SourceFreshness :value="hit.sourceUpdatedAt" />
                <p class="hit-text" data-testid="knowledge-result-snippet">{{ hit.text }}</p>
              </li>
            </ol>
            <p v-else class="muted" data-testid="knowledge-result-empty">Nichts gefunden. Versuche andere oder weniger Begriffe.</p>
          </section>

          <div v-else-if="(auth.documentsBusy || auth.knowledgeBusy) && !auth.documents.length && !uploading" class="knowledge-loading" role="status" data-testid="knowledge-loading">Dokumente werden geladen …</div>
          <DocumentTable
            v-else
            :key="auth.selectedKb.id"
            :documents="auth.documents"
            :can-change="canChange"
            @download="download"
            @reindex="reindex"
            @remove="(doc) => askDelete({ kind: 'doc', doc })"
          >
            <template #empty>
              <div class="drop" data-testid="knowledge-doc-empty">
                <AppIcon name="upload" />
                <p><strong>Noch keine Dokumente.</strong> Ziehe Dateien hierher oder wähle „Hochladen“.</p>
                <p class="muted">{{ acceptText }} · bis 20 MB je Datei</p>
              </div>
            </template>
          </DocumentTable>
        </template>
        <div v-else class="empty slim">
          <p>Wähle eine Wissensbasis aus oder lege eine neue an.</p>
        </div>
      </div>
    </div>

    <AppDialog :open="spaceFormOpen" title="Neuer Arbeitsbereich" :error="spaceError" @close="spaceFormOpen = false">
      <form data-testid="knowledge-space-form" class="dlg" @submit.prevent="submitCreateSpace">
        <Input id="knowledge-space-name" v-model="spaceName" label="Name" data-testid="knowledge-space-name" :maxlength="SPACE_NAME_MAX_LENGTH" autocomplete="off" :disabled="creatingSpace" required />
        <div class="dlg-actions">
          <Button variant="ghost" @click="spaceFormOpen = false">Abbrechen</Button>
          <Button type="submit" variant="primary" data-testid="knowledge-space-submit" :disabled="!canCreateSpace">{{ creatingSpace ? "Wird angelegt…" : "Anlegen" }}</Button>
        </div>
      </form>
    </AppDialog>

    <AppDialog :open="kbFormOpen" title="Neue Wissensbasis" :error="kbError" @close="kbFormOpen = false">
      <form data-testid="knowledge-kb-form" class="dlg" @submit.prevent="submitCreateKb">
        <Input id="knowledge-kb-name" v-model="kbName" label="Name" data-testid="knowledge-kb-name" :maxlength="KB_NAME_MAX_LENGTH" autocomplete="off" :disabled="creatingKb" required />
        <div class="dlg-actions">
          <Button variant="ghost" @click="kbFormOpen = false">Abbrechen</Button>
          <Button type="submit" variant="primary" data-testid="knowledge-kb-submit" :disabled="!canCreateKb">{{ creatingKb ? "Wird angelegt…" : "Anlegen" }}</Button>
        </div>
      </form>
    </AppDialog>

    <AppDialog :open="renameTarget !== null" title="Wissensbasis umbenennen" :error="renameError" @close="renameTarget = null">
      <form class="dlg" data-testid="knowledge-kb-rename-form" @submit.prevent="submitRename">
        <Input id="knowledge-kb-rename" v-model="renameValue" label="Neuer Name" :maxlength="KB_NAME_MAX_LENGTH" autocomplete="off" :disabled="renaming" required />
        <div class="dlg-actions">
          <Button variant="ghost" @click="renameTarget = null">Abbrechen</Button>
          <Button type="submit" variant="primary" :disabled="renaming || renameValue.trim() === ''">Speichern</Button>
        </div>
      </form>
    </AppDialog>

    <ConfirmDialog
      :open="pendingDelete !== null"
      :title="deleteTitle"
      :message="deleteMessage"
      :busy="deleting"
      :error="deleteError"
      confirm-label="Endgültig löschen"
      @confirm="confirmDelete"
      @close="pendingDelete = null"
    />
    <SourcePreview v-if="previewSource" :source="previewSource" @close="previewSource = null" @expired="previewExpired" />
  </section>
</template>

<style scoped>
.knowledge-loading { padding:32px 0; color:var(--ic-text-2); font-size:14px; }
.source-name { padding: 0; border: 0; background: transparent; color: inherit; font: inherit; text-decoration: underline; cursor: pointer; }
.kx-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ic-space-2);
  margin: calc(-1 * var(--ic-space-3)) 0 var(--ic-space-2);
}
.crumbs {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--ic-text-2);
  font-size: var(--ic-text-sm);
}
.kx-bar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.note {
  margin: 0 0 var(--ic-space-3);
  padding: 8px 12px;
  border-radius: var(--ic-radius-input);
  font-size: var(--ic-text-sm);
  background: var(--ic-hover-tint);
  color: var(--ic-text-2);
}
.note.ok {
  color: var(--ic-status-ok);
  background: color-mix(in srgb, var(--ic-status-ok) 8%, transparent);
}
.note.error {
  color: var(--ic-status-error);
  background: color-mix(in srgb, var(--ic-status-error) 8%, transparent);
}
.kx-grid { display:block; }
.kx-main { min-width:0; border-radius:var(--ic-radius-panel); transition:box-shadow var(--ic-transition); }
.kx-main.drag { box-shadow:0 0 0 2px var(--ic-accent); }
.kx-head { display:flex; align-items:flex-start; justify-content:space-between; flex-wrap:wrap; gap:16px; margin-bottom:24px; }
.kx-heading { min-width:0; flex:1; }
.kx-title { margin:0; font:600 20px/28px var(--ic-font); color:var(--ic-text); overflow-wrap:anywhere; }
.kx-description { margin:6px 0 0; color:var(--ic-text-2); font:400 13px/20px var(--ic-font); }
@media(min-width:1280px) { .kx-grid { display:grid; grid-template-columns:200px minmax(0,1fr); gap:32px; align-items:start; } }
@media(max-width:500px) { .kx-head { gap:16px; }.kx-heading { flex-basis:100%; }.kx-description { max-width:32ch; } }
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: var(--ic-space-4);
}
.search-entry :deep(.ic-field) { margin:0; }
.search-entry {
  flex: 1;
  margin: 0;
  min-width: 0;
}
.progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 var(--ic-space-3);
  font-size: var(--ic-text-sm);
  color: var(--ic-text-2);
}
.spinner {
  width: 12px;
  height: 12px;
  border: 1.5px solid var(--ic-separator);
  border-top-color: var(--ic-text-2);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.results-sum {
  margin: 0 0 var(--ic-space-3);
  font-size: var(--ic-text-sm);
  color: var(--ic-text-2);
}
.hits {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
}
.hit {
  padding: var(--ic-space-3) 0;
  border-bottom: 1px solid var(--ic-separator-faint);
}
.hit-src {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 4px;
  font-weight: 500;
  font-size: var(--ic-text-sm);
}
.hit-part {
  color: var(--ic-text-3);
  font-weight: 400;
}
.hit-text {
  margin: 0;
  color: var(--ic-text-2);
  font-size: var(--ic-text-sm);
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.drop {
  display: grid;
  justify-items: center;
  gap: 4px;
  padding: var(--ic-space-8) var(--ic-space-4);
  text-align: center;
  color: var(--ic-text-2);
  border: 1px dashed var(--ic-input-edge);
  border-radius: var(--ic-radius-panel);
}
.drop p {
  margin: 0;
}
.muted {
  color: var(--ic-text-3);
  font-size: var(--ic-text-sm);
}
.empty {
  display: grid;
  justify-items: start;
  gap: var(--ic-space-2);
  padding: var(--ic-space-8) 0;
  max-width: 520px;
}
.empty h2 {
  margin: 0;
  font-size: var(--ic-text-lg);
  font-weight: 600;
}
.empty p {
  margin: 0 0 var(--ic-space-2);
  color: var(--ic-text-2);
}
.empty.slim {
  padding: var(--ic-space-6) 0;
}
.dlg {
  display: grid;
  gap: var(--ic-space-4);
}
.dlg-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ic-space-2);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
@media (max-width: 760px) {
  .kx-grid {
    grid-template-columns: 1fr;
  }
}
</style>
