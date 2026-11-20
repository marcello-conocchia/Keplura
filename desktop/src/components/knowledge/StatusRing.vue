<script setup lang="ts">
/** REDESIGN v3: Status als Ring-Symbol (voll = durchsuchbar, halb = in Arbeit, Kreuz = Fehler, leer = ohne Text). */
import type { KnowledgeDocument } from "../../api";
defineProps<{ status: KnowledgeDocument["indexStatus"] }>();
</script>

<template>
  <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" class="sr" :class="status">
    <circle cx="7" cy="7" r="6" fill="none" stroke="currentColor" stroke-width="1.6" :stroke-dasharray="status === 'NOT_INDEXED' ? '2 2' : undefined" />
    <circle v-if="status === 'INDEXED'" cx="7" cy="7" r="3.2" fill="currentColor" />
    <path v-else-if="status === 'PENDING'" d="M7 3.8 A3.2 3.2 0 0 1 7 10.2 Z" fill="currentColor" />
    <path v-else-if="status === 'FAILED'" d="M5 5l4 4M9 5l-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
  </svg>
</template>

<style scoped>
.sr {
  flex: none;
}
.INDEXED {
  color: var(--ic-status-ok);
}
.PENDING {
  color: var(--ic-status-wait);
}
.FAILED {
  color: var(--ic-status-error);
}
.NO_TEXT_LAYER,
.NOT_INDEXED {
  color: var(--ic-text-3);
}
</style>
