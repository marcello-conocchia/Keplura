<script setup lang="ts">
import { computed, ref } from "vue";
import { useAuthStore } from "../auth-store";
import { useThemeStore } from "../theme-store";
import { toggleFullscreen, useFullscreen } from "../fullscreen";
import { serverUrl } from "../api";
import AppIcon from "./ui/AppIcon.vue";
import Button from "./ui/Button.vue";
import Input from "./ui/Input.vue";
import AccessShell from "./layout/AccessShell.vue";

/**
 * Login form (M2d, restyled UI-1 to DESIGN.md mockup panel 1): centred card
 * on the canvas foil, shield mark, focus-ring inputs, connection line at the
 * bottom. Logic is UNCHANGED — auth-store + outcomes drive everything; error
 * copy is deliberately coarse (ADR-0003 anti-enumeration) and every state
 * keeps its stable data-testid.
 *
 * UI-REFRESH-1 (Phase 1): die Flaeche laeuft jetzt auf dem shadcn-vue-
 * Grundgeruest (ui/Button, ui/Input auf reka-ui) mit Tailwind-Utilities
 * ueber den --ic-*-Tokens; das scoped CSS ist ersatzlos weg, jede Regel
 * lebt 1:1 als Utility weiter (Vorher/Nachher-Pixelvergleich = Belegpfad
 * der Karte). Kein Dialog, kein Dropdown auf dieser Flaeche — deshalb
 * keine solchen Primitives.
 *
 * UI-13: Rahmen jetzt die gemeinsame AccessShell (wie Passwortwechsel und
 * Sitzungsprüfung); Meldungen als Hinweisfläche mit role=alert. Logik unverändert.
 *
 * AT-SPI/QA anchors that must not drift (e2e/packaged_login_e2e.py):
 * - the submit push button's name is exactly "Anmelden" (idle label),
 * - the two entries carry accessible names "E-Mail" / "Passwort" via <label for>.
 */
const auth = useAuthStore();
// DARK-FS: the login screen offers the same appearance + fullscreen controls
// as the sidebar (the sidebar only exists once signed in). Same stores —
// one shared truth, no second implementation.
const theme = useThemeStore();
const { isFullscreen } = useFullscreen();

const email = ref("");
const password = ref("");
const rememberMe = ref(false);
const message = ref("");
const messageKind = ref<"error" | "warn" | "">("");
/** Instance of the Input component — only the exposed focus() is used. */
const emailRef = ref<{ focus: () => void } | null>(null);

const OFFLINE_TEXT = "Server nicht erreichbar. Bitte Verbindung prüfen.";
/** Kein doppelter Hinweis: steht oben schon „nicht erreichbar“, entfällt die gleichlautende Meldung. */
const showMessage = computed(() => message.value !== "" && !(props.status === "unreachable" && message.value === OFFLINE_TEXT));

const canSubmit = computed(() => email.value.trim() !== "" && password.value !== "");

/** Server probe state for the footer line: passed down by App.vue. */
const props = defineProps<{
  status: "checking" | "connected" | "unreachable";
}>();

/** Re-runs the server probe in App.vue (kept from the old status card). */
const emit = defineEmits<{ retry: [] }>();

/** User-facing connection state stays German on browser and desktop. */
const statusLabel = computed(() =>
  props.status === "connected"
    ? "Verbunden"
    : props.status === "unreachable"
      ? "Keine Verbindung"
      : "Verbindung wird geprüft …",
);

/** Origin part of the configured server URL for the quiet footer line. */
const serverHost = computed(() => {
  try {
    return new URL(serverUrl).host;
  } catch {
    return serverUrl;
  }
});

/** DARK-FS: mode label + icon for the cycle button (shared with sidebar). */
const themeModeLabel = computed(
  () =>
    theme.mode === "light"
      ? "Hell"
      : theme.mode === "dark"
        ? "Dunkel"
        : "System",
);
const themeIcon = computed(() =>
  theme.mode === "light"
    ? ("sun" as const)
    : theme.mode === "dark"
      ? ("moon" as const)
      : ("auto" as const),
);

async function submit() {
  if (!canSubmit.value || auth.busy) {
    return;
  }
  message.value = "";
  messageKind.value = "";
  const outcome = await auth.login(email.value.trim(), password.value, rememberMe.value);
  switch (outcome.kind) {
    case "ok":
      // Success: the parent swaps to the session view; nothing to say here.
      password.value = "";
      break;
    case "invalid":
      messageKind.value = "error";
      message.value = "Anmeldung fehlgeschlagen. Bitte E-Mail bzw. Benutzername und Passwort prüfen.";
      break;
    case "rate-limited":
      messageKind.value = "warn";
      message.value =
        "Zu viele Versuche. Bitte später erneut anmelden" +
        (outcome.retryAfterSeconds
          ? ` (ca. ${Math.ceil(outcome.retryAfterSeconds / 60)} Minute(n) warten).`
          : ".");
      break;
    case "offline":
      messageKind.value = "error";
      message.value = OFFLINE_TEXT;
      break;
    case "server-error":
      messageKind.value = "error";
      message.value = "Anmeldung derzeit nicht möglich. Bitte später erneut versuchen.";
      break;
  }
}

// Focus the email field as soon as the form is visible (window opens on login).
setTimeout(() => emailRef.value?.focus(), 0);

// ---- OIDC-M2-Tail (docs/adr/0009 §10) --------------------------------------
/** Server says an active corporate IdP exists → the secondary button shows.
 *  Fail-closed by construction: any probe error leaves oidcEnabled=false. */
const oidcActive = computed(() => auth.oidc.oidcEnabled);

/** Top-level navigation into the server's begin endpoint (browser handles
 *  the IdP leg and returns to us with #oidc=<handoff> / #oidc-error). */
function startOidc() {
  if (!auth.busy) {
    auth.beginOidcLogin(rememberMe.value);
  }
}
</script>

<template>
  <AccessShell
    data-testid="login-card"
    title="Willkommen im Teamwissen"
    intro="Melde dich mit deinen Zugangsdaten an, um Dokumente zu finden und Fragen zu stellen."
  >
    <div v-if="status === 'unreachable'" class="connection-note error login-offline" role="alert" data-testid="login-offline">
      <p>Der Keplura-Server ist gerade nicht erreichbar. Prüfe die Verbindung und versuche es erneut.</p>
      <Button size="compact" data-testid="connection-retry" @click="emit('retry')">Erneut versuchen</Button>
    </div>

    <form class="login-form" data-testid="login-form" @submit.prevent="submit">
      <Input
        id="login-email"
        ref="emailRef"
        v-model="email"
        label="E-Mail oder Benutzername"
        type="text"
        data-testid="login-email"
        autocomplete="username"
        required
      />
      <Input
        id="login-password"
        v-model="password"
        label="Passwort"
        type="password"
        data-testid="login-password"
        autocomplete="current-password"
        required
      />

      <label class="login-remember">
        <input v-model="rememberMe" type="checkbox" data-testid="login-remember-me" :disabled="auth.busy" />
        Angemeldet bleiben
      </label>

      <p
        v-if="showMessage"
        class="connection-note"
        :class="messageKind === 'error' ? 'error' : 'warn'"
        role="alert"
        data-testid="login-message"
      >{{ message }}</p>

      <Button
        type="submit"
        variant="primary"
        class="login-submit"
        data-testid="login-submit"
        :disabled="!canSubmit || auth.busy"
      >
        {{ auth.busy ? "Anmelden…" : "Anmelden" }}
      </Button>
    </form>

    <!-- OIDC (ADR-0009 §10): zweiter Anmeldeweg, nur wenn der Server einen aktiven
         Firmen-IdP meldet (Capabilities-Abfrage, schließt bei Fehler). -->
    <div v-if="oidcActive" class="login-oidc">
      <p v-if="auth.oidcFailed" class="connection-note error" role="alert" data-testid="login-oidc-message">
        Anmeldung über das Firmenkonto fehlgeschlagen. Bitte erneut versuchen oder mit E-Mail und Passwort anmelden.
      </p>
      <div aria-hidden="true" class="login-or"><span></span>oder<span></span></div>
      <Button variant="outline" class="login-submit" data-testid="login-oidc-button" :disabled="auth.busy" @click="startOidc">
        Mit Firmenkonto anmelden
      </Button>
    </div>

    <template #footer>
      <div class="login-foot">
        <p class="login-status">
          <i aria-hidden="true" class="login-dot" :class="status"></i>
          <span data-testid="connection-status" :title="serverHost">{{ statusLabel }}</span>
        </p>
        <span class="login-tools">
          <!-- DARK-FS: Darstellung und Vollbild schon vor der Anmeldung erreichbar. -->
          <Button
            variant="icon"
            size="compact"
            data-testid="theme-toggle"
            :title="`Darstellung: ${themeModeLabel}`"
            :aria-label="`Darstellung wechseln, aktuell ${themeModeLabel}`"
            @click="theme.cycle()"
          ><AppIcon :name="themeIcon" :size="15" /></Button>
          <Button
            variant="icon"
            size="compact"
            data-testid="fullscreen-toggle"
            :title="isFullscreen ? 'Vollbild verlassen (Escape)' : 'Vollbild (F11)'"
            :aria-label="isFullscreen ? 'Vollbild verlassen' : 'Vollbild aktivieren'"
            @click="void toggleFullscreen()"
          ><AppIcon :name="isFullscreen ? 'compress' : 'expand'" :size="15" /></Button>
        </span>
      </div>
    </template>
  </AccessShell>
</template>

<style scoped>
.login-offline { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
.login-offline p { margin: 0; }
.login-form { display: grid; gap: 4px; }
.login-remember { display: flex; align-items: center; gap: 8px; min-height: 32px; margin-bottom: 12px; color: var(--ic-text-2); font: 400 13px/20px var(--ic-font); cursor: pointer; }
.login-form .connection-note { margin-bottom: 12px; }
.login-submit { width: 100%; justify-content: center; }
.login-oidc { display: grid; gap: 12px; margin-top: 20px; }
.login-or { display: flex; align-items: center; gap: 10px; color: var(--ic-text-3); font: 400 12px/1 var(--ic-font); }
.login-or span { flex: 1; height: 1px; background: var(--ic-separator); }
.login-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.login-status { display: flex; align-items: center; gap: 6px; margin: 0; color: var(--ic-text-3); font: 400 12px/1 var(--ic-font); }
.login-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--ic-text-3); }
.login-dot.connected { background: var(--ic-status-ok); }
.login-dot.checking { background: var(--ic-status-wait); }
.login-dot.unreachable { background: var(--ic-status-error); }
.login-tools { display: flex; gap: 2px; }
</style>
