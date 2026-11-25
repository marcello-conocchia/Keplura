<script setup lang="ts">
/**
 * Administration → Modelle (ADR-0008, Scope C/D) im Katalogstil.
 * Ein Wechsel des Such-Standardmodells wird bestätigt, weil er die Suche ändert,
 * ohne bestehende Dokumente neu zu verarbeiten (PO-Regel).
 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  createModel, fetchModels, fetchProviders, patchModel, setModelDefault, updateModelTools, MODEL_TOOL_KINDS,
  type AdminModel, type AdminProvider,
} from "../../admin-api";
import { useAuthStore } from "../../auth-store";
import AppDialog from "../ui/AppDialog.vue";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";
import ConfirmDialog from "../ui/ConfirmDialog.vue";
import Input from "../ui/Input.vue";
import StatusPill from "../ui/StatusPill.vue";
import ActionMenu from "../ui/ActionMenu.vue";
import { tr } from "../../i18n";

const emit = defineEmits<{ forbidden: [] }>();
const auth = useAuthStore();

const models = ref<AdminModel[]>([]);
const providers = ref<AdminProvider[]>([]);
const loading = ref(true), loaded = ref(false), loadError = ref("");
const note = ref(""), noteKind = ref<"ok" | "error" | "">("");
let disposed = false;
/** Nur die jüngste Ladeanfrage darf die Listen setzen. */
let loadSeq = 0;
onBeforeUnmount(() => { disposed = true; });

function show(kind: "ok" | "error", text: string) { noteKind.value = kind; note.value = text; }

async function load() {
  const my = ++loadSeq;
  loading.value = true;
  const [m, p] = await Promise.all([fetchModels(), fetchProviders()]);
  if (disposed || my !== loadSeq) return;
  loading.value = false;
  if (m.kind === "ok" && p.kind === "ok") {
    models.value = m.value; providers.value = p.value; loaded.value = true; loadError.value = "";
  } else if (m.kind === "forbidden" || p.kind === "forbidden") { emit("forbidden"); loadError.value = tr("Dafür sind Administrationsrechte nötig."); }
  else if (m.kind === "expired" || p.kind === "expired") auth.dropExpiredSession();
  else loadError.value = tr("Modelle konnten nicht geladen werden.");
}
onMounted(load);

const groups = computed(() => [
  { type: "CHAT" as const, title: tr("Antworten erstellen"), empty: tr("Noch kein Antwortmodell angelegt."), testId: "admin-chat-models-empty",
    rows: models.value.filter((m) => m.modelType === "CHAT") },
  { type: "EMBEDDING" as const, title: tr("Dokumente durchsuchbar machen"), empty: tr("Die Suche verwendet das bei der Installation eingerichtete Modell."), testId: "admin-embedding-models-empty",
    rows: models.value.filter((m) => m.modelType === "EMBEDDING") },
]);
const enabledProviders = computed(() => providers.value.filter((p) => p.enabled));
function providerName(id: number) { return providers.value.find((p) => p.id === id)?.name ?? tr("(unbekannt)"); }
/** „wird genutzt“: tatsächlich wirksame Auswahl (Standard, aktiv, Anbieter aktiv). */
function isLive(m: AdminModel) { return m.isDefault && m.enabled && providers.value.find((p) => p.id === m.providerId)?.enabled === true; }
function rowKey(m: AdminModel) { return m.modelType === "CHAT" ? "chat" : "embedding"; }

/* ---------- Formular ---------- */
const formOpen = ref(false);
const editing = ref<AdminModel | null>(null);
const formError = ref("");
const saving = ref(false);
const formDialog = ref<InstanceType<typeof AppDialog> | null>(null);
const f = ref({ providerId: null as number | null, type: "CHAT" as AdminModel["modelType"], name: "", technical: "", dim: "", maxTokens: "",
  toolUse: "AUTO" as AdminModel["toolUse"], toolAll: true, toolKinds: [] as string[] });
const savedTools = ref("");
function toolKindLabel(kind: string) {
  switch (kind) {
    case "knowledge": return tr("Wissenssuche und Dokumentstellen");
    case "attachments": return tr("Chat-Anhänge");
    case "web": return tr("Websuche und Webseitenabruf");
    case "mcp": return tr("MCP-Werkzeuge");
    default: return tr("Rückfragen an die Person");
  }
}
function toolsOf(value: typeof f.value) { return JSON.stringify([value.toolUse, value.toolAll, [...value.toolKinds].sort()]); }
const savedForm = ref("");
const formDirty = computed(() => JSON.stringify(f.value) !== savedForm.value);

function openCreate() {
  editing.value = null; formError.value = ""; note.value = "";
  f.value = { providerId: enabledProviders.value[0]?.id ?? null, type: "CHAT", name: "", technical: "", dim: "", maxTokens: "",
    toolUse: "AUTO", toolAll: true, toolKinds: [] };
  savedTools.value = toolsOf(f.value);
  savedForm.value = JSON.stringify(f.value); formOpen.value = true;
}
function openEdit(m: AdminModel) {
  editing.value = m; formError.value = ""; note.value = "";
  f.value = { providerId: m.providerId, type: m.modelType, name: m.name, technical: m.technicalModelId,
    dim: m.embeddingDim == null ? "" : String(m.embeddingDim), maxTokens: m.maxOutputTokens == null ? "" : String(m.maxOutputTokens),
    toolUse: m.toolUse ?? "AUTO", toolAll: m.toolDefaults == null, toolKinds: [...(m.toolDefaults ?? [])] };
  savedTools.value = toolsOf(f.value);
  savedForm.value = JSON.stringify(f.value); formOpen.value = true;
}

async function save() {
  if (saving.value) return;
  if (f.value.providerId == null) { formError.value = tr("Bitte einen aktiven KI-Anbieter wählen."); return; }
  saving.value = true; formError.value = "";
  try {
    const dim = Number.parseInt(f.value.dim, 10);
    const maxTok = Number.parseInt(f.value.maxTokens, 10);
    const outcome = editing.value
      ? await patchModel(editing.value.id, { name: f.value.name.trim(), technicalModelId: f.value.technical.trim(),
          maxOutputTokens: Number.isFinite(maxTok) ? maxTok : null })
      : await createModel({ providerId: f.value.providerId, name: f.value.name.trim(), technicalModelId: f.value.technical.trim(),
          modelType: f.value.type, embeddingDim: f.value.type === "EMBEDDING" && Number.isFinite(dim) ? dim : null,
          maxOutputTokens: Number.isFinite(maxTok) ? maxTok : null });
    let saved = outcome;
    if (outcome.kind === "ok" && outcome.value && outcome.value.modelType === "CHAT" && toolsOf(f.value) !== savedTools.value) {
      saved = await updateModelTools(outcome.value.id, { toolUse: f.value.toolUse, toolDefaults: f.value.toolAll ? null : f.value.toolKinds });
    }
    if (saved.kind !== "ok" && outcome.kind === "ok") {
      formError.value = saved.kind === "invalid" ? saved.message : tr("Die Werkzeugeinstellung konnte nicht gespeichert werden.");
      await load();
    } else if (outcome.kind === "ok") {
      const wasEdit = editing.value !== null;
      formOpen.value = false;
      show("ok", wasEdit ? tr("Modell aktualisiert.") : tr("Modell angelegt."));
      await load();
    } else if (outcome.kind === "invalid") formError.value = outcome.message;
    else if (outcome.kind === "forbidden") { emit("forbidden"); formError.value = tr("Dafür sind Administrationsrechte nötig."); }
    else if (outcome.kind === "expired") auth.dropExpiredSession();
    else formError.value = tr("Modell konnte nicht gespeichert werden.");
  } finally { saving.value = false; }
}

/* ---------- Aktivieren / Standard ---------- */
const working = ref(new Set<number>());
async function toggle(m: AdminModel) {
  if (working.value.has(m.id)) return;
  working.value.add(m.id);
  try {
    const outcome = await patchModel(m.id, { enabled: !m.enabled });
    if (outcome.kind === "ok") { show("ok", outcome.value.enabled ? tr("Modell aktiviert.") : tr("Modell deaktiviert.")); await load(); }
    else if (outcome.kind === "invalid") show("error", outcome.message);
    else if (outcome.kind === "forbidden") { emit("forbidden"); show("error", tr("Dafür sind Administrationsrechte nötig.")); }
    else if (outcome.kind === "expired") auth.dropExpiredSession();
    else show("error", tr("Änderung fehlgeschlagen."));
  } finally { working.value.delete(m.id); }
}

const defaultTarget = ref<AdminModel | null>(null);
const defaultBusy = ref(false);
const defaultError = ref("");
function makeDefault(m: AdminModel) {
  if (defaultBusy.value) return;
  if (m.modelType === "EMBEDDING") { defaultTarget.value = m; defaultError.value = ""; }
  else void applyDefault(m);
}
async function applyDefault(m: AdminModel) {
  if (defaultBusy.value) return;
  defaultBusy.value = true; defaultError.value = "";
  try {
    const outcome = await setModelDefault(m.id);
    if (outcome.kind === "ok") {
      defaultTarget.value = null;
      show("ok", tr("„{0}“ ist jetzt Standard ({1}).", outcome.value.name, outcome.value.modelType === "CHAT" ? tr("Antworten") : tr("Dokumentsuche")));
      await load();
      return;
    }
    if (outcome.kind === "expired") { auth.dropExpiredSession(); return; }
    if (outcome.kind === "forbidden") emit("forbidden");
    const text = outcome.kind === "invalid" ? outcome.message
      : outcome.kind === "forbidden" ? tr("Dafür sind Administrationsrechte nötig.") : tr("Standard konnte nicht gesetzt werden.");
    if (defaultTarget.value) defaultError.value = text; else show("error", text);
  } finally { defaultBusy.value = false; }
}
function rowAction(m: AdminModel, action: string) { if (action === "edit") openEdit(m); else void toggle(m); }
</script>

<template>
  <section class="connection-page" data-testid="admin-models-section">
    <header class="connection-header">
      <div class="connection-heading"><h2>{{ $t("Modelle") }}</h2><p class="connection-description">{{ $t("Welche Modelle Antworten erstellen und Dokumente durchsuchbar machen. Jedes Modell gehört zu einem KI-Anbieter.") }}</p></div>
      <div class="connection-actions">
        <Button variant="icon" size="compact" :aria-label="$t('Modelle aktualisieren')" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" /></Button>
        <Button variant="primary" :disabled="loaded && !enabledProviders.length" data-testid="admin-model-create" @click="openCreate"><AppIcon name="plus" :size="16" />{{ $t("Modell hinzufügen") }}</Button>
      </div>
    </header>

    <p v-if="loading && !loaded" class="connection-loading" role="status">{{ $t("Modelle werden geladen …") }}</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{ loadError }}</p><Button size="compact" @click="load">{{ $t("Erneut versuchen") }}</Button></div>
    <p v-if="note" class="connection-note" :class="noteKind" :role="noteKind === 'error' ? 'alert' : 'status'" data-testid="admin-toast">{{ note }}</p>

    <template v-if="loaded">
      <p v-if="!providers.length" class="connection-empty">{{ $t("Lege zuerst unter „KI-Anbieter“ eine Verbindung an. Jedes Modell gehört zu einer solchen Verbindung.") }}</p>
      <template v-else>
      <p v-if="!enabledProviders.length" class="connection-note">{{ $t("Alle KI-Anbieter sind deaktiviert. Aktiviere unter „KI-Anbieter“ eine Verbindung, um ein Modell hinzuzufügen.") }}</p>
      <section v-for="g in groups" :key="g.type" class="connection-section">
        <h3>{{ g.title }}</h3>
        <div class="connection-list">
          <p v-if="!g.rows.length" class="connection-empty" :data-testid="g.testId">{{ g.empty }}</p>
          <article v-for="m in g.rows" :key="m.id" class="connection-record" :data-testid="`admin-model-row-${rowKey(m)}`">
            <header class="connection-record-header">
              <div class="connection-heading"><h3>{{ m.name }}</h3><code class="connection-address">{{ m.technicalModelId }}</code>
                <p class="connection-meta">{{ $t("über {0}", providerName(m.providerId)) }}<template v-if="m.modelType === 'EMBEDDING'"> {{ $t("· Dimension {0}", m.embeddingDim ?? "?") }}</template><template v-if="m.maxOutputTokens != null"> {{ $t("· max. {0} Tokens", m.maxOutputTokens) }}</template><template v-if="isLive(m)"> · <span :data-testid="`admin-model-live-${rowKey(m)}`">{{ $t("wird genutzt") }}</span></template><template v-if="m.modelType === 'CHAT' && m.toolUse === 'OFF'"> {{ $t("· ohne Werkzeuge") }}</template><template v-else-if="m.modelType === 'CHAT' && m.toolDefaults != null"> {{ $t("· Werkzeuge eingeschränkt") }}</template></p></div>
              <div class="connection-actions">
                <StatusPill v-if="m.isDefault" tone="ok" :data-testid="`admin-model-default-${rowKey(m)}`">{{ $t("Standard") }}</StatusPill>
                <StatusPill v-if="!m.enabled" tone="neutral">{{ $t("Deaktiviert") }}</StatusPill>
                <Button v-if="!m.isDefault && m.enabled" size="compact" :disabled="defaultBusy" :data-testid="`admin-model-default-${m.id}`" @click="makeDefault(m)">{{ $t("Als Standard") }}</Button>
                <ActionMenu :label="$t('Aktionen für {0}', m.name)" :disabled="working.has(m.id)" :items="[{ id: 'edit', label: $t('Bearbeiten'), testId: `admin-model-edit-${m.id}` }, { id: 'toggle', label: m.enabled ? $t('Deaktivieren') : $t('Aktivieren'), testId: `admin-model-toggle-${m.id}` }]" @select="(a) => rowAction(m, a)" />
              </div>
            </header>
          </article>
        </div>
      </section></template>
    </template>

    <AppDialog ref="formDialog" size="md" :busy="saving" :dirty="formDirty" :open="formOpen" :title="editing ? $t('Modell bearbeiten') : $t('Modell hinzufügen')" :error="formError" @close="formOpen = false">
      <form class="connection-form" data-testid="admin-model-form" @submit.prevent="save">
        <template v-if="!editing">
          <label class="connection-field">{{ $t("KI-Anbieter") }}
            <select id="admin-m-provider" v-model.number="f.providerId" class="ic-native-control" data-testid="admin-model-provider" required>
              <option v-for="p in enabledProviders" :key="p.id" :value="p.id">{{ p.name }} ({{ p.providerType }})</option>
            </select>
          </label>
          <label class="connection-field">{{ $t("Wofür wird das Modell genutzt?") }}
            <select id="admin-m-type" v-model="f.type" class="ic-native-control" data-testid="admin-model-type">
              <option value="CHAT">{{ $t("Antworten erstellen") }}</option>
              <option value="EMBEDDING">{{ $t("Dokumente durchsuchbar machen") }}</option>
            </select>
          </label>
        </template>
        <p v-else class="connection-field">{{ $t("Verwendung") }} <span class="connection-hint" data-testid="admin-model-type-static">{{ $t("{0} über {1} (nachträglich nicht änderbar)", editing.modelType === "CHAT" ? $t("Antworten erstellen") : $t("Dokumente durchsuchbar machen"), providerName(editing.providerId)) }}</span></p>
        <Input id="admin-m-name" v-model="f.name" :label="$t('Anzeigename')" data-testid="admin-model-name" required />
        <Input id="admin-m-tech" v-model="f.technical" :label="$t('Technische Model-ID')" :placeholder="$t('z. B. qwen2.5:7b')" data-testid="admin-model-technical" required />
        <Input v-if="f.type === 'EMBEDDING' && !editing" id="admin-m-dim" v-model="f.dim" :label="$t('Embedding-Dimension')" type="number" :hint="$t('Muss zum Modell passen, z. B. 1024 für bge-m3.')" data-testid="admin-model-dim" />
        <Input id="admin-m-tokens" v-model="f.maxTokens" :label="$t('Maximale Antwortlänge in Tokens (optional)')" type="number" data-testid="admin-model-maxtokens" />
        <fieldset v-if="f.type === 'CHAT'" class="connection-field" data-testid="admin-model-tools">
          <legend>{{ $t("Werkzeuge") }}</legend>
          <select id="admin-m-tool-use" v-model="f.toolUse" class="ic-native-control" data-testid="admin-model-tool-use">
            <option value="AUTO">{{ $t("Werkzeuge erlauben") }}</option>
            <option value="OFF">{{ $t("Keine Werkzeuge – für Modelle ohne Werkzeugunterstützung") }}</option>
          </select>
          <template v-if="f.toolUse === 'AUTO'">
            <label class="connection-check"><input v-model="f.toolAll" type="checkbox" data-testid="admin-model-tool-all" />{{ $t("Alle freigegebenen Werkzeugarten standardmäßig anbieten") }}</label>
            <template v-if="!f.toolAll">
              <label v-for="kind in MODEL_TOOL_KINDS" :key="kind" class="connection-check"><input v-model="f.toolKinds" type="checkbox" :value="kind" :data-testid="`admin-model-tool-kind-${kind}`" />{{ toolKindLabel(kind) }}</label>
            </template>
          </template>
          <span class="connection-hint">{{ $t("Gilt, solange ein Chat keine eigene Werkzeugauswahl hat. Freigaben der Organisation bleiben maßgeblich.") }}</span>
        </fieldset>
        <div class="connection-submit">
          <Button variant="ghost" type="button" data-testid="admin-model-cancel" @click="formDialog?.requestClose()">{{ $t("Abbrechen") }}</Button>
          <Button variant="primary" type="submit" :busy="saving" data-testid="admin-model-save">{{ editing ? $t("Speichern") : $t("Hinzufügen") }}</Button>
        </div>
      </form>
    </AppDialog>

    <ConfirmDialog :open="defaultTarget !== null" :title="$t('Such-Standardmodell wechseln?')"
      :message="$t('„{0}“ wird für neue Suchvektoren verwendet. Bestehende Dokumente werden nicht automatisch neu verarbeitet (keine Neuindexierung); ihre gespeicherten Vektoren stammen weiterhin vom bisherigen Modell.', defaultTarget?.name ?? '')"
      :confirm-label="$t('Standard wechseln')" tone="primary" :busy="defaultBusy" :error="defaultError"
      @confirm="defaultTarget && applyDefault(defaultTarget)" @close="defaultTarget = null" />
  </section>
</template>
