<script setup lang="ts">
/**
 * UI-13: gemeinsamer Rahmen der Zugangsflächen (Anmeldung, Pflicht-Passwortwechsel,
 * Sitzungsprüfung). Zentrierte Karte mit Keplura-Marke, Titel und Einleitung; der
 * Inhalt kommt per Slot, ein optionaler Fuß (Verbindung, Darstellung) per Slot „footer“.
 */
import BrandMark from "../ui/BrandMark.vue";

defineProps<{ title?: string; intro?: string; wide?: boolean }>();
</script>

<template>
  <main class="access-surface">
    <div class="access-panel" :class="{ wide }">
      <div class="access-brand">
        <BrandMark :size="28" />
        <span class="access-name">Keplura</span>
      </div>
      <h1 v-if="title" class="access-title">{{ title }}</h1>
      <p v-if="intro" class="access-intro">{{ intro }}</p>
      <slot />
      <div v-if="$slots.footer" class="access-footer"><slot name="footer" /></div>
    </div>
  </main>
</template>

<style scoped>
.access-surface { display: flex; flex: 1; min-height: 0; align-items: center; justify-content: center; padding: 28px 16px; overflow-y: auto; background: var(--ic-canvas); }
.access-panel { width: 400px; max-width: 100%; padding: 32px; border: 1px solid var(--ic-separator); border-radius: 12px; background: var(--ic-content); box-shadow: var(--ic-shadow-raised); }
.access-panel.wide { width: 440px; }
.access-brand { display: flex; align-items: center; gap: 9px; margin-bottom: 28px; }
.access-name { color: var(--ic-text); font: 600 16px/1 var(--ic-font); letter-spacing: -.2px; }
.access-title { margin: 0 0 8px; color: var(--ic-text); font: 600 22px/28px var(--ic-font); letter-spacing: -.4px; }
.access-intro { margin: 0 0 24px; color: var(--ic-text-2); font: 400 14px/20px var(--ic-font); }
.access-footer { margin-top: 24px; padding-top: 16px; border-top: 1px solid var(--ic-separator-faint); }
@media (max-width: 480px) { .access-surface { padding: 20px 12px; align-items: flex-start; } .access-panel { padding: 24px 20px; } }
</style>
