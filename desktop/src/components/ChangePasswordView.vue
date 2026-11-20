<script setup lang="ts">
/**
 * PILOT 4e — Pflicht-Passwortwechsel nach einem Startpasswort.
 * Erscheint statt der App, bis ein eigenes Passwort gesetzt ist.
 * UI-13: gleicher Rahmen wie die Anmeldung (AccessShell), gemeinsame Felder;
 * Regeln, API-Aufruf und Testanker (cp-*) unverändert.
 */
import { computed, onBeforeUnmount, ref } from "vue";
import { useAuthStore } from "../auth-store";
import { changePassword, manageErrorText } from "../api-manage";
import AccessShell from "./layout/AccessShell.vue";
import Button from "./ui/Button.vue";
import Input from "./ui/Input.vue";

const auth = useAuthStore();
const current = ref("");
const next = ref("");
const repeat = ref("");
const busy = ref(false);
const error = ref("");

// Passwörter nie länger als nötig im Speicher halten.
onBeforeUnmount(() => { current.value = next.value = repeat.value = ""; });

const rules = computed(() => [
  { ok: next.value.length >= 12, text: "Mindestens 12 Zeichen" },
  { ok: next.value.length > 0 && next.value !== current.value, text: "Anders als das Startpasswort" },
  { ok: next.value.length > 0 && next.value === repeat.value, text: "Beide Eingaben stimmen überein" },
]);
const canSubmit = computed(() => current.value !== "" && rules.value.every((r) => r.ok) && !busy.value);

async function submit() {
  if (!canSubmit.value) return;
  busy.value = true;
  error.value = "";
  const r = await changePassword(current.value, next.value);
  busy.value = false;
  if (r.kind === "ok") {
    current.value = next.value = repeat.value = "";
    auth.passwordChanged();
    return;
  }
  if (r.kind === "expired") {
    auth.dropExpiredSession();
    return;
  }
  error.value =
    r.kind === "invalid"
      ? "Das Startpasswort stimmt nicht, oder das neue Passwort erfüllt die Regeln nicht."
      : manageErrorText(r.kind);
}
</script>

<template>
  <AccessShell
    data-testid="change-password-view"
    title="Eigenes Passwort festlegen"
    :intro="`Hallo ${auth.user?.displayName ?? ''}, du hast dich mit einem Startpasswort angemeldet. Lege jetzt ein eigenes Passwort fest – danach geht es direkt weiter.`"
  >
    <form class="cp-form" @submit.prevent="submit">
      <Input id="cp-current" v-model="current" label="Startpasswort" type="password" autocomplete="current-password" data-testid="cp-current" :disabled="busy" required />
      <Input id="cp-new" v-model="next" label="Neues Passwort" type="password" autocomplete="new-password" data-testid="cp-new" :disabled="busy" required />
      <Input id="cp-repeat" v-model="repeat" label="Neues Passwort wiederholen" type="password" autocomplete="new-password" data-testid="cp-repeat" :disabled="busy" required />

      <ul class="cp-rules" aria-label="Passwortregeln">
        <li v-for="r in rules" :key="r.text" :class="{ ok: r.ok }">
          <span class="cp-rule-mark" aria-hidden="true">{{ r.ok ? "✓" : "" }}</span>
          {{ r.text }}<span class="sr-only">{{ r.ok ? " – erfüllt" : " – noch offen" }}</span>
        </li>
      </ul>

      <p v-if="error" class="connection-note error" role="alert" data-testid="cp-error">{{ error }}</p>

      <div class="cp-actions">
        <Button variant="ghost" type="button" data-testid="cp-logout" :disabled="busy" @click="auth.logout()">Abmelden</Button>
        <Button variant="primary" type="submit" data-testid="cp-submit" :busy="busy" :disabled="!canSubmit">
          {{ busy ? "Wird gespeichert …" : "Passwort speichern" }}
        </Button>
      </div>
    </form>
  </AccessShell>
</template>

<style scoped>
.cp-form { display: grid; gap: 4px; }
.cp-rules { list-style: none; margin: 4px 0 16px; padding: 0; display: grid; gap: 6px; color: var(--ic-text-2); font: 400 13px/20px var(--ic-font); }
.cp-rules li { display: flex; align-items: center; gap: 8px; }
.cp-rules li.ok { color: var(--ic-status-ok); }
.cp-rule-mark { display: inline-grid; place-items: center; width: 16px; height: 16px; flex: none; border-radius: 50%; box-shadow: 0 0 0 1px var(--ic-separator); font-size: 11px; }
.cp-rules li.ok .cp-rule-mark { background: var(--ic-status-ok-bg); box-shadow: none; }
.cp-form .connection-note { margin-bottom: 12px; }
.cp-actions { display: flex; justify-content: space-between; gap: 8px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
</style>
