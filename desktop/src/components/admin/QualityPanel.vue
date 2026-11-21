<script setup lang="ts">
/**
 * DS-5 — Antwortqualität messbar machen: Testfragen je Wissensbasis,
 * Prüflauf mit Trefferquote, Verlauf, Daumen-Statistik (nur Zahlen).
 */
import { computed, onMounted, ref, watch } from "vue";
import { useAuthStore } from "../../auth-store";
import {
  addQualityQuestion, deleteQualityQuestion, feedbackStats, importQualityQuestions, qualityOverview,
  resultText, runQualityCheck, type QualityOverview,
} from "../../datasource-api";
import AppDialog from "../ui/AppDialog.vue";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";
import { tr, dateLocale } from "../../i18n";

const auth = useAuthStore();
const kbId = ref<number | null>(auth.selectedKbId ?? auth.kbs[0]?.id ?? null);
const data = ref<QualityOverview | null>(null);
const stats = ref<{ up: number; down: number; answersLast30Days: number } | null>(null);
const note = ref("");
const noteKind = ref<"ok" | "error" | "">("");
const running = ref(false);

const target = computed(() =>
  kbId.value !== null && auth.selectedOrgId !== null && auth.selectedSpaceId !== null
    ? { orgId: auth.selectedOrgId, spaceId: auth.selectedSpaceId, knowledgeBaseId: kbId.value }
    : null,
);
const latest = computed(() => data.value?.runs[0] ?? null);

function show(kind: "ok" | "error", t: string) {
  noteKind.value = kind;
  note.value = t;
}

async function load() {
  if (kbId.value === null) return;
  const r = await qualityOverview(kbId.value);
  if (r.kind === "ok") data.value = r.value;
  else show("error", resultText(r));
  if (auth.selectedOrgId !== null) {
    const s = await feedbackStats(auth.selectedOrgId);
    if (s.kind === "ok") stats.value = s.value;
  }
}
onMounted(load);
watch(kbId, load);

async function runCheck() {
  if (!target.value) return;
  running.value = true;
  note.value = "";
  const r = await runQualityCheck(target.value);
  running.value = false;
  if (r.kind === "ok") show("ok", tr("Prüfung abgeschlossen: {0} von {1} Fragen finden die richtige Quelle.", r.value.hits, r.value.total));
  else show("error", resultText(r));
  await load();
}

/* Frage hinzufügen */
const addOpen = ref(false);
const q = ref({ question: "", doc: "", phrase: "" });
const addError = ref("");
async function submitAdd() {
  if (!target.value) return;
  addError.value = "";
  const r = await addQualityQuestion(target.value, q.value.question, q.value.doc, q.value.phrase);
  if (r.kind !== "ok") {
    addError.value = resultText(r);
    return;
  }
  q.value = { question: "", doc: "", phrase: "" };
  addOpen.value = false;
  await load();
}

/* Liste einfügen */
const importOpen = ref(false);
const csv = ref("");
const importError = ref("");
async function submitImport() {
  if (!target.value) return;
  importError.value = "";
  const r = await importQualityQuestions(target.value, csv.value);
  if (r.kind !== "ok") {
    importError.value = resultText(r);
    return;
  }
  importOpen.value = false;
  csv.value = "";
  show(r.value.skipped ? "error" : "ok",
    tr("{0} Fragen übernommen{1}", r.value.imported, r.value.skipped ? tr(", {0} übersprungen: {1}", r.value.skipped, r.value.problems.join(" ")) : "."));
  await load();
}

async function remove(id: number) {
  await deleteQualityQuestion(id);
  await load();
}

function day(iso: string) {
  return new Date(iso).toLocaleDateString(dateLocale(), { day: "2-digit", month: "2-digit" });
}
</script>

<template>
  <section class="qp" data-testid="admin-quality-section">
    <p class="qp-lead">
      {{ $t("Hinterlege typische Fragen deiner Kolleginnen und Kollegen und die Datei, in der die Antwort steht. Keplura prüft dann, ob es die richtige Stelle findet – vor dem Pilot und nach jeder größeren Änderung.") }}
    </p>

    <div class="qp-bar">
      <label class="qp-kb">{{ $t("Wissensbasis") }}
        <select class="ic-native-control" v-model="kbId" data-testid="quality-kb">
          <option v-for="k in auth.kbs" :key="k.id" :value="k.id">{{ k.name }}</option>
        </select>
      </label>
      <Button variant="primary" :disabled="running || !data?.questions.length" data-testid="quality-run" @click="runCheck">
        {{ running ? $t("Prüfung läuft …") : $t("Prüfung starten") }}
      </Button>
    </div>
    <p v-if="note" class="qp-note" :class="noteKind" role="status">{{ note }}</p>

    <div class="qp-cards">
      <div class="qp-card" data-testid="quality-score">
        <span class="qp-big ic-num">{{ latest ? latest.percent + " %" : "–" }}</span>
        <span class="qp-sub">{{ latest ? $t("{0} von {1} Fragen finden die richtige Quelle", latest.hits, latest.total) : $t("Noch keine Prüfung") }}</span>
        <span v-if="data && data.runs.length > 1" class="qp-trend">
          {{ $t("Verlauf:") }} <span v-for="r in [...data.runs].reverse()" :key="r.id" class="ic-num">{{ day(r.startedAt) }} {{ r.percent }} % · </span>
        </span>
      </div>
      <div class="qp-card" data-testid="quality-feedback">
        <span class="qp-big ic-num">
          <AppIcon name="thumb-up" :size="15" /> {{ stats?.up ?? 0 }}
          <AppIcon name="thumb-down" :size="15" /> {{ stats?.down ?? 0 }}
        </span>
        <span class="qp-sub">{{ $t("Bewertungen der letzten 30 Tage bei {0} Antworten (nur Zahlen, keine Inhalte)", stats?.answersLast30Days ?? 0) }}</span>
      </div>
    </div>

    <div class="qp-list">
      <div class="qp-head">
        <span>{{ $t("Testfragen") }}</span>
        <span class="qp-count ic-num">{{ data?.questions.length ?? 0 }}</span>
        <span class="qp-head-actions">
          <Button variant="ghost" data-testid="quality-import" @click="importOpen = true">{{ $t("Liste einfügen") }}</Button>
          <Button variant="secondary" data-testid="quality-add" @click="addOpen = true"><AppIcon name="plus" :size="13" /> {{ $t("Frage") }}</Button>
        </span>
      </div>
      <div v-for="item in data?.questions ?? []" :key="item.id" class="qp-row" data-testid="quality-question">
        <span class="qp-ring" :class="item.lastHit === null ? 'none' : item.lastHit ? 'ok' : 'error'"
              :title="item.lastHit === null ? $t('noch nicht geprüft') : item.lastHit ? 'gefunden' : $t('nicht gefunden')"></span>
        <div class="qp-main">
          <span class="qp-q">{{ item.question }}</span>
          <span class="qp-meta">{{ $t("Erwartet: {0}", item.expectedDocument) }}<template v-if="item.expectedPhrase"> · „{{ item.expectedPhrase }}“</template>
            <template v-if="item.lastHit === true"> {{ $t("· gefunden an Stelle {0}", item.lastRank) }}</template>
            <template v-else-if="item.lastHit === false"> {{ $t("· nicht gefunden") }}<template v-if="item.lastTopDocument">{{ $t(", stattdessen: {0}", item.lastTopDocument) }}</template></template>
          </span>
        </div>
        <button type="button" class="qp-del" :aria-label="$t('Frage löschen: {0}', item.question)" :title="$t('Löschen')" @click="remove(item.id)">
          <AppIcon name="trash" :size="13" />
        </button>
      </div>
      <p v-if="!data?.questions.length" class="qp-empty">
        {{ $t("Noch keine Testfragen. Empfehlung: 30–50 echte Fragen aus dem Arbeitsalltag, je mit der Datei, in der die Antwort steht.") }}
      </p>
    </div>

    <AppDialog :open="addOpen" :title="$t('Testfrage hinzufügen')" :error="addError" @close="addOpen = false">
      <form class="qp-form" @submit.prevent="submitAdd">
        <label>{{ $t("Frage") }} <input class="ic-native-control" v-model="q.question" required maxlength="2000" :placeholder="$t('Bis zu welchem Betrag werden Hotels erstattet?')" /></label>
        <label>{{ $t("Datei mit der Antwort (Name oder Teil davon)") }} <input class="ic-native-control" v-model="q.doc" required maxlength="500" :placeholder="$t('Reisekostenrichtlinie')" /></label>
        <label>{{ $t("Stichwort in der Fundstelle (optional)") }} <input class="ic-native-control" v-model="q.phrase" maxlength="500" :placeholder="$t('120 Euro')" /></label>
        <div class="qp-actions"><Button variant="primary" type="submit">{{ $t("Hinzufügen") }}</Button></div>
      </form>
    </AppDialog>

    <AppDialog :open="importOpen" :title="$t('Liste einfügen')" :error="importError" @close="importOpen = false">
      <form class="qp-form" @submit.prevent="submitImport">
        <p class="qp-sub">{{ $t("Je Zeile: Frage; Datei; Stichwort (optional). Aus Excel kopierte Spalten (Tabulator) funktionieren auch.") }}</p>
        <textarea class="ic-native-control" v-model="csv" rows="10" :placeholder="$t('Wie viele Urlaubstage habe ich?;Urlaubsregelung;30 Tage')"></textarea>
        <div class="qp-actions"><Button variant="primary" type="submit" :disabled="!csv.trim()">{{ $t("Übernehmen") }}</Button></div>
      </form>
    </AppDialog>
  </section>
</template>

<style scoped>
.qp { display: grid; gap: 16px; }
.qp-lead { margin: 0; color: var(--ic-text-2); font-size: 14px; line-height: 1.5; }
.qp-bar { display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap; }
.qp-kb { display: grid; gap: 4px; font-size: 13px; font-weight: 500; }
.qp-note { margin: 0; padding: 8px 12px; border-radius: 6px; font-size: 13px; background: var(--ic-hover-tint); }
.qp-note.ok { color: var(--ic-status-ok); }
.qp-note.error { color: var(--ic-status-error); }
.qp-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
.qp-card { display: grid; gap: 4px; padding: 14px 16px; border: 1px solid var(--ic-separator); border-radius: 8px; }
.qp-big { display: inline-flex; align-items: center; gap: 8px; font-size: 24px; font-weight: 600; }
.qp-sub { font-size: 13px; color: var(--ic-text-3); line-height: 1.5; }
.qp-trend { font-size: 12px; color: var(--ic-text-3); }
.qp-list { border: 1px solid var(--ic-separator); border-radius: 8px; overflow: hidden; }
.qp-head { display: flex; align-items: center; gap: 10px; min-height: 40px; padding: 4px 8px 4px 14px; background: var(--ic-hover-tint); font-size: 13px; font-weight: 500; }
.qp-count { color: var(--ic-text-3); }
.qp-head-actions { margin-left: auto; display: flex; gap: 4px; }
.qp-row { display: flex; align-items: center; gap: 12px; padding: 10px 14px; border-top: 1px solid var(--ic-separator-faint); }
.qp-ring { width: 12px; height: 12px; flex: none; border-radius: 50%; border: 2px solid var(--ic-text-3); }
.qp-ring.ok { border-color: var(--ic-status-ok); background: radial-gradient(circle, var(--ic-status-ok) 0 2.5px, transparent 3px); }
.qp-ring.error { border-color: var(--ic-status-error); }
.qp-main { flex: 1; min-width: 0; display: grid; gap: 2px; }
.qp-q { font-size: 14px; }
.qp-meta { font-size: 12px; color: var(--ic-text-3); }
.qp-del { display: inline-grid; place-items: center; width: 26px; height: 26px; border: 0; border-radius: 5px; background: transparent; color: var(--ic-text-3); cursor: pointer; opacity: 0; }
.qp-row:hover .qp-del, .qp-del:focus-visible { opacity: 1; }
.qp-del:hover { color: var(--ic-status-error); }
.qp-empty { margin: 0; padding: 16px 14px; border-top: 1px solid var(--ic-separator-faint); color: var(--ic-text-3); font-size: 13px; }
.qp-form { display: grid; gap: 12px; }
.qp-form label { display: grid; gap: 4px; font-size: 13px; font-weight: 500; }
.qp-actions { display: flex; justify-content: flex-end; }
@media (hover: none) { .qp-del { opacity: 1; } }
</style>
