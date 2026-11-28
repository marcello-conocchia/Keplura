<script setup lang="ts">
/**
 * Persönliche Verbindungen (TU-06b): jede Person verbindet sich selbst mit Werkzeugservern, die der Admin für
 * „persönlich (OAuth)“ eingerichtet hat. Die Anmeldung läuft wie der OIDC-Login im App-Fenster selbst; zurück in
 * der App löst sie den Einmal-Code ein (siehe connection-return.ts und App.vue).
 * Tokens erreichen die App nie — sie sieht nur „verbunden“ oder „nicht verbunden“.
 */
import { onBeforeUnmount, ref, watch } from "vue";
import { useAuthStore } from "../auth-store";
import { disconnectConnection, listConnections, startConnection, type PersonalConnection } from "../productivity-api";
import { connectionNotice, rememberConnectionOrg } from "../connection-return";
import PageContainer from "./layout/PageContainer.vue";
import PageHeader from "./layout/PageHeader.vue";
import Button from "./ui/Button.vue";
import StatusPill from "./ui/StatusPill.vue";
import { tr, dateLocale } from "../i18n";

const auth = useAuthStore();
const items = ref<PersonalConnection[]>([]);
const loading = ref(false);
const error = ref("");
const note = ref("");
const busy = ref<string | null>(null);
let disposed = false;
const key = (c: PersonalConnection) => `${c.kind}-${c.serverId}`;
onBeforeUnmount(() => { disposed = true; });

// Rückkehr aus der Anmeldung: Ergebnis einmal anzeigen.
if (connectionNotice.value) {
  if (connectionNotice.value.ok) note.value = tr("Verbunden. Die Werkzeuge stehen ab der nächsten Frage zur Verfügung.");
  else error.value = tr("Die Verbindung konnte nicht hergestellt werden. Bitte erneut versuchen.");
  connectionNotice.value = null;
}

async function load() {
  const org = auth.selectedOrgId;
  items.value = []; error.value = "";
  if (org === null) return;
  loading.value = true;
  const r = await listConnections(org);
  if (disposed || org !== auth.selectedOrgId) return;
  loading.value = false;
  if (r.kind === "ok") items.value = r.value;
  else error.value = tr("Verbindungen konnten nicht geladen werden.");
}
watch(() => auth.selectedOrgId, () => { void load(); }, { immediate: true });

async function connect(c: PersonalConnection) {
  const org = auth.selectedOrgId;
  if (org === null || busy.value) return;
  busy.value = key(c); note.value = ""; error.value = "";
  try {
    const r = await startConnection(org, c.kind, c.serverId);
    if (r.kind !== "ok") { error.value = r.kind === "error" ? r.message : tr("Deine Sitzung ist abgelaufen."); return; }
    // Same window, top-level navigation (like the OIDC login): the server sends us back with a one-time code.
    rememberConnectionOrg(org);
    window.location.assign(r.value.authorizationUrl);
  } finally { busy.value = null; }
}

async function disconnect(c: PersonalConnection) {
  const org = auth.selectedOrgId;
  if (org === null || busy.value) return;
  busy.value = key(c); note.value = ""; error.value = "";
  try {
    const r = await disconnectConnection(org, c.kind, c.serverId);
    if (r.kind === "ok") { note.value = tr("Getrennt. Deine Zugangsdaten für {0} wurden gelöscht.", c.name); await load(); }
    else error.value = r.kind === "error" ? r.message : tr("Deine Sitzung ist abgelaufen.");
  } finally { busy.value = null; }
}
</script>

<template>
  <PageContainer width="narrow">
    <template #header><PageHeader :title="$t('Persönliche Verbindungen')" :description="$t('Verbinde dich selbst mit Werkzeugen, die eine eigene Anmeldung brauchen. Deine Zugangsdaten nutzt nur Keplura für deine eigenen Aufrufe.')" /></template>
    <section class="connection-page" data-testid="connections-view">
      <p v-if="loading" class="connection-loading" role="status">{{ $t("Verbindungen werden geladen …") }}</p>
      <p v-if="error" class="connection-note error" role="alert">{{ error }}</p>
      <p v-if="note" class="connection-note ok" role="status">{{ note }}</p>
      <p v-if="!loading && !items.length && !error" class="connection-empty">{{ $t("In dieser Organisation gibt es keine Werkzeuge mit persönlicher Anmeldung.") }}</p>
      <article v-for="c in items" :key="key(c)" class="connection-record" data-testid="connection-row">
        <header class="connection-record-header">
          <div class="connection-heading"><h3>{{ c.name }}</h3>
            <p class="connection-meta">{{ c.kind === "mcp" ? $t("MCP-Server") : $t("OpenAPI-Server") }}<span v-if="c.connectedSince"> · {{ $t("verbunden seit {0}", new Date(c.connectedSince).toLocaleString($dateLocale())) }}</span></p></div>
          <div class="connection-actions">
            <StatusPill :tone="c.connected ? 'ok' : 'neutral'">{{ c.connected ? $t("Verbunden") : $t("Nicht verbunden") }}</StatusPill>
            <Button v-if="c.connected" size="compact" :disabled="busy !== null" @click="disconnect(c)">{{ $t("Trennen") }}</Button>
            <Button v-else variant="primary" size="compact" :disabled="busy !== null" :busy="busy === key(c)" @click="connect(c)">{{ $t("Verbinden") }}</Button>
          </div>
        </header>
      </article>
    </section>
  </PageContainer>
</template>
