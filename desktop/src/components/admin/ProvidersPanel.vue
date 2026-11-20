<script setup lang="ts">
/**
 * Administration → KI-Anbieter (ADR-0008, Scope B) im Katalogstil.
 * Zugangsschlüssel sind write-only: die API liefert nur {secretSet, secretMasked};
 * das Feld startet leer, leer = alter Wert bleibt, nur ein getippter Wert ersetzt.
 * Alle Autorisierung liegt beim Server (Session + CSRF + Rolle).
 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  createProvider, fetchProviders, fetchSeedOffer, patchProvider, seedFromEnv, testProvider,
  type AdminProvider, type ConnectionTestOutcomeView, type SeedOffer,
} from "../../admin-api";
import { useAuthStore } from "../../auth-store";
import AppDialog from "../ui/AppDialog.vue";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";
import Input from "../ui/Input.vue";
import StatusPill from "../ui/StatusPill.vue";
import ActionMenu from "../ui/ActionMenu.vue";

const emit = defineEmits<{ forbidden: [] }>();
const auth = useAuthStore();

const providers = ref<AdminProvider[]>([]);
const seedOffer = ref<SeedOffer | null>(null);
const loading = ref(true), loaded = ref(false), loadError = ref("");
const note = ref(""), noteKind = ref<"ok" | "error" | "">("");
let disposed = false;
/** Nur die jüngste Ladeanfrage darf die Liste setzen (ältere, langsamere Antworten verwerfen). */
let loadSeq = 0;

function show(kind: "ok" | "error", text: string) { noteKind.value = kind; note.value = text; }

async function load() {
  const my = ++loadSeq;
  loading.value = true;
  const [r, s] = await Promise.all([fetchProviders(), fetchSeedOffer()]);
  if (disposed || my !== loadSeq) return;
  loading.value = false;
  if (r.kind === "ok") { providers.value = r.value; loaded.value = true; loadError.value = ""; }
  else if (r.kind === "forbidden") { emit("forbidden"); loadError.value = "Dafür sind Administrationsrechte nötig."; }
  else if (r.kind === "expired") auth.dropExpiredSession();
  else loadError.value = "KI-Anbieter konnten nicht geladen werden.";
  if (s.kind === "ok") seedOffer.value = s.value;
}
onMounted(load);

/* ---------- Formular ---------- */
const formOpen = ref(false);
const editing = ref<AdminProvider | null>(null);
const formError = ref("");
const saving = ref(false);
const formDialog = ref<InstanceType<typeof AppDialog> | null>(null);
const f = ref({ name: "", type: "ollama" as AdminProvider["providerType"], baseUrl: "", secret: "", timeout: "30" });
const savedForm = ref("");
const formDirty = computed(() => JSON.stringify(f.value) !== savedForm.value);
onBeforeUnmount(() => { disposed = true; f.value.secret = ""; });

function openCreate() {
  editing.value = null; formError.value = ""; note.value = "";
  f.value = { name: "", type: "ollama", baseUrl: "", secret: "", timeout: "30" };
  savedForm.value = JSON.stringify(f.value); formOpen.value = true;
}
function openEdit(p: AdminProvider) {
  editing.value = p; formError.value = ""; note.value = "";
  // write-only: der alte Schlüssel bleibt, solange kein neuer getippt wird
  f.value = { name: p.name, type: p.providerType, baseUrl: p.baseUrl, secret: "", timeout: String(p.timeoutSeconds) };
  savedForm.value = JSON.stringify(f.value); formOpen.value = true;
}
function closeForm() { formOpen.value = false; f.value.secret = ""; }

async function save() {
  if (saving.value) return;
  saving.value = true; formError.value = "";
  try {
    const timeout = Number.parseInt(f.value.timeout, 10);
    const secret = f.value.secret === "" ? null : f.value.secret;
    const outcome = editing.value
      ? await patchProvider(editing.value.id, { name: f.value.name.trim(), baseUrl: f.value.baseUrl.trim(),
          timeoutSeconds: Number.isFinite(timeout) ? timeout : null, secret })
      : await createProvider({ name: f.value.name.trim(), providerType: f.value.type, baseUrl: f.value.baseUrl.trim(),
          secret, timeoutSeconds: Number.isFinite(timeout) ? timeout : null });
    if (outcome.kind === "ok") {
      const wasEdit = editing.value !== null;
      closeForm();
      show("ok", wasEdit ? "Provider aktualisiert." : "Provider angelegt.");
      await load();
    } else if (outcome.kind === "invalid") formError.value = outcome.message;
    else if (outcome.kind === "unavailable") formError.value = "Schlüssel können nicht gespeichert werden: Auf dem Server fehlt der Master-Key (KEPLURA_SECRETS_MASTER_KEY).";
    else if (outcome.kind === "forbidden") { emit("forbidden"); formError.value = "Dafür sind Administrationsrechte nötig."; }
    else if (outcome.kind === "expired") auth.dropExpiredSession();
    else formError.value = "Provider konnte nicht gespeichert werden.";
  } finally { saving.value = false; }
}

/* ---------- Aktivieren / Testen / Übernahme ---------- */
const working = ref(new Set<number>());
async function toggle(p: AdminProvider) {
  if (working.value.has(p.id)) return;
  working.value.add(p.id);
  try {
    const outcome = await patchProvider(p.id, { enabled: !p.enabled });
    if (outcome.kind === "ok") { show("ok", outcome.value.enabled ? "Provider aktiviert." : "Provider deaktiviert."); await load(); }
    else if (outcome.kind === "invalid") show("error", outcome.message);
    else if (outcome.kind === "forbidden") { emit("forbidden"); show("error", "Dafür sind Administrationsrechte nötig."); }
    else if (outcome.kind === "expired") auth.dropExpiredSession();
    else show("error", "Änderung fehlgeschlagen.");
  } finally { working.value.delete(p.id); }
}

/** Transientes Testergebnis je Zeile, wird nie gespeichert. */
const testResult = ref<Record<number, ConnectionTestOutcomeView | "pending">>({});
async function runTest(p: AdminProvider) {
  if (testResult.value[p.id] === "pending") return;
  testResult.value = { ...testResult.value, [p.id]: "pending" };
  const outcome = await testProvider(p.id);
  let view: ConnectionTestOutcomeView;
  if (outcome.kind === "ok") view = outcome.value;
  // Richtlinien-Ablehnung (SSRF) ist nicht „nicht erreichbar“ — Servertext ohne Regel/Host/Adresse
  else if (outcome.kind === "invalid") view = { reachable: false, code: "refused_by_policy: " + outcome.message };
  else if (outcome.kind === "expired") {
    const { [p.id]: _done, ...rest } = testResult.value; testResult.value = rest;
    auth.dropExpiredSession(); return;
  }
  else {
    view = { reachable: false, code: outcome.kind === "forbidden" ? "forbidden" : "test_failed" };
    if (outcome.kind === "forbidden") emit("forbidden");
  }
  testResult.value = { ...testResult.value, [p.id]: view };
}
function testLabel(p: AdminProvider): string {
  const r = testResult.value[p.id];
  if (r === "pending" || !r) return "";
  if (r.reachable) return r.code === "reachable_upstream_error" ? "Erreichbar (Upstream-Fehler)" : "Erreichbar";
  if (r.code.startsWith("refused_by_policy")) return "Ziel blockiert (SSRF-Richtlinie)";
  return "Nicht erreichbar";
}
function testTone(p: AdminProvider): "ok" | "error" | "neutral" {
  const r = testResult.value[p.id];
  if (r === "pending" || !r) return "neutral";
  return r.reachable ? "ok" : "error";
}

const seeding = ref(false);
async function runSeed() {
  if (seeding.value) return;
  seeding.value = true;
  try {
    const outcome = await seedFromEnv();
    if (outcome.kind === "ok") { show(outcome.value.created ? "ok" : "error", outcome.value.message); await load(); }
    else if (outcome.kind === "invalid") show("error", outcome.message);
    else if (outcome.kind === "forbidden") { emit("forbidden"); show("error", "Dafür sind Administrationsrechte nötig."); }
    else if (outcome.kind === "expired") auth.dropExpiredSession();
    else show("error", "Übernahme fehlgeschlagen.");
  } finally { seeding.value = false; }
}

const TYPE_LABEL: Record<AdminProvider["providerType"], string> = { ollama: "Ollama · lokal", vllm: "vLLM · eigener Modellserver", api: "Externer Anbieter" };
function rowAction(p: AdminProvider, action: string) { if (action === "edit") openEdit(p); else void toggle(p); }
</script>

<template>
  <section class="connection-page" data-testid="admin-providers-section">
    <header class="connection-header">
      <div class="connection-heading"><h2>KI-Anbieter</h2><p class="connection-description">Verbindungen zu lokalen Modellservern oder externen Anbietern, über die Keplura antwortet und Dokumente durchsuchbar macht.</p></div>
      <div class="connection-actions">
        <Button variant="icon" size="compact" aria-label="KI-Anbieter aktualisieren" data-testid="admin-reload" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" /></Button>
        <Button variant="primary" data-testid="admin-provider-create" @click="openCreate"><AppIcon name="plus" :size="16" />Anbieter hinzufügen</Button>
      </div>
    </header>

    <p v-if="loading && !loaded" class="connection-loading" role="status">KI-Anbieter werden geladen …</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{ loadError }}</p><Button size="compact" @click="load">Erneut versuchen</Button></div>
    <p v-if="note" class="connection-note" :class="noteKind" :role="noteKind === 'error' ? 'alert' : 'status'" data-testid="admin-toast">{{ note }}</p>

    <div v-if="seedOffer?.available" class="connection-note pv-seed" data-testid="admin-seed-box">
      <p>Verbindung aus der Installation: <b>{{ seedOffer.providerType }}</b> · <code>{{ seedOffer.baseUrl }}</code> · Modell {{ seedOffer.chatModel || "Standard" }}<template v-if="seedOffer.hasKey"> · Zugangsschlüssel wird geschützt übernommen</template></p>
      <Button size="compact" :busy="seeding" data-testid="admin-seed-button" @click="runSeed">Installationsvorgaben übernehmen</Button>
    </div>

    <div v-if="loaded" class="connection-list">
      <p v-if="!providers.length" class="connection-empty" data-testid="admin-providers-empty">
        {{ seedOffer?.available ? "Die Verbindung aus der Installation ist aktiv. Übernimm sie oben, um sie hier zu verwalten." : "Noch kein KI-Anbieter eingerichtet. Lege eine Verbindung zu einem lokalen Modell oder einem Anbieter an." }}
      </p>
      <article v-for="p in providers" :key="p.id" class="connection-record" data-testid="admin-provider-row">
        <header class="connection-record-header">
          <div class="connection-heading"><h3>{{ p.name }}</h3><code class="connection-address">{{ p.baseUrl }}</code>
            <p class="connection-meta">{{ TYPE_LABEL[p.providerType] ?? p.providerType }} · Wartezeit {{ p.timeoutSeconds }} s · Zugangsschlüssel: {{ p.secretSet ? p.secretMasked : "nicht nötig oder nicht gesetzt" }}</p></div>
          <div class="connection-actions">
            <StatusPill :tone="p.enabled ? 'info' : 'neutral'" :data-testid="p.enabled ? undefined : 'admin-provider-disabled'">{{ p.enabled ? "Aktiv" : "Deaktiviert" }}</StatusPill>
            <Button size="compact" :disabled="!p.enabled" :busy="testResult[p.id] === 'pending'" :data-testid="`admin-provider-test-${p.id}`" @click="runTest(p)">{{ testResult[p.id] === "pending" ? "Wird geprüft …" : "Verbindung testen" }}</Button>
            <ActionMenu :label="`Aktionen für ${p.name}`" :disabled="working.has(p.id)" :items="[{ id: 'edit', label: 'Bearbeiten', testId: `admin-provider-edit-${p.id}` }, { id: 'toggle', label: p.enabled ? 'Deaktivieren' : 'Aktivieren', testId: `admin-provider-toggle-${p.id}` }]" @select="(a) => rowAction(p, a)" />
          </div>
        </header>
        <p v-if="testLabel(p)" class="pv-test" data-testid="admin-provider-test-result"><StatusPill :tone="testTone(p)">{{ testLabel(p) }}</StatusPill></p>
      </article>
    </div>

    <details class="connection-details"><summary>Welche Verbindung brauche ich?</summary><p>Ollama und vLLM laufen auf eurem eigenen Server – Daten verlassen das Haus nicht. Ein externer Anbieter braucht einen Zugangsschlüssel; er wird verschlüsselt gespeichert und hier nie wieder angezeigt.</p></details>

    <AppDialog ref="formDialog" size="md" :busy="saving" :dirty="formDirty" :open="formOpen" :title="editing ? 'KI-Anbieter bearbeiten' : 'KI-Anbieter hinzufügen'" :error="formError" @close="closeForm">
      <form class="connection-form" data-testid="admin-provider-form" @submit.prevent="save">
        <Input id="admin-p-name" v-model="f.name" label="Anzeigename" data-testid="admin-provider-name" required />
        <label v-if="!editing" class="connection-field">Art der Verbindung
          <select id="admin-p-type" v-model="f.type" class="ic-native-control" data-testid="admin-provider-type">
            <option value="ollama">Ollama · lokales Modell</option>
            <option value="vllm">vLLM · eigener Modellserver</option>
            <option value="api">Externer Anbieter · OpenAI-kompatibel</option>
          </select>
        </label>
        <p v-else class="connection-field">Art der Verbindung <span class="connection-hint" data-testid="admin-provider-type-static">{{ TYPE_LABEL[editing.providerType] ?? editing.providerType }} (nachträglich nicht änderbar)</span></p>
        <Input id="admin-p-url" v-model="f.baseUrl" label="Adresse (Base-URL)" type="url" placeholder="http://127.0.0.1:11434/v1" data-testid="admin-provider-baseurl" required />
        <Input id="admin-p-secret" v-model="f.secret" :label="editing ? 'Zugangsschlüssel' : 'Zugangsschlüssel (falls erforderlich)'" type="password"
          :placeholder="editing?.secretSet ? 'bleibt gespeichert – nur zum Ersetzen eingeben' : 'nur für externe Anbieter nötig'"
          autocomplete="new-password" data-testid="admin-provider-secret" />
        <Input id="admin-p-timeout" v-model="f.timeout" label="Maximale Wartezeit (Sekunden)" type="number" data-testid="admin-provider-timeout" />
        <div class="connection-submit">
          <Button variant="ghost" type="button" data-testid="admin-provider-cancel" @click="formDialog?.requestClose()">Abbrechen</Button>
          <Button variant="primary" type="submit" :busy="saving" data-testid="admin-provider-save">{{ editing ? "Speichern" : "Hinzufügen" }}</Button>
        </div>
      </form>
    </AppDialog>
  </section>
</template>

<style scoped>
.pv-seed { display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap; }.pv-seed p { margin:0; }.pv-seed code { overflow-wrap:anywhere; }
.pv-test { margin:12px 0 0; }
</style>
