import { ref } from "vue";
import { inTauri } from "./transport";

/**
 * DARK-FS (PO-Instruktion 09.09.): one window-fullscreen toggle that works
 * on BOTH runtimes of this app:
 *  - inside the Tauri webview it drives the NATIVE window
 *    (getCurrentWindow().setFullscreen) — that is the only path that also
 *    hides the GTK/Windows/macOS title bar;
 *  - in a plain browser (Vite dev + the documented QA/e2e flow) there is no
 *    native window, so it falls back to the Fullscreen API.
 *
 * State lives in a module-level ref on purpose: App.vue (keyboard shortcuts)
 * and the AppSidebar button must read the SAME truth — a per-component ref
 * would let the button label lie after F11.
 *
 * Tauri 2.11 dispatches NO window event for fullscreen changes (its runtime
 * WindowEvent enum has no fullscreen variant), so on the native path the ref
 * mirrors what our toggles successfully requested. Deliberately NOT
 * persisted across restarts (see theme-store.ts for the same reasoning).
 */

const isFullscreen = ref(false);

async function currentWindow() {
  const { getCurrentWindow } = await import("@tauri-apps/api/window");
  return getCurrentWindow();
}

/**
 * Wire the browser-side sync once. In Tauri there is nothing to listen to
 * (see comment above); the browser emits fullscreenchange for EVERY exit
 * path, including the OS-gesture Escape and the WM's own unfullscreen.
 */
export function initFullscreenState(): void {
  if (inTauri() || typeof document === "undefined") {
    return;
  }
  document.addEventListener("fullscreenchange", () => {
    isFullscreen.value = document.fullscreenElement !== null;
  });
}

/** Toggle to the opposite state on whichever runtime we are on. */
export async function toggleFullscreen(): Promise<void> {
  if (inTauri()) {
    const next = !isFullscreen.value;
    const win = await currentWindow();
    await win.setFullscreen(next);
    isFullscreen.value = next;
    return;
  }
  if (document.fullscreenElement) {
    await document.exitFullscreen();
  } else {
    // Rejections (permissions, headless) propagate to the caller's catch;
    // fullscreenchange only ever sets what actually happened.
    await document.documentElement.requestFullscreen();
  }
}

export function useFullscreen() {
  return { isFullscreen, toggleFullscreen };
}
