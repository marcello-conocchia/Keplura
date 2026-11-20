<script setup lang="ts">
/**
 * Team (UI-12, Katalogstil wie Administration): eigene Organisationen mit Rolle,
 * darunter Mitglieder/Einladungen der ausgewählten Organisation (MembersPanel).
 * Reine Darstellung: gleiche Store-/API-Aufrufe, Rechte entscheidet der Server.
 * Alle data-testids der E2E-Läufe bleiben erhalten.
 */
import { computed, onMounted, ref } from "vue";
import { useAuthStore } from "../auth-store";
import { ORG_NAME_MAX_LENGTH } from "../api";
import PageContainer from "./layout/PageContainer.vue";
import PageHeader from "./layout/PageHeader.vue";
import MembersPanel from "./MembersPanel.vue";
import AppDialog from "./ui/AppDialog.vue";
import AppIcon from "./ui/AppIcon.vue";
import Input from "./ui/Input.vue";
import StatusPill from "./ui/StatusPill.vue";
import Button from "./ui/Button.vue";
import ActionMenu from "./ui/ActionMenu.vue";
import ConfirmDialog from "./ui/ConfirmDialog.vue";
import { deleteOrg, manageErrorText, renameOrg } from "../api-manage";
import type { Organization } from "../api";

const auth = useAuthStore();

const note = ref("");
const noteKind = ref<"ok" | "error" | "">("");
function show(kind: "ok" | "error", text: string) { noteKind.value = kind; note.value = text; }

/** Kürzel wie im Seitenleisten-Avatar: zwei Wörter → je ein Buchstabe, sonst die ersten zwei. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[1][0]).toUpperCase();
}

onMounted(() => {
  // Nach Login hat der Store die Organisationen schon; deckt Neustart/Wiederherstellung ab.
  if (auth.orgs.length === 0 && !auth.orgsError) void auth.loadOrgs();
});

/* ---------- Anlegen ---------- */
const orgFormOpen = ref(false);
const orgName = ref("");
const creating = ref(false);
const orgError = ref("");
const createDialog = ref<InstanceType<typeof AppDialog> | null>(null);
const canCreate = computed(() => orgName.value.trim() !== "" && !creating.value);

function openOrgForm() {
  orgName.value = "";
  orgError.value = "";
  note.value = "";
  orgFormOpen.value = true;
}

async function submitCreate() {
  if (!canCreate.value) return;
  creating.value = true;
  orgError.value = "";
  const outcome = await auth.createOrg(orgName.value.trim());
  creating.value = false;
  switch (outcome.kind) {
    case "created":
      orgName.value = "";
      orgFormOpen.value = false;
      show("ok", `Organisation „${outcome.org.name}“ angelegt. Du verwaltest sie.`);
      break;
    case "invalid-name":
      orgError.value = `Name ungültig. Bitte 1–${ORG_NAME_MAX_LENGTH} Zeichen.`;
      break;
    case "expired":
      orgError.value = "Sitzung abgelaufen. Bitte erneut anmelden.";
      break;
    case "rate-limited":
      orgError.value = "Zu viele Anfragen. Bitte später erneut versuchen"
        + (outcome.retryAfterSeconds ? ` (ca. ${outcome.retryAfterSeconds}s warten).` : ".");
      break;
    case "error":
      orgError.value = "Organisation konnte nicht angelegt werden. Bitte später erneut versuchen.";
      break;
  }
}

/* ---------- Umbenennen ---------- */
const renameTarget = ref<Organization | null>(null);
const renameValue = ref("");
const renameError = ref("");
const renaming = ref(false);
const renameDialog = ref<InstanceType<typeof AppDialog> | null>(null);
const renameDirty = computed(() => renameTarget.value !== null && renameValue.value.trim() !== renameTarget.value.name);

function openRename(org: Organization) {
  renameTarget.value = org;
  renameValue.value = org.name;
  renameError.value = "";
  note.value = "";
}
async function submitRename() {
  const org = renameTarget.value;
  if (!org || renaming.value || renameValue.value.trim() === "") return;
  renaming.value = true;
  renameError.value = "";
  const r = await renameOrg(org.id, renameValue.value.trim());
  renaming.value = false;
  if (r.kind === "ok") {
    renameTarget.value = null;
    show("ok", "Organisation umbenannt.");
    await auth.loadOrgs();
  } else if (r.kind === "expired") {
    renameTarget.value = null;
    auth.dropExpiredSession();
  } else {
    renameError.value = manageErrorText(r.kind);
  }
}

/* ---------- Löschen ---------- */
const deleteTarget = ref<Organization | null>(null);
const deleting = ref(false);
const deleteError = ref("");
function askDelete(org: Organization) {
  deleteError.value = "";
  note.value = "";
  deleteTarget.value = org;
}
async function confirmDelete() {
  const org = deleteTarget.value;
  if (!org || deleting.value) return;
  deleting.value = true;
  const r = await deleteOrg(org.id);
  deleting.value = false;
  if (r.kind === "ok" || r.kind === "not-found") {
    deleteTarget.value = null;
    show("ok", `Organisation „${org.name}“ gelöscht.`);
    await auth.loadOrgs();
    await auth.loadKnowledge();
  } else if (r.kind === "expired") {
    deleteTarget.value = null;
    auth.dropExpiredSession();
  } else {
    deleteError.value = manageErrorText(r.kind);
  }
}

function rowAction(org: Organization, action: string) {
  if (action === "rename") openRename(org);
  else if (action === "delete") askDelete(org);
}
</script>

<template>
  <PageContainer width="narrow">
    <template #header>
      <PageHeader title="Team" description="Organisationen, Mitglieder und Einladungen" />
    </template>
    <section class="connection-page team" data-testid="team-view">
      <section class="connection-section" aria-labelledby="team-orgs-title">
        <header class="connection-header">
          <div class="connection-heading">
            <h2 id="team-orgs-title">Deine Organisationen <span class="team-count ic-num" data-testid="org-count">{{ auth.orgs.length }}</span></h2>
            <p class="connection-description">Wähle aus, in welcher Organisation du arbeitest. Wissen, Chats und Mitglieder gehören immer zu genau einer Organisation.</p>
          </div>
          <div class="connection-actions">
            <Button variant="primary" data-testid="org-create-new" @click="openOrgForm()">
              <AppIcon name="plus" :size="16" />Organisation anlegen
            </Button>
          </div>
        </header>

        <p v-if="note" class="connection-note" :class="noteKind" :role="noteKind === 'error' ? 'alert' : 'status'" data-testid="org-create-toast">{{ note }}</p>

        <div v-if="auth.orgs.length" class="connection-list" role="list" data-testid="org-list">
          <article
            v-for="org in auth.orgs"
            :key="org.id"
            class="connection-record team-row"
            :class="{ current: org.id === auth.selectedOrgId }"
            role="listitem"
            data-testid="org-item"
          >
            <span class="team-logo" aria-hidden="true">{{ initials(org.name) }}</span>
            <div class="connection-heading">
              <h3>{{ org.name }}</h3>
              <p class="connection-meta">{{ org.role === "OWNER" ? "Du verwaltest diese Organisation" : "Du bist Mitglied" }}</p>
            </div>
            <div class="connection-actions">
              <StatusPill :tone="org.role === 'OWNER' ? 'ok' : 'neutral'" data-testid="org-role">{{ org.role === "OWNER" ? "Verwalter:in" : "Mitglied" }}</StatusPill>
              <StatusPill v-if="org.id === auth.selectedOrgId" tone="info" data-testid="org-current">Ausgewählt</StatusPill>
              <Button v-else size="compact" :data-testid="`org-select-${org.id}`" @click="auth.selectOrg(org.id)">Auswählen</Button>
              <ActionMenu
                v-if="org.role === 'OWNER'"
                :label="`Aktionen für ${org.name}`"
                :items="[
                  { id: 'rename', label: 'Umbenennen', testId: `org-rename-${org.id}` },
                  { id: 'delete', label: 'Organisation löschen', danger: true, testId: `org-delete-${org.id}` },
                ]"
                @select="(a) => rowAction(org, a)"
              />
            </div>
          </article>
        </div>
        <div v-else-if="auth.orgsError" class="connection-note error connection-error" role="alert" data-testid="org-error">
          <p>Organisationen konnten nicht geladen werden.</p>
          <Button size="compact" data-testid="org-retry" @click="auth.loadOrgs()">Erneut versuchen</Button>
        </div>
        <p v-else class="connection-empty" data-testid="org-empty">
          Du gehörst noch zu keiner Organisation. Lege eine an oder löse unten einen Einladungscode ein.
        </p>
      </section>

      <MembersPanel />
    </section>

    <AppDialog ref="createDialog" :open="orgFormOpen" title="Organisation anlegen" :error="orgError" :busy="creating" :dirty="orgName.trim() !== ''" @close="orgFormOpen = false">
      <form class="connection-form" data-testid="org-create-form" @submit.prevent="submitCreate">
        <Input id="org-name" v-model="orgName" label="Name der Organisation" data-testid="org-create-name" :maxlength="ORG_NAME_MAX_LENGTH" autocomplete="off" :disabled="creating" required />
        <p class="connection-hint">Du wirst automatisch Verwalter:in und kannst danach Mitglieder hinzufügen oder einladen.</p>
        <div class="connection-submit">
          <Button variant="ghost" type="button" @click="createDialog?.requestClose()">Abbrechen</Button>
          <Button type="submit" variant="primary" data-testid="org-create-submit" :busy="creating" :disabled="!canCreate">Anlegen</Button>
        </div>
      </form>
    </AppDialog>

    <AppDialog ref="renameDialog" :open="renameTarget !== null" title="Organisation umbenennen" :error="renameError" :busy="renaming" :dirty="renameDirty" @close="renameTarget = null">
      <form class="connection-form" data-testid="org-rename-form" @submit.prevent="submitRename">
        <Input id="org-rename" v-model="renameValue" label="Neuer Name" data-testid="org-rename-name" :maxlength="ORG_NAME_MAX_LENGTH" autocomplete="off" :disabled="renaming" required />
        <div class="connection-submit">
          <Button variant="ghost" type="button" @click="renameDialog?.requestClose()">Abbrechen</Button>
          <Button type="submit" variant="primary" data-testid="org-rename-save" :busy="renaming" :disabled="renameValue.trim() === '' || !renameDirty">Speichern</Button>
        </div>
      </form>
    </AppDialog>

    <ConfirmDialog
      :open="deleteTarget !== null"
      title="Organisation löschen?"
      :message="`„${deleteTarget?.name ?? ''}“ wird mit allen Arbeitsbereichen, Wissensbasen, Dokumenten, Chats und Einladungen endgültig gelöscht. Das lässt sich nicht rückgängig machen. Benutzerkonten bleiben erhalten.`"
      confirm-label="Endgültig löschen"
      :busy="deleting"
      :error="deleteError"
      @confirm="confirmDelete"
      @close="deleteTarget = null"
    />
  </PageContainer>
</template>

<style scoped>
.team { padding-top: 8px; gap: 40px; }
.team-count { margin-left: 6px; color: var(--ic-text-3); font-weight: 400; }
.team-row { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 16px 0; }
.team-row:first-child { padding-top: 0; }
.team-row > .connection-heading { flex: 1 1 200px; }
.team-logo { display: inline-grid; place-items: center; width: 32px; height: 32px; flex: none; border-radius: 8px; background: var(--ic-hover-tint); color: var(--ic-text-2); font: 700 12px/1 var(--ic-font); }
.team-row.current .team-logo { background: var(--ic-primary); color: var(--ic-on-primary); }
</style>
