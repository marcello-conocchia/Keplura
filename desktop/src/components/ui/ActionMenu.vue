<script setup lang="ts">
import { ref } from "vue";
import { DropdownMenuRoot, DropdownMenuTrigger, DropdownMenuPortal, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from "reka-ui";
import AppIcon from "./AppIcon.vue";
defineProps<{ label: string; disabled?: boolean; items: { id: string; label: string; danger?: boolean; testId?: string }[] }>();
const emit = defineEmits<{ select: [string] }>();
const root = ref<HTMLElement | null>(null);
const selected = ref<string | null>(null);
function close(event: Event) {
  if (selected.value === null) return;
  const id = selected.value; selected.value = null;
  event.preventDefault();
  // Ein Dialog erhält einen beständigen Rückkehrpunkt, nicht den entfernten Menüeintrag.
  root.value?.querySelector('button')?.focus();
  emit('select', id);
}
</script>
<template>
  <span ref="root" class="action-menu">
    <DropdownMenuRoot><DropdownMenuTrigger class="action-menu-trigger" :disabled="disabled" :aria-label="label" :title="label" data-testid="connection-actions"><AppIcon name="more" :size="20" /></DropdownMenuTrigger>
      <DropdownMenuPortal><DropdownMenuContent class="ic-menu" align="end" :side-offset="6" :collision-padding="12" @close-auto-focus="close">
        <template v-for="item in items" :key="item.id"><DropdownMenuSeparator v-if="item.danger" class="ic-menu-sep" /><DropdownMenuItem class="ic-menu-item" :class="{ 'action-menu-danger': item.danger }" :data-testid="item.testId" @select="selected = item.id">{{item.label}}</DropdownMenuItem></template>
      </DropdownMenuContent></DropdownMenuPortal>
    </DropdownMenuRoot>
  </span>
</template>
<style scoped>
.action-menu { display:inline-flex; }.action-menu-trigger { display:inline-flex; align-items:center; justify-content:center; flex:none; width:32px; min-height:32px; padding:4px; border:0; border-radius:6px; background:transparent; color:var(--ic-text-2); cursor:pointer; }.action-menu-trigger:hover:not(:disabled) { background:var(--ic-hover-tint); }.action-menu-trigger:disabled { color:var(--ic-disabled-text); cursor:default; }.action-menu-danger { color:var(--ic-status-error); }
@media(max-width:767px),(any-pointer:coarse){.action-menu-trigger{width:44px;min-height:44px}}
</style>
