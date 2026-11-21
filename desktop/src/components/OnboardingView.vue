<script setup lang="ts">
/**
 * PILOT 4e — Erste Schritte für den ersten Admin.
 * Erscheint im Chat-Bereich, solange Organisation, Arbeitsbereich oder
 * Wissensbasis fehlen. Jeder Schritt ist direkt hier erledigbar; die
 * Fortschrittsanzeige leitet sich nur aus dem echten Datenstand ab.
 * UI-13: Katalogstil, sichtbare Feldbeschriftungen, Fehler am aktiven Schritt,
 * Fokus wandert zum nächsten offenen Schritt. Testanker onboarding-* unverändert.
 */
import { computed, nextTick, ref, watch } from "vue";
import { useAuthStore } from "../auth-store";
import { KB_NAME_MAX_LENGTH, ORG_NAME_MAX_LENGTH, SPACE_NAME_MAX_LENGTH } from "../api";
import type { AppView } from "../navigation";
import Button from "./ui/Button.vue";
import Input from "./ui/Input.vue";
import { tr } from "../i18n";

const emit = defineEmits<{ navigate: [AppView] }>();
const auth = useAuthStore();

const orgName = ref("");
const spaceName = ref("");
const kbName = ref("");
const busy = ref(false);
const error = ref("");

const hasOrg = computed(() => auth.orgs.length > 0);
const hasSpace = computed(() => auth.spaces.length > 0);
const hasKb = computed(() => auth.kbs.length > 0);
const current = computed(() => (!hasOrg.value ? 1 : !hasSpace.value ? 2 : !hasKb.value ? 3 : 4));

// Nach einem erledigten Schritt direkt im nächsten Feld weiterschreiben können.
// Erst fokussieren, wenn der Vorgang fertig ist (vorher ist das Feld gesperrt).
let focusedStep = current.value;
watch(current, () => { error.value = ""; });
watch([current, busy], async ([step, isBusy]) => {
  if (isBusy || step === focusedStep) return;
  focusedStep = step;
  await nextTick();
  const id = step === 2 ? "ob-space" : step === 3 ? "ob-kb" : null;
  if (id) (document.getElementById(id) as HTMLInputElement | null)?.focus();
});

const firstName = computed(() => (auth.user?.displayName ?? "").split(/\s+/)[0]);

function failText(kind: string, what: string, max: number): string {
  switch (kind) {
    case "invalid-name": return tr("Bitte einen Namen mit 1 bis {0} Zeichen eingeben.", max);
    case "duplicate-name": return tr("{0} mit diesem Namen gibt es bereits.", what);
    case "expired": return tr("Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.");
    case "rate-limited": return tr("Gerade sind zu viele Anfragen unterwegs. Bitte versuche es gleich noch einmal.");
    default: return tr("Das hat nicht geklappt. Bitte versuche es erneut.");
  }
}

async function createOrg() {
  if (!orgName.value.trim() || busy.value) return;
  busy.value = true;
  error.value = "";
  const r = await auth.createOrg(orgName.value.trim());
  if (r.kind === "created") {
    orgName.value = "";
    await auth.loadKnowledge();
  } else {
    error.value = failText(r.kind, tr("Eine Organisation"), ORG_NAME_MAX_LENGTH);
  }
  busy.value = false;
}

async function createSpace() {
  if (!spaceName.value.trim() || busy.value) return;
  busy.value = true;
  error.value = "";
  const r = await auth.createSpace(spaceName.value.trim());
  if (r.kind === "created") spaceName.value = "";
  else error.value = failText(r.kind, tr("Einen Arbeitsbereich"), SPACE_NAME_MAX_LENGTH);
  busy.value = false;
}

async function createKb() {
  if (!kbName.value.trim() || busy.value) return;
  busy.value = true;
  error.value = "";
  const r = await auth.createKnowledgeBase(kbName.value.trim());
  if (r.kind === "created") {
    kbName.value = "";
    // nächster sinnvoller Schritt: Dokumente hochladen
    emit("navigate", "wissen");
  } else {
    error.value = failText(r.kind, tr("Eine Wissensbasis"), KB_NAME_MAX_LENGTH);
  }
  busy.value = false;
}
</script>

<template>
  <main class="ob" data-testid="onboarding-view">
    <div class="ob-inner">
      <header class="ob-head">
        <p class="ob-kicker">{{ $t("Erste Schritte · {0} von 3", Math.min(current, 3)) }}</p>
        <h1 class="ob-title">{{ $t("Willkommen{0}", firstName ? `, ${firstName}` : "") }}</h1>
        <p class="ob-lead">{{ $t("In drei kurzen Schritten ist Keplura für dein Team bereit. Namen kannst du später jederzeit ändern.") }}</p>
      </header>

      <ol class="ob-steps">
        <li class="ob-step" :class="{ done: hasOrg, active: current === 1 }" data-testid="onboarding-step-org" :aria-current="current === 1 ? 'step' : undefined">
          <span class="ob-num" aria-hidden="true">{{ hasOrg ? "✓" : "1" }}</span>
          <div class="ob-body">
            <h2>{{ $t("Organisation anlegen") }}</h2>
            <p v-if="hasOrg" class="ob-done">{{ $t("„{0}“ ist angelegt.", auth.selectedOrg?.name ?? auth.orgs[0]?.name) }}</p>
            <template v-else>
              <p class="ob-text">{{ $t("Meist der Firmenname, zum Beispiel „Muster GmbH“. Du wirst automatisch Verwalter:in.") }}</p>
              <form class="ob-form" @submit.prevent="createOrg">
                <Input id="ob-org" v-model="orgName" :label="$t('Name der Organisation')" :maxlength="ORG_NAME_MAX_LENGTH" autocomplete="organization" data-testid="onboarding-org-name" :disabled="busy" />
                <Button variant="primary" type="submit" :busy="busy" :disabled="!orgName.trim()" data-testid="onboarding-org-submit">{{ $t("Anlegen") }}</Button>
              </form>
              <p v-if="error" class="connection-note error" role="alert" data-testid="onboarding-error">{{ error }}</p>
            </template>
          </div>
        </li>

        <li class="ob-step" :class="{ done: hasSpace, active: current === 2 }" data-testid="onboarding-step-space" :aria-current="current === 2 ? 'step' : undefined">
          <span class="ob-num" aria-hidden="true">{{ hasSpace ? "✓" : "2" }}</span>
          <div class="ob-body">
            <h2>{{ $t("Arbeitsbereich anlegen") }}</h2>
            <p v-if="hasSpace" class="ob-done">{{ $t("„{0}“ ist angelegt.", auth.selectedSpace?.name) }}</p>
            <template v-else>
              <p class="ob-text">{{ $t("Ein Arbeitsbereich bündelt Wissen und Chats eines Teams, zum Beispiel „Vertrieb“.") }}</p>
              <template v-if="current === 2">
                <form class="ob-form" @submit.prevent="createSpace">
                  <Input id="ob-space" v-model="spaceName" :label="$t('Name des Arbeitsbereichs')" :maxlength="SPACE_NAME_MAX_LENGTH" autocomplete="off" data-testid="onboarding-space-name" :disabled="busy" />
                  <Button variant="primary" type="submit" :busy="busy" :disabled="!spaceName.trim()" data-testid="onboarding-space-submit">{{ $t("Anlegen") }}</Button>
                </form>
                <p v-if="error" class="connection-note error" role="alert" data-testid="onboarding-error">{{ error }}</p>
              </template>
            </template>
          </div>
        </li>

        <li class="ob-step" :class="{ done: hasKb, active: current === 3 }" data-testid="onboarding-step-kb" :aria-current="current === 3 ? 'step' : undefined">
          <span class="ob-num" aria-hidden="true">{{ hasKb ? "✓" : "3" }}</span>
          <div class="ob-body">
            <h2>{{ $t("Erste Wissensbasis anlegen") }}</h2>
            <p v-if="hasKb" class="ob-done">{{ $t("„{0}“ ist angelegt.", auth.selectedKb?.name ?? auth.kbs[0]?.name) }}</p>
            <template v-else>
              <p class="ob-text">{{ $t("Eine Sammlung zusammengehöriger Dokumente, zum Beispiel „Handbücher“ oder „Richtlinien“.") }}</p>
              <template v-if="current === 3">
                <form class="ob-form" @submit.prevent="createKb">
                  <Input id="ob-kb" v-model="kbName" :label="$t('Name der Wissensbasis')" :maxlength="KB_NAME_MAX_LENGTH" autocomplete="off" data-testid="onboarding-kb-name" :disabled="busy" />
                  <Button variant="primary" type="submit" :busy="busy" :disabled="!kbName.trim()" data-testid="onboarding-kb-submit">{{ $t("Anlegen") }}</Button>
                </form>
                <p v-if="error" class="connection-note error" role="alert" data-testid="onboarding-error">{{ error }}</p>
              </template>
            </template>
          </div>
        </li>
      </ol>

      <section class="ob-next" aria-labelledby="ob-next-title">
        <h2 id="ob-next-title">{{ $t("Danach") }}</h2>
        <ul class="ob-next-list">
          <li data-testid="onboarding-step-docs">
            <strong>{{ $t("Dokumente hochladen") }}</strong>
            <span>{{ $t("PDF, Word, Excel, PowerPoint, Text, Markdown oder CSV. Nach Schritt 3 öffnet sich „Wissen“ automatisch, dort lädst du sie hoch.") }}</span>
          </li>
          <li data-testid="onboarding-step-team">
            <strong>{{ $t("Kolleginnen und Kollegen hinzufügen") }}</strong>
            <span>{{ $t("Unter „Team“ legst du Zugänge an oder erstellst Einladungscodes.") }}</span>
          </li>
        </ul>
      </section>
    </div>
  </main>
</template>

<style scoped>
.ob { flex: 1; min-width: 0; overflow: auto; background: var(--ic-content); }
.ob-inner { display: grid; gap: 32px; max-width: 640px; margin: 0 auto; padding: 56px 24px; }
.ob-kicker { margin: 0 0 8px; color: var(--ic-text-3); font: 500 12px/16px var(--ic-font); letter-spacing: .02em; }
.ob-title { margin: 0; color: var(--ic-text); font: 600 26px/32px var(--ic-font); letter-spacing: -.02em; }
.ob-lead { margin: 8px 0 0; color: var(--ic-text-2); font: 400 14px/20px var(--ic-font); }
.ob-steps { list-style: none; margin: 0; padding: 0; display: grid; }
.ob-step { display: flex; gap: 16px; padding: 20px 0; border-bottom: 1px solid var(--ic-separator-faint); color: var(--ic-text-3); }
.ob-step:first-child { padding-top: 0; }
.ob-step.active, .ob-step.done { color: var(--ic-text); }
.ob-num { display: inline-grid; place-items: center; width: 28px; height: 28px; flex: none; border-radius: 50%; box-shadow: 0 0 0 1px var(--ic-separator); font: 600 13px/1 var(--ic-font); }
.ob-step.active .ob-num { background: var(--ic-primary); color: var(--ic-on-primary); box-shadow: none; }
.ob-step.done .ob-num { background: var(--ic-status-ok-bg); color: var(--ic-status-ok); box-shadow: none; }
.ob-body { flex: 1; min-width: 0; display: grid; gap: 8px; }
.ob-body h2 { margin: 0; font: 500 16px/28px var(--ic-font); }
.ob-text { margin: 0; color: var(--ic-text-2); font: 400 13px/20px var(--ic-font); }
.ob-step:not(.active):not(.done) .ob-text { color: var(--ic-text-3); }
.ob-done { margin: 0; color: var(--ic-status-ok); font: 400 13px/20px var(--ic-font); }
.ob-form { display: flex; align-items: flex-end; gap: 8px; flex-wrap: wrap; margin-top: 4px; }
.ob-form > :first-child { flex: 1; min-width: 220px; }
.ob-form :deep(.ic-field) { margin: 0; }
.ob-next { display: grid; gap: 12px; }
.ob-next h2 { margin: 0; color: var(--ic-text-2); font: 500 13px/20px var(--ic-font); }
.ob-next-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; }
.ob-next-list li { display: grid; gap: 2px; }
.ob-next-list strong { color: var(--ic-text); font: 500 14px/20px var(--ic-font); }
.ob-next-list span { color: var(--ic-text-2); font: 400 13px/20px var(--ic-font); }
@media (max-width: 480px) { .ob-inner { padding: 32px 16px; } }
</style>
