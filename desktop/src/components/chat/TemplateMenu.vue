<script setup lang="ts">
/**
 * Vorlagen in der Chat-Eingabe: einfügen, die aktuelle Frage als Vorlage
 * speichern (Arbeitsbereich oder ganze Organisation), eigene Vorlagen löschen.
 */
import { computed, ref, watch } from "vue";
import {
  DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuPortal, DropdownMenuRoot,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from "reka-ui";
import { useAuthStore } from "../../auth-store";
import { createTemplate, deleteTemplate, listTemplates, type PromptTemplate } from "../../productivity-api";
import AppDialog from "../ui/AppDialog.vue";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";

const props = defineProps<{ currentPrompt: string; disabled?: boolean; compact?: boolean }>();
const emit = defineEmits<{ insert: [string] }>();
const auth = useAuthStore();

const templates = ref<PromptTemplate[]>([]);
async function load() {
  if (auth.selectedOrgId === null) {
    templates.value = [];
    return;
  }
  const r = await listTemplates(auth.selectedOrgId, auth.selectedSpaceId);
  templates.value = r.kind === "ok" ? r.value : [];
}
watch(() => [auth.selectedOrgId, auth.selectedSpaceId], load, { immediate: true });

const spaceTemplates = computed(() => templates.value.filter((t) => t.spaceId !== null));
const orgTemplates = computed(() => templates.value.filter((t) => t.spaceId === null));

/* Speichern */
const saveOpen = ref(false);
const title = ref("");
const body = ref("");
const scope = ref<"space" | "org">("space");
const saveError = ref("");
function openSave() {
  title.value = "";
  body.value = props.currentPrompt;
  scope.value = auth.selectedSpaceId !== null ? "space" : "org";
  saveError.value = "";
  saveOpen.value = true;
}
async function save() {
  if (auth.selectedOrgId === null) return;
  const r = await createTemplate(auth.selectedOrgId, scope.value === "space" ? auth.selectedSpaceId : null,
    title.value, body.value);
  if (r.kind !== "ok") {
    saveError.value = r.kind === "error" ? r.message : "Deine Sitzung ist abgelaufen.";
    return;
  }
  saveOpen.value = false;
  await load();
}

/* Verwalten */
const manageOpen = ref(false);
async function remove(t: PromptTemplate) {
  if (auth.selectedOrgId === null) return;
  await deleteTemplate(auth.selectedOrgId, t.id);
  await load();
}
</script>

<template>
  <DropdownMenuRoot>
    <DropdownMenuTrigger :class="['tpl-trigger', { compact }]" title="Vorlagen" :disabled="disabled" data-testid="chat-templates" aria-label="Vorlagen">
      <AppIcon name="edit" :size="16" /><span v-if="!compact">Vorlagen</span>
    </DropdownMenuTrigger>
    <DropdownMenuPortal>
      <DropdownMenuContent class="ic-menu tpl-menu" :side-offset="6" align="start" side="top">
        <template v-if="spaceTemplates.length">
          <DropdownMenuLabel class="ic-menu-label">{{ auth.selectedSpace?.name ?? "Arbeitsbereich" }}</DropdownMenuLabel>
          <DropdownMenuItem v-for="t in spaceTemplates" :key="t.id" class="ic-menu-item" data-testid="chat-template-item" @select="emit('insert', t.body)">
            <span class="ic-menu-text">{{ t.title }}</span>
          </DropdownMenuItem>
        </template>
        <template v-if="orgTemplates.length">
          <DropdownMenuLabel class="ic-menu-label">Ganze Organisation</DropdownMenuLabel>
          <DropdownMenuItem v-for="t in orgTemplates" :key="t.id" class="ic-menu-item" data-testid="chat-template-item" @select="emit('insert', t.body)">
            <span class="ic-menu-text">{{ t.title }}</span>
          </DropdownMenuItem>
        </template>
        <DropdownMenuLabel v-if="!templates.length" class="ic-menu-label">Noch keine Vorlagen</DropdownMenuLabel>
        <DropdownMenuSeparator class="ic-menu-sep" />
        <DropdownMenuItem class="ic-menu-item" :disabled="!currentPrompt.trim()" data-testid="chat-template-save" @select="openSave">
          <AppIcon name="plus" :size="13" /><span class="ic-menu-text">Aktuelle Frage als Vorlage speichern</span>
        </DropdownMenuItem>
        <DropdownMenuItem v-if="templates.some((t) => t.canEdit)" class="ic-menu-item" @select="manageOpen = true">
          <AppIcon name="edit" :size="13" /><span class="ic-menu-text">Vorlagen verwalten</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>

  <AppDialog :open="saveOpen" title="Als Vorlage speichern" :error="saveError" @close="saveOpen = false">
    <form class="tpl-form" @submit.prevent="save">
      <label>Name der Vorlage <input class="ic-native-control" v-model="title" required maxlength="120" placeholder="z. B. Angebot prüfen" data-testid="chat-template-title" /></label>
      <label>Text <textarea class="ic-native-control" v-model="body" required maxlength="4000" rows="5"></textarea></label>
      <fieldset class="tpl-scope">
        <legend>Für wen?</legend>
        <label v-if="auth.selectedSpaceId !== null"><input v-model="scope" type="radio" value="space" /> Arbeitsbereich „{{ auth.selectedSpace?.name }}“</label>
        <label><input v-model="scope" type="radio" value="org" /> Ganze Organisation</label>
      </fieldset>
      <div class="tpl-actions"><Button variant="primary" type="submit" data-testid="chat-template-submit">Speichern</Button></div>
    </form>
  </AppDialog>

  <AppDialog :open="manageOpen" title="Vorlagen verwalten" @close="manageOpen = false">
    <ul class="tpl-list">
      <li v-for="t in templates.filter((x) => x.canEdit)" :key="t.id">
        <span><strong>{{ t.title }}</strong><br /><small>{{ t.spaceId === null ? "Ganze Organisation" : "Arbeitsbereich" }}</small></span>
        <Button variant="icon" class="p-[6px]" :aria-label="`Vorlage ${t.title} löschen`" title="Löschen" @click="remove(t)"><AppIcon name="trash" :size="13" /></Button>
      </li>
    </ul>
  </AppDialog>
</template>

<style scoped>
.tpl-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ic-text-2);
  font: 500 13px/1 var(--ic-font);
  cursor: pointer;
}
@media (max-width: 767px), (any-pointer: coarse) { .tpl-trigger { height: auto; min-height: var(--ic-control-touch); } }
.tpl-trigger.compact { justify-content:center; min-width:32px; padding:6px; }
@media(max-width:767px),(any-pointer:coarse){.tpl-trigger.compact{min-width:44px}}
.tpl-trigger:hover:not(:disabled),
.tpl-trigger[data-state="open"] {
  background: var(--ic-hover-tint);
  color: var(--ic-text);
}
.tpl-trigger:disabled {
  opacity: .5;
  cursor: default;
}
.tpl-form {
  display: grid;
  gap: 12px;
}
.tpl-form label {
  display: grid;
  gap: 4px;
  font-size: 13px;
  font-weight: 500;
}
.tpl-scope {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  border: 0;
  font-size: 13px;
}
.tpl-scope label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 400;
}
.tpl-actions {
  display: flex;
  justify-content: flex-end;
}
.tpl-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.tpl-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px solid var(--ic-separator-faint);
}
.tpl-list small {
  color: var(--ic-text-3);
}
</style>

<style>
.tpl-menu {
  max-height: 360px;
  overflow-y: auto;
}
</style>
