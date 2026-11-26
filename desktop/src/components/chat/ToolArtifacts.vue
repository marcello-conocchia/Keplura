<script setup lang="ts">
/**
 * TU-06d — files and images of a tool result. Images are shown as a preview from the owner-checked download (blob
 * URL, never a server URL in an <img>), every other type only as a download. Nothing is rendered as HTML; dropped
 * files show the server's fixed reason.
 */
import { onBeforeUnmount, ref, watch } from "vue";
import { fetchToolArtifact } from "../../productivity-api";
import { artifactSize, type ToolArtifact } from "../../tool-cards";
import { tr, serverText } from "../../i18n";

const props = defineProps<{ artifacts: ToolArtifact[]; orgId: number | null; conversationId: number | null }>();
const previews = ref<Record<string, string>>({});
const failed = ref<Record<string, boolean>>({});
let disposed = false;

function revokeAll() { Object.values(previews.value).forEach((u) => URL.revokeObjectURL(u)); previews.value = {}; }
onBeforeUnmount(() => { disposed = true; revokeAll(); });

async function loadPreviews() {
  revokeAll();
  const org = props.orgId, cid = props.conversationId;
  if (org === null || cid === null) return;
  for (const a of props.artifacts) {
    if (!a.image || a.droppedReason) continue;
    const blob = await fetchToolArtifact(org, cid, a.id);
    if (disposed) return;
    // Only verified image types become a preview; the server already sent a fixed image/* type with nosniff.
    if (blob && /^image\/(png|jpeg|webp|gif)$/.test(blob.type)) previews.value = { ...previews.value, [a.id]: URL.createObjectURL(blob) };
    else failed.value = { ...failed.value, [a.id]: true };
  }
}
watch(() => [props.artifacts.map((a) => a.id).join(","), props.orgId, props.conversationId], loadPreviews, { immediate: true });

async function download(a: ToolArtifact) {
  const org = props.orgId, cid = props.conversationId;
  if (org === null || cid === null) return;
  const blob = await fetchToolArtifact(org, cid, a.id);
  if (!blob) { failed.value = { ...failed.value, [a.id]: true }; return; }
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = a.name;
  link.rel = "noopener";
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}
</script>

<template>
  <div v-if="artifacts.length" class="tool-artifacts" data-testid="chat-tool-artifacts">
    <p class="tool-artifacts-kicker">{{ $t("Dateien aus dem Ergebnis") }}</p>
    <div v-for="a in artifacts" :key="a.id" class="tool-artifact" data-testid="chat-tool-artifact">
      <template v-if="a.droppedReason">
        <span class="tool-artifact-name">{{ a.name }}</span>
        <span class="tool-artifact-note">{{ tr("Verworfen: {0}", serverText(a.droppedReason)) }}</span>
      </template>
      <template v-else>
        <img v-if="a.image && previews[a.id]" :src="previews[a.id]" :alt="tr('Bild aus dem Werkzeugergebnis: {0}', a.name)" class="tool-artifact-image" />
        <span class="tool-artifact-name">{{ a.name }} <small>{{ a.mediaType }} · {{ artifactSize(a.size) }}</small></span>
        <button type="button" class="tool-artifact-download" @click="download(a)">{{ $t("Herunterladen") }}</button>
        <span v-if="failed[a.id]" class="tool-artifact-note" role="alert">{{ $t("Die Datei ist nicht mehr verfügbar.") }}</span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.tool-artifacts { margin-top: 8px; display: grid; gap: 6px; }
.tool-artifacts-kicker { margin: 0; color: var(--ic-text-3); font-size: 12px; }
.tool-artifact { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.tool-artifact-image { display: block; max-width: min(100%, 320px); max-height: 240px; border-radius: var(--ic-radius-small); border: 1px solid var(--ic-border); }
.tool-artifact-name { overflow-wrap: anywhere; font-size: 13px; }
.tool-artifact-name small { color: var(--ic-text-3); }
.tool-artifact-note { color: var(--ic-text-2); font-size: 12px; }
.tool-artifact-download { min-height: 28px; padding: 0 8px; border: 1px solid var(--ic-border); border-radius: 6px; background: transparent; color: var(--ic-text); font: 500 12px/1 var(--ic-font); cursor: pointer; }
@media(max-width:767px),(any-pointer:coarse){ .tool-artifact-download { min-height: 44px; } }
</style>
