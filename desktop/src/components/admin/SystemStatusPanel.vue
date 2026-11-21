<script setup lang="ts">
/** Administration → Systemstatus (ADR-0008, Scope E). Zeigt nur Zustände, nie Zugangsschlüssel. */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { fetchSystemStatus, type ComponentState } from "../../admin-api";
import { useAuthStore } from "../../auth-store";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";
import StatusPill from "../ui/StatusPill.vue";
import { tr } from "../../i18n";

const emit = defineEmits<{ forbidden: [] }>();
const auth = useAuthStore();
const status = ref<Record<string, ComponentState> | null>(null);
const loading = ref(true), loadError = ref("");
let disposed = false;
onBeforeUnmount(() => { disposed = true; });

async function load() {
  loading.value = true;
  const s = await fetchSystemStatus();
  if (disposed) return;
  loading.value = false;
  if (s.kind === "ok") { status.value = s.value; loadError.value = ""; }
  else if (s.kind === "forbidden") { emit("forbidden"); loadError.value = tr("Dafür sind Administrationsrechte nötig."); }
  else if (s.kind === "expired") auth.dropExpiredSession();
  else loadError.value = tr("Systemstatus konnte nicht geladen werden.");
}
onMounted(load);

const labels = (): Record<string, string> => ({
  backend: tr("Keplura-Server"), database: tr("Datenbank"), providers: tr("KI-Anbieter"),
  chatModel: tr("Antwortmodell"), embeddingModel: tr("Dokumentsuche"), secretStore: tr("Geschützte Zugangsdaten"),
});
const stateLabels = (): Record<string, string> => ({ ok: tr("Bereit"), degraded: tr("Eingeschränkt"), unreachable: tr("Nicht erreichbar"),
  disabled: tr("Deaktiviert"), not_configured: tr("Nicht eingerichtet"), missing: tr("Fehlt"), unavailable: tr("Nicht verfügbar") });
function stateLabel(s: ComponentState | null) { return stateLabels()[s?.state ?? ""] ?? tr("Bitte prüfen"); }
function stateTone(s: ComponentState | null): "ok" | "wait" | "error" | "neutral" {
  if (!s) return "neutral";
  if (s.state === "ok") return "ok";
  if (s.state === "degraded") return "wait";
  if (s.state === "unreachable" || s.state === "missing" || s.state === "unavailable") return "error";
  return "neutral";
}
const rows = computed(() => Object.keys(labels()).map((key) => ({ key, label: labels()[key], state: status.value?.[key] ?? null })));
const problems = computed(() => rows.value.filter((r) => r.state && r.state.state !== "ok").length);
</script>

<template>
  <section class="connection-page" data-testid="admin-status-section">
    <header class="connection-header">
      <div class="connection-heading"><h2>{{ $t("Systemstatus") }}</h2><p class="connection-description">{{ $t("Ob Server, Datenbank und KI-Verbindungen bereit sind.") }}</p></div>
      <div class="connection-actions"><Button variant="icon" size="compact" :aria-label="$t('Systemstatus aktualisieren')" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" /></Button></div>
    </header>

    <p v-if="loading && !status" class="connection-loading" role="status">{{ $t("Status wird geprüft …") }}</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{ loadError }}</p><Button size="compact" @click="load">{{ $t("Erneut versuchen") }}</Button></div>

    <template v-if="status">
      <p class="connection-note" :class="problems ? '' : 'ok'" role="status">{{ problems ? $t("{0} {1} Aufmerksamkeit.", problems, problems === 1 ? $t("Bereich braucht") : $t("Bereiche brauchen")) : $t("Alle Bereiche sind bereit.") }}</p>
      <div class="connection-list">
        <article v-for="row in rows" :key="row.key" class="connection-record st-row" :data-testid="`admin-status-${row.key}`">
          <h3>{{ row.label }}</h3>
          <StatusPill :tone="stateTone(row.state)">{{ stateLabel(row.state) }}</StatusPill>
        </article>
      </div>
      <p class="connection-hint">{{ $t("Eine Verbindung kannst du unter „KI-Anbieter“ mit „Verbindung testen“ prüfen. Zugangsschlüssel werden hier niemals angezeigt.") }}</p>
    </template>
  </section>
</template>

<style scoped>
.st-row { display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 0; }.st-row:first-child { padding-top:0; }
</style>
