<script setup lang="ts">
/** Administration → Suche & Wissen (ADR-0008, Scope D): reine Anzeige der Such- und Verarbeitungseinstellungen. */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { fetchRagSettings } from "../../admin-api";
import { useAuthStore } from "../../auth-store";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";

const emit = defineEmits<{ forbidden: [] }>();
const auth = useAuthStore();
const rag = ref<Record<string, unknown> | null>(null);
const loading = ref(true), loadError = ref("");
let disposed = false;
onBeforeUnmount(() => { disposed = true; });

async function load() {
  loading.value = true;
  const r = await fetchRagSettings();
  if (disposed) return;
  loading.value = false;
  if (r.kind === "ok") { rag.value = r.value; loadError.value = ""; }
  else if (r.kind === "forbidden") { emit("forbidden"); loadError.value = "Dafür sind Administrationsrechte nötig."; }
  else if (r.kind === "expired") auth.dropExpiredSession();
  else loadError.value = "Sucheinstellungen konnten nicht geladen werden.";
}
onMounted(load);

const sub = (key: string) => (rag.value?.[key] ?? {}) as Record<string, unknown>;
const show = (v: unknown) => (v === undefined || v === null || v === "" ? "–" : String(v));
const searchRows = computed(() => rag.value ? [
  { label: "Suchmodell", value: show(rag.value.activeEmbeddingModel || "Modell aus der Installation"), testId: "admin-rag-active-model" },
  { label: "Dimension", value: show(rag.value.activeEmbeddingDim) },
  { label: "Modellverbindung", value: rag.value.envEmbeddingBackend === "gateway" ? "Modellserver" : "Lokale Testsuche" },
  { label: "Automatische Neuindexierung", value: rag.value.reindexOnModelChange ? "ja" : "nein", testId: "admin-rag-auto-reindex" },
] : []);
const processingRows = computed(() => rag.value ? [
  { label: "Abschnittslänge (Zeichen)", value: show(sub("chunking").chunkSizeChars) },
  { label: "Gemeinsamer Übergang (Zeichen)", value: show(sub("chunking").chunkOverlapChars) },
  { label: "Berücksichtigte Chatnachrichten", value: show(sub("chat").contextMessages) },
  { label: "Quellen je Antwort", value: show(sub("chat").ragTopK) },
  { label: "Maximale Fragenlänge (Zeichen)", value: show(sub("chat").maxPromptChars) },
] : []);
</script>

<template>
  <section class="connection-page" data-testid="admin-rag-section">
    <header class="connection-header">
      <div class="connection-heading"><h2>Suche &amp; Wissen</h2><p class="connection-description">Wie Keplura Dokumente durchsuchbar macht und wie viel davon in eine Antwort einfließt.</p></div>
      <div class="connection-actions"><Button variant="icon" size="compact" aria-label="Sucheinstellungen aktualisieren" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" /></Button></div>
    </header>

    <p v-if="loading && !rag" class="connection-loading" role="status" data-testid="admin-rag-loading">Einstellungen werden geladen …</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{ loadError }}</p><Button size="compact" @click="load">Erneut versuchen</Button></div>

    <template v-if="rag">
      <section class="connection-section">
        <h3>Dokumentsuche</h3>
        <dl class="ss-list"><div v-for="row in searchRows" :key="row.label" class="ss-row" :data-testid="row.testId"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div></dl>
        <p class="connection-note" data-testid="admin-rag-hint">Nach einem Wechsel des Suchmodells müssen bestehende Dokumente erneut verarbeitet werden. Die Aktion findest du unter „Wissen“.</p>
      </section>
      <section class="connection-section">
        <h3>Verarbeitung und Antwortumfang</h3>
        <p class="connection-hint" data-testid="admin-rag-readonly-note">Diese Werte werden bei der Installation festgelegt. Änderungen wirken sich auf die Verarbeitung aller Dokumente aus.</p>
        <dl class="ss-list" data-testid="admin-rag-settings"><div v-for="row in processingRows" :key="row.label" class="ss-row"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div></dl>
      </section>
    </template>
  </section>
</template>

<style scoped>
.ss-list { margin:0;min-width:0; }
.ss-row { display:flex;justify-content:space-between;gap:16px;padding:12px 0;border-bottom:1px solid var(--ic-separator-faint);font:400 14px/20px var(--ic-font); }
.ss-row dt { color:var(--ic-text-2); }.ss-row dd { margin:0;color:var(--ic-text);font-weight:500;text-align:right;overflow-wrap:anywhere; }
@media(max-width:500px){.ss-row{flex-direction:column;gap:4px}.ss-row dd{text-align:left}}
</style>
