<script setup lang="ts">
import BackupPanel from "./admin/BackupPanel.vue";
import DirectoryPanel from "./admin/DirectoryPanel.vue";
import DataSourcesPanel from "./admin/DataSourcesPanel.vue";
import QualityPanel from "./admin/QualityPanel.vue";
import AccessPanel from "./admin/AccessPanel.vue";
import WebSearchPanel from "./admin/WebSearchPanel.vue";
import McpPanel from "./admin/McpPanel.vue";
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
type Section = "provider" | "modell" | "rag" | "status" | "backup" | "directory" | "datasources" | "quality" | "websearch" | "mcp" | "access";
const section = ref<Section>("provider");
/** 403 aus einem Bereich: die Admin-Rolle fehlt oder ist abgelaufen. */
const forbidden = ref(false);
</script>

<template>
  <PageContainer width="narrow">
    <template #header><PageHeader title="Administration" description="Modelle, Verbindungen und Betrieb deiner Keplura-Installation" /></template>
  <section class="panel" data-testid="admin-panel">

    <p v-if="forbidden" class="note bad" data-testid="admin-forbidden">
      Dieser Bereich ist nur für Administratorinnen und Administratoren zugänglich.
    </p>

    <div class="admin-mobile-nav"><label for="admin-section-select">Administrationsbereich</label>
      <select id="admin-section-select" v-model="section" class="ic-native-control" data-testid="admin-section-select">
        <optgroup label="KI und Wissen"><option value="provider">KI-Anbieter</option><option value="modell">Modelle</option><option value="rag">Suche und Wissen</option></optgroup>
        <optgroup label="Verbindungen"><option value="datasources">Datenquellen</option><option value="websearch">Websuche</option><option value="mcp">MCP-Server</option><option value="directory">Verzeichnis</option><option value="access">Zugriff prüfen</option></optgroup>
        <optgroup label="Betrieb"><option value="status">Systemstatus</option><option value="quality">Antwortqualität</option><option value="backup">Datensicherung</option></optgroup>
      </select>
    </div>

    <nav class="tabs" aria-label="Admin-Bereiche">
      <span class="admin-nav-label">KI &amp; Wissen</span>
      <button type="button" :class="{ act: section === 'provider' }" data-testid="admin-tab-providers" @click="section = 'provider'">KI-Anbieter</button>
      <button type="button" :class="{ act: section === 'modell' }" data-testid="admin-tab-models" @click="section = 'modell'">Modelle</button>
      <button type="button" :class="{ act: section === 'rag' }" data-testid="admin-tab-rag" @click="section = 'rag'">Suche &amp; Wissen</button>
      <span class="admin-nav-label">Verbindungen</span>
      <button type="button" :class="{ act: section === 'datasources' }" data-testid="admin-tab-datasources" @click="section = 'datasources'">Datenquellen</button>
      <button type="button" :class="{ act: section === 'websearch' }" data-testid="admin-tab-websearch" @click="section = 'websearch'">Websuche</button>
      <button type="button" :class="{ act: section === 'mcp' }" data-testid="admin-tab-mcp" @click="section = 'mcp'">MCP-Server</button>
      <button type="button" :class="{ act: section === 'directory' }" data-testid="admin-tab-directory" @click="section = 'directory'">Verzeichnis</button>
      <button type="button" :class="{ act: section === 'access' }" data-testid="admin-tab-access" @click="section = 'access'">Zugriff prüfen</button>
      <span class="admin-nav-label">Betrieb</span>
      <button type="button" :class="{ act: section === 'status' }" data-testid="admin-tab-status" @click="section = 'status'">Systemstatus</button>
      <button type="button" :class="{ act: section === 'quality' }" data-testid="admin-tab-quality" @click="section = 'quality'">Antwortqualität</button>
      <button type="button" :class="{ act: section === 'backup' }" data-testid="admin-tab-backup" @click="section = 'backup'">Datensicherung</button>
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
