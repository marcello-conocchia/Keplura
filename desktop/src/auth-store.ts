import { defineStore } from "pinia";
import { computed, ref } from "vue";
import {
  exchangeOidcHandoff,
  fetchOidcCapabilities,
  startOidcLogin,
  type OidcCapabilities,
} from "./oidc";
import {
  acceptInvite as apiAcceptInvite,
  createInvite as apiCreateInvite,
  createKnowledgeBase as apiCreateKb,
  createOrg as apiCreateOrg,
  createSpace as apiCreateSpace,
  fetchConversations as apiFetchConversations,
  fetchDocuments,
  fetchKnowledgeBases,
  fetchMe,
  fetchOrgs,
  fetchSpaces,
  listInvites as apiListInvites,
  listMembers as apiListMembers,
  login as apiLogin,
  logout as apiLogout,
  refreshCsrfToken,
  revokeInvite as apiRevokeInvite,
  searchKnowledgeBase as apiSearch,
  uploadDocument as apiUpload,
  type AcceptInviteOutcome,
  type AuthUser,
  type ChatConversation,
  type ChatConversationsOutcome,
  type CreateInviteOutcome,
  type CreateNestedOutcome,
  type CreateOrgOutcome,
  type KnowledgeBase,
  type KnowledgeDocument,
  type ListInvitesOutcome,
  type ListMembersOutcome,
  type LoginOutcome,
  type OrgInvite,
  type Organization,
  type OrgMember,
  type RevokeInviteOutcome,
  type SearchOutcome,
  type SearchResult,
  type Space,
  type UploadOutcome,
} from "./api";

/**
 * Session auth state for the desktop (M2d). Holds ONLY the non-sensitive
 * profile returned by the login endpoint (id + display name) — the credential
 * itself is never stored, and the session cookie is HttpOnly (invisible to
 * JS). The server session is the source of truth, so startup re-probes it
 * via restoreSession() and an expired 401 on orgs drops the UI back to login.
 */
export const useAuthStore = defineStore("auth", () => {
  const user = ref<AuthUser | null>(null);
  /**
   * ADMIN-KI: the GLOBAL deployment role of the signed-in user ("USER" |
   * "ADMIN"), from the login response or GET /auth/me on restore. Purely
   * cosmetic here (menu entry visibility); the server gates every admin
   * request independently, so a wrong value can only show a menu that then
   * 403s — never authorise anything.
   */
  const role = ref("USER");
  const isAdmin = computed(() => role.value === "ADMIN");
  const orgs = ref<Organization[]>([]);
  const orgsError = ref(false);
  /** True while the startup "is my old session still alive?" probe runs. */
  const restoring = ref(true);
  const busy = ref(false);

  const loggedIn = computed(() => user.value !== null);

  // ---- OIDC-M2-Tail (docs/adr/0009 §10): second login path state ----
  // Capabilities + a coarse failure flag. NOT persisted: both are per-boot
  // facts of the server probe / the URL fragment App.vue consumed.
  const oidc = ref<OidcCapabilities>({ oidcEnabled: false, providers: [] });
  const oidcFailed = ref(false);

  /** Refresh the button flag from the server (called with every probe). */
  async function checkOidc() {
    oidc.value = await fetchOidcCapabilities();
  }

  /** Navigate the webview into the IdP leg (fire-and-forget by nature). */
  function beginOidcLogin(rememberMe = false): boolean {
    const provider = oidc.value.providers[0];
    return oidc.value.oidcEnabled && provider ? startOidcLogin(provider.id, rememberMe) : false;
  }

  /**
   * Consume the #oidc=<handoff> fragment at boot: exchange the one-time code
   * for a session (CSRF token captured inside), then run the SAME restore
   * probe a restart uses — one convergence path, no second implementation.
   * Failure flips oidcFailed (the login card shows the coarse line once).
   */
  async function completeOidcHandoff(handoff: string): Promise<boolean> {
    oidcFailed.value = false;
    const outcome = await exchangeOidcHandoff(handoff);
    if (outcome.kind === "ok") {
      await restoreSession();
      return true;
    }
    if (outcome.kind === "invalid") {
      oidcFailed.value = true;
    }
    return false;
  }

  /** LoginView shows the failure line until the user touches the form. */
  function clearOidcFailure() {
    oidcFailed.value = false;
  }

  /** Refresh the org list; 401 means the server session died -> show login. */
  async function loadOrgs() {
    const result = await fetchOrgs();
    if (result.kind === "ok") {
      orgs.value = result.orgs;
      orgsError.value = false;
    } else {
      orgs.value = [];
      orgsError.value = true;
      if (result.kind === "expired") {
        dropExpiredSession();
      }
    }
  }

  /**
   * Restore after an app restart: the session cookie survives in the webview,
   * so GET /api/v1/orgs doubles as a liveness probe. A valid session yields
   * the org list but NOT the profile (no /me endpoint exists yet — deliberate
   * MVP gap), so the UI falls back to a neutral greeting. Any error (including
   * offline) just shows the login form; nothing leaks.
   *
   * <p>SEC-2: after the probe succeeds, the CSRF token is re-read from the
   * server (GET /auth/csrf) rather than trusted from localStorage — the stored
   * copy may predate a session rotation, and a stale token would make every
   * state-changing call fail 403.
   */
  async function restoreSession() {
    restoring.value = true;
    const result = await fetchOrgs();
    if (result.kind === "ok") {
      orgs.value = result.orgs;
      orgsError.value = false;
      const me = await fetchMe();
      if (me.kind !== "ok") {
        // A session without its verified profile must not masquerade as a
        // generic signed-in user. The login screen allows a clean retry.
        dropExpiredSession();
        restoring.value = false;
        return;
      }
      user.value = {
        userId: me.me.userId,
        displayName: me.me.displayName,
        mustChangePassword: me.me.mustChangePassword === true,
      };
      role.value = me.me.role || "USER";
      if (!(await refreshCsrfToken())) {
        // Fail-closed: fetchOrgs just proved the session, so anything but a
        // clean token read (401, truncated body, timeout race) is treated as
        // the session being gone — a restored user that cannot perform any
        // write is worse than a clean re-login.
        dropExpiredSession();
      }
    }
    restoring.value = false;
  }

  async function login(email: string, password: string, rememberMe = false): Promise<LoginOutcome> {
    busy.value = true;
    try {
      const outcome = await apiLogin(email, password, rememberMe);
      if (outcome.kind === "ok") {
        // Defensive: a fresh session starts from an empty store even if some
        // earlier path ever misses its teardown (no ref may predate the login).
        clearKnowledge();
        orgs.value = [];
        orgsError.value = false;
        user.value = outcome.user;
        role.value = outcome.user.role || "USER";
        await loadOrgs();
      }
      return outcome;
    } finally {
      busy.value = false;
    }
  }

  /** Local state clears FIRST — logout cannot fail client-side, even offline. */
  async function logout() {
    // Same teardown as a dead session: user, orgs and ALL tenant refs go —
    // spaces/kbs/documents/search must not survive the session they belong to.
    dropExpiredSession();
    await apiLogout();
  }

  /**
   * Create an organization (M2e). On success the new org (caller is OWNER per
   * the server response) is appended locally — no re-fetch, because the POST
   * response is authoritative for that row and a list reload would only add a
   * failure mode. "expired" drops the session like loadOrgs does.
   */
  async function createOrg(name: string): Promise<CreateOrgOutcome> {
    const outcome = await apiCreateOrg(name);
    if (outcome.kind === "created") {
      orgs.value = [...orgs.value, outcome.org];
      orgsError.value = false;
    } else if (outcome.kind === "expired") {
      dropExpiredSession();
    }
    return outcome;
  }

  // ---- M3e: knowledge state (space -> kb -> documents + search) ----
  //
  // One org (auto-selected: the first membership) and one space (auto-selected
  // the same way) scope the whole section; kbs are the unit the user picks.
  // All lists are plain refs refreshed on demand — the server stays the
  // source of truth, local appends mirror the org-store precedent.

  const spaces = ref<Space[]>([]);
  const selectedOrgId = ref<number | null>(null);
  const selectedSpaceId = ref<number | null>(null);
  const kbs = ref<KnowledgeBase[]>([]);
  const selectedKbId = ref<number | null>(null);
  const documents = ref<KnowledgeDocument[]>([]);
  const knowledgeError = ref("");
  /** Simple busy flags so buttons can disable while a call is in flight. */
  const knowledgeBusy = ref(false);
  const documentsBusy = ref(false);
  let documentRequest = 0;
  /** Last successful search result for the selected kb ("" query resets it). */
  const searchResult = ref<SearchResult | null>(null);

  const selectedOrg = computed(
    () => orgs.value.find((o) => o.id === selectedOrgId.value) ?? null,
  );
  const selectedSpace = computed(
    () => spaces.value.find((s) => s.id === selectedSpaceId.value) ?? null,
  );
  const selectedKb = computed(
    () => kbs.value.find((k) => k.id === selectedKbId.value) ?? null,
  );

  /**
   * The ONE way a dead server session is handled locally (Finding 1): the
   * session is gone, so nothing fetched under it may stay visible. Used by
   * every "expired" outcome branch below — an asymmetric list (logout cleared,
   * 401 did not) let tenant rows of user A survive into user B's panel after a
   * server restart, because the pinia store outlives the views.
   */
  function dropExpiredSession() {
    user.value = null;
    role.value = "USER"; // ADMIN-KI: no stale admin menu across accounts
    orgs.value = [];
    orgsError.value = false;
    clearKnowledge();
  }

  /**
   * Clear everything below the ORG level. Called whenever the selected org
   * changes or a space fetch fails: a list must never be renderable while its
   * parent is no longer the one it was loaded from.
   */
  function resetSpaceScope() {
    spaces.value = [];
    selectedSpaceId.value = null;
    resetKbScope();
  }

  /**
   * Clear everything below the SPACE level: selection, kb list and the kb's
   * rows. Invariant (M3e review, Finding 1): no list ref may outlive the
   * parent it was fetched under — so re-scoping the space always drops `kbs`
   * too; the caller that changes the parent either refetches right after or
   * deliberately stops with empty lists.
   */
  function resetKbScope() {
    documentRequest++;
    documentsBusy.value = false;
    selectedKbId.value = null;
    kbs.value = [];
    documents.value = [];
    searchResult.value = null;
  }

  /**
   * Load spaces for an org and auto-select the first one (MVP: no org
   * switcher; first membership = predictable QA path). Chain stops here on
   * error/expired so callers can show one message at a time.
   */
  async function loadSpaces(orgId: number): Promise<boolean> {
    const result = await fetchSpaces(orgId);
    if (result.kind === "expired") {
      dropExpiredSession();
      return false;
    }
    if (result.kind === "error") {
      // A failed list must never render the previous parent's rows.
      resetSpaceScope();
      knowledgeError.value = "Bereiche konnten nicht geladen werden.";
      return false;
    }
    spaces.value = result.items;
    knowledgeError.value = "";
    if (!result.items.some((s) => s.id === selectedSpaceId.value)) {
      selectedSpaceId.value = result.items.length > 0 ? result.items[0].id : null;
      resetKbScope();
    }
    return true;
  }

  /** PILOT 4e: after a successful password change the gate opens. */
  function passwordChanged() {
    if (user.value) user.value = { ...user.value, mustChangePassword: false };
  }

  /** REDESIGN v3: Chat-Verlauf in der Sidebar — ChatView führt die Anfragen aus. */
  const activeConversationId = ref<number | null>(null);
  const conversationRequest = ref<{ kind: "open" | "delete"; id: number; n: number } | null>(null);
  let conversationRequestN = 0;
  function requestConversation(kind: "open" | "delete", id: number) {
    conversationRequest.value = { kind, id, n: ++conversationRequestN };
  }

  /** REDESIGN v3: sidebar "Neuer Chat" → ChatView reacts to this counter. */
  const newChatRequest = ref(0);
  function requestNewChat() {
    newChatRequest.value++;
  }

  /** Workspace switcher (REDESIGN): pick another organisation, reload below it. */
  async function selectOrg(orgId: number): Promise<void> {
    if (orgId === selectedOrgId.value) {
      return;
    }
    selectedOrgId.value = orgId;
    resetSpaceScope();
    conversations.value = [];
    // UI-12: ein Einmal-Einladungscode gehört zur alten Organisation und darf nie
    // unter einer anderen angezeigt werden (Einladung bleibt bestehen, zurückziehbar).
    lastInviteToken.value = null;
    await loadKnowledge();
  }

  /** Workspace switcher (REDESIGN): pick another space of the current org. */
  async function selectSpace(spaceId: number): Promise<void> {
    if (spaceId === selectedSpaceId.value) {
      return;
    }
    selectedSpaceId.value = spaceId;
    resetKbScope();
    conversations.value = [];
    await loadKnowledge();
  }

  /** Load kbs of the selected space; keeps a still-visible selection. */
  async function loadKnowledgeBases(): Promise<boolean> {
    if (selectedOrgId.value === null || selectedSpaceId.value === null) {
      resetKbScope();
      return false;
    }
    const result = await fetchKnowledgeBases(selectedOrgId.value, selectedSpaceId.value);
    if (result.kind === "expired") {
      dropExpiredSession();
      return false;
    }
    if (result.kind === "error") {
      resetKbScope();
      knowledgeError.value = "Knowledge Bases konnten nicht geladen werden.";
      return false;
    }
    kbs.value = result.items;
    knowledgeError.value = "";
    if (!result.items.some((k) => k.id === selectedKbId.value)) {
      // auto-select the first kb so the QA flow has one fewer click
      selectedKbId.value = result.items.length > 0 ? result.items[0].id : null;
      documents.value = [];
      searchResult.value = null;
    }
    return true;
  }

  /** Load the document list of the selected kb (metadata + indexStatus). */
  async function loadDocuments(): Promise<boolean> {
    const request = ++documentRequest;
    const orgId = selectedOrgId.value, spaceId = selectedSpaceId.value, kbId = selectedKbId.value;
    if (orgId === null || spaceId === null || kbId === null) {
      documents.value = [];
      documentsBusy.value = false;
      return false;
    }
    documentsBusy.value = true;
    try {
      const result = await fetchDocuments(orgId, spaceId, kbId);
      // Eine spät eintreffende Liste darf nie unter der inzwischen gewählten Basis erscheinen.
      if (request !== documentRequest || orgId !== selectedOrgId.value || spaceId !== selectedSpaceId.value || kbId !== selectedKbId.value) return false;
      if (result.kind === "expired") { dropExpiredSession(); return false; }
      if (result.kind === "error") {
        documents.value = [];
        knowledgeError.value = "Dokumente konnten nicht geladen werden.";
        return false;
      }
      documents.value = result.items;
      knowledgeError.value = "";
      return true;
    } finally {
      if (request === documentRequest) documentsBusy.value = false;
    }
  }

  /**
   * One entry point the view calls on mount and on "Neu laden": walks the
   * org -> space -> kb -> documents chain, auto-selecting wherever nothing
   * (valid) is selected yet, and stops at the first failure. When NO orgs
   * exist at all there is nothing to scope the section to — that is the
   * documented empty state (the create-org form above the panel), and the
   * space/kb creates below self-heal via the orgs.length watcher in
   * KnowledgePanel, which re-runs loadKnowledge once an org exists.
   */
  async function loadKnowledge(): Promise<void> {
    if (orgs.value.length === 0) {
      await loadOrgs();
    }
    if (orgs.value.length === 0) {
      knowledgeError.value = "Bitte zuerst einer Organisation beitreten oder eine anlegen.";
      return;
    }
    if (selectedOrgId.value === null || !orgs.value.some((o) => o.id === selectedOrgId.value)) {
      // Re-parenting to another org: nothing fetched under the old one stays.
      selectedOrgId.value = orgs.value[0].id;
      resetSpaceScope();
      lastInviteToken.value = null;
    }
    knowledgeBusy.value = true;
    try {
      if (!(await loadSpaces(selectedOrgId.value))) {
        return;
      }
      if (selectedSpaceId.value === null) {
        resetKbScope();
        // Not a failure — the view's create-space form IS the next step.
        knowledgeError.value = "";
        return;
      }
      if (!(await loadKnowledgeBases())) {
        return;
      }
      if (selectedKbId.value === null) {
        documents.value = [];
        return; // empty kb list is a normal state, view shows the create form
      }
      await loadDocuments();
    } finally {
      knowledgeBusy.value = false;
    }
  }

  /**
   * Create a nested row (space or kb) and select it right away — the POST
   * response is authoritative for that row, matching the org-create
   * precedent, so only the SIBLING list is extended without a reload.
   */
  async function createSpace(name: string): Promise<CreateNestedOutcome<Space>> {
    if (selectedOrgId.value === null) {
      return { kind: "error" };
    }
    const outcome = await apiCreateSpace(selectedOrgId.value, name);
    if (outcome.kind === "created") {
      spaces.value = [...spaces.value, outcome.item];
      selectedSpaceId.value = outcome.item.id;
      resetKbScope();
      await loadKnowledgeBases();
    } else if (outcome.kind === "expired") {
      dropExpiredSession();
    }
    return outcome;
  }

  async function createKnowledgeBase(name: string): Promise<CreateNestedOutcome<KnowledgeBase>> {
    if (selectedOrgId.value === null || selectedSpaceId.value === null) {
      return { kind: "error" };
    }
    const outcome = await apiCreateKb(selectedOrgId.value, selectedSpaceId.value, name);
    if (outcome.kind === "created") {
      kbs.value = [...kbs.value, outcome.item];
      selectedKbId.value = outcome.item.id;
      documents.value = [];
      searchResult.value = null;
    } else if (outcome.kind === "expired") {
      dropExpiredSession();
    }
    return outcome;
  }

  /** Upload one document into the selected kb; on success refresh the list. */
  async function uploadDocument(file: File): Promise<UploadOutcome> {
    if (selectedOrgId.value === null || selectedSpaceId.value === null || selectedKbId.value === null) {
      return { kind: "error" };
    }
    knowledgeBusy.value = true;
    try {
      const outcome = await apiUpload(selectedOrgId.value, selectedSpaceId.value, selectedKbId.value, file);
      if (outcome.kind === "uploaded") {
        documents.value = [...documents.value, outcome.document];
      } else if (outcome.kind === "expired") {
        dropExpiredSession();
      } else if (outcome.kind === "parent-gone") {
        await loadKnowledge();
      }
      return outcome;
    } finally {
      knowledgeBusy.value = false;
    }
  }

  /** Search the selected kb; the result (incl. citations) stays in the store. */
  async function searchKb(query: string): Promise<SearchOutcome> {
    if (selectedOrgId.value === null || selectedSpaceId.value === null || selectedKbId.value === null) {
      return { kind: "error" };
    }
    const outcome = await apiSearch(selectedOrgId.value, selectedSpaceId.value, selectedKbId.value, query);
    if (outcome.kind === "ok") {
      searchResult.value = outcome.result;
    } else if (outcome.kind === "expired") {
      dropExpiredSession();
    } else if (outcome.kind === "parent-gone") {
      searchResult.value = null;
      await loadKnowledge();
    }
    return outcome;
  }

  /** Switch the selected kb (view): reload docs, drop the old search. */
  function selectKnowledgeBase(kbId: number) {
    if (selectedKbId.value === kbId) {
      return;
    }
    selectedKbId.value = kbId;
    documents.value = [];
    searchResult.value = null;
    knowledgeError.value = "";
    void loadDocuments();
  }

  /** Logout must not leave tenant rows behind in any store ref. */
  function clearKnowledge() {
    spaces.value = [];
    selectedOrgId.value = null;
    selectedSpaceId.value = null;
    kbs.value = [];
    selectedKbId.value = null;
    documents.value = [];
    searchResult.value = null;
    knowledgeError.value = "";
    knowledgeBusy.value = false;
    documentRequest++;
    documentsBusy.value = false;
    // M4b: chat threads are tenant-scoped too — they must not survive the
    // session/org/space they were loaded for (dropExpiredSession reuses this).
    conversations.value = [];
    // SEC-2: roster, invite list and the one-time token display are org rows
    // — same teardown rule.
    members.value = [];
    invites.value = [];
    lastInviteToken.value = null;
    membersBusy.value = false;
  }

  // ---- M4b: chat conversations (personal threads in the selected space) ----
  //
  // The LIST lives in the store (tenant teardown above covers it); the open
  // transcript + streaming state stay in ChatView — they are view state, not
  // tenant identity, and a re-mount must be able to re-read them server-side.
  const conversations = ref<ChatConversation[]>([]);

  /** Reload my conversations for the current org+space scope. */
  async function loadConversations(): Promise<ChatConversationsOutcome> {
    if (selectedOrgId.value === null || selectedSpaceId.value === null) {
      conversations.value = [];
      return { kind: "ok", conversations: [] };
    }
    const outcome = await apiFetchConversations(selectedOrgId.value, selectedSpaceId.value);
    if (outcome.kind === "ok") {
      conversations.value = outcome.conversations;
    } else if (outcome.kind === "expired") {
      dropExpiredSession();
    }
    return outcome;
  }

  /** Local list append after a create (mirrors the org-create precedent). */
  function rememberConversation(conversation: ChatConversation) {
    conversations.value = [
      conversation,
      ...conversations.value.filter((c) => c.id !== conversation.id),
    ];
  }

  /** Local list drop after a delete (server 204 is authoritative). */
  function forgetConversation(conversationId: number) {
    conversations.value = conversations.value.filter((c) => c.id !== conversationId);
  }

  // ---- SEC-2: organization members + invites (OWNER panel) ----
  //
  // Tenant-scoped like every other list below the org level: `members` and
  // `invites` always describe the CURRENTLY selected org and are dropped by
  // clearKnowledge()/dropExpiredSession(). The plaintext invite token NEVER
  // enters a ref that outlives the create call — `lastInviteToken` is the
  // one-time display value and is cleared on re-load/revoke/teardown (the
  // server cannot re-issue it; the hash is all it kept).
  const members = ref<OrgMember[]>([]);
  const invites = ref<OrgInvite[]>([]);
  const lastInviteToken = ref<string | null>(null);
  const membersBusy = ref(false);

  /** Reload roster + pending invites for the selected org (OWNER surfaces). */
  async function loadMembersAndInvites(): Promise<boolean> {
    if (selectedOrgId.value === null) {
      members.value = [];
      invites.value = [];
      return false;
    }
    membersBusy.value = true;
    try {
      const orgId = selectedOrgId.value;
      const roster: ListMembersOutcome = await apiListMembers(orgId);
      if (roster.kind === "expired") {
        dropExpiredSession();
        return false;
      }
      if (roster.kind !== "ok") {
        // forbidden/error: drop stale rows — a list must never render rows of
        // a parent it was not (re)loaded under (M3e Finding-1 invariant).
        members.value = [];
        invites.value = [];
        return false;
      }
      members.value = roster.members;
      const pending: ListInvitesOutcome = await apiListInvites(orgId);
      invites.value = pending.kind === "ok" ? pending.invites : [];
      return true;
    } finally {
      membersBusy.value = false;
    }
  }

  /** Create an invite; on success the token rides back for the ONE display. */
  async function createInvite(): Promise<CreateInviteOutcome> {
    if (selectedOrgId.value === null) {
      return { kind: "error" };
    }
    const orgId = selectedOrgId.value;
    const outcome = await apiCreateInvite(orgId);
    if (outcome.kind === "expired") {
      dropExpiredSession();
    } else if (outcome.kind === "created" && selectedOrgId.value === orgId) {
      // UI-12: nach einem Org-Wechsel während der Anfrage den Code nie unter der
      // neuen Organisation zeigen; die Einladung bleibt bestehen und ist zurückziehbar.
      lastInviteToken.value = outcome.token;
      invites.value = [outcome.invite, ...invites.value];
    }
    return outcome;
  }

  /** Dismiss the token display once the OWNER copied/read it (UI close). */
  function clearInviteToken() {
    lastInviteToken.value = null;
  }

  async function revokeInvite(inviteId: number): Promise<RevokeInviteOutcome> {
    if (selectedOrgId.value === null) {
      return { kind: "error" };
    }
    const outcome = await apiRevokeInvite(selectedOrgId.value, inviteId);
    if (outcome.kind === "expired") {
      dropExpiredSession();
    } else if (outcome.kind === "revoked") {
      invites.value = invites.value.filter((i) => i.id !== inviteId);
      // cannot tell WHICH row that token belonged to — hide the display
      clearInviteToken();
    }
    return outcome;
  }

  /** Redeem a token for the SIGNED-IN user; a new org appears on success. */
  async function acceptInvite(token: string): Promise<AcceptInviteOutcome> {
    const outcome = await apiAcceptInvite(token);
    if (outcome.kind === "expired-session") {
      dropExpiredSession();
    } else if (outcome.kind === "accepted") {
      await loadOrgs(); // the org list is server truth; no local synthesis
    }
    return outcome;
  }

  return {
    passwordChanged,
    activeConversationId,
    conversationRequest,
    requestConversation,
    newChatRequest,
    requestNewChat,
    selectOrg,
    selectSpace,
    user,
    role,
    isAdmin,
    orgs,
    orgsError,
    restoring,
    busy,
    loggedIn,
    login,
    logout,
    restoreSession,
    // OIDC second login path (ADR-0009 §10; login screen only — UI freeze)
    oidc,
    oidcFailed,
    checkOidc,
    beginOidcLogin,
    completeOidcHandoff,
    clearOidcFailure,
    loadOrgs,
    createOrg,
    // M3e knowledge state
    spaces,
    kbs,
    documents,
    searchResult,
    selectedOrgId,
    selectedSpaceId,
    selectedKbId,
    selectedOrg,
    selectedSpace,
    selectedKb,
    knowledgeError,
    knowledgeBusy,
    documentsBusy,
    loadKnowledge,
    loadDocuments,
    createSpace,
    createKnowledgeBase,
    uploadDocument,
    searchKb,
    selectKnowledgeBase,
    clearKnowledge,
    /**
     * M3e teardown — every "expired" (server 401) outcome must route through
     * this, including the chat view's direct api.ts calls (create/stream/
     * transcript/delete), which bypass the store's own fetch wrappers.
     */
    dropExpiredSession,
    // M4b chat state (tenant-scoped list; teardown via clearKnowledge)
    conversations,
    loadConversations,
    rememberConversation,
    forgetConversation,
    // SEC-2 members/invites (OWNER panel; teardown via clearKnowledge)
    members,
    invites,
    lastInviteToken,
    membersBusy,
    loadMembersAndInvites,
    createInvite,
    clearInviteToken,
    revokeInvite,
    acceptInvite,
  };
});
