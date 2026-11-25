<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import {
  DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuPortal, DropdownMenuRoot, DropdownMenuSeparator, DropdownMenuTrigger,
} from "reka-ui";
import { tr } from "../../i18n";
import { getChatToolSelection, setChatToolSelection, type ChatToolSelection } from "../../productivity-api";

const props = defineProps<{
  orgId: number | null;
  conversationId: number | null;
  disabled: boolean;
  ensureConversation: () => Promise<number | null>;
}>();
const emit = defineEmits<{ saving: [boolean]; saved: [number] }>();
const open = ref(false);
const selection = ref<ChatToolSelection | null>(null);
const draft = ref<string[] | null>(null);
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const MAX_SELECTED = 32;
let requestId = 0;
let creating = false;

watch([() => props.orgId, () => props.conversationId], ([org, chat], [previousOrg, previousChat]) => {
  if (creating && org === previousOrg && previousChat === null && chat !== null) return;
  requestId++;
  open.value = false;
  selection.value = null;
  draft.value = null;
  loading.value = false;
  if (saving.value) emit("saving", false);
  saving.value = false;
  error.value = "";
});
onBeforeUnmount(() => {
  requestId++;
  if (saving.value) emit("saving", false);
});

async function load() {
  const org = props.orgId;
  if (org === null || saving.value) return;
  const request = ++requestId;
  loading.value = true;
  error.value = "";
  selection.value = null;
  let cid = props.conversationId;
  if (cid === null) {
    creating = true;
    try {
      cid = await props.ensureConversation();
      await nextTick();
    } finally {
      creating = false;
    }
    if (request !== requestId) return;
    if (cid === null || props.orgId !== org || props.conversationId !== cid) {
      loading.value = false;
      if (props.orgId !== org || props.conversationId !== null) {
        open.value = false;
        return;
      }
      error.value = tr("Werkzeuge konnten nicht geladen werden.");
      return;
    }
  }
  const r = await getChatToolSelection(org, cid);
  if (request !== requestId) return;
  if (r.kind === "ok" && r.value) {
    selection.value = r.value;
    draft.value = r.value.selectedIds;
  } else {
    error.value = tr("Werkzeuge konnten nicht geladen werden.");
  }
  loading.value = false;
}

function toggleTool(id: string, checked: boolean) {
  if (saving.value || draft.value === null) return;
  if (checked && draft.value.length >= MAX_SELECTED) {
    error.value = tr("Wähle höchstens 32 Werkzeuge.");
    return;
  }
  error.value = "";
  draft.value = checked ? [...draft.value, id] : draft.value.filter((value) => value !== id);
}

function setDefault(isDefault: boolean) {
  error.value = "";
  // Leaving the default starts from what the default offers now: the model's default kinds or every approved tool.
  const start = selection.value?.modelDefaultIds ?? selection.value?.tools.map((tool) => tool.id) ?? [];
  draft.value = isDefault ? null : start.slice(0, MAX_SELECTED);
}

async function save() {
  if (props.disabled || props.orgId === null || props.conversationId === null || !selection.value || saving.value) return;
  const cid = props.conversationId;
  const request = ++requestId;
  saving.value = true;
  emit("saving", true);
  error.value = "";
  const r = await setChatToolSelection(props.orgId, cid, draft.value);
  if (request !== requestId) return;
  saving.value = false;
  emit("saving", false);
  if (r.kind === "ok" && r.value) {
    selection.value = r.value;
    draft.value = r.value.selectedIds;
    open.value = false;
    emit("saved", cid);
  } else {
    error.value = tr("Die Werkzeugauswahl konnte nicht gespeichert werden.");
  }
}
</script>

<template>
  <DropdownMenuRoot v-model:open="open" @update:open="(v) => v && load()">
    <DropdownMenuTrigger class="chat-tool-trigger" :disabled="disabled || saving" data-testid="chat-tool-selection">
      {{ $t("Werkzeuge") }}
    </DropdownMenuTrigger>
    <DropdownMenuPortal>
      <DropdownMenuContent class="ic-menu chat-tool-menu" side="top" align="start" :side-offset="8" :collision-padding="12">
        <DropdownMenuLabel class="ic-menu-label">{{ $t("Werkzeuge für diesen Chat") }}</DropdownMenuLabel>
        <p v-if="loading" class="tool-menu-note" role="status">{{ $t("Werkzeuge werden geladen …") }}</p>
        <p v-if="error" class="tool-menu-note" role="alert">{{ error }}</p>
        <DropdownMenuItem v-if="error && !selection && !loading" class="ic-menu-item" @select.prevent="load">
          {{ $t("Erneut versuchen") }}
        </DropdownMenuItem>
        <template v-if="selection">
          <DropdownMenuCheckboxItem class="ic-menu-item" :model-value="draft === null" :disabled="saving || disabled"
            @update:model-value="(v) => setDefault(v === true)" @select.prevent>
            <span class="tool-menu-check">{{ draft === null ? "✓" : "" }}</span>{{ $t("Serverstandard verwenden") }}
          </DropdownMenuCheckboxItem>
          <p v-if="selection.modelToolsOff" class="tool-menu-note" data-testid="chat-tool-model-off">{{ $t("Das aktuelle Modell nutzt keine Werkzeuge. Eine Auswahl wirkt erst mit einem Modell, das Werkzeuge erlaubt.") }}</p>
          <p v-else-if="draft === null && selection.modelDefaultIds !== null" class="tool-menu-note" data-testid="chat-tool-model-defaults">{{ $t("Das Modell bietet standardmäßig {0} von {1} Werkzeugen an.", selection.modelDefaultIds.length, selection.tools.length) }}</p>
          <DropdownMenuItem v-if="draft !== null && draft.length > 0" class="ic-menu-item" :disabled="saving || disabled"
            @select.prevent="draft = []; error = ''">{{ $t("Alle Werkzeuge abwählen") }}</DropdownMenuItem>
          <DropdownMenuGroup v-if="draft !== null" class="chat-tool-list">
            <DropdownMenuCheckboxItem v-for="tool in selection.tools" :key="tool.id" class="ic-menu-item"
              :model-value="draft.includes(tool.id)" :disabled="saving || disabled"
              @update:model-value="(v) => toggleTool(tool.id, v === true)" @select.prevent>
              <span class="tool-menu-check">{{ draft.includes(tool.id) ? "✓" : "" }}</span>
              <span class="tool-menu-name">{{ tool.name }} <small>{{ tool.source }}</small></span>
            </DropdownMenuCheckboxItem>
          </DropdownMenuGroup>
          <p v-if="selection.tools.length === 0" class="tool-menu-note">{{ $t("Für diesen Chat sind keine Werkzeuge freigegeben.") }}</p>
          <p class="tool-menu-note">{{ $t("Eine Änderung gilt ab der nächsten Frage und beendet offene Bestätigungen.") }}</p>
          <DropdownMenuSeparator />
          <DropdownMenuItem class="ic-menu-item" :disabled="saving || disabled" @select.prevent="save">
            {{ saving ? $t("Wird gespeichert …") : $t("Werkzeugauswahl speichern") }}
          </DropdownMenuItem>
        </template>
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>
</template>

<style scoped>
.chat-tool-trigger { min-height: 32px; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; color: var(--ic-text-2); font: 500 13px/1 var(--ic-font); cursor: pointer; }
.chat-tool-trigger:hover:not(:disabled), .chat-tool-trigger[data-state="open"] { background: var(--ic-hover-tint); color: var(--ic-text); }
.chat-tool-trigger:disabled { opacity: .5; cursor: default; }
.chat-tool-menu { max-width: min(320px, calc(100vw - 24px)); }
.chat-tool-list { max-height: min(220px, 35vh); overflow-y: auto; }
.tool-menu-name { overflow-wrap: anywhere; }
.tool-menu-name small { display: block; color: var(--ic-text-3); }
.tool-menu-check { flex: 0 0 14px; }
.tool-menu-note { margin: 4px 8px; color: var(--ic-text-2); font-size: 12px; }
@media(max-width:767px),(any-pointer:coarse){ .chat-tool-trigger { min-height: 44px; } }
</style>
