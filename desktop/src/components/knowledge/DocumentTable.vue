<script setup lang="ts">
import Button from "../ui/Button.vue";
import Input from "../ui/Input.vue";
/** Dokumente nach Verarbeitungsstatus, mit festen Spalten und sichtbaren Aktionen. */
import { computed, ref } from "vue";
import type { KnowledgeDocument } from "../../api";
import AppIcon from "../ui/AppIcon.vue";
import StatusRing from "./StatusRing.vue";
import { fileType, formatDate, formatSize, statusHint, statusLabel } from "./status";

type Status = KnowledgeDocument["indexStatus"];

const props = defineProps<{ documents: KnowledgeDocument[]; canChange: (doc: KnowledgeDocument) => boolean }>();
const emit = defineEmits<{ download: [KnowledgeDocument]; reindex: [KnowledgeDocument]; remove: [KnowledgeDocument] }>();

const ORDER: Status[] = ["PENDING", "FAILED", "INDEXED", "NO_TEXT_LAYER", "NOT_INDEXED"];
const filter = ref("");
const collapsed = ref<Record<string, boolean>>({});

const groups = computed(() => {
  const q = filter.value.trim().toLowerCase();
  const docs = q ? props.documents.filter((d) => d.filename.toLowerCase().includes(q)) : props.documents;
  return ORDER.map((status) => ({ status, docs: docs.filter((d) => d.indexStatus === status) })).filter((g) => g.docs.length);
});
</script>

<template>
  <div class="dl">
    <div v-if="documents.length>8" class="dl-filter"><Input id="knowledge-doc-filter" v-model="filter" :label="$t('Dokumente filtern')" label-hidden icon="search" type="search" :placeholder="$t('Dokumente nach Namen filtern …')" data-testid="knowledge-doc-filter" /></div>
    <div v-if="documents.length" class="dl-list" data-testid="knowledge-doc-list">
      <section v-for="g in groups" :key="g.status" class="doc-group">
        <button type="button" class="group-heading" :aria-expanded="!collapsed[g.status]" @click="collapsed[g.status]=!collapsed[g.status]">
          <AppIcon class="group-chevron" :class="{open:!collapsed[g.status]}" name="chevron" :size="13" /><StatusRing :status="g.status" /><span>{{statusLabel(g.status)}}</span><span class="group-count ic-num">{{g.docs.length}}</span>
        </button>
        <table v-if="!collapsed[g.status]" :aria-label="statusLabel(g.status)">
          <thead><tr><th scope="col">{{ $t("Dokument") }}</th><th scope="col" class="size">{{ $t("Größe") }}</th><th scope="col" class="date">{{ $t("Hinzugefügt") }}</th><th scope="col" class="actions-heading"><span class="sr-only">{{ $t("Aktionen") }}</span></th></tr></thead>
          <tbody><tr v-for="doc in g.docs" :key="doc.id" data-testid="knowledge-doc-item">
            <td class="document-cell"><div class="document-name"><AppIcon name="doc" :size="20" /><div class="document-label"><span class="name" data-testid="knowledge-doc-name" :title="doc.filename">{{doc.filename}}</span><span class="document-meta">{{fileType(doc.filename)}}<span class="mobile-meta"> · {{formatSize(doc.sizeBytes)}} · {{formatDate(doc.createdAt)}}</span></span></div></div><span class="sr-only" data-testid="knowledge-doc-status" :data-index-status="doc.indexStatus">{{statusLabel(doc.indexStatus)}}. {{statusHint(doc.indexStatus)}}</span></td>
            <td class="size ic-num">{{formatSize(doc.sizeBytes)}}</td><td class="date ic-num">{{formatDate(doc.createdAt)}}</td>
            <td class="document-actions"><div class="actions">
              <Button variant="icon" size="compact" :title="$t('Herunterladen')" :aria-label="$t('„{0}“ herunterladen', doc.filename)" data-testid="knowledge-doc-download" @click="emit('download',doc)"><AppIcon name="download" :size="16" /></Button>
              <template v-if="canChange(doc)"><Button variant="icon" size="compact" :title="$t('Erneut verarbeiten')" :aria-label="$t('„{0}“ erneut verarbeiten', doc.filename)" data-testid="knowledge-doc-reindex" @click="emit('reindex',doc)"><AppIcon name="refresh" :size="16" /></Button><Button variant="icon" size="compact" :title="$t('Löschen')" :aria-label="$t('„{0}“ löschen', doc.filename)" data-testid="knowledge-doc-delete" @click="emit('remove',doc)"><AppIcon name="trash" :size="16" /></Button></template>
            </div></td>
          </tr></tbody>
        </table>
      </section>
      <p v-if="!groups.length" class="none">{{ $t("Keine Dokumente passen zum Filter.") }}</p>
    </div>
    <slot v-else name="empty" />
  </div>
</template>
<style scoped>
.dl-filter { margin-bottom:20px; }.dl-filter :deep(.ic-field) { margin:0; }.doc-group + .doc-group { margin-top:24px; }.group-heading { display:flex; align-items:center; gap:8px; width:100%; min-height:40px; padding:8px 0; border:0; background:transparent; color:var(--ic-text-2); font:500 13px/20px var(--ic-font); text-align:left; cursor:pointer; }.group-heading:hover { color:var(--ic-text); }.group-chevron.open { transform:rotate(90deg); }.group-count { margin-left:4px; padding:0 6px; border-radius:4px; background:var(--ic-surface-subtle); color:var(--ic-text-2); font-size:12px; }
table { width:100%; table-layout:fixed; border-collapse:collapse; }th { height:36px; padding:8px 12px; border-bottom:1px solid var(--ic-separator); color:var(--ic-text-2); font:500 12px/18px var(--ic-font); text-align:left; }th:first-child { padding-left:0; }td { padding:12px; border-bottom:1px solid var(--ic-separator-faint); color:var(--ic-text-2); font:400 13px/20px var(--ic-font); }td:first-child { padding-left:0; }tr:hover td { background:var(--ic-hover-tint); }.size { width:82px; }.date { width:106px; }.actions-heading,.document-actions { width:128px; padding-right:0; }.document-cell { position:relative; }.document-name { display:flex; align-items:center; gap:12px; min-width:0; }.document-name>svg { flex:none; color:var(--ic-text-3); }.document-label { min-width:0; display:grid; gap:2px; }.name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font:500 14px/20px var(--ic-font); color:var(--ic-text); }.document-meta { font:400 12px/18px var(--ic-font); color:var(--ic-text-3); }.mobile-meta { display:none; }.actions { display:flex; justify-content:flex-end; gap:2px; }.none { margin:0; padding:32px 16px; color:var(--ic-text-2); font-size:14px; text-align:center; }.sr-only { position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0); }
@media(max-width:1100px){.date,.size{display:none}.mobile-meta{display:inline}.mobile-meta{font-size:12px}}
@media(max-width:767px),(any-pointer:coarse){.group-heading{min-height:44px}.actions-heading,.document-actions{width:136px}}
@media(max-width:600px){.size{display:none}td{padding:12px 4px}.document-name{gap:8px}.document-name>svg{display:none}.document-meta{line-height:18px}.name{white-space:normal;overflow-wrap:anywhere}.actions{gap:0}}
</style>
