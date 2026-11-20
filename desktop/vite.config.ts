import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";

// Tauri expects the dev server at a fixed port (see src-tauri/tauri.conf.json).
export default defineConfig({
  // UI-REFRESH-1: Tailwind v4 as a CSS layer next to the legacy theme.css.
  // Order: theme.css (tokens + legacy base) stays first in main.ts; the
  // tailwind.css entry only contributes @theme mappings + utilities.
  plugins: [vue(), tailwindcss()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
  },
  build: {
    target: "es2021",
  },
});
