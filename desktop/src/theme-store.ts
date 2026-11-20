import { defineStore } from "pinia";
import { computed, ref } from "vue";

/**
 * DARK-FS (PO-Instruktion 09.09.): appearance-mode state for the desktop.
 *
 * Three user-facing modes — "light", "dark" and "system". Only the RESOLVED
 * mode ever lands on <html> as data-ic-theme (the token block in
 * theme.css switches on exactly that attribute); the chosen mode persists in
 * localStorage so restarts keep it, and a "system" choice tracks live OS
 * theme flips via matchMedia.
 *
 * Anti-flash contract: index.html carries a tiny inline pre-paint script
 * that reads the SAME localStorage key and sets the attribute before Vue
 * mounts, so the first frame already uses the right palette. This store then
 * takes over ownership of the attribute.
 *
 * Fullscreen lives in App.vue (it is window state, not appearance state) —
 * deliberately NOT persisted here: a restored FS session would hide the
 * OS chrome the user expects after a restart.
 */

const STORAGE_KEY = "keplura.theme.mode";
export type ThemeMode = "light" | "dark" | "system";
export type ResolvedMode = "light" | "dark";

function readStoredMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw === "light" || raw === "dark" || raw === "system" ? raw : "system";
  } catch {
    // localStorage can throw in hardened webviews; default is non-critical.
    return "system";
  }
}

function systemPrefersDark(): boolean {
  return (
    typeof matchMedia === "function" &&
    matchMedia("(prefers-color-scheme: dark)").matches
  );
}

export const useThemeStore = defineStore("theme", () => {
  const mode = ref<ThemeMode>(readStoredMode());

  /**
   * Reactive mirror of the OS preference. matchMedia itself is NOT a
   * reactive source — without this ref, the `resolved` computed below
   * caches its first answer and a live OS flip in "system" mode would be
   * silently ignored (dark_flow_e2e.cjs step 2 catches exactly this).
   */
  const sysDark = ref(systemPrefersDark());

  const resolved = computed<ResolvedMode>(() =>
    mode.value === "system" ? (sysDark.value ? "dark" : "light") : mode.value,
  );

  /** Single writer of the attribute theme.css switches on. */
  function apply() {
    document.documentElement.setAttribute("data-ic-theme", resolved.value);
  }

  function setMode(next: ThemeMode) {
    mode.value = next;
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Persistence is best-effort; the session still gets the chosen mode.
    }
    apply();
  }

  /** One-step cycle for the sidebar toggle: light → dark → system → light. */
  function cycle() {
    setMode(
      mode.value === "light"
        ? "dark"
        : mode.value === "dark"
          ? "system"
          : "light",
    );
  }

  // "system" must follow the live OS preference, not just the value at boot.
  // addEventListener exists on every supported webview (WebKitGTK ≥ 2.14 /
  // Chromium ≥ 79); the addListener legacy path is intentionally not carried.
  if (typeof matchMedia === "function") {
    matchMedia("(prefers-color-scheme: dark)").addEventListener(
      "change",
      (event) => {
        sysDark.value = event.matches;
        if (mode.value === "system") {
          apply();
        }
      },
    );
  }

  apply();

  return { mode, resolved, setMode, cycle };
});
