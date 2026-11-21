<script setup lang="ts">
/**
 * ADR-0012 — Verzeichnis (Active Directory / LDAP) in der Administration.
 * Anlegen mit sinnvollen Vorgaben, „Verbindung testen“ und „Benutzer
 * nachschlagen“ (zeigt aufgelöste Gruppen). Passwort des Dienstkontos ist
 * write-only.
 */
import { computed, onMounted, ref } from "vue";
import { useAuthStore } from "../../auth-store";
import {
  createDirectory, deleteDirectory, listDirectories, lookupDirectoryUser, principalLabel, resultText,
  testDirectory, updateDirectory, type DirectoryConnection, type DirectoryLookup,
} from "../../datasource-api";
import AppDialog from "../ui/AppDialog.vue";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";
import ConfirmDialog from "../ui/ConfirmDialog.vue";
import StatusPill from "../ui/StatusPill.vue";
import ActionMenu from "../ui/ActionMenu.vue";
import { tr } from "../../i18n";

const auth = useAuthStore();
const items = ref<DirectoryConnection[]>([]);
const note = ref("");
const noteKind = ref<"ok" | "error" | "">("");
const loading=ref(true),loaded=ref(false),loadError=ref("");

async function load() {
  loading.value=true;
  const r=await listDirectories();loading.value=false;
  if(r.kind==="ok"){items.value=r.value;loaded.value=true;loadError.value="";}else loadError.value=resultText(r);
}
onMounted(load);

function show(kind: "ok" | "error", text: string) {
  noteKind.value = kind;
  note.value = text;
}

function orgName(id: number) {
  return auth.orgs.find((o) => o.id === id)?.name ?? tr("Organisation {0}", id);
}

/* ---------- Formular ---------- */
const formOpen = ref(false);
const editing = ref<DirectoryConnection | null>(null);
const formError = ref("");
const saving = ref(false);
const formDialog=ref<InstanceType<typeof AppDialog>|null>(null);
const savedForm=ref("");
const formDirty=computed(()=>JSON.stringify(f.value)!==savedForm.value);
function closeForm(){formOpen.value=false;f.value.bindPassword="";}

const f = ref({
  kind: "AD" as "AD" | "LDAP",
  displayName: "",
  url: "",
  baseDn: "",
  bindDn: "",
  bindPassword: "",
  userFilter: "",
  startTls: false,
  allowPlainLdap: false,
  loginEnabled: true,
  autoProvision: true,
});

const placeholders = computed(() =>
  f.value.kind === "AD"
    ? { url: "ldaps://dc01.firma.local", baseDn: tr("DC=firma,DC=local"), bindDn: tr("CN=svc-keplura,OU=Dienste,DC=firma,DC=local") }
    : { url: "ldaps://ldap.firma.local", baseDn: tr("dc=firma,dc=local"), bindDn: tr("uid=keplura,ou=services,dc=firma,dc=local") },
);

function openCreate() {
  editing.value = null;
  formError.value = "";
  f.value = { kind: "AD", displayName: tr("Active Directory"), url: "", baseDn: "", bindDn: "", bindPassword: "",
    userFilter: "", startTls: false, allowPlainLdap: false, loginEnabled: true, autoProvision: true };
  savedForm.value=JSON.stringify(f.value);
  formOpen.value = true;
}

function openEdit(d: DirectoryConnection) {
  editing.value = d;
  formError.value = "";
  f.value = { kind: d.kind, displayName: d.displayName, url: d.url, baseDn: d.baseDn, bindDn: d.bindDn, bindPassword: "",
    userFilter: d.userFilter, startTls: d.startTls, allowPlainLdap: d.allowPlainLdap,
    loginEnabled: d.loginEnabled, autoProvision: d.autoProvision };
  savedForm.value=JSON.stringify(f.value);
  formOpen.value = true;
}

async function save() {
  if(saving.value)return;
  if (auth.selectedOrgId === null && !editing.value) {
    formError.value = tr("Bitte zuerst oben links eine Organisation wählen.");
    return;
  }
  saving.value = true;
  formError.value = "";
  const input = { ...f.value, userFilter: f.value.userFilter.trim() || undefined,
    bindPassword: f.value.bindPassword || undefined };
  const r = editing.value
    ? await updateDirectory(editing.value.id, input)
    : await createDirectory({ ...input, orgId: auth.selectedOrgId ?? undefined });
  saving.value = false;
  if (r.kind !== "ok") {
    formError.value = resultText(r);
    return;
  }
  closeForm();
  show("ok", editing.value ? tr("Verbindung gespeichert.") : tr("Verbindung angelegt. Teste sie jetzt."));
  await load();
}

/* ---------- Test / Nachschlagen / Löschen ---------- */
const testing = ref<number | null>(null);
const testResult = ref<Record<number, { ok: boolean; message: string }>>({});
async function runTest(d: DirectoryConnection) {
  if(testing.value!==null)return;
  testing.value = d.id;
  const r = await testDirectory(d.id);
  testing.value = null;
  testResult.value[d.id] = r.kind === "ok" ? { ok: r.value.ok, message: r.value.message } : { ok: false, message: resultText(r) };
}

const lookupFor = ref<DirectoryConnection | null>(null);
const lookupLogin = ref("");
const lookupBusy = ref(false);
const lookupResult = ref<DirectoryLookup | null>(null);
const lookupError = ref("");
let lookupRequest=0;
function closeLookup(){lookupRequest++;lookupFor.value=null;lookupBusy.value=false;}
function openLookup(d: DirectoryConnection) {
  lookupRequest++;lookupBusy.value=false;
  lookupFor.value = d;
  lookupLogin.value = "";
  lookupResult.value = null;
  lookupError.value = "";
}
async function runLookup() {
  if (!lookupFor.value || !lookupLogin.value.trim() || lookupBusy.value) return;
  const request=++lookupRequest;
  lookupBusy.value = true;
  lookupError.value = "";
  const r = await lookupDirectoryUser(lookupFor.value.id, lookupLogin.value.trim());
  if(request!==lookupRequest)return;
  lookupBusy.value = false;
  if (r.kind === "ok") lookupResult.value = r.value;
  else lookupError.value = resultText(r);
}

const deleteTarget = ref<DirectoryConnection | null>(null);
const deleting=ref(false),deleteError=ref("");
function askDelete(d:DirectoryConnection){deleteError.value="";deleteTarget.value=d;}
async function confirmDelete() {
  const d=deleteTarget.value;if(!d||deleting.value)return;
  deleting.value=true;deleteError.value="";
  try{const r=await deleteDirectory(d.id);if(r.kind==="ok"){deleteTarget.value=null;show("ok",tr("Verbindung entfernt. Bereits angelegte Konten bleiben bestehen."));await load();}else deleteError.value=resultText(r);}finally{deleting.value=false;}
}
function rowAction(d:DirectoryConnection,action:string){if(action==='edit')openEdit(d);else if(action==='lookup')openLookup(d);else askDelete(d);}

</script>

<template>
  <section class="connection-page" data-testid="admin-directory-section">
    <header class="connection-header"><div class="connection-heading"><h2>{{ $t("Verzeichnis") }}</h2><p class="connection-description">{{ $t("Verbinde Firmenkonten und Gruppen aus Active Directory oder LDAP.") }}</p></div><div class="connection-actions"><Button variant="icon" size="compact" :aria-label="$t('Verzeichnisse aktualisieren')" :disabled="loading" @click="load"><AppIcon name="refresh" :size="16" /></Button><Button variant="primary" data-testid="directory-create" @click="openCreate"><AppIcon name="plus" :size="16" />{{ $t("Verbindung hinzufügen") }}</Button></div></header>
    <p v-if="loading&&!loaded" class="connection-loading" role="status">{{ $t("Verbindungen werden geladen …") }}</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{loadError}}</p><Button size="compact" @click="load">{{ $t("Erneut versuchen") }}</Button></div>
    <p v-if="note" class="connection-note" :class="noteKind" :role="noteKind==='error'?'alert':'status'" data-testid="directory-note">{{note}}</p>
    <div v-if="loaded" class="connection-list">
      <p v-if="!items.length" class="connection-empty" data-testid="directory-empty">{{ $t("Noch kein Verzeichnis verbunden. Lokale Keplura-Konten bleiben wie bisher verfügbar.") }}</p>
      <article v-for="d in items" :key="d.id" class="connection-record" data-testid="directory-item">
        <header class="connection-record-header"><div class="connection-heading"><h3>{{d.displayName}}</h3><code class="connection-address">{{d.url}}</code><p class="connection-meta">{{d.kind==='AD'?$t("Active Directory"):'LDAP'}} · {{orgName(d.orgId)}}</p></div>
          <div class="connection-actions"><StatusPill :tone="d.enabled?'info':'neutral'">{{d.enabled?$t("Aktiv"):$t("Deaktiviert")}}</StatusPill><Button size="compact" :disabled="testing!==null" :busy="testing===d.id" data-testid="directory-test" @click="runTest(d)">{{testing===d.id?$t("Wird geprüft …"):$t("Verbindung testen")}}</Button><ActionMenu :label="$t('Aktionen für {0}', d.displayName)" :items="[{id:'lookup',label:$t('Benutzer nachschlagen'),testId:'directory-lookup'},{id:'edit',label:$t('Bearbeiten'),testId:'directory-edit'},{id:'remove',label:$t('Entfernen'),danger:true,testId:'directory-remove'}]" @select="action=>rowAction(d,action)" /></div>
        </header>
        <p class="connection-meta">{{d.enabled&&d.loginEnabled?$t("Anmeldung mit Firmenkonto erlaubt"):!d.enabled?$t("Verbindung deaktiviert"): $t("Nur Gruppenabgleich – keine Anmeldung")}} · {{d.autoProvision?$t("Neue Konten beim ersten Anmelden anlegen"):$t("Neue Konten werden nicht automatisch angelegt")}}</p>
        <p v-if="testResult[d.id]" class="connection-note" style="margin-top:16px" :class="testResult[d.id].ok?'ok':'error'" :role="testResult[d.id].ok?'status':'alert'" data-testid="directory-test-result">{{testResult[d.id].message}}</p>
      </article>
    </div>
    <details class="connection-details"><summary>{{ $t("Firmenkonten und Zugriffsrechte") }}</summary><p>{{ $t("Mit einem Verzeichnis können Mitarbeiter ihre bestehenden Firmenkonten verwenden. Die erkannten Konten und Gruppen bestimmen den Zugriff auf übernommene Dateien. Lokale Konten bleiben weiterhin verfügbar.") }}</p></details>

    <AppDialog ref="formDialog" size="md" :busy="saving" :dirty="formDirty" :open="formOpen" :title="editing ? $t('Verbindung bearbeiten') : $t('Verzeichnis verbinden')" :error="formError" @close="closeForm">
      <form class="connection-form dp-form" data-testid="directory-form" @submit.prevent="save">
        <fieldset class="dp-kinds">
          <legend>{{ $t("Art des Verzeichnisses") }}</legend>
          <label><input v-model="f.kind" name="directory-kind" type="radio" value="AD" /> {{ $t("Active Directory (Windows)") }}</label>
          <label><input v-model="f.kind" name="directory-kind" type="radio" value="LDAP" /> {{ $t("LDAP (z. B. OpenLDAP, FreeIPA)") }}</label>
        </fieldset>
        <label>{{ $t("Name") }} <input class="ic-native-control" v-model="f.displayName" required maxlength="200" /></label>
        <label>{{ $t("Adresse des Servers") }} <input class="ic-native-control" v-model="f.url" required :placeholder="placeholders.url" data-testid="directory-url" /></label>
        <label class="dp-check"><input v-model="f.startTls" type="checkbox" /> {{ $t("StartTLS verwenden (bei ldap:// statt ldaps://)") }}</label>
        <label>{{ $t("Suchbasis (Base DN)") }} <input class="ic-native-control" v-model="f.baseDn" required :placeholder="placeholders.baseDn" /></label>
        <label>{{ $t("Dienstkonto (nur Lesen)") }} <input class="ic-native-control" v-model="f.bindDn" required :placeholder="placeholders.bindDn" /></label>
        <label>{{ $t("Passwort des Dienstkontos") }}
          <input class="ic-native-control" v-model="f.bindPassword" type="password" autocomplete="new-password"
                 :placeholder="editing?.secretSet ? $t('bleibt gespeichert – nur zum Ändern eingeben') : ''" />
        </label>
        <label class="dp-check"><input v-model="f.loginEnabled" type="checkbox" /> {{ $t("Mitarbeiter dürfen sich mit diesem Konto anmelden") }}</label>
        <label class="dp-check"><input v-model="f.autoProvision" type="checkbox" /> {{ $t("Beim ersten Anmelden automatisch als Mitglied aufnehmen") }}</label>
        <details class="dp-more">
          <summary>{{ $t("Erweitert") }}</summary>
          <label>{{ $t("Benutzerfilter (Platzhalter {login})") }}
            <input class="ic-native-control" v-model="f.userFilter" :placeholder="$t('leer lassen für die passende Vorgabe')" />
          </label>
          <label class="dp-check"><input v-model="f.allowPlainLdap" type="checkbox" /> {{ $t("Unverschlüsselte Verbindung erlauben (nicht empfohlen)") }}</label>
        </details>
        <div class="dp-actions">
          <Button variant="ghost" type="button" @click="formDialog?.requestClose()">{{ $t("Abbrechen") }}</Button>
          <Button variant="primary" type="submit" :disabled="saving" data-testid="directory-save">{{ saving ? $t("Speichere …") : $t("Speichern") }}</Button>
        </div>
      </form>
    </AppDialog>

    <AppDialog size="md" :open="lookupFor !== null" :title="$t('Benutzer nachschlagen')" :error="lookupError" @close="closeLookup">
      <form class="connection-form dp-form" @submit.prevent="runLookup">
        <label>{{ $t("Benutzername oder E-Mail") }} <input class="ic-native-control" v-model="lookupLogin" required data-testid="directory-lookup-login" /></label>
        <div class="dp-actions">
          <Button variant="primary" type="submit" :disabled="lookupBusy">{{ lookupBusy ? $t("Suche …") : $t("Nachschlagen") }}</Button>
        </div>
      </form>
      <div v-if="lookupResult" class="dp-lookup" data-testid="directory-lookup-result">
        <p v-if="!lookupResult.found" class="dp-test error">{{ lookupResult.message }}</p>
        <template v-else>
          <p><strong>{{ lookupResult.displayName }}</strong> · {{ lookupResult.email ?? $t("keine E-Mail im Verzeichnis") }}</p>
          <p class="dp-meta">{{ $t("{0} Konten und Gruppen erkannt – danach richtet sich, welche Dateien die Person sieht:", lookupResult.principals.length) }}</p>
          <ul><li v-for="p in lookupResult.principals.slice(0, 40)" :key="p">{{ principalLabel(p) }}</li></ul>
        </template>
      </div>
    </AppDialog>

    <ConfirmDialog
      :open="deleteTarget !== null"
      :title="$t('Verbindung entfernen?')"
      :busy="deleting"
      :error="deleteError"
      :message="$t('„{0}“ wird entfernt. Anmeldungen über dieses Verzeichnis sind danach nicht mehr möglich; bestehende Konten bleiben.', deleteTarget?.displayName ?? '')"
      :confirm-label="$t('Entfernen')"
      @confirm="confirmDelete"
      @close="deleteTarget = null"
    />
  </section>
</template>

<style scoped>
.dp-kinds { display:grid;gap:8px;margin:0;padding:0;border:0; }.dp-kinds legend { margin-bottom:8px;color:var(--ic-text-2);font:500 13px/20px var(--ic-font); }.dp-kinds label,.dp-form .dp-check { display:flex;align-items:center;gap:8px;min-height:32px;font:400 13px/20px var(--ic-font);color:var(--ic-text-2); }.dp-kinds input,.dp-check input { flex:none;width:16px;height:16px;margin:0;accent-color:var(--ic-accent); }.dp-more summary { padding:10px 0;color:var(--ic-text-2);font:500 13px/20px var(--ic-font);cursor:pointer; }.dp-more[open] { display:grid;gap:20px; }.dp-more label { display:grid;gap:8px;color:var(--ic-text-2);font:500 13px/20px var(--ic-font); }.dp-actions { display:flex;justify-content:flex-end;gap:8px; }.dp-lookup { margin-top:24px;color:var(--ic-text-2);font:400 14px/20px var(--ic-font);overflow-wrap:anywhere; }.dp-lookup ul { max-height:240px;overflow:auto;padding-left:20px;font:400 13px/20px var(--ic-font-mono); }.dp-test.error { color:var(--ic-status-error); }.dp-meta { color:var(--ic-text-2);font:400 13px/20px var(--ic-font); }
@media(max-width:767px),(any-pointer:coarse){.dp-kinds label,.dp-form .dp-check,.dp-more summary{min-height:44px}}
</style>
