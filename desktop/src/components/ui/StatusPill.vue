<script setup lang="ts">
/** Gemeinsamer statischer Status mit Textlabel. Live-Rückmeldungen gehören an den Vorgang. */
withDefaults(
  defineProps<{ tone?: "ok" | "wait" | "neutral" | "error" | "info" }>(),
  { tone: "neutral" },
);
</script>

<template>

  <span class="ic-pill" :class="tone"><slot /></span>
</template>
