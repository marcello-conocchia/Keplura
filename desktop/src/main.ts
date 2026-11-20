import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
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

createApp(App).use(createPinia()).mount("#app");
