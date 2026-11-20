<script setup lang="ts">
/** Administration → Systemstatus (ADR-0008, Scope E). Zeigt nur Zustände, nie Zugangsschlüssel. */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { fetchSystemStatus, type ComponentState } from "../../admin-api";
import { useAuthStore } from "../../auth-store";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";
import StatusPill from "../ui/StatusPill.vue";

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
  else if (s.kind === "forbidden") { emit("forbidden"); loadError.value = "Dafür sind Administrationsrechte nötig."; }
  else if (s.kind === "expired") auth.dropExpiredSession();
  else loadError.value = "Systemstatus konnte nicht geladen werden.";
}
onMounted(load);

const LABELS: Record<string, string> = {
  backend: "Keplura-Server", database: "Datenbank", providers: "KI-Anbieter",
  chatModel: "Antwortmodell", embeddingModel: "Dokumentsuche", secretStore: "Geschützte Zugangsdaten",
};
const STATE_LABELS: Record<string, string> = { ok: "Bereit", degraded: "Eingeschränkt", unreachable: "Nicht erreichbar",
  disabled: "Deaktiviert", not_configured: "Nicht eingerichtet", missing: "Fehlt", unavailable: "Nicht verfügbar" };
function stateLabel(s: ComponentState | null) { return STATE_LABELS[s?.state ?? ""] ?? "Bitte prüfen"; }
function stateTone(s: ComponentState | null): "ok" | "wait" | "error" | "neutral" {
  if (!s) return "neutral";
  if (s.state === "ok") return "ok";
  if (s.state === "degraded") return "wait";
  if (s.state === "unreachable" || s.state === "missing" || s.state === "unavailable") return "error";
  return "neutral";
}
const rows = computed(() => Object.keys(LABELS).map((key) => ({ key, label: LABELS[key], state: status.value?.[key] ?? null })));
const problems = computed(() => rows.value.filter((r) => r.state && r.state.state !== "ok").length);
</script>

<template>
  <section class="connection-page" data-testid="admin-status-section">
    <header class="connection-header">
      <div class="connection-heading"><h2>Systemstatus</h2><p class="connection-description">Ob Server, Datenbank und KI-Verbindungen bereit sind.</p></div>
      <div class="connection-actions"><Button variant="icon" size="compact" aria-label="Systemstatus aktualisieren" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" /></Button></div>
    </header>

    <p v-if="loading && !status" class="connection-loading" role="status">Status wird geprüft …</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{ loadError }}</p><Button size="compact" @click="load">Erneut versuchen</Button></div>

    <template v-if="status">
      <p class="connection-note" :class="problems ? '' : 'ok'" role="status">{{ problems ? `${problems} ${problems === 1 ? "Bereich braucht" : "Bereiche brauchen"} Aufmerksamkeit.` : "Alle Bereiche sind bereit." }}</p>
      <div class="connection-list">
        <article v-for="row in rows" :key="row.key" class="connection-record st-row" :data-testid="`admin-status-${row.key}`">
          <h3>{{ row.label }}</h3>
          <StatusPill :tone="stateTone(row.state)">{{ stateLabel(row.state) }}</StatusPill>
        </article>
      </div>
      <p class="connection-hint">Eine Verbindung kannst du unter „KI-Anbieter“ mit „Verbindung testen“ prüfen. Zugangsschlüssel werden hier niemals angezeigt.</p>
    </template>
  </section>
</template>

<style scoped>
.st-row { display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 0; }.st-row:first-child { padding-top:0; }
</style>
