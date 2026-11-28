<script setup lang="ts">
import BackupPanel from "./admin/BackupPanel.vue";
import DirectoryPanel from "./admin/DirectoryPanel.vue";
import DataSourcesPanel from "./admin/DataSourcesPanel.vue";
import QualityPanel from "./admin/QualityPanel.vue";
import AccessPanel from "./admin/AccessPanel.vue";
import WebSearchPanel from "./admin/WebSearchPanel.vue";
import McpPanel from "./admin/McpPanel.vue";
import OpenApiPanel from "./admin/OpenApiPanel.vue";
import OAuthClientsPanel from "./admin/OAuthClientsPanel.vue";
import ProvidersPanel from "./admin/ProvidersPanel.vue";
import ModelsPanel from "./admin/ModelsPanel.vue";
import SearchSettingsPanel from "./admin/SearchSettingsPanel.vue";
import SystemStatusPanel from "./admin/SystemStatusPanel.vue";
import { ref } from "vue";
import PageContainer from "./layout/PageContainer.vue";
import PageHeader from "./layout/PageHeader.vue";

/**
 * Administration: reiner Rahmen mit Bereichsnavigation. Jeder Bereich ist eine
 * eigene Ansicht im Katalogstil (UI-10/UI-11) und lädt seine Daten selbst.
 * Alle Autorisierung liegt beim Server (Session + CSRF + Rolle, docs/adr/0008 §6);
 * meldet ein Bereich 403, zeigt der Rahmen einmal den ehrlichen Hinweis.
 */
type Section = "provider" | "modell" | "rag" | "status" | "backup" | "directory" | "datasources" | "quality" | "websearch" | "mcp" | "openapi" | "oauth" | "access";
const section = ref<Section>("provider");
/** 403 aus einem Bereich: die Admin-Rolle fehlt oder ist abgelaufen. */
const forbidden = ref(false);
</script>

<template>
  <PageContainer width="narrow">
    <template #header><PageHeader :title="$t('Administration')" :description="$t('Modelle, Verbindungen und Betrieb deiner Keplura-Installation')" /></template>
  <section class="panel" data-testid="admin-panel">

    <p v-if="forbidden" class="note bad" data-testid="admin-forbidden">
      {{ $t("Dieser Bereich ist nur für Administratorinnen und Administratoren zugänglich.") }}
    </p>

    <div class="admin-mobile-nav"><label for="admin-section-select">{{ $t("Administrationsbereich") }}</label>
      <select id="admin-section-select" v-model="section" class="ic-native-control" data-testid="admin-section-select">
        <optgroup :label="$t('KI und Wissen')"><option value="provider">{{ $t("KI-Anbieter") }}</option><option value="modell">{{ $t("Modelle") }}</option><option value="rag">{{ $t("Suche und Wissen") }}</option></optgroup>
        <optgroup :label="$t('Verbindungen')"><option value="datasources">{{ $t("Datenquellen") }}</option><option value="websearch">{{ $t("Websuche") }}</option><option value="mcp">{{ $t("MCP-Server") }}</option><option value="openapi">{{ $t("OpenAPI-Server") }}</option><option value="oauth">{{ $t("Persönliche Verbindungen") }}</option><option value="directory">{{ $t("Verzeichnis") }}</option><option value="access">{{ $t("Zugriff prüfen") }}</option></optgroup>
        <optgroup :label="$t('Betrieb')"><option value="status">{{ $t("Systemstatus") }}</option><option value="quality">{{ $t("Antwortqualität") }}</option><option value="backup">{{ $t("Datensicherung") }}</option></optgroup>
      </select>
    </div>

    <nav class="tabs" :aria-label="$t('Admin-Bereiche')">
      <span class="admin-nav-label">{{ $t("KI & Wissen") }}</span>
      <button type="button" :class="{ act: section === 'provider' }" data-testid="admin-tab-providers" @click="section = 'provider'">{{ $t("KI-Anbieter") }}</button>
      <button type="button" :class="{ act: section === 'modell' }" data-testid="admin-tab-models" @click="section = 'modell'">{{ $t("Modelle") }}</button>
      <button type="button" :class="{ act: section === 'rag' }" data-testid="admin-tab-rag" @click="section = 'rag'">{{ $t("Suche & Wissen") }}</button>
      <span class="admin-nav-label">{{ $t("Verbindungen") }}</span>
      <button type="button" :class="{ act: section === 'datasources' }" data-testid="admin-tab-datasources" @click="section = 'datasources'">{{ $t("Datenquellen") }}</button>
      <button type="button" :class="{ act: section === 'websearch' }" data-testid="admin-tab-websearch" @click="section = 'websearch'">{{ $t("Websuche") }}</button>
      <button type="button" :class="{ act: section === 'mcp' }" data-testid="admin-tab-mcp" @click="section = 'mcp'">{{ $t("MCP-Server") }}</button>
      <button type="button" :class="{ act: section === 'openapi' }" data-testid="admin-tab-openapi" @click="section = 'openapi'">{{ $t("OpenAPI-Server") }}</button>
      <button type="button" :class="{ act: section === 'oauth' }" data-testid="admin-tab-oauth" @click="section = 'oauth'">{{ $t("Persönliche Verbindungen") }}</button>
      <button type="button" :class="{ act: section === 'directory' }" data-testid="admin-tab-directory" @click="section = 'directory'">{{ $t("Verzeichnis") }}</button>
      <button type="button" :class="{ act: section === 'access' }" data-testid="admin-tab-access" @click="section = 'access'">{{ $t("Zugriff prüfen") }}</button>
      <span class="admin-nav-label">{{ $t("Betrieb") }}</span>
      <button type="button" :class="{ act: section === 'status' }" data-testid="admin-tab-status" @click="section = 'status'">{{ $t("Systemstatus") }}</button>
      <button type="button" :class="{ act: section === 'quality' }" data-testid="admin-tab-quality" @click="section = 'quality'">{{ $t("Antwortqualität") }}</button>
      <button type="button" :class="{ act: section === 'backup' }" data-testid="admin-tab-backup" @click="section = 'backup'">{{ $t("Datensicherung") }}</button>
    </nav>
    <div class="admin-content">
    <ProvidersPanel v-if="section === 'provider'" @forbidden="forbidden = true" />
    <ModelsPanel v-else-if="section === 'modell'" @forbidden="forbidden = true" />
    <SearchSettingsPanel v-else-if="section === 'rag'" @forbidden="forbidden = true" />
    <SystemStatusPanel v-else-if="section === 'status'" @forbidden="forbidden = true" />
    <BackupPanel v-else-if="section === 'backup'" />
    <DirectoryPanel v-else-if="section === 'directory'" />
    <DataSourcesPanel v-else-if="section === 'datasources'" />
    <AccessPanel v-else-if="section === 'access'" />
    <QualityPanel v-else-if="section === 'quality'" />
    <WebSearchPanel v-else-if="section === 'websearch'" />
    <McpPanel v-else-if="section === 'mcp'" />
    <OpenApiPanel v-else-if="section === 'openapi'" />
    <OAuthClientsPanel v-else-if="section === 'oauth'" />
    </div>
  </section>
  </PageContainer>
</template>

<style scoped>
.admin-mobile-nav { display:none; }
@media(max-width:760px) { .admin-mobile-nav { display:grid; gap:8px; min-width:0; }.admin-mobile-nav label { color:var(--ic-text-2); font:500 13px/20px var(--ic-font); } }

.panel {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 0 0 24px;
  background: var(--ic-content);
}

.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--ic-separator);
  overflow-x: auto;
}
.tabs button {
  border: none;
  background: none;
  font: inherit;
  font-size: 13px;
  color: var(--ic-text-2);
  padding: 6px 12px;
  border-radius: 0;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--ic-transition);
}
.tabs button:hover {
  background: var(--ic-hover-tint);
}
.tabs button.act {
  background: transparent;
  box-shadow: inset 0 -2px var(--ic-accent);
  color: var(--ic-text);
  font-weight: 500;
}

.note {
  font-size: 13px;
  border-radius: 8px;
  padding: 12px 16px;
  margin: 0 0 16px;
}
.note.bad {
  color: var(--ic-status-error);
  background: var(--ic-status-error-bg);
}
</style>
