<script setup lang="ts">
/** REDESIGN v2: kompakte Kopfleiste (48 px) — Titel links, Aktionen rechts. */
defineProps<{ title: string; description?: string }>();
</script>

<template>
  <header class="ph">
    <h1 class="ph-title">{{ title }}</h1>
    <span v-if="description || $slots.description" class="ph-desc">
      <slot name="description">{{ description }}</slot>
    </span>
    <div id="page-actions" class="ph-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.ph {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: var(--ic-space-3);
  min-height: 48px;
  padding: 0 var(--ic-space-6);
  background: color-mix(in srgb, var(--ic-content) 92%, transparent);
  backdrop-filter: saturate(1.4) blur(8px);
  border-bottom: 1px solid var(--ic-separator);
}
.ph-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
  white-space: nowrap;
}
.ph-desc {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--ic-text-sm);
  color: var(--ic-text-3);
}
.ph-actions {
  display: flex;
  align-items: center;
  gap: var(--ic-space-2);
  margin-left: auto;
  flex: none;
}
</style>
