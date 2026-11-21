import { createApp, h } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import { tr, dateLocale, locale } from "./i18n";
// REDESIGN: Inter Variable lokal gebündelt (kein CDN, offline-fähig).
import "@fontsource-variable/inter";
import "./styles/theme.css";
// UI-REFRESH-1: Tailwind-Schicht NACH der Altschicht — die --ic-*-Token-
// Definitionen und das Legacy-Base bleiben massgeblich; tailwind.css liefert
// nur die utilities-Schicht gegen dieselben Tokens (Koexistenz Pfad Phase 1).
import "./styles/tailwind.css";
import "./styles/workspace.css";
import "./styles/controls.css";
import "./styles/administration.css";

// Re-mounting App on a language switch refreshes labels that components compute
// once in setup; stores (session, selection) live in Pinia and are kept.
const app = createApp({ render: () => h(App, { key: locale.value }) });
// UI language: $t in templates, tr in scripts (src/i18n.ts)
app.config.globalProperties.$t = tr;
app.config.globalProperties.$dateLocale = dateLocale;
app.use(createPinia()).mount("#app");
