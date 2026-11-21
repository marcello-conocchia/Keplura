<script setup lang="ts">
/** Official Keplura mark (orbit), same artwork as src-tauri/icons/app-icon.svg. */
withDefaults(defineProps<{ size?: number }>(), { size: 30 });
</script>

<template>
  <svg class="brand-mark" :width="size" :height="size" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
    <rect width="32" height="32" rx="8" fill="#08080a" />
    <ellipse cx="16" cy="16" rx="11" ry="6.2" transform="rotate(-28 16 16)" fill="none" stroke="#f0a83a" stroke-width="1.8" />
    <circle cx="12.4" cy="17.9" r="2.6" fill="#ece7dc" />
    <circle cx="24.6" cy="9.6" r="1.7" fill="#f0a83a" />
  </svg>
</template>

<style scoped>
.brand-mark { display: block; flex: none; }
</style>
