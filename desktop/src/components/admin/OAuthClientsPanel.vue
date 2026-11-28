<script setup lang="ts">
/** Administration → Persönliche Verbindungen (TU-06b): OAuth-Client je MCP-/OpenAPI-Server. Nie Tokens oder Konten. */
import { onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { deleteOAuthClient, listOAuthClients, putOAuthClient, type OAuthClientView } from "../../productivity-api";
import Button from "../ui/Button.vue";
import Input from "../ui/Input.vue";
import StatusPill from "../ui/StatusPill.vue";
import { tr, serverText } from "../../i18n";

const rows = ref<OAuthClientView[]>([]);
const error = ref(""), note = ref(""), loading = ref(true);
const busy = ref<string | null>(null);
const editing = ref<string | null>(null);
const form = reactive({ authorizeUrl: "", tokenUrl: "", revokeUrl: "", clientId: "", clientSecret: "", scopes: "" });
const key = (r: OAuthClientView) => `${r.kind}-${r.serverId}`;
onBeforeUnmount(() => { form.clientSecret = ""; });

async function load() {
  loading.value = true;
  const r = await listOAuthClients();
  loading.value = false;
  if (r.kind === "ok") { rows.value = r.value; error.value = ""; }
  else error.value = r.kind === "error" ? r.message : tr("Deine Sitzung ist abgelaufen.");
}
onMounted(load);

function edit(r: OAuthClientView) {
  editing.value = key(r); note.value = "";
  Object.assign(form, { authorizeUrl: r.authorizeUrl ?? "", tokenUrl: r.tokenUrl ?? "", revokeUrl: r.revokeUrl ?? "",
    clientId: r.clientId ?? "", clientSecret: "", scopes: r.scopes ?? "" });
}
async function save(r: OAuthClientView) {
  if (busy.value) return;
  busy.value = key(r); error.value = "";
  try {
    const res = await putOAuthClient(r.kind, r.serverId, { authorizeUrl: form.authorizeUrl, tokenUrl: form.tokenUrl,
      revokeUrl: form.revokeUrl || null, clientId: form.clientId, clientSecret: form.clientSecret ? form.clientSecret : null, scopes: form.scopes || null });
    if (res.kind === "ok") { editing.value = null; form.clientSecret = ""; note.value = tr("Gespeichert. Geänderte Angaben trennen alle bisherigen Verbindungen dieses Servers."); await load(); }
    else error.value = res.kind === "error" ? serverText(res.message) : tr("Deine Sitzung ist abgelaufen.");
  } finally { busy.value = null; }
}
async function remove(r: OAuthClientView) {
  if (busy.value) return;
  busy.value = key(r); error.value = "";
  try {
    const res = await deleteOAuthClient(r.kind, r.serverId);
    if (res.kind === "ok") {
      // MCP falls back to the shared admin key only with fresh approvals (the server cleared them).
      note.value = r.kind === "mcp"
        ? tr("Persönliche Anmeldung ausgeschaltet. Alle Verbindungen wurden gelöscht; die Werkzeugfreigaben dieses MCP-Servers müssen neu erteilt werden.")
        : tr("Persönliche Anmeldung ausgeschaltet. Alle Verbindungen dieses Servers wurden gelöscht.");
      await load();
    }
    else error.value = res.kind === "error" ? res.message : tr("Deine Sitzung ist abgelaufen.");
  } finally { busy.value = null; }
}
</script>

<template>
  <section class="connection-page" data-testid="admin-oauth-section">
    <header class="connection-header"><div class="connection-heading"><h2>{{ $t("Persönliche Verbindungen") }}</h2><p class="connection-description">{{ $t("Jede Person meldet sich selbst beim Werkzeugserver an (OAuth mit PKCE). Hier trägst du den OAuth-Client ein; Tokens und Konten der Personen siehst du nie.") }}</p></div></header>
    <p v-if="loading" class="connection-loading" role="status">{{ $t("Verbindungen werden geladen …") }}</p>
    <p v-if="error" class="connection-note error" role="alert">{{ error }}</p>
    <p v-if="note" class="connection-note ok" role="status">{{ note }}</p>
    <p v-if="!loading && !rows.length" class="connection-empty">{{ $t("Noch kein MCP-Server und kein OpenAPI-Server mit Zugangsart „persönlich (OAuth)“ vorhanden.") }}</p>
    <article v-for="r in rows" :key="key(r)" class="connection-record" data-testid="admin-oauth-row">
      <header class="connection-record-header"><div class="connection-heading"><h3>{{ r.serverName }}</h3><p class="connection-meta">{{ r.kind === "mcp" ? $t("MCP-Server") : $t("OpenAPI-Server") }} · {{ $t("{0} Personen verbunden", r.connectedCount) }}</p></div>
        <div class="connection-actions"><StatusPill :tone="r.configured ? 'info' : 'neutral'">{{ r.configured ? $t("Persönlich") : $t("Nicht eingerichtet") }}</StatusPill>
          <Button size="compact" :disabled="busy !== null" @click="edit(r)">{{ r.configured ? $t("Bearbeiten") : $t("Einrichten") }}</Button>
          <Button v-if="r.configured" size="compact" :disabled="busy !== null" @click="remove(r)">{{ $t("Ausschalten") }}</Button></div></header>
      <form v-if="editing === key(r)" class="connection-form" @submit.prevent="save(r)">
        <Input :id="`oauth-auth-${key(r)}`" v-model="form.authorizeUrl" :label="$t('Autorisierungs-Endpunkt')" type="url" required maxlength="500" />
        <Input :id="`oauth-token-${key(r)}`" v-model="form.tokenUrl" :label="$t('Token-Endpunkt')" type="url" required maxlength="500" />
        <Input :id="`oauth-revoke-${key(r)}`" v-model="form.revokeUrl" :label="$t('Widerrufs-Endpunkt (optional)')" type="url" maxlength="500" />
        <Input :id="`oauth-client-${key(r)}`" v-model="form.clientId" :label="$t('Client-ID')" required maxlength="300" />
        <Input :id="`oauth-secret-${key(r)}`" v-model="form.clientSecret" :label="$t('Client-Secret (optional, leer = unverändert)')" type="password" autocomplete="off" />
        <Input :id="`oauth-scopes-${key(r)}`" v-model="form.scopes" :label="$t('Scopes (optional, durch Leerzeichen getrennt)')" maxlength="1000" />
        <p class="connection-hint">{{ $t("Weiterleitungsadresse beim Anbieter: /api/v1/oauth/connections/callback auf der öffentlichen Keplura-Adresse.") }}</p>
        <div class="connection-submit"><Button :disabled="busy !== null" @click="editing = null">{{ $t("Abbrechen") }}</Button><Button variant="primary" type="submit" :disabled="busy !== null" :busy="busy === key(r)">{{ $t("Speichern") }}</Button></div>
      </form>
    </article>
  </section>
</template>
