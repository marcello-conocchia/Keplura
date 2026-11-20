<script setup lang="ts">
/**
 * Mitglieder & Einladungen (SEC-2, Pilot 4e; UI-12 Katalogstil).
 * Verwalter:innen sehen die Mitgliederliste der ausgewählten Organisation, fügen
 * Mitglieder hinzu, erstellen einmalige Einladungscodes (nur EINMAL sichtbar — der
 * Server hält nur den Hash) und ziehen offene Einladungen zurück. JEDE angemeldete
 * Person sieht „Einladungscode einlösen“: so tritt ein Konto ohne Organisation bei.
 * Reine Darstellung; Rollen und Rechte entscheidet ausschließlich der Server.
 * Die data-testids `invite-*` / `member-*` verankern die E2E-Läufe.
 */
import { computed, onMounted, ref, watch } from "vue";
import { useAuthStore } from "../auth-store";
import AppIcon from "./ui/AppIcon.vue";
import Input from "./ui/Input.vue";
import StatusPill from "./ui/StatusPill.vue";
import ConfirmDialog from "./ui/ConfirmDialog.vue";
import Button from "./ui/Button.vue";
import ActionMenu from "./ui/ActionMenu.vue";
import AppDialog from "./ui/AppDialog.vue";
import { addMember, manageErrorText, removeMember } from "../api-manage";
import type { OrgMember } from "../api";

const auth = useAuthStore();

const toast = ref("");
const toastKind = ref<"ok" | "error" | "">("");
/** Wo der Hinweis erscheint: bei der Verwaltung oder direkt am Einlöseformular. */
const toastArea = ref<"manage" | "accept">("manage");
function showToast(kind: "ok" | "error", message: string, area: "manage" | "accept" = "manage") {
  toastKind.value = kind;
  toastArea.value = area;
  toast.value = message;
}

/** Verwalter:in der ausgewählten Organisation → Mitglieder-/Einladungsflächen. */
const isOwner = computed(() => auth.selectedOrg?.role === "OWNER");

/* ---------- Laden ---------- */
const loadFailed = ref(false);
async function reload() {
  // Ohne Organisation oder als Mitglied gibt es nichts zu laden (Listen sind serverseitig
  // nur für Verwalter:innen); das Einlöseformular funktioniert trotzdem.
  loadFailed.value = false;
  if (auth.selectedOrgId === null || !isOwner.value) return;
  const ok = await auth.loadMembersAndInvites();
  if (!ok && auth.user !== null) loadFailed.value = true;
}
onMounted(() => { void reload(); });
// Organisationswechsel: nie Zeilen oder Einmal-Zugangsdaten der vorigen Organisation zeigen.
watch(() => auth.selectedOrgId, () => {
  startCredentials.value = null;
  tokenCopy.value = null;
  // „Beigetreten …“ darf einen durch den Beitritt ausgelösten Wechsel überleben.
  if (toastArea.value === "manage") toast.value = "";
  void reload();
});

/* ---------- Einladungen ---------- */
const creatingInvite = ref(false);
/** Kopierstatus des Einmal-Codes: null = noch nicht versucht. */
const tokenCopy = ref<"ok" | "failed" | null>(null);

async function submitCreateInvite() {
  if (creatingInvite.value || !isOwner.value) return;
  creatingInvite.value = true;
  tokenCopy.value = null;
  try {
    const orgId = auth.selectedOrgId;
    const outcome = await auth.createInvite();
    if (auth.selectedOrgId !== orgId) {
      // Org gewechselt, während die Einladung entstand: der Store hat den Code verworfen.
      return;
    }
    if (outcome.kind === "created") {
      showToast("ok", "Einladung erstellt. Gib den Code jetzt weiter – er wird nur einmal angezeigt.");
    } else if (outcome.kind === "forbidden") {
      showToast("error", "Nur Verwalter:innen können einladen.");
    } else if (outcome.kind === "expired") {
      // der Store hat die Sitzung bereits verworfen
    } else {
      showToast("error", "Einladung konnte nicht erstellt werden.");
    }
  } finally {
    creatingInvite.value = false;
  }
}

async function copyToken() {
  const token = auth.lastInviteToken;
  if (!token) return;
  try {
    await navigator.clipboard.writeText(token);
    tokenCopy.value = "ok";
  } catch {
    // Zwischenablage nicht verfügbar: der Code bleibt markierbar sichtbar.
    tokenCopy.value = "failed";
  }
}

const revoking = ref(new Set<number>());
async function submitRevoke(inviteId: number) {
  if (revoking.value.has(inviteId)) return;
  revoking.value.add(inviteId);
  try {
    const outcome = await auth.revokeInvite(inviteId);
    if (outcome.kind === "revoked") {
      showToast("ok", "Einladung zurückgezogen. Der Code kann nicht mehr eingelöst werden.");
    } else if (outcome.kind === "not-found") {
      showToast("error", "Diese Einladung gibt es nicht mehr.");
      await reload();
    } else if (outcome.kind === "expired") {
      // Sitzung vom Store verworfen
    } else {
      showToast("error", "Die Einladung konnte nicht zurückgezogen werden.");
    }
  } finally {
    revoking.value.delete(inviteId);
  }
}

function expiresLabel(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" });
}
function remaining(iso: string): { label: string; tone: "neutral" | "wait" | "error" } {
  const ms = new Date(iso).getTime() - Date.now();
  if (Number.isNaN(ms)) return { label: "Gültig", tone: "neutral" };
  if (ms <= 0) return { label: "Abgelaufen", tone: "error" };
  const hours = Math.floor(ms / 3_600_000);
  if (hours < 1) return { label: `Noch ${Math.max(1, Math.floor(ms / 60_000))} Min`, tone: "wait" };
  if (hours < 24) return { label: `Noch ${hours} h`, tone: "wait" };
  const days = Math.floor(hours / 24);
  return { label: `Noch ${days} ${days === 1 ? "Tag" : "Tage"}`, tone: "neutral" };
}

/* ---------- Einlösen ---------- */
const acceptToken = ref("");
const accepting = ref(false);
const canAccept = computed(() => acceptToken.value.trim() !== "" && !accepting.value);

async function submitAccept() {
  if (!canAccept.value) return;
  accepting.value = true;
  try {
    const outcome = await auth.acceptInvite(acceptToken.value.trim());
    if (outcome.kind === "accepted") {
      showToast("ok", `Beigetreten: ${outcome.orgName}`, "accept");
      acceptToken.value = "";
      await reload();
    } else if (outcome.kind === "invalid-token") {
      showToast("error", "Dieser Code ist ungültig, abgelaufen oder wurde schon verwendet.", "accept");
    } else if (outcome.kind === "already-member") {
      showToast("error", "Du bist bereits Mitglied dieser Organisation.", "accept");
    } else if (outcome.kind === "expired-session") {
      // Sitzung verworfen; die Anmeldung erscheint wieder
    } else {
      showToast("error", "Der Beitritt hat nicht geklappt. Bitte versuche es erneut.", "accept");
    }
  } finally {
    accepting.value = false;
  }
}

/* ---------- Mitglied entfernen ---------- */
const removeTarget = ref<OrgMember | null>(null);
const removing = ref(false);
const removeError = ref("");
function askRemove(m: OrgMember) {
  removeError.value = "";
  toast.value = "";
  removeTarget.value = m;
}
async function confirmRemove() {
  const m = removeTarget.value;
  if (!m || auth.selectedOrgId === null || removing.value) return;
  removing.value = true;
  const r = await removeMember(auth.selectedOrgId, m.userId);
  removing.value = false;
  if (r.kind === "ok" || r.kind === "not-found") {
    removeTarget.value = null;
    showToast("ok", `${m.displayName} wurde aus der Organisation entfernt.`);
    await reload();
  } else if (r.kind === "expired") {
    removeTarget.value = null;
    auth.dropExpiredSession();
  } else {
    removeError.value = r.kind === "conflict"
      ? "Eine Organisation braucht mindestens eine Verwalterin oder einen Verwalter."
      : manageErrorText(r.kind);
  }
}

/* ---------- Mitglied hinzufügen (legt bei Bedarf ein Konto mit Startpasswort an) ---------- */
const addOpen = ref(false);
const addEmail = ref("");
const addName = ref("");
const adding = ref(false);
const addError = ref("");
const addDialog = ref<InstanceType<typeof AppDialog> | null>(null);
const addDirty = computed(() => addEmail.value.trim() !== "" || addName.value.trim() !== "");
const startCredentials = ref<{ name: string; email: string; password: string; orgName: string } | null>(null);
const credentialsCopy = ref<"ok" | "failed" | null>(null);

function openAdd() {
  addEmail.value = "";
  addName.value = "";
  addError.value = "";
  toast.value = "";
  addOpen.value = true;
}

async function submitAdd() {
  if (auth.selectedOrgId === null || addEmail.value.trim() === "" || adding.value) return;
  adding.value = true;
  addError.value = "";
  const email = addEmail.value.trim();
  // Org fest merken: kommt die Antwort erst nach einem Org-Wechsel, nennt die Box die richtige Organisation.
  const orgName = auth.selectedOrg?.name ?? "";
  const r = await addMember(auth.selectedOrgId, email, addName.value.trim());
  adding.value = false;
  if (r.kind === "ok") {
    addOpen.value = false;
    if (r.data.createdAccount && r.data.startPassword) {
      startCredentials.value = { name: r.data.displayName, email, password: r.data.startPassword, orgName };
      credentialsCopy.value = null;
    } else {
      showToast("ok", `${r.data.displayName} hat bereits ein Konto und ist jetzt Mitglied.`);
    }
    await reload();
    return;
  }
  if (r.kind === "expired") {
    addOpen.value = false;
    auth.dropExpiredSession();
    return;
  }
  addError.value =
    r.kind === "conflict" ? "Diese Person ist bereits Mitglied."
    : r.kind === "invalid" ? "Bitte eine gültige E-Mail-Adresse und einen Namen bis 200 Zeichen eingeben."
    : manageErrorText(r.kind);
}

async function copyCredentials() {
  const c = startCredentials.value;
  if (!c) return;
  const text = `Keplura-Zugang\nE-Mail: ${c.email}\nStartpasswort: ${c.password}\nBeim ersten Anmelden wirst du gebeten, ein eigenes Passwort festzulegen.`;
  try {
    await navigator.clipboard.writeText(text);
    credentialsCopy.value = "ok";
  } catch {
    credentialsCopy.value = "failed";
  }
}

function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[1][0]).toUpperCase();
}
function joinedLabel(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : `Mitglied seit ${d.toLocaleDateString("de-DE")}`;
}
</script>

<template>
  <!-- Rendert für JEDE angemeldete Person: wer noch keine Organisation hat, ist genau
       die Zielgruppe für „Code einlösen“ und darf hier nicht ohne Weg dastehen. -->
  <section v-if="auth.user !== null" class="mp" data-testid="invite-section">

    <section v-if="auth.selectedOrg !== null" class="connection-section" aria-labelledby="team-members-title">
      <header class="connection-header">
        <div class="connection-heading">
          <h2 id="team-members-title">Mitglieder &amp; Einladungen</h2>
          <p class="connection-description">Für <strong data-testid="invite-org">{{ auth.selectedOrg.name }}</strong>. Wer Mitglied ist, sieht deren Arbeitsbereiche, Wissen und Chats.</p>
        </div>
        <div v-if="isOwner" class="connection-actions">
          <Button variant="icon" size="compact" data-testid="invite-reload" aria-label="Mitglieder und Einladungen neu laden" :disabled="auth.membersBusy" @click="reload">
            <AppIcon name="refresh" :size="16" />
          </Button>
          <Button variant="primary" data-testid="member-add" @click="openAdd">
            <AppIcon name="plus" :size="16" />Mitglied hinzufügen
          </Button>
        </div>
      </header>

      <p v-if="toast && toastArea === 'manage'" class="connection-note" :class="toastKind" :role="toastKind === 'error' ? 'alert' : 'status'" data-testid="invite-toast">{{ toast }}</p>
      <p v-if="!isOwner" class="connection-note" data-testid="member-only-note">
        Mitglieder und Einladungen verwalten die Verwalter:innen dieser Organisation. Einen Einladungscode für eine weitere Organisation kannst du unten einlösen.
      </p>

      <template v-else>
        <div v-if="loadFailed" class="connection-note error connection-error" role="alert" data-testid="member-load-error">
          <p>Mitglieder und Einladungen konnten nicht geladen werden.</p>
          <Button size="compact" @click="reload">Erneut versuchen</Button>
        </div>

        <!-- Einmal-Zugangsdaten nach „Mitglied hinzufügen“ mit neuem Konto -->
        <div v-if="startCredentials" class="mp-once" data-testid="member-start-credentials">
          <p class="mp-once-title"><AppIcon name="shield" :size="16" />Konto für {{ startCredentials.name }} angelegt<template v-if="startCredentials.orgName"> · {{ startCredentials.orgName }}</template></p>
          <p class="mp-once-text">Gib die Zugangsdaten jetzt persönlich weiter. Das Startpasswort wird nur dieses eine Mal angezeigt und muss beim ersten Anmelden geändert werden.</p>
          <code class="mp-once-code">E-Mail: {{ startCredentials.email }}<br />Startpasswort: <span data-testid="member-start-password">{{ startCredentials.password }}</span></code>
          <p v-if="credentialsCopy === 'failed'" class="mp-once-warn" role="alert">Kopieren war nicht möglich. Markiere die Zugangsdaten und kopiere sie von Hand.</p>
          <div class="connection-actions">
            <Button variant="primary" size="compact" data-testid="member-start-copy" @click="copyCredentials">{{ credentialsCopy === "ok" ? "Kopiert ✓" : "Zugangsdaten kopieren" }}</Button>
            <Button variant="ghost" size="compact" data-testid="member-start-close" @click="startCredentials = null">Ausblenden</Button>
          </div>
        </div>

        <div class="mp-group">
          <h3>Mitglieder <span class="mp-count ic-num">{{ auth.members.length }}</span></h3>
          <div v-if="auth.members.length" class="connection-list" role="list" data-testid="invite-members">
            <article v-for="m in auth.members" :key="m.userId" class="connection-record mp-row" role="listitem" :data-testid="`invite-member-${m.userId}`">
              <span class="mp-avatar" aria-hidden="true">{{ initialsOf(m.displayName) }}</span>
              <div class="connection-heading">
                <h4 class="mp-name">{{ m.displayName }}<span v-if="m.userId === auth.user?.userId" class="mp-you"> (du)</span></h4>
                <p class="connection-meta">{{ joinedLabel(m.joinedAt) }}</p>
              </div>
              <div class="connection-actions">
                <StatusPill :tone="m.role === 'OWNER' ? 'ok' : 'neutral'">{{ m.role === "OWNER" ? "Verwalter:in" : "Mitglied" }}</StatusPill>
                <ActionMenu
                  v-if="m.userId !== auth.user?.userId"
                  :label="`Aktionen für ${m.displayName}`"
                  :items="[{ id: 'remove', label: 'Aus der Organisation entfernen', danger: true, testId: `member-remove-${m.userId}` }]"
                  @select="askRemove(m)"
                />
                <span v-else class="mp-menu-gap" aria-hidden="true" />
              </div>
            </article>
          </div>
          <p v-else-if="auth.membersBusy" class="connection-loading" role="status">Mitglieder werden geladen …</p>
          <p v-else-if="!loadFailed" class="connection-empty">Noch keine Mitglieder geladen.</p>
        </div>

        <div class="mp-group">
          <header class="connection-header">
            <h3>Offene Einladungen <span class="mp-count ic-num">{{ auth.invites.length }}</span></h3>
            <Button size="compact" data-testid="invite-create" :busy="creatingInvite" :disabled="auth.membersBusy" @click="submitCreateInvite">
              <AppIcon name="plus" :size="15" />Einladung erstellen
            </Button>
          </header>

          <div v-if="auth.lastInviteToken" class="mp-once" data-testid="invite-token-box">
            <p class="mp-once-title"><AppIcon name="shield" :size="16" />Neuer Einladungscode</p>
            <p class="mp-once-text">Gib diesen Code jetzt weiter. Er wird nur dieses eine Mal angezeigt, ist 7 Tage gültig und kann einmal eingelöst werden.</p>
            <code class="mp-once-code" data-testid="invite-token">{{ auth.lastInviteToken }}</code>
            <p v-if="tokenCopy === 'failed'" class="mp-once-warn" role="alert">Kopieren war nicht möglich. Markiere den Code und kopiere ihn von Hand.</p>
            <div class="connection-actions">
              <Button variant="primary" size="compact" data-testid="invite-token-copy" @click="copyToken">{{ tokenCopy === "ok" ? "Kopiert ✓" : "Code kopieren" }}</Button>
              <Button variant="ghost" size="compact" data-testid="invite-token-close" @click="auth.clearInviteToken()">Ausblenden</Button>
            </div>
          </div>

          <div v-if="auth.invites.length" class="connection-list" role="list" data-testid="invite-list">
            <article v-for="inv in auth.invites" :key="inv.id" class="connection-record mp-row" role="listitem" :data-testid="`invite-item-${inv.id}`">
              <span class="mp-avatar ghost" aria-hidden="true"><AppIcon name="users" :size="15" /></span>
              <div class="connection-heading">
                <h4 class="mp-name">Einladung von {{ inv.inviterName }}</h4>
                <p class="connection-meta" data-testid="invite-expires">Gültig bis {{ expiresLabel(inv.expiresAt) }} · Beitritt als Mitglied</p>
              </div>
              <div class="connection-actions">
                <StatusPill :tone="remaining(inv.expiresAt).tone">{{ remaining(inv.expiresAt).label }}</StatusPill>
                <Button size="compact" variant="ghost" :busy="revoking.has(inv.id)" :data-testid="`invite-revoke-${inv.id}`" @click="submitRevoke(inv.id)">Zurückziehen</Button>
              </div>
            </article>
          </div>
          <p v-else-if="auth.membersBusy" class="connection-loading" role="status">Einladungen werden geladen …</p>
          <p v-else-if="!loadFailed" class="connection-empty" data-testid="invite-list-empty">
            Keine offenen Einladungen. Wer einen Code einlöst, wird Mitglied; jeder Code ist 7 Tage gültig und nur einmal verwendbar.
          </p>
        </div>
      </template>
    </section>

    <section class="connection-section" aria-labelledby="team-accept-title">
      <div class="connection-heading">
        <h2 id="team-accept-title">Einladungscode einlösen</h2>
        <p class="connection-description">Hast du einen Code bekommen? Damit trittst du einer weiteren Organisation bei.</p>
      </div>
      <p v-if="toast && toastArea === 'accept'" class="connection-note" :class="toastKind" :role="toastKind === 'error' ? 'alert' : 'status'" data-testid="invite-toast">{{ toast }}</p>
      <form class="mp-accept" data-testid="invite-accept-form" @submit.prevent="submitAccept">
        <Input id="invite-accept-token" v-model="acceptToken" label="Einladungscode" data-testid="invite-accept-token" :maxlength="128" autocomplete="off" :disabled="accepting" required />
        <Button type="submit" data-testid="invite-accept-submit" :busy="accepting" :disabled="!canAccept">Beitreten</Button>
      </form>
    </section>

    <AppDialog ref="addDialog" :open="addOpen" title="Mitglied hinzufügen" :error="addError" :busy="adding" :dirty="addDirty" @close="addOpen = false">
      <form class="connection-form" data-testid="member-add-form" @submit.prevent="submitAdd">
        <Input id="member-add-email" v-model="addEmail" label="E-Mail-Adresse" type="email" data-testid="member-add-email" autocomplete="off" :disabled="adding" required />
        <Input id="member-add-name" v-model="addName" label="Name (optional)" data-testid="member-add-name" :maxlength="200" autocomplete="off" :disabled="adding" />
        <p class="connection-hint">Hat die Person schon ein Keplura-Konto, wird sie direkt Mitglied. Sonst entsteht ein neues Konto mit einem Startpasswort, das du einmalig angezeigt bekommst.</p>
        <div class="connection-submit">
          <Button variant="ghost" type="button" @click="addDialog?.requestClose()">Abbrechen</Button>
          <Button variant="primary" type="submit" data-testid="member-add-submit" :busy="adding" :disabled="addEmail.trim() === ''">Hinzufügen</Button>
        </div>
      </form>
    </AppDialog>

    <ConfirmDialog
      :open="removeTarget !== null"
      title="Mitglied entfernen?"
      :message="`${removeTarget?.displayName ?? ''} verliert den Zugriff auf alle Arbeitsbereiche, Wissen und Chats dieser Organisation. Das Benutzerkonto bleibt erhalten.`"
      confirm-label="Entfernen"
      :busy="removing"
      :error="removeError"
      @confirm="confirmRemove"
      @close="removeTarget = null"
    />
  </section>
</template>

<style scoped>
.mp { display: grid; gap: 40px; min-width: 0; }
.mp h2 { margin: 0; color: var(--ic-text); font: 600 20px/28px var(--ic-font); }
.mp .connection-description strong { color: var(--ic-text); font-weight: 600; }
.mp-group { display: grid; gap: 12px; min-width: 0; }
.mp-group h3 { margin: 0; color: var(--ic-text); font: 500 16px/24px var(--ic-font); }
.mp-group > .connection-header { align-items: center; }
.mp-count { margin-left: 6px; color: var(--ic-text-3); font-weight: 400; }
.mp-row { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 14px 0; }
.mp-row:first-child { padding-top: 0; }
.mp-row > .connection-heading { flex: 1 1 200px; }
.mp-name { margin: 0; font: 500 15px/22px var(--ic-font); color: var(--ic-text); overflow-wrap: anywhere; }
.mp-you { font-weight: 400; color: var(--ic-text-3); }
.mp-avatar { display: inline-grid; place-items: center; width: 32px; height: 32px; flex: none; border-radius: 50%; background: var(--ic-surface); box-shadow: 0 0 0 1px var(--ic-separator); color: var(--ic-text-2); font: 600 12px/1 var(--ic-font); }
.mp-avatar.ghost { color: var(--ic-text-3); }
.mp-menu-gap { width: 32px; flex: none; }
.mp-once { display: grid; gap: 10px; padding: 16px; border-radius: 8px; border: 1px solid color-mix(in srgb, var(--ic-status-wait) 40%, transparent); background: color-mix(in srgb, var(--ic-status-wait) 8%, transparent); min-width: 0; }
.mp-once-title { display: flex; align-items: center; gap: 8px; margin: 0; color: var(--ic-text); font: 600 14px/20px var(--ic-font); }
.mp-once-text { margin: 0; color: var(--ic-text-2); font: 400 13px/20px var(--ic-font); }
.mp-once-code { display: block; padding: 10px 12px; border-radius: 6px; background: var(--ic-surface); box-shadow: 0 0 0 1px var(--ic-separator); font: 13px/1.6 var(--ic-font-mono); word-break: break-all; user-select: all; }
.mp-once-warn { margin: 0; color: var(--ic-status-error); font: 400 13px/20px var(--ic-font); }
.mp-accept { display: flex; align-items: flex-end; gap: 8px; flex-wrap: wrap; max-width: 640px; }
.mp-accept > :first-child { flex: 1; min-width: 220px; }
.mp-accept :deep(.ic-field) { margin: 0; }
@media (max-width: 767px), (any-pointer: coarse) { .mp-menu-gap { width: 44px; } }
</style>
