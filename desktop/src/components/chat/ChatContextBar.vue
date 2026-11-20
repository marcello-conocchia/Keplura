<script setup lang="ts">
/**
 * Zusatzkontext in der Chat-Eingabe: weitere Wissensbasen („Handbücher + Richtlinien“)
 * und angehängte Dateien („Fasse diese Datei zusammen“). Legt bei Bedarf zuerst eine
 * Unterhaltung an (ensureConversation).
 */
import { computed, ref, watch } from "vue";
import {
  DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuLabel, DropdownMenuPortal, DropdownMenuRoot,
  DropdownMenuTrigger, DropdownMenuItem, DropdownMenuSub, DropdownMenuSubTrigger, DropdownMenuSubContent,
} from "reka-ui";
import { useAuthStore } from "../../auth-store";
import {
  deleteChatAttachment, getChatContext, setChatKnowledgeBases, uploadChatAttachment, type ChatAttachment,
} from "../../productivity-api";
import AppIcon from "../ui/AppIcon.vue";

const props = defineProps<{
  conversationId: number | null;
  primaryKbId: number | null;
  disabled?: boolean;
  ensureConversation: () => Promise<number | null>;
}>();
const emit = defineEmits<{ error: [string] }>();
const auth = useAuthStore();

const extraKbIds = ref<number[]>([]);
const attachments = ref<ChatAttachment[]>([]);
const uploading = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);

async function load() {
  extraKbIds.value = [];
  attachments.value = [];
  if (props.conversationId === null || auth.selectedOrgId === null) return;
  const r = await getChatContext(auth.selectedOrgId, props.conversationId);
  if (r.kind === "ok") {
    extraKbIds.value = r.value.extraKnowledgeBaseIds;
    attachments.value = r.value.attachments;
  }
}
watch(() => props.conversationId, load, { immediate: true });

const otherKbs = computed(() => auth.kbs.filter((k) => k.id !== props.primaryKbId));
const extraKbs = computed(() => auth.kbs.filter((k) => extraKbIds.value.includes(k.id)));

const MESSAGES: Record<string, string> = {
  "unsupported file type (pdf, docx, xlsx, pptx, txt, md, csv)": "Dieser Dateityp wird nicht unterstützt (PDF, Word, Excel, PowerPoint, TXT, Markdown, CSV).",
  "file contains no readable text": "Die Datei enthält keinen lesbaren Text.",
  "file contains no readable text (scan?)": "Die Datei enthält keinen lesbaren Text – vermutlich ein Scan.",
  "text file is not valid UTF-8": "Die Textdatei ist nicht UTF-8-kodiert.",
  "file could not be read": "Die Datei konnte nicht gelesen werden.",
  "file is empty": "Die Datei ist leer.",
  "at most 5 attachments per chat": "Pro Chat sind höchstens 5 Dateien möglich.",
  "at most 5 additional knowledge bases": "Höchstens 5 zusätzliche Wissensbasen.",
};
const german = (m: string) => MESSAGES[m] ?? m;

async function toggleKb(id: number, on: boolean) {
  const cid = props.conversationId ?? (await props.ensureConversation());
  if (cid === null || auth.selectedOrgId === null) return;
  const next = on ? [...new Set([...extraKbIds.value, id])] : extraKbIds.value.filter((x) => x !== id);
  const r = await setChatKnowledgeBases(auth.selectedOrgId, cid, next);
  if (r.kind === "ok") extraKbIds.value = r.value.extraKnowledgeBaseIds;
  else emit("error", r.kind === "error" ? german(r.message) : "Deine Sitzung ist abgelaufen.");
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  if (file.size > 10 * 1024 * 1024) {
    emit("error", "Die Datei ist zu groß (höchstens 10 MB).");
    return;
  }
  uploading.value = true;
  try {
    const cid = props.conversationId ?? (await props.ensureConversation());
    if (cid === null || auth.selectedOrgId === null) return;
    const r = await uploadChatAttachment(auth.selectedOrgId, cid, file);
    if (r.kind === "ok") attachments.value = [...attachments.value, r.value];
    else emit("error", r.kind === "error" ? german(r.message) : "Deine Sitzung ist abgelaufen.");
  } finally {
    uploading.value = false;
  }
}

async function removeAttachment(a: ChatAttachment) {
  if (props.conversationId === null || auth.selectedOrgId === null) return;
  const r = await deleteChatAttachment(auth.selectedOrgId, props.conversationId, a.id);
  if (r.kind === "ok") attachments.value = attachments.value.filter((x) => x.id !== a.id);
}
</script>

<template>
  <DropdownMenuRoot>
    <DropdownMenuTrigger class="cx-btn cx-add" :disabled="disabled || uploading" data-testid="chat-context-add"
      aria-label="Datei oder weiteres Wissen hinzufügen" title="Datei oder weiteres Wissen hinzufügen"><AppIcon name="plus" :size="20" /></DropdownMenuTrigger>
    <DropdownMenuPortal>
      <DropdownMenuContent class="ic-menu" :side-offset="8" align="start" side="top">
        <DropdownMenuItem class="ic-menu-item" data-testid="chat-attach" :disabled="uploading" @select="fileInput?.click()">
          <AppIcon name="upload" :size="16" /><span>{{ uploading ? "Datei wird gelesen …" : "Datei anhängen" }}</span>
        </DropdownMenuItem>
        <DropdownMenuSub v-if="otherKbs.length">
          <DropdownMenuSubTrigger class="ic-menu-item" data-testid="chat-extra-kbs"><AppIcon name="book" :size="16" /><span class="ic-menu-text">Weiteres Wissen hinzufügen</span><AppIcon name="chevron" :size="13" /></DropdownMenuSubTrigger>
          <DropdownMenuPortal>
            <DropdownMenuSubContent class="ic-menu" :side-offset="6" :collision-padding="12">
              <DropdownMenuLabel class="ic-menu-label">Zusätzlich durchsuchen</DropdownMenuLabel>
              <DropdownMenuCheckboxItem v-for="k in otherKbs" :key="k.id" class="ic-menu-item"
                :model-value="extraKbIds.includes(k.id)" data-testid="chat-extra-kb-item"
                @update:model-value="(v: boolean | 'indeterminate') => toggleKb(k.id, v === true)" @select.prevent>
                <span class="cx-check">{{ extraKbIds.includes(k.id) ? "✓" : "" }}</span><span class="ic-menu-text">{{ k.name }}</span>
              </DropdownMenuCheckboxItem>
            </DropdownMenuSubContent>
          </DropdownMenuPortal>
        </DropdownMenuSub>
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>
  <input ref="fileInput" type="file" hidden accept=".pdf,.docx,.xlsx,.pptx,.txt,.md,.csv" data-testid="chat-attach-input" @change="onFile" />

  <span v-for="k in extraKbs" :key="`kb${k.id}`" class="cx-chip" data-testid="chat-extra-kb-chip">
    <AppIcon name="book" :size="13" />{{ k.name }}
    <button type="button" :aria-label="`${k.name} entfernen`" @click="toggleKb(k.id, false)"><AppIcon name="close" :size="13" /></button>
  </span>
  <span v-for="a in attachments" :key="`a${a.id}`" class="cx-chip" data-testid="chat-attachment-chip"
    :title="a.truncated ? 'Nur der Anfang der Datei wird verwendet.' : undefined">
    <AppIcon name="doc" :size="13" />{{ a.filename }}<small v-if="a.truncated">(gekürzt)</small>
    <button type="button" :aria-label="`${a.filename} entfernen`" @click="removeAttachment(a)"><AppIcon name="close" :size="13" /></button>
  </span>
</template>

<style scoped>
.cx-btn { display: inline-flex; align-items: center; gap: 6px; min-height: 32px; padding: 0 8px; border: 0; border-radius: 6px; background: transparent; color: var(--ic-text-2); font: 500 13px/1 var(--ic-font); cursor: pointer; }
.cx-add { justify-content:center; min-width:32px; padding:6px; }
.cx-btn:hover:not(:disabled), .cx-btn[data-state="open"] { background: var(--ic-hover-tint); color: var(--ic-text); }
.cx-btn:disabled { opacity: .5; cursor: default; }
.cx-chip { display: inline-flex; align-items: center; gap: 5px; max-width: 220px; min-height: 32px; padding: 0 4px 0 8px; border: 1px solid var(--ic-separator-faint); border-radius: 6px; color: var(--ic-text-2); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cx-chip small { color: var(--ic-text-3); }
.cx-chip button { display: inline-flex; align-items: center; justify-content: center; min-width: 24px; min-height: 24px; padding: 2px; border: 0; border-radius: 999px; background: transparent; color: var(--ic-text-3); cursor: pointer; }
.cx-chip button:hover { color: var(--ic-text); background: var(--ic-hover-tint); }
.cx-check { width: 12px; }
@media(max-width:767px),(any-pointer:coarse){.cx-btn,.cx-chip,.cx-chip button{min-height:44px}.cx-add{min-width:44px}.cx-chip{max-width:100%;height:auto;white-space:normal}.cx-chip button{min-width:44px;flex:none}}
</style>
