<script setup lang="ts">
/** Administration → OpenAPI-Server (TU-06a): Dokument laden, je Operation Risikoklasse setzen und einzeln freigeben. */
import { onBeforeUnmount, onMounted, ref } from "vue";
import {
  createOpenApiServer, deleteOpenApiServer, listMcpSpaces, listOpenApiServers, refreshOpenApiServer, updateOpenApiServer,
  type OpenApiAuthMode, type OpenApiInput, type OpenApiServer, type OpenApiTool, type ToolRisk,
} from "../../productivity-api";
import Button from "../ui/Button.vue";
import ConfirmDialog from "../ui/ConfirmDialog.vue";
import AppDialog from "../ui/AppDialog.vue";
import Input from "../ui/Input.vue";
import AppIcon from "../ui/AppIcon.vue";
import StatusPill from "../ui/StatusPill.vue";
import ActionMenu from "../ui/ActionMenu.vue";
import { tr, serverText } from "../../i18n";

const servers = ref<OpenApiServer[]>([]);
const spaces = ref<{ id: number; name: string; orgName: string }[]>([]);
const name = ref(""), specUrl = ref(""), baseUrl = ref(""), token = ref("");
const authMode = ref<OpenApiAuthMode>("none");
const message = ref(""), messageError = ref(false), busy = ref(false);
const loading = ref(true), loaded = ref(false), loadError = ref(""), spacesError = ref("");
const connectOpen = ref(false), connectError = ref("");
const connectDialog = ref<InstanceType<typeof AppDialog> | null>(null);
const working = ref(new Set<number>());
const expandedId = ref<number | null>(null);
let disposed = false;
onBeforeUnmount(() => { disposed = true; token.value = ""; });

async function load() {
  loading.value = true;
  const [r, sp] = await Promise.all([listOpenApiServers(), listMcpSpaces()]);
  if (disposed) return;
  loading.value = false;
  if (r.kind === "ok") { servers.value = r.value; loaded.value = true; loadError.value = ""; }
  else loadError.value = r.kind === "error" ? r.message : tr("Deine Sitzung ist abgelaufen.");
  spacesError.value = sp.kind === "ok" ? "" : tr("Arbeitsbereiche konnten nicht geladen werden.");
  if (sp.kind === "ok") spaces.value = sp.value;
}
onMounted(load);

function fail(r: { kind: string; message?: string }) {
  messageError.value = true;
  message.value = r.kind === "error" ? (r.message ?? tr("Fehler")) : tr("Deine Sitzung ist abgelaufen.");
}
function replace(s: OpenApiServer) { servers.value = servers.value.map((x) => (x.id === s.id ? s : x)); }
async function patch(s: OpenApiServer, input: OpenApiInput) {
  if (working.value.has(s.id)) return;
  message.value = ""; working.value.add(s.id);
  try { const r = await updateOpenApiServer(s.id, input); if (r.kind === "ok") replace(r.value); else fail(r); }
  finally { working.value.delete(s.id); }
}
function openConnect() { name.value = specUrl.value = baseUrl.value = token.value = ""; authMode.value = "none"; connectError.value = ""; connectOpen.value = true; }
function closeConnect() { connectOpen.value = false; token.value = ""; }
async function add() {
  if (busy.value) return;
  busy.value = true; connectError.value = ""; message.value = "";
  try {
    const r = await createOpenApiServer({ name: name.value, specUrl: specUrl.value, baseUrl: baseUrl.value, authMode: authMode.value,
      token: authMode.value !== "none" && token.value.trim() ? token.value.trim() : null });
    if (r.kind !== "ok") { connectError.value = r.kind === "error" ? r.message : tr("Deine Sitzung ist abgelaufen."); return; }
    expandedId.value = r.value.id; token.value = ""; connectOpen.value = false;
    await load();
    messageError.value = !!r.value.lastError;
    message.value = r.value.lastError ? tr("Gespeichert, aber das Dokument konnte nicht geladen werden: {0}", serverText(r.value.lastError))
      : tr("{0} Operationen gefunden. Bitte je Operation eine Risikoklasse wählen und einzeln freigeben.", r.value.tools.length);
  } finally { busy.value = false; }
}
async function refresh(s: OpenApiServer) {
  if (working.value.has(s.id)) return;
  message.value = ""; working.value.add(s.id);
  try { const r = await refreshOpenApiServer(s.id); if (r.kind === "ok") replace(r.value); else fail(r); }
  finally { working.value.delete(s.id); }
}
const riskLabel = (r: ToolRisk) => ({ READ: tr("Lesen"), WRITE: tr("Schreiben"), CRITICAL: tr("Kritisch") })[r];
function toggleTool(s: OpenApiServer, tool: string, on: boolean) {
  return patch(s, { enabledTools: s.tools.filter((t) => t.name && (t.name === tool ? on : t.enabled)).map((t) => t.name as string) });
}
function hint(t: OpenApiTool): string {
  if (!t.usable) return t.problem ? serverText(t.problem) : tr("Diese Operation ist nicht nutzbar.");
  if (!t.risk) return tr("Erst eine Risikoklasse wählen, dann freigeben. Bis dahin gesperrt.");
  if (t.risk === "CRITICAL") return tr("Kritische Werkzeuge laufen nach Freigabe nur mit Bestätigung je Aufruf durch die Person.");
  if (t.risk === "WRITE") return tr("Schreibende Werkzeuge sind standardmäßig aus. Nur bewusst freigeben.");
  return "";
}
function toggleSpace(s: OpenApiServer, id: number, on: boolean) {
  return patch(s, { spaceIds: on ? [...new Set([...s.spaceIds, id])] : s.spaceIds.filter((x) => x !== id) });
}
const removeTarget = ref<OpenApiServer | null>(null), removing = ref(false), removeError = ref("");
function requestRemove(s: OpenApiServer) { removeTarget.value = s; removeError.value = ""; }
async function confirmRemove() {
  if (!removeTarget.value || removing.value) return;
  removing.value = true; removeError.value = "";
  try {
    const r = await deleteOpenApiServer(removeTarget.value.id);
    if (r.kind === "ok") { removeTarget.value = null; await load(); }
    else removeError.value = r.kind === "error" ? r.message : tr("Deine Sitzung ist abgelaufen.");
  } finally { removing.value = false; }
}
</script>

<template>
  <section class="connection-page" data-testid="admin-openapi-section">
    <header class="connection-header"><div class="connection-heading"><h2>{{ $t("OpenAPI-Server") }}</h2><p class="connection-description">{{ $t("Binde REST-Schnittstellen über ihr OpenAPI-Dokument an. Aufrufe gehen nur an die feste Basis-Adresse.") }}</p></div><div class="connection-actions"><Button variant="icon" size="compact" :disabled="loading" :aria-label="$t('OpenAPI-Server aktualisieren')" :title="$t('Aktualisieren')" @click="load"><AppIcon name="refresh" :size="16" /></Button><Button variant="primary" data-testid="admin-openapi-open" @click="openConnect"><AppIcon name="plus" :size="16" />{{ $t("Server verbinden") }}</Button></div></header>
    <p v-if="loading&&!loaded" class="connection-loading" role="status">{{ $t("Verbindungen werden geladen …") }}</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{loadError}}</p><Button size="compact" @click="load">{{ $t("Erneut versuchen") }}</Button></div>
    <p v-if="message" class="connection-note" :class="messageError?'error':'ok'" :role="messageError?'alert':'status'" data-testid="openapi-message">{{message}}</p>
    <div v-if="loaded" class="connection-list">
      <p v-if="!servers.length" class="connection-empty">{{ $t("Noch kein OpenAPI-Server verbunden. Neue Operationen bleiben zunächst gesperrt, bis du sie freigibst.") }}</p>
      <article v-for="s in servers" :key="s.id" class="connection-record" data-testid="admin-openapi-server">
        <header class="connection-record-header"><div class="connection-heading"><h3>{{s.name}}</h3><code class="connection-address">{{s.baseUrl}}</code><p class="connection-meta">{{ $t("Dokument: {0}", s.specUrl) }}<span v-if="s.authMode==='bearer'"> {{ $t("· Zugangsschlüssel hinterlegt") }}</span><span v-if="s.authMode==='oauth'"> {{ $t("· persönliche Anmeldung je Person") }}</span></p></div><div class="connection-actions"><StatusPill :tone="s.enabled?'info':'neutral'">{{s.enabled?$t("Aktiv"):$t("Pausiert")}}</StatusPill><Button size="compact" :disabled="working.has(s.id)" :busy="working.has(s.id)" data-testid="openapi-refresh" @click="refresh(s)">{{ $t("Neu laden") }}</Button><ActionMenu :label="$t('Aktionen für {0}', s.name)" :disabled="working.has(s.id)" :items="[{id:'toggle',label:s.enabled?$t('Pausieren'):$t('Aktivieren')},{id:'remove',label:$t('Entfernen'),danger:true}]" @select="action=>action==='toggle'?patch(s,{enabled:!s.enabled}):requestRemove(s)" /></div></header>
        <p v-if="s.lastError" class="connection-note error" style="margin-top:12px">{{serverText(s.lastError)}}</p>
        <details class="connection-details mcp-tools" :open="expandedId===s.id"><summary>{{ $t("Operationen · {0} von {1} freigegeben", s.tools.filter(t=>t.enabled).length, s.tools.length) }}</summary>
          <div class="connection-options"><p v-if="!s.tools.length" class="connection-hint">{{ $t("Das Dokument enthält noch keine Operationen.") }}</p>
            <div v-for="(t,i) in s.tools" :key="t.name??`blocked-${i}`" class="connection-option" data-testid="admin-openapi-tool-row">
              <div class="connection-tool-head"><label class="connection-check"><input type="checkbox" :checked="t.enabled" :disabled="working.has(s.id)||!t.usable||!t.risk||!t.name" :aria-label="$t('{0} freigeben', t.name??t.path)" @change="event=>{(event.target as HTMLInputElement).checked=t.enabled;toggleTool(s,t.name as string,!t.enabled)}" /><code>{{t.method}} {{t.path}}</code><span v-if="t.name"> · <code>{{t.name}}</code></span></label>
                <select v-if="t.usable&&t.name" class="ic-native-control connection-risk" :value="t.risk??''" :disabled="working.has(s.id)" :aria-label="$t('Risikoklasse für {0}', t.name)" @change="event=>{const v=(event.target as HTMLSelectElement).value;(event.target as HTMLSelectElement).value=t.risk??'';patch(s,{toolRisks:{[t.name as string]:v as ToolRisk|''}})}"><option value="">{{ $t("Nicht klassifiziert") }}</option><option value="READ">{{riskLabel("READ")}}</option><option value="WRITE">{{riskLabel("WRITE")}}</option><option value="CRITICAL">{{riskLabel("CRITICAL")}}</option></select>
                <StatusPill v-else tone="neutral">{{ $t("nicht nutzbar") }}</StatusPill></div>
              <p>{{t.description}}</p><p v-if="hint(t)" class="connection-hint">{{hint(t)}}</p></div></div>
        </details>
        <details class="connection-details mcp-access" :open="expandedId===s.id"><summary>{{ $t("Arbeitsbereiche · {0}", s.allSpaces?$t("Alle freigegeben"):`${s.spaceIds.length} freigegeben`) }}</summary>
          <div v-if="spacesError" class="connection-note error"><p>{{spacesError}}</p></div>
          <div v-else class="connection-options"><label class="connection-check"><input type="checkbox" :checked="s.allSpaces" :disabled="working.has(s.id)" @change="event=>{(event.target as HTMLInputElement).checked=s.allSpaces;patch(s,{allSpaces:!s.allSpaces})}" />{{ $t("Alle Arbeitsbereiche") }}</label><template v-if="!s.allSpaces"><label v-for="sp in spaces" :key="sp.id" class="connection-check"><input type="checkbox" :checked="s.spaceIds.includes(sp.id)" :disabled="working.has(s.id)" @change="event=>{(event.target as HTMLInputElement).checked=s.spaceIds.includes(sp.id);toggleSpace(s,sp.id,!s.spaceIds.includes(sp.id))}" />{{sp.orgName}} / {{sp.name}}</label></template></div>
        </details>
      </article>
    </div>
    <details class="connection-details"><summary>{{ $t("Hinweise zur Einrichtung") }}</summary><p>{{ $t("Unterstützt werden OpenAPI 3.0 und 3.1 als JSON (höchstens 1 MB, kein YAML) mit lokalen Referenzen. Nur Operationen mit operationId werden Werkzeuge; Pfad- und Query-Parameter sowie JSON-Körper sind möglich, Header-, Cookie- und Formularparameter sowie Datei-Uploads nicht. Die servers-Angabe des Dokuments wird ignoriert; Aufrufe gehen nur an die eingetragene Basis-Adresse, ohne Weiterleitungen.") }}</p></details>
    <AppDialog ref="connectDialog" :open="connectOpen" :title="$t('OpenAPI-Server verbinden')" size="md" :busy="busy" :dirty="!!name||!!specUrl||!!baseUrl||!!token" :error="connectError" @close="closeConnect">
      <form class="connection-form" @submit.prevent="add">
        <Input id="openapi-name" v-model="name" :label="$t('Name der Verbindung')" required maxlength="120" :disabled="busy" />
        <Input id="openapi-spec" v-model="specUrl" :label="$t('Adresse des OpenAPI-Dokuments (JSON)')" type="url" required maxlength="500" :placeholder="$t('https://api.firma.local/openapi.json')" :disabled="busy" />
        <Input id="openapi-base" v-model="baseUrl" :label="$t('Feste Basis-Adresse für Aufrufe')" type="url" required maxlength="500" :placeholder="$t('https://api.firma.local/v1')" :disabled="busy" />
        <label class="connection-check">{{ $t("Zugang") }} <select v-model="authMode" class="ic-native-control" :disabled="busy"><option value="none">{{ $t("Keiner") }}</option><option value="bearer">{{ $t("Bearer-Token (verschlüsselt gespeichert)") }}</option><option value="oauth">{{ $t("Persönlich (OAuth, je Person)") }}</option></select></label>
        <p v-if="authMode==='oauth'" class="connection-hint">{{ $t("Den OAuth-Client richtest du danach unter „Persönliche Verbindungen“ ein. Ein optionales Token unten dient nur zum Laden des Dokuments.") }}</p>
        <Input v-if="authMode!=='none'" id="openapi-token" v-model="token" :label="authMode==='bearer'?$t('Bearer-Token'):$t('Token für das Dokument (optional)')" type="password" autocomplete="off" :disabled="busy" />
        <div class="connection-submit"><Button :disabled="busy" @click="connectDialog?.requestClose()">{{ $t("Abbrechen") }}</Button><Button variant="primary" type="submit" :disabled="busy" :busy="busy">{{busy?$t("Dokument wird geladen …"):$t("Verbinden")}}</Button></div>
      </form>
    </AppDialog>
    <ConfirmDialog :open="removeTarget!==null" :title="$t('OpenAPI-Verbindung entfernen?')" :message="$t('Die Verbindung „{0}“ und ihre Werkzeugfreigaben werden entfernt.', removeTarget?.name??'')" :confirm-label="$t('Entfernen')" :busy="removing" :error="removeError" @confirm="confirmRemove" @close="removeTarget=null" />
  </section>
</template>
