<script lang="ts">
// Shared icon-name type (UI-POLISH-1: Input takes an optional icon name
// from the SAME closed set — no parallel stringly-typed vocabulary).
export type IconName = "shield" | "overview" | "book" | "doc" | "logout"
  | "refresh" | "plus" | "chat" | "close" | "upload" | "search" | "admin"
  | "copy" | "sun" | "moon" | "auto" | "expand" | "compress"
  | "users" | "folder" | "trash" | "edit" | "chevron" | "more" | "download" | "external"
  | "thumb-up" | "thumb-down" | "arrow-up" | "stop" | "globe";
</script>
<script setup lang="ts">
import { computed } from "vue";

/**
 * Shared inline-SVG icon set (DESIGN.md §6): ONE stroke system — 1.7 px,
 * round caps/joins, no fills, no emojis, no mixed sets. Only two sizes:
 * 15 px (inline/UI, default) and 13 px (dense). Colour is `currentColor`
 * so the parent sets --ic-text-2 (rest) / --ic-text (leading).
 * Paths are drawn on a 24×24 grid (same geometry as the approved mockup).
 */
const props = withDefaults(defineProps<{ name: IconName; size?: 13 | 15 | 16 | 20 }>(), {
  size: 15,
});

const PATHS = {
  globe: ["M21 12a9 9 0 1 0-18 0 9 9 0 0 0 18 0", "M3 12h18", "M12 3c-5 5-5 13 0 18", "M12 3c5 5 5 13 0 18"],
  "arrow-up": ["M12 20V4", "M5 11l7-7 7 7"],
  stop: ["M6 6h12v12H6z"],
  shield: ["M12 3l7 3.5v5c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9v-5L12 3z"],
  overview: ["M4 6h16", "M4 12h16", "M4 18h16"],
  book: [
    "M4 5.5A1.5 1.5 0 0 1 5.5 4H11v16H5.5A1.5 1.5 0 0 1 4 18.5v-13z",
    "M13 4h5.5A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5H13V4z",
  ],
  doc: ["M6 2h9l4 4v14H6V2z"],
  logout: ["M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "M16 17l5-5-5-5", "M21 12H9"],
  refresh: ["M20 11A8 8 0 1 0 18.5 16", "M20 5v6h-6"],
  plus: ["M12 5v14", "M5 12h14"],
  // M4b chat: speech bubble (DESIGN.md §6 one-stroke system)
  chat: ["M21 12a8 8 0 0 1-8 8H4l2.2-2.6A8 8 0 1 1 21 12z"],
  // M4b chat: X for destructive row actions (same 1.7px stroke system)
  close: ["M6 6l12 12", "M18 6L6 18"],
  /* UI-3: upload arrow (geometry from the approved mockup, panel 3 header). */
  upload: ["M12 16V4", "M6 9l6-6 6 6", "M5 20h14"],
  /* UI-POLISH-1: magnifier for the knowledge search field — same geometry
     grid, 1.7px round-cap stroke, no new icon SET (DESIGN.md §6). */
  search: ["M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z", "M20.5 20.5l-4.2-4.2"],
  /* ADMIN-KI: settings sliders for the admin nav entry — two rails with
     knob ticks, same 1.7px round-cap stroke system (DESIGN.md §6). */
  admin: ["M4 8h6", "M15 8h5", "M4 16h3", "M12 16h8", "M12.5 5.5v5", "M9.5 13.5v5"],
  /* CHAT-POLISH §2: two overlapping sheets (copy) — same 24-grid, 1.7 px
     round-cap stroke system (DESIGN.md §6), no filled shapes. */
  copy: ["M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z", "M15.5 5.5V4a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10.5"],
  /* DARK-FS: appearance + fullscreen set — same stroke system, no fills.
     sun = disc + four cardinal rays (the two diagonals read as noise at
     13 px); moon = classic crescent arc; auto = circle split by a chord
     (half-light/half-dark); expand/compress = four corner arrows. */
  sun: ["M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z", "M12 2.5v2.6", "M12 18.9v2.6", "M2.5 12h2.6", "M18.9 12h2.6"],
  moon: ["M20 14.2A8.2 8.2 0 0 1 9.8 4a8.2 8.2 0 1 0 10.2 10.2z"],
  auto: ["M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z", "M12 4v16"],
  expand: ["M9 4H4v5", "M15 4h5v5", "M9 20H4v-5", "M15 20h5v-5"],
  compress: ["M4 9h5V4", "M20 9h-5V4", "M4 15h5v5", "M20 15h-5v5"],
  /* REDESIGN-2026-11: same stroke system, no fills. */
  users: ["M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z", "M2.5 20a6.5 6.5 0 0 1 13 0", "M16 4.5a3.5 3.5 0 0 1 0 6.5", "M18.5 14.2A6.5 6.5 0 0 1 21.5 20"],
  folder: ["M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2.5h8.5A1.5 1.5 0 0 1 21 9v9.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5v-12z"],
  trash: ["M4 7h16", "M9 7V4h6v3", "M6.5 7l1 13h9l1-13"],
  edit: ["M4 20h4L19 9l-4-4L4 16v4z", "M13.5 6.5l4 4"],
  chevron: ["M9 6l6 6-6 6"],
  more: ["M5 12h.01", "M12 12h.01", "M19 12h.01"],
  download: ["M12 4v12", "M6 11l6 6 6-6", "M5 20h14"],
  "thumb-up": ["M7 11v9H4v-9h3z", "M7 11l4-7a2 2 0 0 1 2 2.5L12 10h6a2 2 0 0 1 2 2.3l-1.2 6A2 2 0 0 1 16.8 20H7"],
  "thumb-down": ["M7 13V4H4v9h3z", "M7 13l4 7a2 2 0 0 0 2-2.5L12 14h6a2 2 0 0 0 2-2.3l-1.2-6A2 2 0 0 0 16.8 4H7"],
  external: ["M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"],
} as const;

const paths = computed(() => PATHS[props.name]);
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>
