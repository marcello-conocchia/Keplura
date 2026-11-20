<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { DropdownMenuRoot, DropdownMenuTrigger, DropdownMenuPortal, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from "reka-ui";
import type { KnowledgeBase } from "../../api";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";
const props = defineProps<{ kbs: KnowledgeBase[]; selectedId: number | null; canManage: boolean; docCount: number }>();
const emit = defineEmits<{ select: [number]; create: []; rename: [KnowledgeBase]; remove: [KnowledgeBase] }>();
const selected = computed(() => props.kbs.find(k => k.id === props.selectedId));
const wide = ref(false);
let media: MediaQueryList | undefined;
const update = () => { wide.value = media?.matches ?? false; };
onMounted(() => { media = matchMedia("(min-width: 1280px)"); update(); media.addEventListener("change", update); });
onUnmounted(() => media?.removeEventListener("change", update));
</script>
<template>
  <aside class="kbl" aria-label="Wissensbasen" :class="{ compact: !wide }">
    <template v-if="wide">
      <div class="kbl-head"><h2>Wissensbasen</h2><Button variant="icon" size="compact" data-testid="knowledge-kb-new" title="Neue Wissensbasis" aria-label="Neue Wissensbasis" @click="emit('create')"><AppIcon name="plus" :size="16" /></Button></div>
      <ul v-if="kbs.length" class="kbl-list" data-testid="knowledge-kb-list">
        <li v-for="kb in kbs" :key="kb.id" class="kbl-item" :class="{ active: kb.id === selectedId }" data-testid="knowledge-kb-item">
          <button type="button" class="kbl-btn" :data-testid="'knowledge-kb-select-' + kb.id" :aria-current="kb.id === selectedId ? 'true' : undefined" @click="emit('select',kb.id)">
            <AppIcon name="folder" :size="16" /><span class="kbl-name" :title="kb.name">{{kb.name}}</span><span v-if="kb.id===selectedId" class="kbl-count ic-num" data-testid="knowledge-kb-active">{{docCount}}</span>
          </button>
        </li>
      </ul>
      <div v-if="canManage && selected" class="kbl-manage">
        <Button variant="ghost" size="compact" :data-testid="'knowledge-kb-rename-' + selected.id" @click="emit('rename',selected)"><AppIcon name="edit" :size="13" />Umbenennen</Button>
        <Button variant="icon" size="compact" :data-testid="'knowledge-kb-delete-' + selected.id" :aria-label="`„${selected.name}“ löschen`" title="Wissensbasis löschen" @click="emit('remove',selected)"><AppIcon name="trash" :size="16" /></Button>
      </div>
    </template>
    <template v-else>
      <span class="kbl-caption">Wissensbasis</span>
      <DropdownMenuRoot>
        <DropdownMenuTrigger class="kbl-switch" data-testid="knowledge-kb-switch" aria-label="Wissensbasis wechseln"><AppIcon name="folder" :size="16" /><span class="kbl-name">{{ selected?.name || 'Bitte auswählen' }}</span><AppIcon class="kbl-chevron" name="chevron" :size="13" /></DropdownMenuTrigger>
        <DropdownMenuPortal><DropdownMenuContent class="ic-menu" :side-offset="8" align="start">
          <DropdownMenuItem v-for="kb in kbs" :key="kb.id" class="ic-menu-item" :data-testid="'knowledge-kb-select-'+kb.id" @select="emit('select',kb.id)"><AppIcon name="folder" :size="16" /><span class="ic-menu-text">{{kb.name}}</span><span v-if="kb.id===selectedId" aria-label="Ausgewählt">✓</span></DropdownMenuItem>
          <DropdownMenuSeparator v-if="kbs.length" class="ic-menu-sep" />
          <DropdownMenuItem class="ic-menu-item" data-testid="knowledge-kb-new-menu" @select="emit('create')"><AppIcon name="plus" :size="16" />Neue Wissensbasis</DropdownMenuItem>
          <template v-if="canManage && selected">
            <DropdownMenuItem class="ic-menu-item" :data-testid="'knowledge-kb-rename-'+selected.id" @select="emit('rename',selected)"><AppIcon name="edit" :size="16" />Umbenennen</DropdownMenuItem>
            <DropdownMenuItem class="ic-menu-item" :data-testid="'knowledge-kb-delete-'+selected.id" @select="emit('remove',selected)"><AppIcon name="trash" :size="16" />Wissensbasis löschen</DropdownMenuItem>
          </template>
        </DropdownMenuContent></DropdownMenuPortal>
      </DropdownMenuRoot>
      <Button variant="icon" size="compact" data-testid="knowledge-kb-new" aria-label="Neue Wissensbasis" title="Neue Wissensbasis" @click="emit('create')"><AppIcon name="plus" :size="16" /></Button>
    </template>
    <p v-if="!kbs.length" class="kbl-empty" data-testid="knowledge-kb-empty">Noch keine Wissensbasis. Lege eine an, zum Beispiel „Handbücher“.</p>
  </aside>
</template>
<style scoped>
.kbl { min-width:0; }.kbl-head { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:12px; }.kbl-head h2 { margin:0; font:500 13px/20px var(--ic-font); color:var(--ic-text-2); }
.kbl-list { list-style:none; padding:0; margin:0; display:grid; gap:4px; }.kbl-item { min-width:0; border-radius:6px; }.kbl-item.active { background:var(--ic-selection); }.kbl-item.active .kbl-btn { color:var(--ic-accent); }
.kbl-btn { display:flex; align-items:center; gap:8px; width:100%; min-height:40px; padding:10px; border:0; border-radius:6px; background:transparent; color:var(--ic-text-2); text-align:left; font:500 13px/20px var(--ic-font); cursor:pointer; }.kbl-btn:hover { background:var(--ic-hover-tint); }.kbl-btn svg { flex:none; }.kbl-name { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.kbl-count { font-size:12px; }.kbl-manage { display:flex; justify-content:space-between; gap:8px; margin-top:20px; padding-top:12px; border-top:1px solid var(--ic-separator); }
.kbl.compact { display:flex; align-items:center; gap:12px; padding-bottom:20px; margin-bottom:24px; border-bottom:1px solid var(--ic-separator); }.kbl-caption { flex:none; font:500 13px/20px var(--ic-font); color:var(--ic-text-2); }.kbl-switch { display:inline-flex; align-items:center; gap:8px; min-width:0; max-width:360px; min-height:40px; padding:8px 12px; border:1px solid var(--ic-input-edge); border-radius:6px; background:var(--ic-surface); color:var(--ic-text); font:500 14px/22px var(--ic-font); cursor:pointer; }.kbl-switch svg { flex:none; }.kbl-chevron { transform:rotate(90deg); }.kbl-empty { color:var(--ic-text-2); font-size:13px; line-height:20px; }
@media(max-width:767px),(any-pointer:coarse){.kbl-switch,.kbl-btn{min-height:44px}}
@media(max-width:500px){.kbl-caption{display:none}.kbl-switch{flex:1;max-width:none}.kbl.compact{gap:8px}}
</style>
