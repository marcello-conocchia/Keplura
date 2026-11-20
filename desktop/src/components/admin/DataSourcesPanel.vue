<script setup lang="ts">
/**
 * ADR-0012 §5 — Datenquellen: Statusliste + Einrichtungs-Assistent für
 * Sysadmins ohne KI-Wissen. Schritte: Art → Verbindung (mit Test) → Auswahl
 * → Rechte-Vorschau → Zeitplan → Aktivieren. Eine Quelle wird erst nach dem
 * Probelauf aktiviert; die Ablage selbst wird nie verändert.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { DropdownMenuRoot, DropdownMenuTrigger, DropdownMenuPortal, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from "reka-ui";
import { useAuthStore } from "../../auth-store";
import {
  createDataSource, dataSourceCapabilities, dataSourceProblems, deleteDataSource, listDataSources,
  previewDataSource, principalLabel, resultText, runDataSource, updateDataSource,
  type DataSource, type DataSourcePreview,
} from "../../datasource-api";
import AppDialog from "../ui/AppDialog.vue";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";
import ConfirmDialog from "../ui/ConfirmDialog.vue";
import ExposureDialog from "./ExposureDialog.vue";
import Input from "../ui/Input.vue";
import StatusPill from "../ui/StatusPill.vue";

const auth = useAuthStore();
const items = ref<DataSource[]>([]);
const localRoots = ref<string[]>([]);
const note = ref("");
const noteKind = ref<"ok" | "error" | "">("");
const refreshing = ref(false);
const loaded = ref(false);
const listError = ref("");
const query = ref("");
const filter = ref("all");
const searchField = ref<InstanceType<typeof Input> | null>(null);
const pending = ref(new Set<number>());
let poll: ReturnType<typeof setInterval> | null = null;
let listRequest = 0, disposed = false;

function show(kind: "ok" | "error", text: string) {
  noteKind.value = kind;
  note.value = text;
}
async function load() {
  const request = ++listRequest;
  refreshing.value = true;
  const r = await listDataSources();
  if (request !== listRequest || disposed) return;
  refreshing.value = false;
  if (r.kind === "ok") { items.value = r.value; loaded.value = true; listError.value = ""; }
  else listError.value = resultText(r);
  const anyRunning = items.value.some(d => d.lastRunStatus === "RUNNING");
  if (anyRunning && !poll) poll = setInterval(() => { if (!refreshing.value) void load(); }, 4000);
  if (!anyRunning && poll) { clearInterval(poll); poll = null; }
}
onMounted(async () => {
  await Promise.all([load(), dataSourceCapabilities().then(c => {
    if (!disposed && c.kind === "ok") localRoots.value = c.value.localRoots;
  })]);
});
onBeforeUnmount(() => { disposed = true; listRequest++; if (poll) clearInterval(poll); });

function kbName(id: number) {
  return auth.kbs.find(k => k.id === id)?.name ?? `Wissensbasis ${id}`;
}
function kindName(d: DataSource) {
  return d.kind === "SMB" ? "Windows-Freigabe" : d.kind === "SHAREPOINT" ? "SharePoint" : "Linux-Ordner";
}
function operation(d: DataSource) {
  return d.enabled ? "Aktiv" : d.lastRunStatus ? "Pausiert" : "Nicht aktiviert";
}
function schedule(d: DataSource) {
  if (!d.enabled) return d.lastRunStatus ? "Zeitplan pausiert" : "Zeitplan noch nicht aktiv";
  if (d.intervalMinutes === 60) return "Stündlich";
  if (d.intervalMinutes === 1440) return "Täglich";
  if (d.intervalMinutes % 60 === 0) return `Alle ${d.intervalMinutes / 60} Stunden`;
  return `Alle ${d.intervalMinutes} Minuten`;
}
function statusText(d: DataSource): string {
  switch (d.lastRunStatus) {
    case "RUNNING": return "Wird abgeglichen";
    case "OK": return "Erfolgreich";
    case "PARTIAL": return "Teilweise erfolgreich";
    case "FAILED": return "Fehlgeschlagen";
    default: return "Noch kein Abgleich";
  }
}
function tone(d: DataSource): "ok" | "wait" | "error" | "neutral" {
  if (d.lastRunStatus === "FAILED") return "error";
  if (d.lastRunStatus === "PARTIAL" || d.lastRunStatus === "RUNNING") return "wait";
  return d.lastRunStatus === "OK" ? "ok" : "neutral";
}
const activeCount = computed(() => items.value.filter(d => d.enabled).length);
const pausedCount = computed(() => items.value.filter(d => !d.enabled && d.lastRunStatus).length);
const newCount = computed(() => items.value.filter(d => !d.enabled && !d.lastRunStatus).length);
const visibleItems = computed(() => {
  const q = query.value.trim().toLocaleLowerCase("de-DE");
  return items.value.filter(d => {
    const matches = !q || [d.displayName, d.location, kbName(d.knowledgeBaseId), kindName(d)].some(t => t.toLocaleLowerCase("de-DE").includes(q));
    return matches && (filter.value === "all" || filter.value === "active" && d.enabled
      || filter.value === "paused" && !d.enabled && !!d.lastRunStatus
      || filter.value === "new" && !d.enabled && !d.lastRunStatus
      || filter.value === "problems" && (d.filesFailed > 0 || d.lastRunStatus === "FAILED" || d.lastRunStatus === "PARTIAL"));
  });
});
function resetFilters() { query.value = ""; filter.value = "all"; }
async function focusAfterRemoval(id: number) {
  await nextTick();
  if (!visibleItems.value.some(d => d.id === id)) {
    if (searchField.value) searchField.value.focus();
    else document.querySelector<HTMLButtonElement>('[data-testid="datasource-create"]')?.focus();
  }
}
let queuedDialog: (() => void) | null = null;
let queuedSourceId: number | null = null;
function menuDialog(d: DataSource, action: () => void) {
  queuedSourceId = d.id;
  queuedDialog = action;
}
function afterMenuClose(event: Event) {
  const action = queuedDialog, id = queuedSourceId;
  queuedDialog = null; queuedSourceId = null;
  if (!action || disposed) return;
  event.preventDefault();
  // Der Menüeintrag verschwindet. Der Dialog merkt sich stattdessen die beständige Zeilenaktion.
  document.querySelector<HTMLButtonElement>(`[data-source-id="${id}"] [data-testid="datasource-actions"]`)?.focus();
  action();
}

function when(iso: string | null) {
  return iso ? new Date(iso).toLocaleString("de-DE", { dateStyle: "short", timeStyle: "short" }) : "–";
}
function size(b: number) {
  return b >= 1024 ** 3 ? `${(b / 1024 ** 3).toFixed(1)} GB` : b >= 1024 ** 2 ? `${Math.round(b / 1024 ** 2)} MB` : `${Math.round(b / 1024)} KB`;
}

/* ------------------------------------------------------------ Assistent */
type Step = 1 | 2 | 3 | 4 | 5 | 6;
const open = ref(false);
const step = ref<Step>(1);
const busy = ref(false);
const error = ref("");
const source = ref<DataSource | null>(null);
const preview = ref<DataSourcePreview | null>(null);
const w = ref({
  kind: "SMB" as "SMB" | "LOCAL" | "SHAREPOINT",
  tenantId: "",
  clientId: "",
  displayName: "",
  location: "",
  username: "",
  password: "",
  knowledgeBaseId: null as number | null,
  includePatterns: "",
  excludePatterns: "**/~$*, **/.*, **/archiv/**",
  maxMb: 20,
  mirrorPermissions: true,
  intervalMinutes: 15,
});
const wizardDialog = ref<InstanceType<typeof AppDialog> | null>(null);
const savedWizard = ref("");
const stepHeading = ref<HTMLElement | null>(null);
const wizardDirty = computed(() => JSON.stringify(w.value) !== savedWizard.value);
watch(step, async () => { await nextTick(); stepHeading.value?.focus(); });
const stepTitles = ["Art der Ablage", "Verbindung", "Auswahl", "Rechte", "Zeitplan", "Aktivieren"];

function startWizard(existing?: DataSource) {
  error.value = "";
  preview.value = null;
  source.value = existing ?? null;
  w.value = existing
    ? { kind: existing.kind, tenantId: existing.tenantId ?? "", clientId: existing.clientId ?? "",
        displayName: existing.displayName, location: existing.location, username: existing.username ?? "",
        password: "", knowledgeBaseId: existing.knowledgeBaseId, includePatterns: existing.includePatterns,
        excludePatterns: existing.excludePatterns, maxMb: Math.round((existing.maxFileBytes || 20 * 1024 * 1024) / 1024 / 1024),
        mirrorPermissions: existing.mirrorPermissions, intervalMinutes: existing.intervalMinutes }
    : { kind: "SMB", tenantId: "", clientId: "", displayName: "", location: "", username: "", password: "", knowledgeBaseId: auth.selectedKbId ?? auth.kbs[0]?.id ?? null,
        includePatterns: "", excludePatterns: "**/~$*, **/.*, **/archiv/**", maxMb: 20, mirrorPermissions: true, intervalMinutes: 15 };
  savedWizard.value = JSON.stringify(w.value);
  step.value = existing ? 2 : 1;
  open.value = true;
}

const canNext = computed(() => {
  if (step.value === 2) return w.value.location.trim() !== "" && w.value.displayName.trim() !== "" && w.value.knowledgeBaseId !== null;
  return true;
});

function payload() {
  return {
    kind: w.value.kind, displayName: w.value.displayName.trim(), location: w.value.location.trim(),
    username: w.value.kind === "SMB" ? w.value.username.trim() : "", password: w.value.password || undefined,
    tenantId: w.value.kind === "SHAREPOINT" ? w.value.tenantId.trim() : "",
    clientId: w.value.kind === "SHAREPOINT" ? w.value.clientId.trim() : "",
    includePatterns: w.value.includePatterns, excludePatterns: w.value.excludePatterns,
    maxFileBytes: Math.max(1, w.value.maxMb) * 1024 * 1024, mirrorPermissions: w.value.mirrorPermissions,
    intervalMinutes: w.value.intervalMinutes,
  };
}

/** Speichert (anlegen oder ändern) und macht einen Probelauf. */
async function saveAndPreview(): Promise<boolean> {
  busy.value = true;
  error.value = "";
  const r = source.value
    ? await updateDataSource(source.value.id, payload())
    : await createDataSource({ ...payload(), orgId: auth.selectedOrgId ?? undefined,
        spaceId: auth.selectedSpaceId ?? undefined, knowledgeBaseId: w.value.knowledgeBaseId ?? undefined });
  if (r.kind !== "ok") {
    busy.value = false;
    error.value = resultText(r);
    return false;
  }
  source.value = r.value;
  // Gespeicherter Zugang verlässt den Eingabestate; leeres Feld behält ihn (PATCH ohne password).
  w.value.password = "";
  savedWizard.value = JSON.stringify(w.value);
  const p = await previewDataSource(r.value.id);
  busy.value = false;
  if (p.kind !== "ok") {
    error.value = resultText(p);
    return false;
  }
  preview.value = p.value;
  if (p.value.filesFound === 0 && p.value.message !== "Verbunden.") {
    error.value = p.value.message;
    return false;
  }
  return true;
}

async function next() {
  if(busy.value) return;
  error.value = "";
  if (step.value === 2 || step.value === 3 || (step.value === 4 && source.value)) {
    if (!(await saveAndPreview())) return;
  }
  if (step.value < 6) step.value = (step.value + 1) as Step;
}

async function activate() {
  if(busy.value) return;
  if (!source.value) return;
  busy.value = true;
  const u = await updateDataSource(source.value.id, { ...payload(), enabled: true });
  if (u.kind !== "ok") {
    busy.value = false;
    error.value = resultText(u);
    return;
  }
  const run = await runDataSource(source.value.id);
  busy.value = false;
  open.value = false;
  if (run.kind === "ok") show("ok", `„${u.value.displayName}“ ist aktiv. Der erste Abgleich läuft – das kann je nach Menge einige Minuten dauern.`);
  else show("error", `„${u.value.displayName}“ ist aktiviert, aber der erste Abgleich konnte nicht gestartet werden: ${resultText(run)} Starte ihn in der Liste mit „Jetzt abgleichen“ erneut.`);
  await load();
}

async function closeWizard() {
  open.value = false;
  await load();
}

/* ------------------------------------------------------------ Aktionen */
async function runNow(d: DataSource) {
  if (pending.value.has(d.id) || !d.enabled || d.lastRunStatus === "RUNNING") return;
  pending.value.add(d.id);
  try {
    const r = await runDataSource(d.id);
    if (r.kind === "ok") show("ok", `Abgleich von „${d.displayName}“ gestartet.`);
    else show("error", resultText(r));
    await load();
  } finally { pending.value.delete(d.id); }
}
async function toggle(d: DataSource) {
  if (pending.value.has(d.id)) return;
  pending.value.add(d.id);
  try {
    const r = await updateDataSource(d.id, { enabled: !d.enabled });
    if (r.kind === "ok") {
      items.value = items.value.map(item => item.id === d.id ? r.value : item);
      show("ok", `Automatischer Abgleich für „${d.displayName}“ ${r.value.enabled ? 'aktiviert' : 'pausiert'}.`);
    } else show("error", resultText(r));
    await load();
    await focusAfterRemoval(d.id);
  } finally { pending.value.delete(d.id); }
}
const problemsFor = ref<DataSource | null>(null);
const problems = ref<{ relativePath: string; error: string }[]>([]);
const problemsLoading = ref(false), problemsError = ref("");
let problemRequest = 0;
async function showProblems(d: DataSource) {
  const request = ++problemRequest;
  problemsFor.value = d; problems.value = []; problemsError.value = ""; problemsLoading.value = true;
  const r = await dataSourceProblems(d.id);
  if (request !== problemRequest || disposed) return;
  problemsLoading.value = false;
  if (r.kind === "ok") problems.value = r.value;
  else problemsError.value = resultText(r);
}
function closeProblems() { problemRequest++; problemsFor.value = null; problemsLoading.value = false; }
/* RISK-01: Oversharing-Check (aus dem Zeilenmenü und aus dem Assistenten). */
const exposureFor = ref<DataSource | null>(null);
function exposureUpdated(updated: DataSource) {
  items.value = items.value.map(item => item.id === updated.id ? updated : item);
  if (source.value?.id === updated.id) {
    source.value = updated;
    // Assistent offen: die neue Ausschlussliste übernehmen, sonst überschreibt „Aktivieren“ sie wieder.
    w.value.excludePatterns = updated.excludePatterns;
    savedWizard.value = JSON.stringify(w.value);
  }
}
const deleteTarget = ref<DataSource | null>(null);
const deleting = ref(false), deleteError = ref("");
function askDelete(d: DataSource) { deleteTarget.value = d; deleteError.value = ""; }
async function confirmDelete() {
  const d = deleteTarget.value;
  if (!d || deleting.value) return;
  deleting.value = true; deleteError.value = "";
  try {
    const r = await deleteDataSource(d.id);
    if (r.kind === "ok") {
      deleteTarget.value = null;
      show("ok", "Datenquelle entfernt. Die übernommenen Dokumente wurden aus Keplura gelöscht; die Originale bleiben unverändert.");
      await load(); await focusAfterRemoval(d.id);
    } else deleteError.value = resultText(r);
  } finally { deleting.value = false; }
}

</script>

<template>
  <section class="ds" data-testid="admin-datasources-section">
    <header class="ds-overview-head">
      <div><h2>Datenquellen</h2><p class="ds-lead">Verbinde Ablagen und übernimm Dateien mit ihren Zugriffsrechten.</p></div>
      <div class="ds-head-actions">
        <Button variant="icon" size="compact" data-testid="datasource-reload" :disabled="refreshing" :busy="refreshing" title="Datenquellen aktualisieren" aria-label="Datenquellen aktualisieren" @click="load"><AppIcon name="refresh" :size="16" /></Button>
        <Button variant="primary" :disabled="auth.kbs.length === 0" data-testid="datasource-create" @click="startWizard()"><AppIcon name="plus" :size="16" />Datenquelle hinzufügen</Button>
      </div>
    </header>
    <p v-if="note" class="ds-note" :class="noteKind" :role="noteKind === 'error' ? 'alert' : 'status'" data-testid="datasource-note">{{ note }}</p>
    <div v-if="listError" class="ds-error" role="alert" data-testid="datasource-list-error"><p>{{listError}}</p><Button size="compact" :disabled="refreshing" @click="load">Erneut versuchen</Button></div>
    <p v-if="!loaded && refreshing" class="ds-loading" role="status" data-testid="datasource-loading">Datenquellen werden geladen …</p>
    <template v-if="loaded">
      <p v-if="auth.kbs.length === 0" class="ds-prerequisite">Lege zuerst unter „Wissen“ eine Wissensbasis an. Dort werden die Dateien abgelegt.</p>
      <template v-if="items.length">
        <div class="ds-toolbar">
          <div class="ds-search"><Input ref="searchField" id="datasource-query" v-model="query" label="Datenquellen durchsuchen" label-hidden icon="search" type="search" placeholder="Quellen oder Ordner suchen …" data-testid="datasource-search" /></div>
          <select v-model="filter" class="ic-native-control ds-filter" aria-label="Datenquellen filtern" data-testid="datasource-filter"><option value="all">Alle Quellen</option><option value="active">Aktiv</option><option value="paused">Pausiert</option><option value="new">Nicht aktiviert</option><option value="problems">Mit Problemen</option></select>
        </div>
        <div class="ds-summary" data-testid="datasource-summary"><span>{{visibleItems.length}} von {{items.length}} Quellen</span><span>{{activeCount}} aktiv · {{pausedCount}} pausiert<span v-if="newCount"> · {{newCount}} nicht aktiviert</span></span></div>
        <div v-if="visibleItems.length" class="ds-list" data-testid="datasource-list">
          <table class="ds-table" aria-label="Datenquellen"><thead><tr><th scope="col">Quelle</th><th scope="col" class="ds-operation-col">Betrieb</th><th scope="col" class="ds-result-col">Letzter Abgleich</th><th scope="col" class="ds-action-col"><span class="ds-sr-only">Aktionen</span></th></tr></thead>
            <tbody><tr v-for="d in visibleItems" :key="d.id" :data-source-id="d.id" data-testid="datasource-item">
              <td class="ds-source"><div class="ds-title-line"><span class="ds-name">{{d.displayName}}</span><span class="ds-kind">{{kindName(d)}}</span></div><span class="ds-path" :title="d.location">{{d.location}}</span><span class="ds-meta">Ziel: {{kbName(d.knowledgeBaseId)}}</span></td>
              <td class="ds-operation"><span class="ds-mobile-label">Betrieb</span><StatusPill :tone="d.enabled ? 'info' : 'neutral'" data-testid="datasource-operation">{{operation(d)}}</StatusPill><span class="ds-meta">{{schedule(d)}}</span></td>
              <td class="ds-result"><span class="ds-mobile-label">Letzter Abgleich</span><StatusPill :tone="tone(d)" data-testid="datasource-status">{{statusText(d)}}</StatusPill>
                <span v-if="d.lastRunStatus === 'RUNNING'" class="ds-meta">Gestartet {{when(d.lastRunStartedAt)}}</span>
                <template v-else-if="d.lastRunStatus"><time class="ds-meta" :datetime="d.lastRunFinishedAt || undefined">{{when(d.lastRunFinishedAt)}}</time><span class="ds-meta">{{d.filesSeen}} {{d.filesSeen === 1 ? 'Datei' : 'Dateien'}}<template v-if="d.filesFailed"> · {{d.filesFailed}} mit Problemen</template></span></template>
                <span v-if="d.lastRunMessage" class="ds-run-message">{{d.lastRunMessage}}</span>
                <Button v-if="d.filesFailed > 0 || d.lastRunStatus === 'FAILED' || d.lastRunStatus === 'PARTIAL'" variant="ghost" size="compact" class="ds-problems-action" data-testid="datasource-problems" @click="showProblems(d)">Probleme ansehen</Button>
              </td>
              <td class="ds-acts"><div class="ds-row-actions">
                <Button variant="secondary" size="compact" :disabled="d.lastRunStatus === 'RUNNING' || !d.enabled || pending.has(d.id)" data-testid="datasource-run" aria-label="Jetzt abgleichen" :title="!d.enabled ? 'Aktiviere zuerst den automatischen Abgleich' : d.lastRunStatus === 'RUNNING' ? 'Der Abgleich läuft bereits' : 'Jetzt abgleichen'" @click="runNow(d)"><AppIcon name="refresh" :size="16" /><span class="ds-run-label">Abgleichen</span></Button>
                <DropdownMenuRoot><DropdownMenuTrigger class="ds-more" :disabled="pending.has(d.id)" :aria-label="`Aktionen für ${d.displayName}`" title="Weitere Aktionen" data-testid="datasource-actions"><AppIcon name="more" :size="20" /></DropdownMenuTrigger><DropdownMenuPortal><DropdownMenuContent class="ic-menu" align="end" :side-offset="6" :collision-padding="12" @close-auto-focus="afterMenuClose">
                  <DropdownMenuItem class="ic-menu-item" data-testid="datasource-settings" @select="menuDialog(d, () => startWizard(d))"><AppIcon name="edit" :size="16" />Einstellungen</DropdownMenuItem>
                  <DropdownMenuItem class="ic-menu-item" data-testid="datasource-exposure" @select="menuDialog(d, () => { exposureFor = d; })">Oversharing prüfen</DropdownMenuItem>
                  <DropdownMenuItem class="ic-menu-item" data-testid="datasource-toggle" @select="toggle(d)">{{d.enabled ? 'Pausieren' : 'Aktivieren'}}</DropdownMenuItem>
                  <DropdownMenuItem v-if="d.filesFailed > 0 || d.lastRunStatus === 'FAILED'" class="ic-menu-item" @select="menuDialog(d, () => showProblems(d))">Probleme ansehen</DropdownMenuItem>
                  <DropdownMenuSeparator class="ic-menu-sep" /><DropdownMenuItem class="ic-menu-item ds-menu-danger" data-testid="datasource-delete" @select="menuDialog(d, () => askDelete(d))"><AppIcon name="trash" :size="16" />Entfernen</DropdownMenuItem>
                </DropdownMenuContent></DropdownMenuPortal></DropdownMenuRoot>
              </div></td>
            </tr></tbody>
          </table>
        </div>
        <div v-else class="ds-empty" data-testid="datasource-no-results"><h3>Keine passenden Quellen</h3><p>Versuche einen anderen Suchbegriff oder setze den Filter zurück.</p><Button @click="resetFilters">Filter zurücksetzen</Button></div>
      </template>
      <div v-else class="ds-empty" data-testid="datasource-empty"><AppIcon name="folder" :size="20" /><h3>Deine erste Datenquelle</h3><p>Verbinde eine Ablage, damit Keplura neue und geänderte Dateien automatisch übernimmt. Die Originaldateien bleiben unverändert.</p><p class="ds-meta">Windows-Freigaben · Linux-Ordner · SharePoint</p></div>
    </template>
    <details class="ds-explanation"><summary>Wie werden Dateien und Rechte übernommen?</summary><p>Keplura liest die Originaldateien, ohne sie zu verändern. Bei eingeschalteter Rechteübernahme findet jede Person nur die Dokumente, auf die sie auch in der ursprünglichen Ablage zugreifen darf.</p></details>

    <AppDialog ref="wizardDialog" :open="open" :title="source ? 'Datenquelle einrichten' : 'Datenquelle hinzufügen'" :error="error" size="wizard" :busy="busy" :dirty="wizardDirty" initial-focus="title" @close="closeWizard">
      <div class="wizard-layout">
        <nav class="wizard-nav" aria-label="Einrichtungsschritte"><p class="wizard-caption">Einrichtung</p>
          <ol class="ds-steps"><li v-for="(t, i) in stepTitles" :key="t" :class="{ on: step === i + 1, done: step > i + 1 }">
            <button type="button" :disabled="busy || i + 1 > step" :aria-current="step === i + 1 ? 'step' : undefined" @click="step = (i + 1) as Step"><span class="step-number">{{ i + 1 }}</span>{{ t }}</button>
          </li></ol>
          <details class="wizard-mobile"><summary>Schritt {{ step }} von 6 · {{ stepTitles[step - 1] }}</summary><ol><li v-for="(t,i) in stepTitles" :key="t"><button type="button" :disabled="busy || i + 1 > step" :aria-current="step === i + 1 ? 'step' : undefined" @click="step = (i + 1) as Step">{{ i + 1 }} · {{ t }}</button></li></ol></details>
          <p class="wizard-scope">{{ auth.selectedOrg?.name }}<br />{{ auth.selectedSpace?.name }}</p>
        </nav>
        <div class="wizard-main"><p class="wizard-caption">Schritt {{ step }} von 6</p><h3 ref="stepHeading" tabindex="-1" class="wizard-title">{{ stepTitles[step - 1] }}</h3>
      <!-- 1 Art -->
      <div v-if="step === 1" class="ds-form">
        <label class="ds-choice"><input v-model="w.kind" name="source-kind" type="radio" value="SMB" />
          <span><strong>Windows-Freigabe</strong><br />z. B. \\fileserver\Abteilungen – auch Samba und DFS</span></label>
        <label class="ds-choice"><input v-model="w.kind" name="source-kind" type="radio" value="SHAREPOINT" />
          <span><strong>SharePoint Online</strong><br />eine Site oder Dokumentbibliothek aus Microsoft 365</span></label>
        <label class="ds-choice" :class="{ off: localRoots.length === 0 }"><input v-model="w.kind" name="source-kind" type="radio" value="LOCAL" :disabled="localRoots.length === 0" />
          <span><strong>Linux-Ordner</strong><br />
            <template v-if="localRoots.length">auf dem Keplura-Server eingebunden, z. B. per NFS unter {{ localRoots.join(", ") }}</template>
            <template v-else>Noch nicht eingerichtet. Bitte lasse den Ordner auf dem Keplura-Server bereitstellen.</template>
          </span></label>
      </div>

      <!-- 2 Verbindung -->
      <div v-if="step === 2" class="ds-form">
        <label>Name <input class="ic-native-control" v-model="w.displayName" maxlength="200" placeholder="z. B. Vertrieb – Angebote" data-testid="datasource-name" /></label>
        <label v-if="w.kind === 'SMB'">Freigabe und Ordner <input class="ic-native-control" v-model="w.location" placeholder="\\fileserver\Abteilungen\Vertrieb" data-testid="datasource-location" /></label>
        <template v-else-if="w.kind === 'SHAREPOINT'">
          <label>Adresse der Site, Bibliothek oder des Ordners
            <input class="ic-native-control" v-model="w.location" placeholder="https://firma.sharepoint.com/sites/Vertrieb/Freigegebene Dokumente" data-testid="datasource-location" /></label>
          <label>Verzeichnis-ID (Tenant) <input class="ic-native-control" v-model="w.tenantId" placeholder="firma.onmicrosoft.com oder GUID" autocomplete="off" /></label>
          <label>Anwendungs-ID (Client-ID) <input class="ic-native-control" v-model="w.clientId" placeholder="00000000-0000-0000-0000-000000000000" autocomplete="off" /></label>
          <label>{{ source?.secretSet ? 'Clientschlüssel ändern (optional)' : 'Geheimer Clientschlüssel' }} <input class="ic-native-control" v-model="w.password" type="password" autocomplete="new-password"
                                                 :placeholder="source?.secretSet ? 'bleibt gespeichert – nur zum Ändern eingeben' : ''" /></label>
          <details class="ds-help">
            <summary>So richtest du den Zugang in Microsoft 365 ein</summary>
            <ol>
              <li>Entra Admin Center → App-Registrierungen → Neue Registrierung „Keplura“.</li>
              <li>API-Berechtigungen → Microsoft Graph → Anwendungsberechtigung <strong>Sites.Selected</strong> → Administratorzustimmung erteilen.</li>
              <li>Zertifikate &amp; Geheimnisse → neuen geheimen Clientschlüssel anlegen und hier eintragen.</li>
              <li>Die gewünschte Site für die App freigeben (Leserecht). Anleitung: <code>docs/howto/sharepoint.md</code>.</li>
              <li>Für die Anmeldung der Mitarbeiter: Entra-Anmeldung unter Administration einrichten und im Token die Gruppen ausgeben (Tokenkonfiguration → Gruppenanspruch).</li>
            </ol>
          </details>
        </template>
        <label v-else>Ordner <input class="ic-native-control" v-model="w.location" :placeholder="(localRoots[0] ?? '/mnt/quellen') + '/vertrieb'" data-testid="datasource-location" /></label>
        <template v-if="w.kind === 'SMB'">
          <label>Dienstkonto (nur Lesen) <input class="ic-native-control" v-model="w.username" placeholder="FIRMA\svc-keplura" autocomplete="off" /></label>
          <label>{{ source?.secretSet ? 'Passwort ändern (optional)' : 'Passwort' }} <input class="ic-native-control" v-model="w.password" type="password" autocomplete="new-password"
                                 :placeholder="source?.secretSet ? 'bleibt gespeichert – nur zum Ändern eingeben' : ''" /></label>
        </template>
        <label>Ziel-Wissensbasis
          <select class="ic-native-control" v-model="w.knowledgeBaseId" :disabled="!!source">
            <option v-for="k in auth.kbs" :key="k.id" :value="k.id">{{ k.name }}</option>
          </select>
        </label>
        <p v-if="source?.secretSet" class="ds-hint" data-testid="datasource-secret-saved">Zugang gespeichert. Ein leeres Passwortfeld behält den gespeicherten Zugang bei.</p>
        <p class="ds-hint">Mit „Weiter“ verbindet sich Keplura probeweise und zählt die Dateien. Es wird noch nichts übernommen.</p>
      </div>

      <!-- 3 Auswahl -->
      <div v-if="step === 3" class="ds-form">
        <p v-if="preview" class="ds-ok">{{ preview.message }} {{ preview.filesFound }} Dateien gefunden, davon
          <strong>{{ preview.filesAccepted }}</strong> übernehmbar ({{ size(preview.bytesAccepted) }}).
          <template v-if="preview.skippedType"> {{ preview.skippedType }} haben ein nicht unterstütztes Format.</template>
          <template v-if="preview.skippedSize"> {{ preview.skippedSize }} sind zu groß.</template>
          <template v-if="preview.skippedPattern"> {{ preview.skippedPattern }} sind ausgeschlossen.</template></p>
        <label>Nur diese Unterordner/Dateien (optional)
          <input class="ic-native-control" v-model="w.includePatterns" placeholder="z. B. angebote/**, handbuecher/**" /></label>
        <label>Ausschließen
          <input class="ic-native-control" v-model="w.excludePatterns" /></label>
        <label>Größte Datei (MB) <input class="ic-native-control" v-model.number="w.maxMb" type="number" min="1" max="200" /></label>
        <p v-if="preview?.sample.length" class="ds-hint">Beispiele: {{ preview.sample.slice(0, 5).join(" · ") }}</p>
        <p class="ds-hint">Formate: PDF, Word (DOCX), Excel (XLSX), PowerPoint (PPTX), Text, Markdown, CSV. Muster: ** = beliebig viele Ordner, * = beliebiger Name.</p>
      </div>

      <!-- 4 Rechte -->
      <div v-if="step === 4" class="ds-form">
        <label class="ds-check"><input v-model="w.mirrorPermissions" type="checkbox" data-testid="datasource-mirror" />
          Zugriffsrechte der Ablage übernehmen (empfohlen)</label>
        <p v-if="!w.mirrorPermissions" class="ds-warn">Ohne Rechte-Übernahme sehen alle Mitglieder der Wissensbasis alle Dateien dieser Quelle.</p>
        <template v-if="w.mirrorPermissions && preview">
          <p class="ds-hint">Stichprobe von {{ preview.aclSampled }} Dateien<template v-if="preview.aclUnreadable">, davon {{ preview.aclUnreadable }} ohne lesbare Rechte (sind für niemanden sichtbar)</template>:</p>
          <p v-if="preview.aclUnreadable && preview.aclUnreadableReason" class="ds-warn" data-testid="datasource-acl-reason">{{ preview.aclUnreadableReason }}</p>
          <ul class="ds-acl" data-testid="datasource-acl-preview">
            <li v-for="p in preview.principals" :key="p.principal"><span :title="p.principal">{{ principalLabel(p.principal, p.label) }}</span><span class="ic-num">{{ p.files }} Dateien</span></li>
            <li v-if="preview.principals.length === 0">Keine Leserechte gefunden – das Dienstkonto sieht die Rechte evtl. nicht.</li>
          </ul>
          <div class="ds-exposure-cta" data-testid="datasource-exposure-cta">
            <p class="ds-hint"><strong>Vor dem Aktivieren empfohlen:</strong> alle {{ preview.filesAccepted }} Dateien prüfen, welche für praktisch alle lesbar sind
              und sensibel wirken (z. B. Gehalt, Personalakte, Passwörter). Es wird nichts übernommen.</p>
            <Button variant="secondary" size="compact" :disabled="busy || !source || wizardDirty" :title="wizardDirty ? 'Erst mit „Weiter“ speichern' : undefined" data-testid="datasource-exposure-start" @click="exposureFor = source">Oversharing prüfen</Button>
          </div>
          <p v-if="w.kind === 'SHAREPOINT'" class="ds-hint">Mitarbeiter erhalten diese Rechte, wenn sie sich mit ihrem Microsoft-365-Konto anmelden.
            SharePoint-Gruppen „Mitglieder“ und „Besitzer“ zählen als die zugehörige Microsoft-365-Gruppe; andere SharePoint-Gruppen
            (z. B. „Besucher“) werden nicht übernommen – diese Dateien sieht dann nur, wer zusätzlich direkt berechtigt ist.</p>
          <p v-else class="ds-hint">Damit Personen diese Rechte haben, müssen sie sich über das Verzeichnis anmelden (Administration → Verzeichnis).
            Freigabe-Berechtigungen werden nicht ausgewertet; maßgeblich sind die Datei- bzw. NTFS-Rechte.</p>
        </template>
      </div>

      <!-- 5 Zeitplan -->
      <div v-if="step === 5" class="ds-form">
        <label>Änderungen übernehmen alle
          <select class="ic-native-control" v-model.number="w.intervalMinutes">
            <option :value="15">15 Minuten</option>
            <option :value="60">Stunde</option>
            <option :value="360">6 Stunden</option>
            <option :value="1440">Tag</option>
          </select>
        </label>
        <p class="ds-hint">Neue und geänderte Dateien werden übernommen, gelöschte entfernt, geänderte Rechte nachgezogen.</p>
      </div>

      <!-- 6 Aktivieren -->
      <div v-if="step === 6" class="ds-form">
        <p><strong>{{ w.displayName }}</strong> – {{ w.location }}</p>
        <p class="ds-hint">{{ preview?.filesAccepted ?? 0 }} Dateien werden übernommen, danach alle
          {{ w.intervalMinutes >= 60 ? w.intervalMinutes / 60 + " Std." : w.intervalMinutes + " Min." }} abgeglichen.
          Rechte: {{ w.mirrorPermissions ? "wie in der Ablage" : "alle Mitglieder der Wissensbasis" }}.</p>
      </div>

        </div>
      </div>
      <template #footer>
        <p class="wizard-save-hint">{{ source ? 'Die getestete Verbindung ist gespeichert. Ungespeicherte Änderungen werden beim Abbrechen verworfen.' : 'Die Verbindung wird beim Test gespeichert. Die Übernahme startet erst nach dem Aktivieren.' }}</p>
      <div class="ds-actions">
        <Button variant="ghost" :disabled="busy" data-testid="datasource-cancel" @click="wizardDialog?.requestClose()">Abbrechen</Button>
        <Button v-if="step > 1" variant="ghost" type="button" :disabled="busy" @click="step = (step - 1) as Step">Zurück</Button>
        <span class="ds-spacer"></span>
        <Button v-if="step < 6" variant="primary" :disabled="busy || !canNext" data-testid="datasource-next" :busy="busy" @click="next">
          {{ busy ? "Bitte warten …" : "Weiter" }}
        </Button>
        <Button v-else variant="primary" :disabled="busy" data-testid="datasource-activate" :busy="busy" @click="activate">
          {{ busy ? "Bitte warten …" : "Aktivieren und abgleichen" }}
        </Button>
      </div>
      </template>
    </AppDialog>

    <AppDialog :open="problemsFor !== null" title="Dateien mit Problemen" :error="problemsError" initial-focus="title" @close="closeProblems">
      <p class="ds-problem-source">{{problemsFor?.displayName}}</p>
      <p v-if="problemsLoading" role="status" data-testid="datasource-problems-loading">Probleme werden geladen …</p>
      <div v-else-if="problemsError"><Button data-testid="datasource-problems-retry" @click="problemsFor && showProblems(problemsFor)">Erneut versuchen</Button></div>
      <ul v-else class="ds-problems" data-testid="datasource-problems-list"><li v-for="p in problems" :key="p.relativePath"><code>{{p.relativePath}}</code><span>{{p.error}}</span></li><li v-if="!problems.length">Keine Dateien mit Problemen gemeldet.<span v-if="problemsFor?.lastRunMessage">{{problemsFor.lastRunMessage}}</span></li></ul>
      <template #footer><Button @click="closeProblems">Schließen</Button></template>
    </AppDialog>

    <ExposureDialog :source="exposureFor" @close="exposureFor = null" @updated="exposureUpdated" />

    <ConfirmDialog
      :open="deleteTarget !== null"
      title="Datenquelle entfernen?"
      :busy="deleting"
      :error="deleteError"
      :message="`„${deleteTarget?.displayName ?? ''}“ wird entfernt und alle daraus übernommenen Dokumente werden aus Keplura gelöscht. Die Originaldateien bleiben unverändert.`"
      confirm-label="Entfernen"
      @confirm="confirmDelete"
      @close="deleteTarget = null"
    />
  </section>
</template>

<style scoped>
.ds { display:grid; gap:20px; min-width:0; container-type:inline-size; }
.ds-overview-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; flex-wrap:wrap; }.ds-overview-head h2 { margin:0; font:600 20px/28px var(--ic-font); }.ds-lead { margin:6px 0 0; color:var(--ic-text-2); font:400 14px/20px var(--ic-font); max-width:54ch; }.ds-head-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.ds-note,.ds-error { margin:0; padding:12px 16px; border-radius:var(--ic-radius-panel); font:400 14px/20px var(--ic-font); }.ds-note.ok { color:var(--ic-status-ok); background:var(--ic-status-ok-bg); }.ds-note.error,.ds-error { color:var(--ic-status-error); background:var(--ic-status-error-bg); }.ds-error { display:flex; align-items:center; justify-content:space-between; gap:12px; flex-wrap:wrap; }.ds-error p { margin:0; }.ds-loading { margin:0; padding:32px 0; color:var(--ic-text-2); }.ds-prerequisite { margin:0; color:var(--ic-text-2); font:400 14px/20px var(--ic-font); }
.ds-toolbar { display:flex; gap:12px; flex-wrap:wrap; }.ds-search { flex:1; min-width:180px; }.ds-search :deep(.ic-field) { margin:0; }.ds-filter { width:200px; }.ds-summary { display:flex; justify-content:space-between; gap:8px 20px; flex-wrap:wrap; color:var(--ic-text-2); font:400 12px/18px var(--ic-font); font-variant-numeric:tabular-nums; }
.ds-table { width:100%; table-layout:fixed; border-collapse:collapse; }.ds-table th { padding:12px 16px 12px 0; text-align:left; color:var(--ic-text-2); font:500 12px/18px var(--ic-font); border-bottom:1px solid var(--ic-separator); }.ds-table td { padding:20px 16px 20px 0; border-bottom:1px solid var(--ic-separator-faint); vertical-align:top; }.ds-operation-col { width:136px; }.ds-result-col { width:220px; }.ds-action-col { width:152px; }.ds-table th:last-child,.ds-table td:last-child { padding-right:0; }.ds-table tbody tr:hover { background:var(--ic-hover-tint); }.ds-source { min-width:0; }.ds-name { display:block; color:var(--ic-text); font:500 14px/20px var(--ic-font); overflow-wrap:anywhere; }.ds-title-line { display:flex; align-items:baseline; gap:4px 12px; flex-wrap:wrap; margin-bottom:8px; }.ds-kind { display:block; color:var(--ic-text-2); font:400 12px/18px var(--ic-font); }.ds-path { display:block; color:var(--ic-text-2); font:400 13px/20px var(--ic-font-mono); overflow-wrap:anywhere; }.ds-meta { display:block; margin-top:4px; color:var(--ic-text-2); font:400 12px/18px var(--ic-font); overflow-wrap:anywhere; }.ds-run-message { display:block; margin-top:8px; color:var(--ic-text-2); font:400 13px/20px var(--ic-font); overflow-wrap:anywhere; }.ds-row-actions { display:flex; justify-content:flex-end; gap:8px; }.ds-run-label { font-size:13px; }.ds-more { display:inline-flex; align-items:center; justify-content:center; flex:none; width:32px; min-height:32px; padding:4px; border:0; border-radius:6px; background:transparent; color:var(--ic-text-2); cursor:pointer; }.ds-more:hover:not(:disabled) { background:var(--ic-hover-tint); }.ds-more:disabled { color:var(--ic-disabled-text); cursor:default; }.ds-menu-danger { color:var(--ic-status-error); }.ds-problems-action { margin-top:8px; }.ds-mobile-label { display:none; }
.ds-empty { display:grid; justify-items:start; gap:12px; padding:32px 0; color:var(--ic-text-2); max-width:60ch; }.ds-empty h3 { margin:0; color:var(--ic-text); font:500 16px/24px var(--ic-font); }.ds-empty p { margin:0; font:400 14px/20px var(--ic-font); }.ds-explanation { padding-top:4px; color:var(--ic-text-2); font:400 13px/20px var(--ic-font); }.ds-explanation summary { padding:8px 0; cursor:pointer; }.ds-explanation p { margin:8px 0 0; max-width:72ch; }.ds-problem-source { margin:0 0 20px; font:500 14px/20px var(--ic-font); color:var(--ic-text-2); }.ds-sr-only { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0,0,0,0); }
@container(max-width:780px) { .ds-table thead { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0,0,0,0); }.ds-table tbody { display:block; }.ds-table tr { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:12px 16px; padding:16px 0; border-top:1px solid var(--ic-separator); }.ds-table td { display:block; min-width:0; padding:0; border:0; }.ds-source { grid-column:1; grid-row:1; }.ds-acts { grid-column:2; grid-row:1; }.ds-operation { grid-column:1; grid-row:2; }.ds-result { grid-column:2; grid-row:2; max-width:260px; }.ds-mobile-label { display:block; margin-bottom:8px; color:var(--ic-text-2); font:500 12px/18px var(--ic-font); }.ds-run-label { display:none; }.ds-table { table-layout:auto; }.ds-row-actions { gap:4px; }.ds-table tbody tr:hover { background:transparent; } }
@container(max-width:440px) { .ds-filter { width:100%; }.ds-head-actions { width:100%; }.ds-overview-head>.ds-head-actions>.primary { flex:1; }.ds-table tr { grid-template-columns:minmax(0,1fr) 104px; }.ds-result { grid-column:1 / -1; grid-row:3; max-width:none; }.ds-operation { grid-column:1 / -1; }.ds-row-actions { justify-content:flex-end; } }
@media(max-width:767px),(any-pointer:coarse) { .ds-more { width:44px; min-height:44px; }.ds-explanation summary { min-height:44px; } }
.ds-form { display: grid; gap: 20px; }
.ds-form label { display: grid; gap: 8px; font-size: 13px; font-weight: 500; }
.ds-form input:not([type="checkbox"]):not([type="radio"]), .ds-form select { min-width: 0; width: 100%;
}
.ds-choice { display: flex !important; grid-template-columns: none; align-items: flex-start; gap: 10px; padding: 16px; border: 1px solid transparent; border-radius: 8px; font-weight: 400 !important; min-height: 72px; cursor: pointer; }
.ds-choice:has(input:checked) { background:var(--ic-selection); }
.ds-choice:hover:not(.off):not(:has(input:checked)) { background:var(--ic-hover-tint); }
.ds-choice input { accent-color:var(--ic-accent); width:16px; height:16px; flex:none; margin:3px 0 0; }
.ds-choice strong { display:inline-block; margin-bottom:4px; font:500 14px/20px var(--ic-font); color:var(--ic-text); }
.ds-choice>span { color:var(--ic-text-2); font:400 13px/20px var(--ic-font); }
.ds-choice.off { opacity:1; cursor:default; color:var(--ic-disabled-text); }
.ds-check { display: flex !important; align-items: center; gap: 8px; font-weight: 400 !important; }
.ds-hint { margin: 0; font-size: 14px; color: var(--ic-text-2); line-height: 1.5; }
.ds-ok { margin: 0; font-size: 13px; color: var(--ic-text-2); line-height: 1.5; }
.ds-warn { margin:0; padding:16px; border-left:3px solid var(--ic-status-wait); background:var(--ic-status-wait-bg); font-size:14px; line-height:22px; color:var(--ic-status-wait); }
.ds-help { font-size: 13px; color: var(--ic-text-2); }
.ds-help summary { cursor: pointer; }
.ds-help ol { margin: 8px 0 0; padding-left: 18px; display: grid; gap: 4px; line-height: 1.5; }
.ds-acl { list-style: none; margin: 0; padding: 0; border: 1px solid var(--ic-separator); border-radius: 8px; max-height: 220px; overflow: auto; }
.ds-acl li { display: flex; justify-content: space-between; gap: 12px; padding: 12px 16px; overflow-wrap:anywhere; border-top: 1px solid var(--ic-separator-faint); font-size: 13px; }
.ds-acl li:first-child { border-top: 0; }
.ds-actions { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.ds-exposure-cta { display:grid; gap:12px; justify-items:start; padding:16px; border-radius:8px; background:var(--ic-surface-subtle); }
.ds-spacer { flex: 1; }
.ds-problems { list-style: none; margin: 0; padding: 0; max-height: 320px; overflow: auto; font-size: 13px; }
.ds-problems li { display: grid; gap: 2px; padding: 6px 0; border-top: 1px solid var(--ic-separator-faint); }
.ds-problems code { white-space:pre-wrap; overflow-wrap:anywhere; font:400 13px/20px var(--ic-font-mono); }
.ds-problems li span { overflow-wrap:anywhere; }

.wizard-mobile { display:none; }.wizard-layout { display:grid; grid-template-columns:192px minmax(0,1fr); min-height:420px; }
.wizard-nav { background:transparent; padding:24px 16px 24px 24px; }
.wizard-caption { margin:0 0 12px; font-size:13px; color:var(--ic-text-2); }
.ds-steps { list-style:none; margin:0; padding:0; display:grid; gap:8px; }
.ds-steps button { width:100%; display:flex; align-items:center; gap:12px; min-height:40px; padding:8px; border:0; border-radius:6px; background:transparent; color:var(--ic-text-2); text-align:left; font:500 14px/20px var(--ic-font); cursor:pointer; }
.ds-steps .on button { background:var(--ic-selection); color:var(--ic-accent); }
.ds-steps button:disabled { cursor:default; color:var(--ic-text-3); }.step-number { display:grid; place-items:center; width:24px; height:24px; flex:none; font-variant-numeric:tabular-nums; }
.wizard-main { padding:24px 32px 32px; min-width:0; }.wizard-title { margin:0 0 24px; font-size:20px; line-height:28px; font-weight:600; }
.wizard-scope { margin-top:24px; color:var(--ic-text-2); font-size:13px; overflow-wrap:anywhere; }
.wizard-save-hint { margin:0; font-size:13px; line-height:20px; color:var(--ic-text-2); }
@media(max-width:767px) { .wizard-layout { grid-template-columns:minmax(0,1fr); min-height:0; }.wizard-nav { padding:12px 16px 0; border:0; }.wizard-nav>.wizard-caption,.wizard-scope { display:none; }.ds-steps { display:none; }.wizard-mobile { display:block; }.wizard-mobile summary { min-height:44px; padding:12px 0; cursor:pointer; font-size:14px; }.wizard-mobile ol { padding:0; list-style:none; }.wizard-mobile button { min-height:44px; width:100%; background:transparent; border:0; color:var(--ic-text); text-align:left; font:inherit; }.wizard-mobile button:disabled { color:var(--ic-text-3); }.ds-steps button { font-size:12px; gap:2px; min-height:44px; padding:4px; }.step-number { width:20px; }.wizard-main { padding:20px 16px; }.wizard-title { font-size:20px; line-height:28px; margin-bottom:20px; }.ds-acl li { flex-wrap:wrap; }.ds-actions { flex-wrap:wrap; }.ds-actions .ds-spacer { display:none; }.ds-actions button { flex:1; }.ds-head { flex-wrap:wrap; padding:12px; }.ds-head-action { margin-left:0; } }
</style>
