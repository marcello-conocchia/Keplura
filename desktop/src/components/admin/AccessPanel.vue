<script setup lang="ts">
/**
 * ACCESS-01 — Rechte-Nachweis: „Warum sieht diese Person dieses Dokument (nicht)?“
 * Das Ergebnis stammt serverseitig aus derselben Abfrage wie Suche und Chat;
 * die Begründung erklärt es Schritt für Schritt. Nur lesend.
 */
import { computed, onBeforeUnmount, ref, watch } from "vue";
import {
  accessOverview, explainAccess, principalLabel, resultText, searchAccessDocuments, searchAccessUsers,
  type AccessDocument, type AccessExplanation, type AccessOverview, type AccessUser,
} from "../../datasource-api";
import Input from "../ui/Input.vue";
import StatusPill from "../ui/StatusPill.vue";
import { tr, dateLocale } from "../../i18n";

const userQuery = ref(""), docQuery = ref("");
const userHits = ref<AccessUser[]>([]), docHits = ref<AccessDocument[]>([]);
const user = ref<AccessUser | null>(null), doc = ref<AccessDocument | null>(null);
const overview = ref<AccessOverview | null>(null);
const explanation = ref<AccessExplanation | null>(null);
const error = ref(""), loading = ref(false);
let userTimer: ReturnType<typeof setTimeout> | null = null, docTimer: ReturnType<typeof setTimeout> | null = null;
let userReq = 0, docReq = 0, explainReq = 0;

onBeforeUnmount(() => { if (userTimer) clearTimeout(userTimer); if (docTimer) clearTimeout(docTimer); explainReq++; });

watch(userQuery, q => {
  if (userTimer) clearTimeout(userTimer);
  if (user.value && q === user.value.displayName) return;
  userTimer = setTimeout(async () => {
    const req = ++userReq;
    if (!q.trim()) { userHits.value = []; return; }
    const r = await searchAccessUsers(q);
    if (req !== userReq) return;
    if (r.kind === "ok") userHits.value = r.value; else error.value = resultText(r);
  }, 250);
});
watch(docQuery, q => {
  if (docTimer) clearTimeout(docTimer);
  if (doc.value && q === doc.value.filename) return;
  docTimer = setTimeout(async () => {
    const req = ++docReq;
    if (!q.trim()) { docHits.value = []; return; }
    const r = await searchAccessDocuments(q);
    if (req !== docReq) return;
    if (r.kind === "ok") docHits.value = r.value; else error.value = resultText(r);
  }, 250);
});

async function pickUser(u: AccessUser) {
  user.value = u; userQuery.value = u.displayName; userHits.value = []; error.value = "";
  overview.value = null;
  const r = await accessOverview(u.id);
  if (user.value?.id !== u.id) return;
  if (r.kind === "ok") overview.value = r.value; else error.value = resultText(r);
  await run();
}
async function pickDoc(d: AccessDocument) {
  doc.value = d; docQuery.value = d.filename; docHits.value = []; error.value = "";
  await run();
}
async function run() {
  explanation.value = null;
  if (!user.value || !doc.value) return;
  const req = ++explainReq;
  loading.value = true;
  const r = await explainAccess(user.value.id, doc.value.id);
  if (req !== explainReq) return;
  loading.value = false;
  if (r.kind === "ok") explanation.value = r.value; else error.value = resultText(r);
}

const matchingGroups = computed(() => explanation.value?.userGroups.filter(g => g.matchesUser) ?? []);
function when(iso: string | null) {
  if (!iso) return tr("noch nie");
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return tr("gerade eben");
  if (minutes < 120) return tr("vor {0} Min.", minutes);
  return new Date(iso).toLocaleString(dateLocale(), { dateStyle: "short", timeStyle: "short" });
}
function mark(ok: boolean | null) { return ok === true ? "✓" : ok === false ? "✕" : "i"; }
</script>

<template>
  <section class="ax" data-testid="admin-access-section">
    <header>
      <h2>{{ $t("Zugriff prüfen") }}</h2>
      <p class="ax-lead">{{ $t("Warum findet eine Person ein Dokument, oder warum nicht? Das Ergebnis kommt aus derselben Prüfung, die auch Suche und Chat verwenden. Hier wird nur gelesen, nichts verändert.") }}</p>
    </header>
    <p v-if="error" class="ax-error" role="alert">{{ error }}</p>

    <div class="ax-pickers">
      <div class="ax-picker">
        <Input id="access-user" v-model="userQuery" :label="$t('Person')" icon="users" :placeholder="$t('Name oder E-Mail …')" data-testid="access-user-search" />
        <ul v-if="userHits.length" class="ax-hits" role="listbox" :aria-label="$t('Gefundene Personen')">
          <li v-for="u in userHits" :key="u.id"><button type="button" role="option" :aria-selected="user?.id === u.id" @click="pickUser(u)">
            <strong>{{ u.displayName }}</strong><span>{{ u.email }} · {{ u.groups }} {{ u.groups === 1 ? $t("Gruppe") : $t("Gruppen") }}</span></button></li>
        </ul>
      </div>
      <div class="ax-picker">
        <Input id="access-doc" v-model="docQuery" :label="$t('Dokument')" icon="doc" :placeholder="$t('Dateiname …')" data-testid="access-doc-search" />
        <ul v-if="docHits.length" class="ax-hits" role="listbox" :aria-label="$t('Gefundene Dokumente')">
          <li v-for="d in docHits" :key="d.id"><button type="button" role="option" :aria-selected="doc?.id === d.id" @click="pickDoc(d)">
            <strong>{{ d.filename }}</strong><span>{{ d.location }} · {{ d.origin }}</span></button></li>
        </ul>
      </div>
    </div>

    <p v-if="loading" role="status" class="ax-muted">{{ $t("Wird geprüft …") }}</p>

    <article v-if="explanation" class="ax-card" data-testid="access-explanation">
      <div class="ax-verdict">
        <StatusPill :tone="explanation.visible ? 'ok' : 'error'" data-testid="access-verdict">{{ explanation.visible ? $t("Sichtbar") : $t("Nicht sichtbar") }}</StatusPill>
        <div><strong>{{ explanation.userName }}</strong> → <strong>{{ explanation.filename }}</strong><span class="ax-muted">{{ explanation.location }}</span></div>
      </div>
      <ol class="ax-steps" data-testid="access-steps">
        <li v-for="(s, i) in explanation.steps" :key="i" :class="s.ok === true ? 'ok' : s.ok === false ? 'no' : 'info'">
          <span class="ax-mark" aria-hidden="true">{{ mark(s.ok) }}</span>
          <div><p>{{ s.title }}</p><p v-if="s.detail" class="ax-muted">{{ s.detail }}</p></div>
        </li>
      </ol>
      <p v-if="!explanation.consistent" class="ax-warn">{{ $t("Die Begründung passt nicht zum tatsächlichen Ergebnis. Maßgeblich ist das Ergebnis oben; bitte als Fehler melden.") }}</p>

      <template v-if="explanation.acl.length">
        <h3>{{ $t("Rechte der Datei") }}</h3>
        <div class="ax-table-wrap"><table class="ax-table">
          <thead><tr><th scope="col">{{ $t("Person oder Gruppe") }}</th><th scope="col">{{ $t("Recht") }}</th><th scope="col">{{ $t("Betrifft {0}", explanation.userName) }}</th></tr></thead>
          <tbody><tr v-for="a in explanation.acl" :key="a.principal + a.allow" :class="{ hit: a.matchesUser }">
            <td :title="a.principal">{{ principalLabel(a.principal, a.label) }}</td>
            <td>{{ a.allow ? $t("Lesen erlaubt") : $t("Verweigert") }}</td>
            <td>{{ a.matchesUser ? $t("Ja") : '–' }}</td></tr></tbody>
        </table></div>
      </template>
      <p class="ax-muted">{{ $t("Gruppen von {0} zuletzt abgeglichen: {1}", explanation.userName, when(explanation.groupsRefreshedAt)) }}
        <template v-if="matchingGroups.length"> {{ $t("· relevant hier: {0}", matchingGroups.map(g => principalLabel(g.principal, g.label)).join(', ')) }}</template></p>
    </article>

    <article v-if="overview" class="ax-card" data-testid="access-overview">
      <h3>{{ $t("Was findet {0} insgesamt?", overview.userName) }}</h3>
      <div v-if="overview.knowledgeBases.length" class="ax-table-wrap"><table class="ax-table">
        <thead><tr><th scope="col">{{ $t("Wissensbasis") }}</th><th scope="col" class="n">{{ $t("Sichtbar") }}</th><th scope="col" class="n">{{ $t("Dokumente") }}</th></tr></thead>
        <tbody><tr v-for="k in overview.knowledgeBases" :key="k.kbId"><td>{{ k.location }}</td>
          <td class="n ic-num">{{ k.visible }}</td><td class="n ic-num">{{ k.total }}</td></tr></tbody>
      </table></div>
      <p v-else class="ax-muted">{{ $t("{0} ist in keiner Organisation Mitglied und findet daher keine Dokumente.", overview.userName) }}</p>
      <p class="ax-muted">{{ $t("Gruppen zuletzt abgeglichen: {0}", when(overview.groupsRefreshedAt)) }}</p>
    </article>

    <p v-if="!user && !doc" class="ax-muted">{{ $t("Wähle eine Person, um zu sehen, was sie findet, und zusätzlich ein Dokument für die genaue Begründung.") }}</p>
  </section>
</template>

<style scoped>
.ax { display:grid; gap:20px; min-width:0; }
.ax h2 { margin:0; font:600 20px/28px var(--ic-font); }
.ax h3 { margin:8px 0 0; font:600 14px/20px var(--ic-font); }
.ax-lead,.ax-muted { margin:6px 0 0; color:var(--ic-text-2); font:400 13px/20px var(--ic-font); max-width:72ch; }
.ax-muted { display:block; margin:0; }
.ax-error { margin:0; padding:12px 16px; border-radius:var(--ic-radius-panel); color:var(--ic-status-error); background:var(--ic-status-error-bg); }
.ax-warn { margin:0; padding:12px 16px; border-left:3px solid var(--ic-status-wait); background:var(--ic-status-wait-bg); color:var(--ic-status-wait); font-size:14px; }
.ax-pickers { display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:16px; }
.ax-picker { position:relative; min-width:0; }
.ax-picker :deep(.ic-field) { margin:0; }
.ax-hits { list-style:none; margin:4px 0 0; padding:4px; border:1px solid var(--ic-separator); border-radius:8px; background:var(--ic-surface); max-height:260px; overflow:auto; }
.ax-hits button { display:grid; gap:2px; width:100%; padding:8px 10px; border:0; border-radius:6px; background:transparent; color:var(--ic-text); text-align:left; cursor:pointer; font:inherit; }
.ax-hits button:hover,.ax-hits button:focus-visible { background:var(--ic-hover-tint); }
.ax-hits span { color:var(--ic-text-2); font-size:12px; overflow-wrap:anywhere; }
.ax-card { display:grid; gap:14px; padding:16px; border:1px solid var(--ic-separator); border-radius:var(--ic-radius-panel); min-width:0; }
.ax-verdict { display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
.ax-verdict > div { display:grid; gap:2px; min-width:0; overflow-wrap:anywhere; }
.ax-steps { list-style:none; margin:0; padding:0; display:grid; gap:10px; }
.ax-steps li { display:grid; grid-template-columns:24px minmax(0,1fr); gap:10px; align-items:start; }
.ax-steps p { margin:0; font-size:14px; line-height:20px; }
.ax-mark { display:grid; place-items:center; width:22px; height:22px; border-radius:999px; font:600 12px/1 var(--ic-font); }
.ok .ax-mark { color:var(--ic-status-ok); background:var(--ic-status-ok-bg); }
.no .ax-mark { color:var(--ic-status-error); background:var(--ic-status-error-bg); }
.info .ax-mark { color:var(--ic-text-2); background:var(--ic-surface-subtle); }
.ax-table-wrap { overflow-x:auto; border:1px solid var(--ic-separator); border-radius:8px; }
.ax-table { width:100%; border-collapse:collapse; font-size:13px; }
.ax-table th,.ax-table td { padding:8px 12px; text-align:left; border-bottom:1px solid var(--ic-separator-faint); overflow-wrap:anywhere; }
.ax-table th { color:var(--ic-text-2); font-weight:500; }
.ax-table .n { text-align:right; white-space:nowrap; }
.ax-table tr.hit td { font-weight:600; }
</style>
