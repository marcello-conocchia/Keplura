<script setup lang="ts">
import { sourcePageLabel } from "../source-preview";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { documentSourceUrl, fetchSourcePreview, type DocumentPreview, type SourceSelection } from "../source-preview";
import Button from "./ui/Button.vue";
import SourceFreshness from "./ui/SourceFreshness.vue";
import AppIcon from "./ui/AppIcon.vue";

const props = defineProps<{source: SourceSelection}>();
const emit = defineEmits<{close: []; expired: []}>();
const preview = ref<DocumentPreview | null>(null);
const loading = ref(false);
const error = ref("");
const center = ref(props.source.chunkIdx);
const panel = ref<HTMLDialogElement | null>(null);
const closeButton = ref<HTMLButtonElement | null>(null);
const narrow = ref(false);
const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
let controller: AbortController | null = null;
let sequence = 0;
const media = window.matchMedia("(max-width: 1439px)");
function updateWidth() {
  narrow.value=media.matches; const d=panel.value;if(!d)return;
  const focus=document.activeElement instanceof HTMLElement&&d.contains(document.activeElement)?document.activeElement:null;
  if(d.open)d.close();if(narrow.value)d.showModal();else d.show();(focus??closeButton.value)?.focus();
}
const originalUrl = computed(() => `${documentSourceUrl(props.source)}/download`);
const emptyText = computed(() => preview.value?.indexStatus === "PENDING"
  ? "Das Dokument wird noch verarbeitet. Bitte versuche es gleich erneut."
  : preview.value?.indexStatus === "NO_TEXT_LAYER"
    ? "Dieses Dokument enthält keinen lesbaren Text. Du kannst das Original herunterladen."
    : "Für dieses Dokument ist noch keine Textvorschau verfügbar.");

async function load() {
  const ticket = ++sequence;
  controller?.abort(); const currentController = new AbortController(); controller = currentController;
  const timeout = setTimeout(() => currentController.abort(), 15000);
  loading.value = true; error.value = ""; preview.value = null;
  try {
    const result = await fetchSourcePreview(props.source, center.value, currentController.signal);
    if (ticket !== sequence) return;
    if (result.kind === "expired") { emit("expired"); return; }
    if (result.kind !== "ok") {
      error.value = result.kind === "gone"
        ? "Diese Textstelle ist nicht mehr verfügbar oder du hast keinen Zugriff mehr."
        : "Die Vorschau konnte nicht geladen werden. Bitte versuche es erneut.";
      return;
    }
    preview.value = result.preview;
    await nextTick();
    panel.value?.querySelector<HTMLElement>(`[data-source-chunk="${center.value}"]`)
      ?.scrollIntoView({block: "center", behavior: "auto"});
  } finally { clearTimeout(timeout); if (ticket === sequence) loading.value = false; }
}
function move(direction: -1 | 1) {
  const blocks = preview.value?.blocks;
  if (!blocks?.length) return;
  center.value = direction < 0 ? Math.max(0, blocks[0].chunkIdx - 1) : blocks[blocks.length - 1].chunkIdx + 1;
  void load();
}
function keyboard(event: KeyboardEvent) { if(event.key === "Escape" && !narrow.value && panel.value?.contains(event.target as Node)) { event.preventDefault(); emit("close"); } }
watch(() => props.source, () => { center.value = props.source.chunkIdx; void load(); }, {immediate: true});
onMounted(() => { updateWidth(); media.addEventListener("change", updateWidth); window.addEventListener("keydown", keyboard); closeButton.value?.focus(); });
onBeforeUnmount(() => { sequence++; controller?.abort(); media.removeEventListener("change", updateWidth); window.removeEventListener("keydown", keyboard); panel.value?.close(); if(previousFocus?.isConnected)previousFocus.focus(); });
</script>

<template>
  <Teleport to="body">
    <div v-if="narrow" class="source-scrim" aria-hidden="true" @click="emit('close')"></div>
    <dialog id="document-source-preview" @cancel.prevent="emit('close')" ref="panel" class="source-panel" role="dialog" :aria-modal="narrow" aria-label="Dokumentvorschau" data-testid="document-preview">
      <header class="source-header">
        <div class="min-w-0"><p class="source-caption">Dokumentvorschau · Textansicht</p><h2>{{ preview?.filename || source.filename }}</h2><SourceFreshness :value="preview ? preview.sourceUpdatedAt : source.sourceUpdatedAt" /></div>
        <button ref="closeButton" type="button" class="source-close" aria-label="Dokumentvorschau schließen" @click="emit('close')"><AppIcon name="close" :size="15" /></button>
      </header>
      <div class="source-tools" data-testid="chat-source-detail">
        <a :href="originalUrl" download data-testid="chat-source-download">Original herunterladen</a>
        <button v-if="center !== source.chunkIdx" type="button" @click="center = source.chunkIdx; load()">Zur zitierten Stelle</button>
      </div>
      <div class="source-reading" aria-live="polite">
        <p v-if="loading" role="status">Die Textstelle wird geladen …</p>
        <div v-else-if="error" role="alert"><p>{{ error }}</p><Button variant="secondary" @click="load">Erneut versuchen</Button></div>
        <template v-else-if="preview">
          <p v-if="!preview.blocks.length">{{ emptyText }}</p>
          <template v-else>
            <p v-if="!preview.exactLocation" class="source-caption">Ältere Quelle: Eine genaue Seitenposition wurde noch nicht gespeichert.</p>
            <Button v-if="preview.hasPrevious" variant="secondary" @click="move(-1)">Vorherige Textabschnitte</Button>
            <section v-for="(block, index) in preview.blocks" :key="block.chunkIdx" :data-source-chunk="block.chunkIdx" class="source-block" :class="{'source-cited': block.chunkIdx === source.chunkIdx}" data-testid="document-preview-block">
              <h3 v-if="block.pageNumber && (index === 0 || block.pageNumber !== preview.blocks[index - 1].pageNumber)">{{ sourcePageLabel(source.filename) }} {{ block.pageNumber }}</h3>
              <p v-if="block.chunkIdx === source.chunkIdx || block.sectionTitle && (index === 0 || block.sectionTitle !== preview.blocks[index - 1].sectionTitle)" class="source-section" data-testid="document-preview-location">{{ block.sectionTitle || `Abschnitt ${block.chunkIdx + 1}` }}</p>
              <p v-if="block.chunkIdx === source.chunkIdx" class="source-citation-label">Zitierte Textstelle</p>
              <mark v-if="block.chunkIdx === source.chunkIdx" data-testid="document-preview-highlight">{{ block.text }}</mark>
              <p v-else class="source-text">{{ block.text }}</p>
            </section>
            <Button v-if="preview.hasNext" variant="secondary" @click="move(1)">Weitere Textabschnitte</Button>
          </template>
        </template>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.source-scrim { position: fixed; inset: 0; z-index: 59; background: #0005; }
.source-panel { position: fixed; inset: 0 0 0 auto; z-index: 40; width: 400px; height:100dvh; max-height:none; max-width:none; margin:0; padding:0; display: flex; flex-direction: column; background: var(--ic-content); color: var(--ic-text); border-left: 1px solid var(--ic-separator); box-shadow:none; border-top:0; border-right:0; border-bottom:0; border-radius:0; }
.source-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 20px 24px 12px; border-bottom: 1px solid var(--ic-separator); }
.source-header h2 { margin: 4px 0 0; font-size: 16px; font-weight: 600; overflow-wrap: anywhere; }
.source-caption { margin: 0; font-size: 12px; color: var(--ic-text-3); }
.source-close { display: grid; place-items: center; flex: none; width: 32px; height: 32px; border-radius: 6px; border: 0; background: var(--ic-hover-tint); color: var(--ic-text); cursor: pointer; }
.source-tools { display: flex; flex-wrap: wrap; gap: 12px; padding: 12px 24px; font-size: 12px; border-bottom: 1px solid var(--ic-separator); }
.source-tools a, .source-tools button { color: var(--ic-accent); text-decoration: underline; }
.source-tools button { border: 0; background: transparent; padding: 0; font: inherit; cursor: pointer; }
.source-reading { flex: 1; min-height: 0; overflow: auto; padding: 20px 24px; font-size: 16px; line-height: 26px; overflow-wrap: anywhere; }
.source-block { margin: 0 0 20px; scroll-margin: 24px; }
.source-block h3 { margin: 20px 0 8px; font-size: 13px; color: var(--ic-text-2); }
.source-section { margin: 8px 0; font-weight: 600; }
.source-citation-label { margin: 0 0 6px; font-size: 13px; color: var(--ic-accent); }
.source-text, .source-block mark { display: block; margin: 0; white-space: pre-wrap; font: inherit; }
.source-block mark { background: var(--ic-selection); color: inherit; border-left: 3px solid var(--ic-accent); padding: 12px; border-radius: 4px; }
@media(max-width:1439px){.source-panel{width:min(720px,100vw);z-index:60}}
@media(max-width:767px){.source-panel{width:100vw}.source-close{width:44px;height:44px}.source-reading,.source-header{padding:16px}.source-tools a,.source-tools button{min-height:44px;display:inline-flex;align-items:center}}
.source-panel:not([open]){display:none}.source-panel::backdrop{background:var(--ic-scrim)}
</style>
