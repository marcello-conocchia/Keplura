<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, provide, ref, watch } from "vue";
import LoginView from "./components/LoginView.vue";
import AccessShell from "./components/layout/AccessShell.vue";
import KnowledgeView from "./components/KnowledgeView.vue";
import TeamView from "./components/TeamView.vue";
import ConnectionsView from "./components/ConnectionsView.vue";
import ChatView from "./components/ChatView.vue";
import ChangePasswordView from "./components/ChangePasswordView.vue";
import OnboardingView from "./components/OnboardingView.vue";
import AdminPanel from "./components/AdminPanel.vue";
import AppSidebar from "./components/AppSidebar.vue";
import CommandPalette from "./components/CommandPalette.vue";
import AppIcon from "./components/ui/AppIcon.vue";
import { NAVIGATE_KEY, type AppView } from "./navigation";
import { useAuthStore } from "./auth-store";
import { useThemeStore } from "./theme-store";
import { initFullscreenState, toggleFullscreen, useFullscreen } from "./fullscreen";
import { inTauri } from "./transport";
import { serverUrl } from "./api";
import { readOidcErrorFragment, readOidcFragment, stripOidcFragment } from "./oidc";
import { tr } from "./i18n";

/** M4b: the signed-in content area has two views; ADMIN-KI adds the admin
 * panel (third) — reachable for ADMINs via the sidebar entry only. */
type ActiveView = AppView;

interface SystemInfo {
  product: string;
  serverVersion: string;
  apiVersion: string;
  /**
   * M4c provider state — closed vocabulary from the server
   * ("none" | "local" | "configured"). Optional for wire-compat with older
   * servers; the desktop never learns provider names, URLs or keys.
   */
  model?: string;
}

const auth = useAuthStore();
// DARK-FS: instantiating the store IS the boot wiring — its setup body
// applies the persisted/system-resolved mode to <html> (the index.html
// pre-paint script already did the first frame) and keeps the "system"
// mode following live OS flips.
useThemeStore();
const activeView = ref<ActiveView>("chat");
const mobile = ref(false);
const menuOpen = ref(false);
const menuPanel = ref<HTMLElement | null>(null);
const menuButton = ref<HTMLButtonElement | null>(null);
const menuMedia = window.matchMedia("(max-width: 760px)");
const viewTitle = computed(() => ({chat:tr("Chat"),wissen:tr("Wissen"),team:tr("Team"),admin:tr("Administration"),verbindungen:tr("Persönliche Verbindungen")})[activeView.value]);
function resizeMenu() { mobile.value = menuMedia.matches; if (!mobile.value) menuOpen.value = false; }
async function closeMenu() { menuOpen.value = false; await nextTick(); menuButton.value?.focus(); }
async function openMenu() { menuOpen.value = true; await nextTick(); menuPanel.value?.querySelector<HTMLElement>("button")?.focus(); }
function navigate(view: ActiveView) { activeView.value = view; if (mobile.value) void closeMenu(); }
provide(NAVIGATE_KEY, navigate);
function menuKeys(event: KeyboardEvent) {
  if (!mobile.value || !menuOpen.value) return;
  // Portal menus own Escape first; their selection remains a normal UI action.
  if (document.querySelector('[role="menu"]')) return;
  if (event.key === "Escape") { event.preventDefault(); void closeMenu(); }
  if (event.key !== "Tab") return;
  const items = Array.from(menuPanel.value?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href], [tabindex="0"]') ?? []);
  const first=items[0], last=items[items.length-1];
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
}
onMounted(() => { resizeMenu(); menuMedia.addEventListener("change",resizeMenu); window.addEventListener("keydown",menuKeys); });
onBeforeUnmount(() => { menuMedia.removeEventListener("change",resizeMenu); window.removeEventListener("keydown",menuKeys); });
watch([() => auth.selectedOrgId, () => auth.selectedSpaceId, () => auth.loggedIn], () => {
  if (mobile.value && menuOpen.value) void closeMenu(); else menuOpen.value=false;
});

// REDESIGN: the sidebar workspace switcher needs org + spaces right after
// sign-in, independent of which area is open.
watch(
  () => auth.loggedIn && !auth.restoring,
  (ready) => {
    if (ready) void auth.loadKnowledge();
  },
  { immediate: true },
);
// REDESIGN v3: the sidebar lists the space's conversations on every page.
watch(
  () => [auth.loggedIn, auth.selectedSpaceId] as const,
  ([loggedIn, spaceId]) => {
    if (loggedIn && spaceId !== null) void auth.loadConversations();
  },
);
// A freshly created/joined first org must become the active workspace.
watch(
  () => auth.orgs.length,
  () => {
    if (auth.loggedIn && auth.selectedOrgId === null && auth.orgs.length > 0) void auth.loadKnowledge();
  },
);

// DARK-FS: fullscreen state is a module-level ref shared with the sidebar
// button (see fullscreen.ts); the browser fullscreenchange sync is installed
// once here, keyboard shortcuts below.
const { isFullscreen } = useFullscreen();
initFullscreenState();

/**
 * F11 toggles on both runtimes; Escape exits, but ONLY when nothing "above"
 * fullscreen wants the key first: an open <dialog> (native Escape-close), or
 * any focused text input (composer/chat/fields — Escape there means "drop
 * my edit", never "leave fullscreen"). In Tauri there is no
 * fullscreenchange event (2.11 emits none — see fullscreen.ts), so the
 * manual Escape exit is load-bearing there.
 * In the plain browser BOTH keys are native (Chrome/GTK handle F11 and
 * Escape fullscreen themselves; the fullscreenchange listener mirrors the
 * result into our ref) — intercepting there would fight the browser and
 * double-toggle, so the shortcuts are Tauri-only by design.
 */
function onGlobalKeydown(event: KeyboardEvent) {
  if (!inTauri()) {
    return;
  }
  if (event.key === "F11" && !event.repeat) {
    event.preventDefault();
    void toggleFullscreen();
    return;
  }
  if (
    event.key === "Escape" &&
    isFullscreen.value &&
    !event.repeat &&
    document.querySelector("dialog[open]") === null &&
    !(event.target instanceof HTMLElement && event.target.isContentEditable) &&
    !(
      event.target instanceof HTMLInputElement ||
      event.target instanceof HTMLTextAreaElement
    )
  ) {
    void toggleFullscreen();
  }
}

// --- Connectivity indicator (kept from M1; login rides on the same server).
// Presentation moved into the views: the login footer line and the sidebar
// status row consume this state; the probe itself is UNCHANGED. ---
const status = ref<"checking" | "connected" | "unreachable">("checking");
const systemInfo = ref<SystemInfo | null>(null);
const errorDetail = ref("");

async function checkServer() {
  status.value = "checking";
  systemInfo.value = null;
  errorDetail.value = "";
  // OIDC-M2-Tail: the capabilities flag rides the same probe cycle (button
  // appears/disappears exactly when the server state changes; coarse GET,
  // fails closed to "no button" — no new UI states beyond the local form).
  void auth.checkOidc();
  try {
    const response = await fetch(`${serverUrl}/api/v1/system/info`, {
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    systemInfo.value = (await response.json()) as SystemInfo;
    status.value = "connected";
  } catch (error) {
    status.value = "unreachable";
    errorDetail.value = error instanceof Error ? error.message : String(error);
  }
}

async function reloadAll() {
  await Promise.all([auth.loadOrgs(), auth.loadKnowledge(), checkServer()]);
}

onMounted(async () => {
  // DARK-FS: window-level keyboard handling for fullscreen (F11 / Escape).
  window.addEventListener("keydown", onGlobalKeydown);
  // OIDC-M2-Tail (ADR-0009 §6/§10): a returning navigation from the server
  // callback carries #oidc=<handoff> (or #oidc-error). Consume the fragment
  // BEFORE the restore probe — success mints the session via the exchange
  // and then converges on the SAME restore path; either way the fragment is
  // wiped from the URL (history.replaceState) so a reload cannot replay it.
  const handoff = readOidcFragment();
  if (handoff) {
    stripOidcFragment();
    await auth.completeOidcHandoff(handoff);
  } else if (readOidcErrorFragment()) {
    stripOidcFragment();
    auth.oidcFailed = true; // coarse failure line on the login card
  }
  // Probe a possibly-surviving session cookie first (app restart), then the
  // open connectivity check. Order matters only for a quiet first paint.
  await auth.restoreSession();
  await checkServer();
});
// PILOT 4e: Erste Schritte, solange Organisation, Arbeitsbereich oder Wissensbasis fehlen.
const showOnboarding = computed(
  () => !auth.orgsError && (auth.orgs.length === 0 || auth.spaces.length === 0 || auth.kbs.length === 0),
);

// PILOT 4e: Konto mit Startpasswort muss zuerst ein eigenes Passwort setzen.
const mustChangePassword = computed(() => auth.user?.mustChangePassword === true);
</script>

<template>
  <div class="shell">
    <header v-if="auth.loggedIn && mobile && !auth.restoring && !mustChangePassword" class="mobile-topbar">
      <button ref="menuButton" type="button" :aria-label="$t('Menü öffnen')" :aria-expanded="menuOpen" aria-controls="primary-navigation" data-testid="mobile-menu-open" @click="openMenu"><AppIcon name="overview" :size="15" /><span>{{ $t("Menü") }}</span></button>
      <span>{{ viewTitle }}</span><strong>Keplura</strong>
    </header>
    <div v-if="auth.loggedIn && mobile && menuOpen" class="mobile-menu-scrim" @click="closeMenu"></div>
    <div v-if="auth.loggedIn && !auth.restoring && !mustChangePassword && (!mobile || menuOpen)" id="primary-navigation" ref="menuPanel" class="navigation-slot" :class="{'mobile-drawer': mobile}" :role="mobile ? 'dialog' : undefined" :aria-modal="mobile || undefined" :aria-label="$t('Navigation')">
      <div v-if="mobile" class="mobile-menu-head"><strong>Keplura</strong><button type="button" :aria-label="$t('Menü schließen')" data-testid="mobile-menu-close" @click="closeMenu"><AppIcon name="close" :size="15" /></button></div>
    <AppSidebar
      :status="status"
      :model-state="systemInfo?.model ?? ''"
      :server-version="systemInfo?.serverVersion ?? ''"
      :active-view="activeView"
      :is-fullscreen="isFullscreen"
      :show-fullscreen="true"
      @navigate="navigate"
      @toggle-fullscreen="void toggleFullscreen()"
      @reload="reloadAll"
      @logout="auth.logout()"
    />
    </div>
    <template v-if="auth.restoring">
      <AccessShell data-testid="auth-restoring">
        <p class="boot-line" role="status"><span class="boot-spinner" aria-hidden="true"></span>{{ $t("Sitzung wird geprüft …") }}</p>
      </AccessShell>
    </template>
    <ChangePasswordView v-else-if="auth.loggedIn && mustChangePassword" />
    <template v-else-if="auth.loggedIn">
      <AdminPanel v-if="activeView === 'admin' && auth.isAdmin" />
      <OnboardingView
        v-else-if="activeView === 'chat' && showOnboarding"
        @navigate="navigate"
      />
      <ChatView v-else-if="activeView === 'chat'" />
      <TeamView v-else-if="activeView === 'team'" />
      <ConnectionsView v-else-if="activeView === 'verbindungen'" />
      <KnowledgeView v-else />
    </template>
    <LoginView v-else :status="status" @retry="checkServer" />
    <CommandPalette v-if="auth.loggedIn && !mustChangePassword" @navigate="navigate" />
  </div>
</template>

<style scoped>
/* Full-window shell: sidebar + content on the canvas foil (mockup panel 2).
   The old standalone "Server connection" card is gone — its probe state now
   surfaces in the login footer and the sidebar status row (UI-1 scope). */
.shell {
  height: 100%;
  display: flex;
  min-width: 0;
  background: var(--ic-canvas);
  overflow: hidden;
}
.navigation-slot { display: flex; min-height: 0; flex: none; }
.mobile-topbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 48px; padding: 6px 14px; flex: none; color: var(--ic-text); background: var(--ic-content); border-bottom: 1px solid var(--ic-separator); font-size: 13px; }
.mobile-topbar button, .mobile-menu-head button { min-height: var(--ic-control-touch); min-width: var(--ic-control-touch); display: inline-flex; align-items: center; justify-content: center; gap: 7px; padding: 8px; border: 0; border-radius: 6px; color: var(--ic-text); background: var(--ic-hover-tint); font: inherit; cursor: pointer; }
.mobile-menu-scrim { position: fixed; inset: 0; z-index: 39; background: #0005; }
.mobile-drawer { position: fixed; inset: 0 auto 0 0; z-index: 40; width: min(300px, calc(100vw - 32px)); flex-direction: column; background: var(--ic-sidebar); box-shadow: 8px 0 24px #0002; }
.mobile-drawer :deep(.sb) { flex: 1; width: 100%; border: 0; }
.mobile-menu-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; border-bottom: 1px solid var(--ic-separator); color: var(--ic-text); font-size: 13px; }

@media (max-width: 760px) {
  .shell {
    flex-direction: column;
    overflow: hidden;
  }
}

/* UI-13: Sitzungsprüfung im gemeinsamen Zugangsrahmen. */
.boot-line { display: flex; align-items: center; gap: 10px; margin: 0; color: var(--ic-text-2); font: 400 14px/20px var(--ic-font); }
.boot-spinner { width: 14px; height: 14px; flex: none; border: 2px solid var(--ic-separator); border-top-color: var(--ic-primary); border-radius: 50%; animation: boot-spin .8s linear infinite; }
@keyframes boot-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .boot-spinner { animation: none; } }
</style>
