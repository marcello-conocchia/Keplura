<script setup lang="ts">
/**
 * REDESIGN v3 — Sidebar nach Produktivitäts-App-Muster:
 *   Organisation (Menü) + Benutzer (Menü)
 *   [Neuer Chat] [Suche]
 *   Chat · Wissen · Team (· Administration)
 *   Arbeitsbereiche  ▸ Baum, aktiver Bereich zeigt seine Wissensbasen
 *   Verbindungsstatus
 */
import { computed, nextTick, ref } from "vue";
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "reka-ui";
import { useAuthStore } from "../auth-store";
import { useThemeStore } from "../theme-store";
import type { AppView } from "../navigation";
import AppIcon from "./ui/AppIcon.vue";
import type { IconName } from "./ui/AppIcon.vue";

const auth = useAuthStore();
const theme = useThemeStore();

const props = defineProps<{
  status: "checking" | "connected" | "unreachable";
  modelState: string;
  serverVersion: string;
  activeView: AppView;
  isFullscreen: boolean;
  showFullscreen: boolean;
}>();

function openPalette() {
  window.dispatchEvent(new Event("ic-open-palette"));
}

const emit = defineEmits<{
  reload: [];
  logout: [];
  navigate: [AppView];
  toggleFullscreen: [];
}>();

const nav: { view: AppView; label: string; icon: IconName; testid: string }[] = [
  { view: "chat", label: "Chat", icon: "chat", testid: "sidebar-nav-chat" },
  { view: "wissen", label: "Wissen", icon: "book", testid: "sidebar-nav-wissen" },
  { view: "team", label: "Team", icon: "users", testid: "sidebar-nav-team" },
];

function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[1][0]).toUpperCase();
}
const currentOrg = computed(() => auth.orgs.find((o) => o.id === auth.selectedOrgId) ?? auth.orgs[0] ?? null);
const userName = computed(() => auth.user?.displayName || "Keplura-Nutzer");

const statusLabel = computed(() =>
  props.status === "connected" ? "Verbunden" : props.status === "unreachable" ? "Keine Verbindung" : "Verbindung wird geprüft…",
);
const statusTone = computed(() =>
  props.status === "connected" ? "var(--ic-status-ok)" : props.status === "unreachable" ? "var(--ic-status-error)" : "var(--ic-status-wait)",
);
const modelLabel = computed(() =>
  props.modelState === "local" ? "KI-Modell lokal" : props.modelState === "configured" ? "KI-Modell bereit" : "Kein KI-Modell",
);

async function newChat() {
  emit("navigate", "chat");
  await nextTick();
  auth.requestNewChat();
}

/* Chat-Verlauf aufklappbar (Zustand je Gerät gemerkt). */
function readChatsOpen(): boolean {
  try {
    return localStorage.getItem("keplura.sidebar.chats") !== "0";
  } catch {
    return true;
  }
}
const chatsOpen = ref(readChatsOpen());
function toggleChats() {
  chatsOpen.value = !chatsOpen.value;
  try {
    localStorage.setItem("keplura.sidebar.chats", chatsOpen.value ? "1" : "0");
  } catch {
    /* privater Modus: nur für diese Sitzung */
  }
}
const showAllChats = ref(false);
const visibleChats = computed(() => (showAllChats.value ? auth.conversations : auth.conversations.slice(0, 6)));

async function openChat(id: number) {
  emit("navigate", "chat");
  await nextTick();
  auth.requestConversation("open", id);
}
async function deleteChat(id: number) {
  emit("navigate", "chat");
  await nextTick();
  auth.requestConversation("delete", id);
}

async function openSpace(spaceId: number) {
  await auth.selectSpace(spaceId);
}

function openKb(kbId: number) {
  auth.selectKnowledgeBase(kbId);
  emit("navigate", "wissen");
}
</script>

<template>
  <nav class="sb" data-testid="sidebar" aria-label="Hauptnavigation">
    <div class="brand"><span class="brand-symbol" aria-hidden="true"><AppIcon name="book" /></span>Keplura</div>
    <!-- Kopf: Organisation + Benutzer -->
    <div class="head">
      <DropdownMenuRoot v-if="auth.orgs.length">
        <DropdownMenuTrigger class="org" data-testid="sidebar-org" aria-label="Organisation wechseln">
          <span class="org-logo" aria-hidden="true">{{ initialsOf(currentOrg?.name ?? "") }}</span>
          <span class="org-name">{{ currentOrg?.name }}</span>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M7 10l5 5 5-5" /></svg>
        </DropdownMenuTrigger>
        <DropdownMenuPortal>
          <DropdownMenuContent class="ic-menu" :side-offset="4" align="start">
            <DropdownMenuLabel class="ic-menu-label">Organisation wechseln</DropdownMenuLabel>
            <DropdownMenuItem v-for="org in auth.orgs" :key="org.id" class="ic-menu-item" :data-testid="`sidebar-org-${org.id}`" @select="auth.selectOrg(org.id)">
              <span class="ic-menu-logo" aria-hidden="true">{{ initialsOf(org.name) }}</span>
              <span class="ic-menu-text">{{ org.name }}</span>
              <span v-if="org.id === currentOrg?.id" class="ic-menu-check">✓</span>
            </DropdownMenuItem>
            <DropdownMenuSeparator class="ic-menu-sep" />
            <DropdownMenuItem class="ic-menu-item" @select="emit('navigate', 'team')">Organisationen verwalten</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenuPortal>
      </DropdownMenuRoot>
      <span v-else class="org org-none" data-testid="sidebar-org-empty">Noch keine Organisation</span>

      <DropdownMenuRoot>
        <DropdownMenuTrigger class="me" :aria-label="`Konto: ${userName}`" data-testid="sidebar-user">
          <span class="me-avatar">{{ initialsOf(userName) }}</span>
          <span class="me-dot" :style="{ background: statusTone }" aria-hidden="true"></span>
        </DropdownMenuTrigger>
        <DropdownMenuPortal>
          <DropdownMenuContent class="ic-menu" :side-offset="4" align="end">
            <DropdownMenuLabel class="ic-menu-label strong">{{ userName }}</DropdownMenuLabel>
            <DropdownMenuSeparator class="ic-menu-sep" />
            <DropdownMenuLabel class="ic-menu-label">Darstellung</DropdownMenuLabel>
            <DropdownMenuItem class="ic-menu-item" data-testid="theme-light" @select="theme.setMode('light')">
              <AppIcon name="sun" :size="13" /><span class="ic-menu-text">Hell</span><span v-if="theme.mode === 'light'" class="ic-menu-check">✓</span>
            </DropdownMenuItem>
            <DropdownMenuItem class="ic-menu-item" data-testid="theme-dark" @select="theme.setMode('dark')">
              <AppIcon name="moon" :size="13" /><span class="ic-menu-text">Dunkel</span><span v-if="theme.mode === 'dark'" class="ic-menu-check">✓</span>
            </DropdownMenuItem>
            <DropdownMenuItem class="ic-menu-item" data-testid="theme-toggle" @select="theme.setMode('system')">
              <AppIcon name="auto" :size="13" /><span class="ic-menu-text">Wie System</span><span v-if="theme.mode === 'system'" class="ic-menu-check">✓</span>
            </DropdownMenuItem>
            <template v-if="showFullscreen">
              <DropdownMenuSeparator class="ic-menu-sep" />
              <DropdownMenuItem class="ic-menu-item" data-testid="fullscreen-toggle" :aria-label="isFullscreen ? 'Vollbild verlassen' : 'Vollbild aktivieren'" @select="emit('toggleFullscreen')">
                <AppIcon :name="isFullscreen ? 'compress' : 'expand'" :size="13" />
                <span class="ic-menu-text">{{ isFullscreen ? "Vollbild verlassen" : "Vollbild" }}</span>
                <span class="ic-menu-kbd">F11</span>
              </DropdownMenuItem>
            </template>
            <DropdownMenuSeparator class="ic-menu-sep" />
            <DropdownMenuItem class="ic-menu-item" data-testid="sidebar-logout" @select="emit('logout')">
              <AppIcon name="logout" :size="13" /><span class="ic-menu-text">Abmelden</span>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenuPortal>
      </DropdownMenuRoot>
    </div>

    <!-- Schnellaktionen -->
    <div class="quick">
      <button type="button" class="new" data-testid="sidebar-new-chat" :disabled="auth.selectedSpaceId === null" @click="newChat">
        <AppIcon name="edit" :size="15" />
        <span>Neuer Chat</span>
      </button>
      <button type="button" class="search" title="Suchen (Strg+K)" aria-label="Suchen (Strg+K)" data-testid="sidebar-search" @click="openPalette">
        <AppIcon name="search" :size="15" />
      </button>
    </div>

    <ul class="nav" role="list">
      <li v-for="item in nav" :key="item.view">
        <div class="item-wrap">
          <button
            type="button"
            class="item"
            :class="{ active: activeView === item.view }"
            :aria-current="activeView === item.view ? 'page' : undefined"
            :data-testid="item.testid"
            @click="emit('navigate', item.view)"
          >
            <AppIcon :name="item.icon" />
            <span class="label">{{ item.label }}</span>
            <span v-if="item.view === 'wissen'" class="count ic-num" data-testid="sidebar-kb-count">{{ auth.kbs.length }}</span>
          </button>
        </div>
      </li>
      <li v-if="auth.isAdmin">
        <button
          type="button"
          class="item"
          :class="{ active: activeView === 'admin' }"
          :aria-current="activeView === 'admin' ? 'page' : undefined"
          data-testid="sidebar-nav-admin"
          @click="emit('navigate', 'admin')"
        >
          <AppIcon name="admin" />
          <span class="label">Administration</span>
        </button>
      </li>
    </ul>

    <div class="sidebar-context">
      <section v-if="auth.conversations.length" class="sidebar-history" aria-label="Unterhaltungen">
        <div class="sidebar-section-head"><span>Unterhaltungen</span>
          <button
            v-if="auth.conversations.length"
            type="button"
            class="toggle"
            :aria-expanded="chatsOpen"
            :aria-label="chatsOpen ? 'Verlauf einklappen' : 'Verlauf ausklappen'"
            data-testid="sidebar-chats-toggle"
            @click="toggleChats"
          >
            <span class="tree-caret" :class="{ open: chatsOpen }" aria-hidden="true"><AppIcon name="chevron" :size="13" /></span>
          </button>
        </div>
        <ul v-if="chatsOpen && auth.conversations.length" class="sub chats" role="list" data-testid="chat-conv-list">
          <li v-for="c in visibleChats" :key="c.id" class="chat-row">
            <button
              type="button"
              class="item subitem chat"
              :class="{ active: activeView === 'chat' && c.id === auth.activeConversationId }"
              data-testid="chat-conv-item"
              :title="c.title"
              @click="openChat(c.id)"
            >
              <span class="label">{{ c.title }}</span>
              <span v-if="c.knowledgeBaseId !== null" class="kb-mark" title="Mit Wissen"><AppIcon name="book" :size="13" /></span>
            </button>
            <button
              type="button"
              class="chat-del"
              data-testid="chat-conv-delete"
              :data-conv-id="c.id"
              title="Unterhaltung löschen"
              aria-label="Unterhaltung löschen"
              @click="deleteChat(c.id)"
            >
              <AppIcon name="trash" :size="13" />
            </button>
          </li>
          <li v-if="auth.conversations.length > 6">
            <button type="button" class="item subitem muted" @click="showAllChats = !showAllChats">
              {{ showAllChats ? "Weniger anzeigen" : `Alle ${auth.conversations.length} anzeigen` }}
            </button>
          </li>
        </ul>
      </section>

    <!-- Arbeitsbereiche als Baum -->
    <div v-if="auth.spaces.length" class="section">
      <p class="section-title">Arbeitsbereiche</p>
      <ul class="tree" role="list">
        <li v-for="space in auth.spaces" :key="space.id">
          <button
            type="button"
            class="item"
            :class="{ current: space.id === auth.selectedSpaceId }"
            :aria-expanded="space.id === auth.selectedSpaceId"
            :data-testid="`sidebar-space-${space.id}`"
            @click="openSpace(space.id)"
          >
            <span class="tree-caret" :class="{ open: space.id === auth.selectedSpaceId }" aria-hidden="true"><AppIcon name="chevron" :size="13" /></span>
            <span class="space-logo" aria-hidden="true">{{ initialsOf(space.name).slice(0, 1) }}</span>
            <span class="label">{{ space.name }}</span>
          </button>
          <ul v-if="space.id === auth.selectedSpaceId" class="sub" role="list">
            <li v-for="kb in auth.kbs" :key="kb.id">
              <button
                type="button"
                class="item subitem"
                :class="{ active: activeView === 'wissen' && kb.id === auth.selectedKbId }"
                :data-testid="`sidebar-kb-${kb.id}`"
                @click="openKb(kb.id)"
              >
                <AppIcon name="folder" :size="13" />
                <span class="label">{{ kb.name }}</span>
              </button>
            </li>
            <li v-if="auth.kbs.length === 0">
              <button type="button" class="item subitem muted" @click="emit('navigate', 'wissen')">Wissensbasis anlegen …</button>
            </li>
          </ul>
        </li>
      </ul>
    </div>

    </div>

    <div class="foot" data-testid="sidebar-status">
      <span class="dot" :style="{ background: statusTone }" aria-hidden="true"></span>
      <span>{{ statusLabel }}</span>
      <span v-if="status === 'connected' && modelState" class="sep" data-testid="sidebar-model">· {{ modelLabel }}</span>
      <span v-if="status === 'connected' && serverVersion" class="ver ic-num" data-testid="sidebar-version" :title="`Server-Version ${serverVersion}`">v{{ serverVersion }}</span>
      <button type="button" class="reload" data-testid="sidebar-reload" title="Neu laden" aria-label="Neu laden" @click="emit('reload')">
        <AppIcon name="refresh" :size="13" />
      </button>

    </div>
  </nav>
</template>

<style scoped>
.sidebar-context { flex:1; min-height:0; overflow-y:auto; overflow-x:hidden; overscroll-behavior:contain; padding-top:20px; scrollbar-width:thin; scrollbar-color:var(--ic-separator) transparent; }
.sidebar-section-head { display:flex; align-items:center; justify-content:space-between; min-height:32px; padding:0 10px 8px; color:var(--ic-text-3); font:500 12px/20px var(--ic-font); }
.sidebar-section-head .toggle { position:static; display:inline-flex; align-items:center; justify-content:center; width:24px; height:24px; border:0; border-radius:4px; color:var(--ic-text-3); background:transparent; cursor:pointer; }
.sidebar-section-head .toggle:hover { background:var(--ic-hover-tint); }
.nav { flex:none; }
@media(max-width:767px),(any-pointer:coarse) { .sidebar-section-head .toggle { width:44px; height:44px; } }

.sb {
  display: flex;
  flex-direction: column;
  flex: none;
  width: 252px;
  min-height: 0;
  padding: 10px 10px 8px;
  background: var(--ic-sidebar);
  border-right: 1px solid var(--ic-separator);
  overflow: hidden;
}
.head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}
.org {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 5px 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ic-text);
  font: 600 14px/1.2 var(--ic-font);
  text-align: left;
  cursor: pointer;
}
.org:hover,
.org[data-state="open"] {
  background: var(--ic-hover-tint);
}
.org svg {
  color: var(--ic-text-3);
  flex: none;
}
.org-none {
  font-weight: 400;
  color: var(--ic-text-3);
  cursor: default;
}
.org-logo {
  display: inline-grid;
  place-items: center;
  width: 22px;
  height: 22px;
  flex: none;
  border-radius: 5px;
  background: var(--ic-primary);
  color: var(--ic-on-primary);
  font-size: 10px;
  font-weight: 700;
}
.org-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.me {
  position: relative;
  flex: none;
  padding: 3px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  cursor: pointer;
}
.me:hover .me-avatar,
.me[data-state="open"] .me-avatar {
  box-shadow: 0 0 0 2px var(--ic-separator);
}
.me-avatar {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--ic-surface);
  box-shadow: 0 0 0 1px var(--ic-separator);
  color: var(--ic-text-2);
  font-size: 10px;
  font-weight: 600;
}
.me-dot {
  position: absolute;
  right: 2px;
  bottom: 2px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  box-shadow: 0 0 0 2px var(--ic-sidebar);
}
.quick {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}
.new,
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  border: 1px solid var(--ic-separator);
  border-radius: 7px;
  background: var(--ic-surface);
  color: var(--ic-text);
  font: 500 14px/1 var(--ic-font);
  cursor: pointer;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .03);
  transition: background var(--ic-transition), border-color var(--ic-transition);
}
.new {
  flex: 1;
  padding: 0 10px;
}
.search {
  justify-content: center;
  width: 34px;
  flex: none;
  color: var(--ic-text-2);
}
.new:hover:not(:disabled),
.search:hover {
  background: var(--ic-hover-tint);
}
.new:disabled {
  opacity: .5;
  cursor: default;
}
.nav,
.tree,
.sub {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1px;
}
.nav li,
.tree li {
  min-width: 0;
}
.item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 30px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ic-text-2);
  font: 500 14px/1 var(--ic-font);
  text-align: left;
  cursor: pointer;
  transition: background var(--ic-transition), color var(--ic-transition);
}
.item:hover {
  background: var(--ic-hover-tint);
  color: var(--ic-text);
}
.item.active {
  background: var(--ic-selection);
  color: var(--ic-text);
}
.item.current {
  color: var(--ic-text);
}
.item-wrap {
  position: relative;
  display: flex;
  align-items: center;
}
.toggle {
  position: absolute;
  right: 4px;
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--ic-text-3);
  cursor: pointer;
}
.toggle:hover {
  background: var(--ic-selection);
  color: var(--ic-text);
}
.chats .subitem.chat {
  padding-left: 34px;
  padding-right: 30px;
}
.chat-row {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
}
.chat-row > .item {
  flex: 1;
  min-width: 0;
}
.kb-mark {
  display: inline-flex;
  color: var(--ic-text-3);
}
.chat-del {
  position: absolute;
  right: 4px;
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 5px;
  background: var(--ic-sidebar);
  color: var(--ic-text-3);
  opacity: 0;
  cursor: pointer;
}
.chat-row:hover .chat-del,
.chat-del:focus-visible {
  opacity: 1;
}
.chat-del:hover {
  color: var(--ic-status-error);
}
.label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.count {
  font-size: 12px;
  color: var(--ic-text-3);
}
.section {
  margin-top: 18px;
}
.section-title {
  margin: 0 0 4px;
  padding: 0 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ic-text-3);
}
.tree-caret {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  min-width: 14px;
  height: 14px;
  flex: none;
  color: var(--ic-text-3);
  transition: transform var(--ic-transition);
}
.tree-caret.open {
  transform: rotate(90deg);
}
.space-logo {
  display: inline-grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex: none;
  margin-left: -4px;
  border-radius: 4px;
  background: var(--ic-hover-tint);
  color: var(--ic-text-2);
  font-size: 10px;
  font-weight: 700;
}
.sub {
  margin: 1px 0 4px;
}
.subitem {
  padding-left: 38px;
  font-weight: 400;
  height: 28px;
}
.subitem.muted {
  color: var(--ic-text-3);
}
.grow {
  flex: 1;
  min-height: 16px;
}
.foot {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px 0;
  font-size: 12px;
  color: var(--ic-text-3);
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
}
.ver {
  margin-left: auto;
}
.reload {
  display: inline-grid;
  place-items: center;
  min-width: 24px;
  min-height: 24px;
  padding: 4px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--ic-text-3);
  cursor: pointer;
}
.reload:hover {
  background: var(--ic-hover-tint);
  color: var(--ic-text);
}
.sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
@media (max-width: 760px) {
  .sb {
    width: 100%;
    border-right: 0;
    border-bottom: 1px solid var(--ic-separator);
  }
}
</style>

<style>
</style>
