<script setup lang="ts">
/** REDESIGN: verständliche Bestätigung für endgültige Aktionen (Löschen). */
import AppDialog from "./AppDialog.vue";
import Button from "./Button.vue";
import { tr } from "../../i18n";

withDefaults(
  defineProps<{ open: boolean; title: string; message: string; confirmLabel?: string; busy?: boolean; error?: string; tone?: "danger" | "primary" }>(),
  { confirmLabel: () => tr("Löschen"), busy: false, error: "", tone: "danger" },
);
const emit = defineEmits<{ confirm: []; close: [] }>();
</script>

<template>
  <AppDialog :open="open" :title="title" :error="error" :busy="busy" initial-focus="cancel" @close="emit('close')">
    <p class="msg">{{ message }}</p>
    <div class="actions">
      <Button variant="secondary" data-testid="confirm-cancel" :disabled="busy" @click="emit('close')">{{ $t("Abbrechen") }}</Button>
      <Button :variant="tone" data-testid="confirm-submit" :disabled="busy" :busy="busy" @click="emit('confirm')">
        {{ busy ? $t("Bitte warten…") : confirmLabel }}
      </Button>
    </div>
  </AppDialog>
</template>

<style scoped>
.msg {
  margin: 0 0 var(--ic-space-4);
  color: var(--ic-text-2);
  line-height: 1.5;
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ic-space-2);
}
</style>
