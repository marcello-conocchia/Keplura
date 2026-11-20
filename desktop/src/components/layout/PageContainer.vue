<script setup lang="ts">
/** REDESIGN v2: Inhaltsfläche — Kopfleiste (Slot) volle Breite, Inhalt mit begrenzter Breite. */
withDefaults(defineProps<{ width?: "narrow" | "wide" }>(), { width: "wide" });
</script>

<template>
  <main class="pc">
    <slot name="header" />
    <div class="pc-inner" :class="width"><slot /></div>
  </main>
</template>

<style scoped>
.pc {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  background: var(--ic-content);
}
.pc-inner {
  margin: 0 auto;
  padding: var(--ic-space-6) var(--ic-space-8) var(--ic-space-8);
}
.pc-inner.narrow {
  max-width: 760px;
}
.pc-inner.wide {
  max-width: 1160px;
}
@media (max-width: 760px) {
  .pc-inner {
    padding: var(--ic-space-4);
  }
}
</style>
