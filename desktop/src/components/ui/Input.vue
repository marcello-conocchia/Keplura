<script lang="ts">
// Attrs (data-testid, autocomplete, required, maxlength, disabled, readonly, …) landen auf dem nativen
// <input>, nicht im Wrapper — e2e fill() und native Formularvalidierung zielen auf das Input selbst.
export default { inheritAttrs: false };
</script>

<script setup lang="ts">
import { computed, ref, useAttrs } from "vue";
import { Label } from "reka-ui";
import AppIcon, { type IconName } from "./AppIcon.vue";

/**
 * FO-Vertrag System 1.0 (UI-05): einziges Textfeld-Rezept. Anatomie Label → Control → Hilfe → Fehler,
 * Hilfe/Fehler über stabile IDs per aria-describedby, invalid über aria-invalid. Das reka-Label trägt
 * die <label for>-Zuordnung — der Accessible Name IST der Labeltext (AT-SPI-tragend für den nativen Login).
 */
const props = defineProps<{
  id: string;
  label: string;
  /** Suchfelder behalten ihren zugänglichen Namen ohne sichtbare Labelzeile. */
  labelHidden?: boolean;
  modelValue: string;
  type?: string;
  icon?: IconName;
  /** Dauerhafte Hilfe, bleibt auch bei Fehler sichtbar. */
  hint?: string;
  /** Konkreter Feldfehler; setzt aria-invalid. */
  error?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const attrs = useAttrs();

const inputEl = ref<HTMLInputElement | null>(null);
defineExpose({ focus: () => inputEl.value?.focus() });

const hintId = computed(() => `${props.id}-hint`);
const errorId = computed(() => `${props.id}-error`);
const describedBy = () => [attrs["aria-describedby"] as string | undefined,
  props.hint ? hintId.value : undefined, props.error ? errorId.value : undefined].filter(Boolean).join(" ") || undefined;

function onInput(event: Event) {
  emit("update:modelValue", (event.target as HTMLInputElement).value);
}
</script>

<template>
  <div class="ic-field">
    <Label :for="id" class="ic-field-label" :class="{ 'ic-field-label-hidden': labelHidden }">{{ label }}</Label>
    <span class="ic-field-control">
      <span v-if="icon" aria-hidden="true" class="ic-field-icon"><AppIcon :name="icon" :size="16" /></span>
      <input
        :id="id"
        ref="inputEl"
        class="ic-field-input"
        :class="{ 'has-icon': !!icon, invalid: !!error }"
        :type="type || 'text'"
        :value="modelValue"
        v-bind="$attrs"
        :aria-describedby="describedBy()"
        :aria-invalid="error ? 'true' : attrs['aria-invalid'] as boolean | 'true' | 'false' | 'grammar' | 'spelling' | undefined"
        @input="onInput"
      />
    </span>
    <p v-if="hint" :id="hintId" class="ic-field-hint">{{ hint }}</p>
    <p v-if="error" :id="errorId" class="ic-field-error">{{ error }}</p>
  </div>
</template>
