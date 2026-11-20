<script setup lang="ts">
/** Administration → MCP-Server (Stufe 1): eintragen, verbinden, Werkzeuge einzeln freigeben. */
import { onBeforeUnmount, onMounted, ref } from "vue";
import {
  createMcpServer, deleteMcpServer, listMcpCalls, listMcpServers, listMcpSpaces, refreshMcpServer, updateMcpServer,
  type McpServer,
} from "../../productivity-api";
import Button from "../ui/Button.vue";
import ConfirmDialog from "../ui/ConfirmDialog.vue";
import AppDialog from "../ui/AppDialog.vue";
import Input from "../ui/Input.vue";
import AppIcon from "../ui/AppIcon.vue";
import StatusPill from "../ui/StatusPill.vue";
import ActionMenu from "../ui/ActionMenu.vue";

const servers = ref<McpServer[]>([]);
const name = ref("");
const url = ref("");
const token = ref("");
const message = ref("");
const busy = ref(false);
const expandedId = ref<number | null>(null);
const connectDialog=ref<InstanceType<typeof AppDialog>|null>(null);
const messageError=ref(false);
const connectOpen=ref(false),connectError=ref(""),loading=ref(true),loaded=ref(false),loadError=ref(""),spacesError=ref(""),callsError=ref("");
const working=ref(new Set<number>());
let disposed=false;
onBeforeUnmount(()=>{disposed=true;token.value="";});
function openConnect(){name.value=url.value=token.value="";connectError.value="";connectOpen.value=true;}
function closeConnect(){connectOpen.value=false;token.value="";}


const spaces = ref<{ id: number; name: string; orgName: string }[]>([]);
const calls = ref<{ called_at: string; server_name: string; tool_name: string; duration_ms: number; ok: boolean; error: string | null; user_id: number }[]>([]);
async function load() {
  loading.value=true;
  const [r,sp,c]=await Promise.all([listMcpServers(),listMcpSpaces(),listMcpCalls()]);
  if(disposed)return;
  loading.value=false;
  if(r.kind==="ok"){servers.value=r.value;loaded.value=true;loadError.value="";}else loadError.value=r.kind==="error"?r.message:"Deine Sitzung ist abgelaufen.";
  spacesError.value=sp.kind==="ok"?"":"Arbeitsbereiche konnten nicht geladen werden.";
  if(sp.kind==="ok")spaces.value=sp.value;
  callsError.value=c.kind==="ok"?"":"Letzte Aufrufe konnten nicht geladen werden.";
  if(c.kind==="ok")calls.value=c.value;
}
async function patchServer(s:McpServer,input:Parameters<typeof updateMcpServer>[1]){
  if(working.value.has(s.id))return;message.value="";working.value.add(s.id);
  try{const r=await updateMcpServer(s.id,input);if(r.kind==="ok")replace(r.value);else fail(r);}finally{working.value.delete(s.id);}
}
async function setAll(s: McpServer, on: boolean) {
  await patchServer(s, { allSpaces: on });
}
async function toggleSpace(s: McpServer, id: number, on: boolean) {
  const next = on ? [...new Set([...s.spaceIds, id])] : s.spaceIds.filter((x) => x !== id);
  await patchServer(s, { spaceIds: next });
}
onMounted(load);

function fail(r: { kind: string; message?: string }) {
  messageError.value=true;
  message.value = r.kind === "error" ? (r.message ?? "Fehler") : "Deine Sitzung ist abgelaufen.";
}
function replace(s: McpServer) {
  servers.value = servers.value.map((x) => (x.id === s.id ? s : x));
}

async function add() {
  if(busy.value)return;
  busy.value = true;
  message.value = "";
  connectError.value="";
  try {
    const r = await createMcpServer({ name: name.value, url: url.value, token: token.value.trim() || null });
    if (r.kind !== "ok") { connectError.value=r.kind === "error" ? r.message : "Deine Sitzung ist abgelaufen.";return; }
    expandedId.value = r.value.id;
    name.value = url.value = token.value = "";
    connectOpen.value=false;
    await load();
    messageError.value=!!r.value.lastError;
    message.value = r.value.lastError ? `Gespeichert, aber keine Verbindung: ${r.value.lastError}` : `${r.value.tools.length} Werkzeuge gefunden. Bitte einzeln freigeben.`;
  } finally { busy.value = false; }
}
async function refresh(s: McpServer) {
  if(working.value.has(s.id))return;message.value="";working.value.add(s.id);
  try{const r=await refreshMcpServer(s.id);if(r.kind==="ok")replace(r.value);else fail(r);}finally{working.value.delete(s.id);}
}
async function toggleTool(s: McpServer, tool: string, on: boolean) {
  const next = s.tools.filter((t) => (t.name === tool ? on : t.enabled)).map((t) => t.name);
  await patchServer(s, { enabledTools: next });
}
async function toggleServer(s: McpServer) {
  await patchServer(s, { enabled: !s.enabled });
}
const removeTarget = ref<McpServer | null>(null);
const removing = ref(false);
const removeError = ref("");
function requestRemove(s: McpServer) {
  removeTarget.value = s;
  removeError.value = "";
}
async function confirmRemove() {
  if (!removeTarget.value || removing.value) return;
  removing.value = true;
  removeError.value = "";
  try {
    const r = await deleteMcpServer(removeTarget.value.id);
    if (r.kind === "ok") {
      removeTarget.value = null;
      await load();
    } else {
      removeError.value = r.kind === "error"
        ? r.message ?? "Die Verbindung konnte nicht entfernt werden."
        : "Deine Sitzung ist abgelaufen.";
    }
  } finally {
    removing.value = false;
  }
}
</script>

<template>
  <section class="connection-page" data-testid="admin-mcp-section">
    <header class="connection-header"><div class="connection-heading"><h2>MCP-Server</h2><p class="connection-description">Verbinde Werkzeuge für den Chat und lege fest, wer sie verwenden darf.</p></div><div class="connection-actions"><Button variant="icon" size="compact" :disabled="loading" aria-label="MCP-Verbindungen aktualisieren" title="Aktualisieren" @click="load"><AppIcon name="refresh" :size="16" /></Button><Button variant="primary" data-testid="admin-mcp-open" @click="openConnect"><AppIcon name="plus" :size="16" />Server verbinden</Button></div></header>
    <p v-if="loading&&!loaded" class="connection-loading" role="status">Verbindungen werden geladen …</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{loadError}}</p><Button size="compact" @click="load">Erneut versuchen</Button></div>
    <p v-if="message" class="connection-note" :class="messageError?'error':'ok'" :role="messageError?'alert':'status'" data-testid="mcp-message">{{message}}</p>
    <div v-if="loaded" class="connection-list">
      <p v-if="!servers.length" class="connection-empty" data-testid="mcp-empty">Noch kein MCP-Server verbunden. Neue Werkzeuge bleiben zunächst gesperrt, bis du sie freigibst.</p>
      <article v-for="s in servers" :key="s.id" class="connection-record" data-testid="admin-mcp-server">
        <header class="connection-record-header"><div class="connection-heading"><h3>{{s.name}}</h3><code class="connection-address">{{s.url}}</code><p class="connection-meta">{{s.tools.length}} Werkzeuge<span v-if="s.hasToken"> · Zugangsschlüssel hinterlegt</span></p></div><div class="connection-actions"><StatusPill :tone="s.enabled?'info':'neutral'" data-testid="mcp-operation">{{s.enabled?'Aktiv':'Pausiert'}}</StatusPill><Button size="compact" :disabled="working.has(s.id)" :busy="working.has(s.id)" data-testid="mcp-refresh" @click="refresh(s)">Neu laden</Button><ActionMenu :label="`Aktionen für ${s.name}`" :disabled="working.has(s.id)" :items="[{id:'toggle',label:s.enabled?'Pausieren':'Aktivieren',testId:'mcp-toggle'},{id:'remove',label:'Entfernen',danger:true,testId:'mcp-remove'}]" @select="action=>action==='toggle'?toggleServer(s):requestRemove(s)" /></div></header>
        <div class="connection-state" style="margin-top:16px"><StatusPill :tone="s.lastError?'error':s.lastCheckedAt?'ok':'neutral'">{{s.lastError?'Verbindung fehlgeschlagen':s.lastCheckedAt?'Verbindung geprüft':'Noch nicht geprüft'}}</StatusPill><span v-if="s.lastCheckedAt" class="connection-meta">Zuletzt geprüft {{new Date(s.lastCheckedAt).toLocaleString('de-DE')}}</span></div>
        <p v-if="s.lastError" class="connection-note error" style="margin-top:12px">{{s.lastError}}</p>
        <details class="connection-details mcp-tools" :open="expandedId===s.id"><summary>Werkzeuge · {{s.tools.filter(t=>t.enabled).length}} von {{s.tools.length}} freigegeben</summary>
          <div class="connection-options"><p v-if="!s.tools.length" class="connection-hint">Dieser Server hat noch keine Werkzeuge bereitgestellt.</p><div v-for="t in s.tools" :key="t.name" class="connection-option"><label class="connection-check"><input type="checkbox" :checked="t.enabled" :disabled="working.has(s.id)" data-testid="admin-mcp-tool" @change="event=>{(event.target as HTMLInputElement).checked=t.enabled;toggleTool(s,t.name,!t.enabled)}" /><code>{{t.name}}</code></label><p>{{t.description}}</p></div></div>
        </details>
        <details class="connection-details mcp-access" :open="expandedId===s.id"><summary>Arbeitsbereiche · {{s.allSpaces?'Alle freigegeben':`${s.spaceIds.length} freigegeben`}}</summary>
          <div v-if="spacesError" class="connection-note error"><p>{{spacesError}}</p><Button size="compact" @click="load">Erneut versuchen</Button></div>
          <div v-else class="connection-options"><label class="connection-check"><input type="checkbox" :checked="s.allSpaces" :disabled="working.has(s.id)" data-testid="admin-mcp-all-spaces" @change="event=>{(event.target as HTMLInputElement).checked=s.allSpaces;setAll(s,!s.allSpaces)}" />Alle Arbeitsbereiche</label><template v-if="!s.allSpaces"><label v-for="sp in spaces" :key="sp.id" class="connection-check"><input type="checkbox" :checked="s.spaceIds.includes(sp.id)" :disabled="working.has(s.id)" data-testid="admin-mcp-space" @change="event=>{(event.target as HTMLInputElement).checked=s.spaceIds.includes(sp.id);toggleSpace(s,sp.id,!s.spaceIds.includes(sp.id))}" />{{sp.orgName}} / {{sp.name}}</label><p v-if="!s.spaceIds.length" class="connection-hint">Noch in keinem Arbeitsbereich freigegeben.</p></template></div>
        </details>
      </article>
    </div>
    <details class="connection-details log"><summary>Letzte Werkzeugaufrufe<span v-if="calls.length"> · {{calls.length}}</span></summary><p v-if="callsError" class="connection-note error">{{callsError}}</p><p v-else-if="!calls.length" class="connection-hint">Noch keine Aufrufe protokolliert.</p><div v-else class="connection-table-wrap" tabindex="0" role="region" aria-label="Protokoll der Werkzeugaufrufe"><table class="connection-table"><thead><tr><th>Zeitpunkt</th><th>Verbindung / Werkzeug</th><th>Person</th><th>Dauer</th><th>Ergebnis</th></tr></thead><tbody><tr v-for="(c,i) in calls" :key="i"><td>{{new Date(c.called_at).toLocaleString('de-DE')}}</td><td>{{c.server_name}}<br /><code>{{c.tool_name}}</code></td><td>Benutzer {{c.user_id}}</td><td>{{c.duration_ms}} ms</td><td>{{c.ok?'Erfolgreich':c.error||'Fehlgeschlagen'}}</td></tr></tbody></table></div></details>
    <details class="connection-details"><summary>Hinweise zur Einrichtung</summary><p>Verbindungen ins Firmennetz müssen vom Betrieb erlaubt sein. Neue Werkzeuge sind zunächst gesperrt. Gib Werkzeuge und Arbeitsbereiche einzeln frei. Neue Verbindungen und geänderte Zugangsdaten benötigen eine erneute Werkzeugfreigabe. Das verwendete Modell muss Werkzeugaufrufe unterstützen.</p></details>
    <AppDialog ref="connectDialog" :open="connectOpen" title="MCP-Server verbinden" size="md" :busy="busy" :dirty="!!name||!!url||!!token" :error="connectError" @close="closeConnect">
      <form class="connection-form" @submit.prevent="add"><Input id="mcp-name" v-model="name" label="Name der Verbindung" required maxlength="120" placeholder="z. B. Ticketsystem" :disabled="busy" data-testid="admin-mcp-name" /><Input id="mcp-url" v-model="url" label="Server-Adresse" type="url" required maxlength="500" placeholder="https://mcp.firma.local/mcp" :disabled="busy" data-testid="admin-mcp-url" /><Input id="mcp-token" v-model="token" label="Zugangsschlüssel (optional)" type="password" autocomplete="off" hint="Nur falls der Server einen Schlüssel benötigt." :disabled="busy" data-testid="admin-mcp-token" /><div class="connection-submit"><Button :disabled="busy" @click="connectDialog?.requestClose()">Abbrechen</Button><Button variant="primary" type="submit" :disabled="busy" :busy="busy" data-testid="admin-mcp-add">{{busy?'Verbindung wird geprüft …':'Verbinden'}}</Button></div></form>
    </AppDialog>
    <ConfirmDialog :open="removeTarget!==null" title="MCP-Verbindung entfernen?" :message="`Die Verbindung „${removeTarget?.name??''}“ und ihre Werkzeugfreigaben werden entfernt.`" confirm-label="Entfernen" :busy="removing" :error="removeError" @confirm="confirmRemove" @close="removeTarget=null" />
  </section>
</template>
