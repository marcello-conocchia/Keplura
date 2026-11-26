<script setup lang="ts">
/**
 * Persönliche Verbindungen (TU-06b): jede Person verbindet sich selbst mit Werkzeugservern, die der Admin für
 * „persönlich (OAuth)“ eingerichtet hat. Die Anmeldung läuft im Browser; die App fragt danach den Status ab.
 * Tokens erreichen die App nie — sie sieht nur „verbunden“ oder „nicht verbunden“.
 */
import { onBeforeUnmount, ref, watch } from "vue";
import { useAuthStore } from "../auth-store";
import {
  connectionStatus, disconnectConnection, listConnections, startConnection, type PersonalConnection,
} from "../productivity-api";
import { inTauri } from "../transport";
import { copyText } from "./ui/clipboard";
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
/** The open sign-in: link shown as fallback when no browser window could be opened. */
const pending = ref<{ key: string; url: string; flowId: string } | null>(null);
let timer: ReturnType<typeof setTimeout> | null = null;
let disposed = false;
const key = (c: PersonalConnection) => `${c.kind}-${c.serverId}`;

function stopPolling() { if (timer) clearTimeout(timer); timer = null; }
onBeforeUnmount(() => { disposed = true; stopPolling(); });

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
watch(() => auth.selectedOrgId, () => { stopPolling(); pending.value = null; void load(); }, { immediate: true });

async function connect(c: PersonalConnection) {
  const org = auth.selectedOrgId;
  if (org === null || busy.value) return;
  busy.value = key(c); note.value = ""; error.value = "";
  try {
    const r = await startConnection(org, c.kind, c.serverId);
    if (r.kind !== "ok") { error.value = r.kind === "error" ? r.message : tr("Deine Sitzung ist abgelaufen."); return; }
    pending.value = { key: key(c), url: r.value.authorizationUrl, flowId: r.value.flowId };
    // Web: a new tab. Desktop: the system browser is not reachable without an extra plugin, so the link is shown to copy.
    if (!inTauri()) window.open(r.value.authorizationUrl, "_blank", "noopener,noreferrer");
    poll(org, r.value.flowId, Date.now());
  } finally { busy.value = null; }
}

function poll(org: number, flowId: string, started: number) {
  stopPolling();
  timer = setTimeout(async () => {
    if (disposed || org !== auth.selectedOrgId || pending.value?.flowId !== flowId) return;
    const r = await connectionStatus(org, flowId);
    if (disposed || pending.value?.flowId !== flowId) return;
    const status = r.kind === "ok" ? r.value.status : "FAILED";
    if (status === "PENDING" && Date.now() - started < 10 * 60 * 1000) { poll(org, flowId, started); return; }
    pending.value = null;
    if (status === "CONNECTED") note.value = tr("Verbunden. Die Werkzeuge stehen ab der nächsten Frage zur Verfügung.");
    else error.value = status === "EXPIRED" || status === "PENDING" ? tr("Die Anmeldung ist abgelaufen. Bitte erneut verbinden.")
      : tr("Die Verbindung konnte nicht hergestellt werden. Bitte erneut versuchen.");
    await load();
  }, 2000);
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
async function copyLink() {
  if (pending.value) note.value = (await copyText(pending.value.url)) ? tr("Link kopiert. Öffne ihn in deinem Browser.") : tr("Kopieren nicht möglich.");
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
            <Button v-else variant="primary" size="compact" :disabled="busy !== null || pending !== null" :busy="busy === key(c)" @click="connect(c)">{{ $t("Verbinden") }}</Button>
          </div>
        </header>
        <div v-if="pending?.key === key(c)" class="connection-note" role="status">
          <p>{{ $t("Melde dich im Browser an. Keplura prüft danach automatisch, ob die Verbindung steht.") }}</p>
          <p><a :href="pending.url" target="_blank" rel="noopener noreferrer">{{ $t("Anmeldung im Browser öffnen") }}</a></p>
          <Button size="compact" @click="copyLink">{{ $t("Link kopieren") }}</Button>
        </div>
      </article>
    </section>
  </PageContainer>
</template>
