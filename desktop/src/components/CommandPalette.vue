<script setup lang="ts">
/**
 * Globale Suche (Strg/⌘+K): Arbeitsbereiche, Wissensbasen, Dokumente und eigene
 * Chats der aktuellen Organisation. Pfeiltasten + Enter, Escape schließt.
 * Öffnen auch per Ereignis "ic-open-palette" (z. B. Suchknopf in der Sidebar).
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useAuthStore } from "../auth-store";
import { globalSearch, type GlobalSearchResult } from "../productivity-api";
import AppIcon from "./ui/AppIcon.vue";

type View = "chat" | "wissen";
const emit = defineEmits<{ navigate: [View] }>();
const auth = useAuthStore();

const open = ref(false);
const q = ref("");
const result = ref<GlobalSearchResult | null>(null);
const loading = ref(false);
const active = ref(0);
const input = ref<HTMLInputElement | null>(null);

interface Item { key: string; group: string; label: string; hint: string; icon: "folder" | "book" | "doc" | "chat"; run: () => Promise<void> | void }

const items = computed<Item[]>(() => {
  const r = result.value;
  if (!r) return [];
  const out: Item[] = [];
  for (const s of r.spaces) out.push({ key: `s${s.id}`, group: "Arbeitsbereiche", label: s.name, hint: "", icon: "folder",
    run: async () => { await auth.selectSpace(s.id); emit("navigate", "chat"); } });
  for (const k of r.knowledgeBases) out.push({ key: `k${k.id}`, group: "Wissensbasen", label: k.name, hint: k.spaceName, icon: "book",
    run: () => openKb(k.spaceId, k.id) });
  for (const d of r.documents) out.push({ key: `d${d.id}`, group: "Dokumente", label: d.filename, hint: d.kbName, icon: "doc",
    run: () => openKb(d.spaceId, d.kbId) });
  for (const c of r.conversations) out.push({ key: `c${c.id}`, group: "Chats", label: c.title || "Unbenannter Chat", hint: "", icon: "chat",
    run: async () => { await auth.selectSpace(c.spaceId); emit("navigate", "chat"); await nextTick(); auth.requestConversation("open", c.id); } });
  return out;
});

async function openKb(spaceId: number, kbId: number) {
  await auth.selectSpace(spaceId);
  auth.selectKnowledgeBase(kbId);
  emit("navigate", "wissen");
}

let timer: ReturnType<typeof setTimeout> | undefined;
let seq = 0;
watch(q, (v) => {
  clearTimeout(timer);
  const term = v.trim();
  if (!term || auth.selectedOrgId === null) { result.value = null; loading.value = false; return; }
  loading.value = true;
  timer = setTimeout(async () => {
    const n = ++seq;
    const r = await globalSearch(auth.selectedOrgId!, term);
    if (n !== seq) return;
    loading.value = false;
    result.value = r.kind === "ok" ? r.value : null;
    active.value = 0;
  }, 180);
});

async function show() {
  if (!auth.loggedIn) return;
  open.value = true;
  q.value = "";
  result.value = null;
  await nextTick();
  input.value?.focus();
}
function close() { open.value = false; }

async function choose(i: number) {
  const it = items.value[i];
  if (!it) return;
  close();
  await it.run();
}

function onKey(e: KeyboardEvent) {
  if (e.key === "ArrowDown") { e.preventDefault(); active.value = Math.min(active.value + 1, items.value.length - 1); scroll(); }
  else if (e.key === "ArrowUp") { e.preventDefault(); active.value = Math.max(active.value - 1, 0); scroll(); }
  else if (e.key === "Enter") { e.preventDefault(); void choose(active.value); }
  else if (e.key === "Escape") { e.preventDefault(); close(); }
}
function scroll() {
  void nextTick(() => document.querySelector(".cp-item.is-active")?.scrollIntoView({ block: "nearest" }));
}
function globalKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "k") {
    e.preventDefault();
    if (open.value) close(); else void show();
  }
}
const onOpenEvent = () => void show();
onMounted(() => { window.addEventListener("keydown", globalKey); window.addEventListener("ic-open-palette", onOpenEvent); });
onBeforeUnmount(() => { window.removeEventListener("keydown", globalKey); window.removeEventListener("ic-open-palette", onOpenEvent); clearTimeout(timer); });
</script>

<template>
  <div v-if="open" class="cp-scrim" data-testid="command-palette" @mousedown.self="close">
    <div class="cp" role="dialog" aria-modal="true" aria-label="Globale Suche">
      <div class="cp-head">
        <AppIcon name="search" :size="15" />
        <input ref="input" v-model="q" class="cp-input" placeholder="Arbeitsbereiche, Wissensbasen, Dokumente, Chats suchen…"
          aria-label="Suchbegriff" data-testid="command-palette-input" @keydown="onKey" />
        <kbd>Esc</kbd>
      </div>
      <div class="cp-body" role="listbox">
        <p v-if="!q.trim()" class="cp-empty">Tippe, um in deiner Organisation zu suchen.</p>
        <p v-else-if="loading && !items.length" class="cp-empty">Suche läuft…</p>
        <p v-else-if="!items.length" class="cp-empty">Keine Treffer für „{{ q.trim() }}“.</p>
        <template v-for="(it, i) in items" :key="it.key">
          <div v-if="i === 0 || items[i - 1].group !== it.group" class="cp-group">{{ it.group }}</div>
          <button type="button" class="cp-item" :class="{ 'is-active': i === active }" role="option" :aria-selected="i === active"
            data-testid="command-palette-item" @mouseenter="active = i" @click="choose(i)">
            <AppIcon :name="it.icon" :size="13" />
            <span class="cp-label">{{ it.label }}</span>
            <span v-if="it.hint" class="cp-hint">{{ it.hint }}</span>
          </button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cp-scrim { position: fixed; inset: 0; z-index: 60; display: flex; justify-content: center; padding: 12vh 16px 16px; background: rgb(0 0 0 / .28); }
.cp { width: min(620px, 100%); max-height: 64vh; display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--ic-separator-faint); border-radius: 12px; background: var(--ic-surface); color: var(--ic-text); box-shadow: 0 20px 50px rgb(0 0 0 / .25); }
.cp-head { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--ic-separator-faint); color: var(--ic-text-3); }
.cp-input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--ic-text); font: 400 15px/1.4 var(--ic-font); }
kbd { padding: 2px 6px; border: 1px solid var(--ic-separator-faint); border-radius: 4px; font: 500 11px/1 var(--ic-font); color: var(--ic-text-3); }
.cp-body { overflow-y: auto; padding: 6px; }
.cp-empty { margin: 0; padding: 18px 10px; color: var(--ic-text-3); font-size: 13px; text-align: center; }
.cp-group { padding: 10px 10px 4px; color: var(--ic-text-3); font: 600 11px/1 var(--ic-font); text-transform: uppercase; letter-spacing: .04em; }
.cp-item { display: flex; align-items: center; gap: 10px; width: 100%; padding: 8px 10px; border: 0; border-radius: 6px; background: transparent; color: var(--ic-text-2); font: 400 13px/1.3 var(--ic-font); text-align: left; cursor: pointer; }
.cp-item.is-active { background: var(--ic-hover-tint); color: var(--ic-text); }
.cp-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cp-hint { flex-shrink: 0; max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ic-text-3); font-size: 12px; }
</style>
