<script setup lang="ts">
/**
 * RISK-01 — Oversharing-Check: zeigt VOR der Übernahme, welche Dateien einer
 * Quelle für (praktisch) alle lesbar sind und welche davon nach Pfad/Name
 * sensibel wirken. Ausschließen ändert nur die Ausschlussliste in Keplura,
 * nie die Ablage selbst.
 */
import { computed, onBeforeUnmount, ref, watch } from "vue";
import {
  contentHitText, contentReasonText, excludeFromSource, exposureReport, principalLabel, resultText, startExposureScan,
  type DataSource, type ExposureFinding, type ExposureReport,
} from "../../datasource-api";
import AppDialog from "../ui/AppDialog.vue";
import Button from "../ui/Button.vue";
import StatusPill from "../ui/StatusPill.vue";
import { tr, dateLocale } from "../../i18n";

const props = defineProps<{ source: DataSource | null }>();
const emit = defineEmits<{ close: []; updated: [source: DataSource] }>();

const report = ref<ExposureReport | null>(null);
const error = ref("");
const note = ref("");
const starting = ref(false);
const excluding = ref(false);
const selected = ref(new Set<string>());
let poll: ReturnType<typeof setTimeout> | null = null;
let request = 0;

const running = computed(() => report.value?.state === "RUNNING");
const done = computed(() => report.value?.state === "DONE");

function stopPoll() { if (poll) { clearTimeout(poll); poll = null; } }
onBeforeUnmount(() => { request++; stopPoll(); });

async function refresh(id: number, req: number) {
  const r = await exposureReport(id);
  if (req !== request) return;
  if (r.kind === "ok") {
    report.value = r.value;
    if (r.value.state === "RUNNING") poll = setTimeout(() => void refresh(id, req), 1500);
  } else if (r.kind === "invalid") {
    report.value = null; // noch keine Prüfung
  } else {
    error.value = resultText(r);
  }
}

async function start() {
  const s = props.source;
  if (!s || starting.value || running.value) return;
  starting.value = true; error.value = ""; note.value = "";
  stopPoll();
  const req = ++request;
  const r = await startExposureScan(s.id);
  starting.value = false;
  if (req !== request) return;
  if (r.kind !== "ok" && r.kind !== "busy") { error.value = resultText(r); return; }
  selected.value = new Set();
  await refresh(s.id, req);
}

watch(() => props.source?.id, async id => {
  stopPoll();
  const req = ++request;
  report.value = null; error.value = ""; note.value = ""; selected.value = new Set();
  if (id == null) return;
  await refresh(id, req);
  // Erster Aufruf ohne vorhandenen Bericht: gleich prüfen, der Admin hat „Prüfen“ gewählt.
  if (req === request && report.value === null && !error.value) await start();
}, { immediate: true });

function toggle(path: string) {
  const next = new Set(selected.value);
  if (next.has(path)) next.delete(path); else next.add(path);
  selected.value = next;
}
const allSelected = computed(() => !!report.value?.findings.length
  && report.value.findings.every(f => selected.value.has(f.relativePath)));
function toggleAll() {
  selected.value = allSelected.value ? new Set() : new Set(report.value?.findings.map(f => f.relativePath) ?? []);
}

async function exclude() {
  const s = props.source;
  if (!s || excluding.value || selected.value.size === 0) return;
  excluding.value = true; error.value = ""; note.value = "";
  const paths = [...selected.value].slice(0, 200);
  const r = await excludeFromSource(s.id, paths);
  excluding.value = false;
  if (r.kind !== "ok") { error.value = resultText(r); return; }
  emit("updated", r.value);
  if (report.value) {
    const gone = new Set(paths);
    report.value = { ...report.value, findings: report.value.findings.filter(f => !gone.has(f.relativePath)) };
  }
  selected.value = new Set([...selected.value].filter(p => !paths.includes(p)));
  note.value = tr("{0} {1} nicht übernommen. ", paths.length, paths.length === 1 ? tr("Datei wird") : tr("Dateien werden"))
    + (s.lastRunStatus ? tr("Bereits übernommene Fassungen entfernt der nächste Abgleich aus Keplura.") : "")
    + tr(" Die Originale in der Ablage bleiben unverändert.");
}

const copied = ref(false);
function listText(): string {
  const r = report.value;
  if (!r || !props.source) return "";
  const lines = [tr("Oversharing-Check „{0}“ ({1})", props.source.displayName, props.source.location),
    tr("{0} von {1} Dateien sind für praktisch alle lesbar.", r.broadFiles, r.filesChecked), "", tr("Ordner mit breiten Leserechten:")];
  for (const f of r.folders) lines.push(tr("  {0}: {1} Dateien{2}", f.folder, f.broadFiles, f.sensitiveFiles ? tr(", davon {0} sensibel wirkend", f.sensitiveFiles) : ""));
  if (r.findings.length) {
    lines.push("", tr("Sensibel wirkende Dateien mit breiten Leserechten:"));
    for (const f of r.findings) lines.push(tr("  {0} — {1} — lesbar für {2}", f.relativePath, reasons(f).join(", "), f.broadGroups.join(", ")));
  }
  return lines.join("\n");
}
async function copyList() {
  try {
    await navigator.clipboard.writeText(listText());
    copied.value = true;
    setTimeout(() => { copied.value = false; }, 2500);
  } catch {
    error.value = tr("Kopieren ist hier nicht möglich. Markiere die Liste und kopiere sie von Hand.");
  }
}

/** Name-Kategorien, Inhaltstreffer und „nicht prüfbar“ als kurze Etiketten. */
function reasons(f: ExposureFinding): string[] {
  const out = [...f.categories.map((c) => tr(c)), ...f.contentHits.map(contentHitText)];
  if (f.contentState === "NOT_CHECKABLE") out.push(tr("Inhalt nicht prüfbar: {0}", contentReasonText(f.contentReason)));
  return out;
}

function pct(n: number, of: number) { return of ? Math.round((n / of) * 100) : 0; }
function when(iso: string | null) {
  return iso ? new Date(iso).toLocaleString(dateLocale(), { dateStyle: "short", timeStyle: "short" }) : "";
}
</script>

<template>
  <AppDialog :open="source !== null" :title="$t('Oversharing-Check')" size="lg" :error="error" :busy="excluding" initial-focus="title" @close="emit('close')">
    <div class="xp" data-testid="exposure-dialog">
      <p class="xp-source">{{ source?.displayName }} <span>{{ source?.location }}</span></p>
      <p class="xp-lead">{{ report?.content?.enabled === false ? $t("Bevor Keplura Dateien übernimmt: Welche sind für praktisch alle Mitarbeitenden lesbar, und welche davon wirken sensibel? Geprüft werden nur Dateiliste und Zugriffsrechte, keine Inhalte. Die Ablage wird nicht verändert.") : $t("Bevor Keplura Dateien übernimmt: Welche sind für praktisch alle Mitarbeitenden lesbar, und welche davon sind sensibel? Geprüft werden Dateiliste, Zugriffsrechte und bei breit lesbaren Dateien der Inhalt, z. B. auf IBAN, Steuer-ID oder Gehaltsangaben. Gefundene Werte werden weder angezeigt noch gespeichert. Die Ablage wird nicht verändert.") }}</p>

      <div v-if="running || starting" class="xp-progress" role="status" data-testid="exposure-running">
        <StatusPill tone="wait">{{ $t("Prüfung läuft") }}</StatusPill>
        <span v-if="report && report.filesTotal">{{ $t("{0} von {1} Dateien geprüft", report.filesChecked, report.filesTotal) }}</span>
        <span v-else>{{ $t("Dateiliste wird gelesen …") }}</span>
        <progress v-if="report && report.filesTotal" :max="report.filesTotal" :value="report.filesChecked" :aria-label="$t('Fortschritt')"></progress>
      </div>

      <div v-else-if="report?.state === 'FAILED'" class="xp-failed" data-testid="exposure-failed">
        <StatusPill tone="error">{{ $t("Prüfung fehlgeschlagen") }}</StatusPill>
        <p>{{ report.message }}</p>
      </div>

      <template v-else-if="done && report">
        <p v-if="report.message" class="xp-warn">{{ report.message }}</p>
        <div class="xp-summary" data-testid="exposure-summary">
          <div><strong class="ic-num">{{ report.filesChecked }}</strong><span>{{ $t("Dateien geprüft") }}</span></div>
          <div :class="{ hot: report.broadFiles }"><strong class="ic-num">{{ report.broadFiles }}</strong><span>{{ $t("für praktisch alle lesbar ({0} %)", pct(report.broadFiles, report.filesChecked)) }}</span></div>
          <div :class="{ hot: report.sensitiveBroadFiles }"><strong class="ic-num">{{ report.sensitiveBroadFiles }}</strong><span>{{ $t("davon mit sensiblem Namen") }}</span></div>
          <div v-if="report.content?.enabled" :class="{ hot: report.content.sensitive }" data-testid="exposure-content-sensitive"><strong class="ic-num">{{ report.content.sensitive }}</strong><span>{{ $t("davon mit sensiblem Inhalt") }}</span></div>
          <div v-if="report.content?.notCheckable" data-testid="exposure-content-unchecked"><strong class="ic-num">{{ report.content.notCheckable }}</strong><span>{{ $t("Inhalt nicht prüfbar") }}</span></div>
          <div v-if="report.unreadable"><strong class="ic-num">{{ report.unreadable }}</strong><span>{{ $t("ohne lesbare Rechte (für niemanden sichtbar)") }}</span></div>
        </div>
        <p class="xp-meta">{{ $t("Geprüft {0}", when(report.finishedAt)) }}</p>

        <p v-if="report.broadFiles === 0" class="xp-ok" data-testid="exposure-clean">{{ $t("Keine Datei ist für alle lesbar. Die Rechte dieser Quelle sind eng gefasst.") }}</p>

        <template v-else>
          <h4>{{ $t("Wer hat breite Leserechte?") }}</h4>
          <ul class="xp-groups">
            <li v-for="g in report.broadGroups" :key="g.principal"><span :title="g.principal">{{ g.label ?? principalLabel(g.principal) }}</span><span class="ic-num">{{ $t("{0} Dateien", g.files) }}</span></li>
          </ul>

          <h4>{{ $t("Ordner mit den meisten breit lesbaren Dateien") }}</h4>
          <div class="xp-table-wrap"><table class="xp-table" data-testid="exposure-folders">
            <thead><tr><th scope="col">{{ $t("Ordner") }}</th><th scope="col" class="n">{{ $t("Breit lesbar") }}</th><th scope="col" class="n">{{ $t("Sensibel wirkend") }}</th></tr></thead>
            <tbody><tr v-for="f in report.folders" :key="f.folder"><td><code>{{ f.folder }}</code></td><td class="n ic-num">{{ f.broadFiles }}</td><td class="n ic-num">{{ f.sensitiveFiles || "–" }}</td></tr></tbody>
          </table></div>

          <h4>{{ $t("Sensibel wirkende Dateien mit breiten Leserechten") }}</h4>
          <p class="xp-hint">{{ report.content?.enabled ? $t("Hinweis aus Ordner- und Dateinamen (z. B. Gehalt, Personalakte, Diagnose, Passwort) und aus dem Inhalt (z. B. IBAN, Kreditkarte, Steuer-ID, Sozialversicherungsnummer, Gehalts- oder Gesundheitsangaben). Angezeigt werden nur Art und Anzahl, nie die Werte. Nicht prüfbare Dateien (zu groß, Scan, verschlüsselt) stehen ebenfalls hier.") : $t("Hinweis aus Ordner- und Dateinamen (z. B. Gehalt, Personalakte, Kündigung, Diagnose, Passwort, Bankverbindung), keine Inhaltsprüfung. Dateien mit unauffälligem Namen können trotzdem sensibel sein; prüfe dafür die Ordnerliste oben.") }}</p>
          <p v-if="report.content?.skipped" class="xp-warn">{{ $t("Der Inhalt von {0} weiteren breit lesbaren Dateien wurde nicht geprüft (Grenze je Prüflauf).", report.content.skipped) }}</p>
          <p v-if="report.findings.length === 0" class="xp-ok">{{ report.content?.enabled ? $t("Keine breit lesbare Datei hat einen auffälligen Namen oder Inhalt.") : $t("Keine Datei mit auffälligem Namen ist breit lesbar.") }}</p>
          <template v-else>
            <label class="xp-all"><input type="checkbox" :checked="allSelected" data-testid="exposure-select-all" @change="toggleAll" /> {{ $t("Alle auswählen ({0}", report.findings.length) }}<template v-if="report.truncated"> {{ $t("von {0}", report.sensitiveBroadFiles) }}</template>)</label>
            <ul class="xp-findings" data-testid="exposure-findings">
              <li v-for="f in report.findings" :key="f.relativePath">
                <label><input type="checkbox" :checked="selected.has(f.relativePath)" @change="toggle(f.relativePath)" />
                  <span class="xp-path">{{ f.relativePath }}</span></label>
                <span class="xp-tags"><span v-for="c in f.categories" :key="c" class="xp-tag">{{ $t(c) }}</span><span v-for="h in f.contentHits" :key="h.category" class="xp-tag" data-testid="exposure-content-hit">{{ contentHitText(h) }}</span><span v-if="f.contentState === 'NOT_CHECKABLE'" class="xp-tag muted" :title="$t('Fail-closed: lieber ausschließen oder von Hand prüfen.')">{{ $t("Inhalt nicht prüfbar: {0}", contentReasonText(f.contentReason)) }}</span><span class="xp-who">{{ $t("lesbar für {0}", f.broadGroups.join(", ")) }}</span></span>
              </li>
            </ul>
          </template>
          <p class="xp-hint">{{ $t("Besser als Ausschließen: die Rechte in der Ablage korrigieren. Keplura übernimmt die neuen Rechte beim nächsten Abgleich automatisch. Ganze Ordner schließt du in den Einstellungen der Quelle unter „Auswahl“ aus (z. B.") }} <code>{{ $t("personal/**") }}</code>).</p>
        </template>
      </template>

      <p v-if="note" class="xp-note" role="status" data-testid="exposure-note">{{ note }}</p>
    </div>
    <template #footer>
      <div class="xp-actions">
        <Button variant="ghost" :disabled="running || starting || excluding" data-testid="exposure-rescan" @click="start">{{ $t("Erneut prüfen") }}</Button>
        <Button v-if="done && report && report.broadFiles" variant="ghost" data-testid="exposure-copy" @click="copyList">{{ copied ? $t("Kopiert") : $t("Liste für Fileserver-Admin kopieren") }}</Button>
        <span class="xp-spacer"></span>
        <Button v-if="done && report?.findings.length" variant="primary" :disabled="selected.size === 0 || excluding" :busy="excluding" data-testid="exposure-exclude" @click="exclude">
          {{ selected.size ? $t("{0} von der Übernahme ausschließen", selected.size) : $t("Dateien auswählen") }}
        </Button>
        <Button v-else @click="emit('close')">{{ $t("Schließen") }}</Button>
      </div>
    </template>
  </AppDialog>
</template>

<style scoped>
.xp { display:grid; gap:16px; min-width:0; }
.xp h4 { margin:8px 0 0; font:600 14px/20px var(--ic-font); color:var(--ic-text); }
.xp-source { margin:0; font:500 14px/20px var(--ic-font); color:var(--ic-text); overflow-wrap:anywhere; }
.xp-source span { display:block; color:var(--ic-text-2); font:400 13px/20px var(--ic-font-mono); }
.xp-lead,.xp-hint,.xp-meta { margin:0; color:var(--ic-text-2); font:400 13px/20px var(--ic-font); max-width:72ch; }
.xp-progress { display:grid; gap:8px; justify-items:start; }
.xp-progress progress { width:100%; accent-color:var(--ic-accent); }
.xp-failed p { margin:8px 0 0; }
.xp-warn { margin:0; padding:12px 16px; border-left:3px solid var(--ic-status-wait); background:var(--ic-status-wait-bg); color:var(--ic-status-wait); font:400 14px/22px var(--ic-font); }
.xp-ok { margin:0; color:var(--ic-status-ok); font:400 14px/20px var(--ic-font); }
.xp-note { margin:0; padding:12px 16px; border-radius:var(--ic-radius-panel); color:var(--ic-status-ok); background:var(--ic-status-ok-bg); font:400 14px/20px var(--ic-font); }
.xp-summary { display:grid; grid-template-columns:repeat(auto-fit,minmax(140px,1fr)); gap:12px; }
.xp-summary div { display:grid; gap:2px; padding:12px 16px; border:1px solid var(--ic-separator); border-radius:8px; }
.xp-summary strong { font:600 22px/28px var(--ic-font); color:var(--ic-text); }
.xp-summary span { color:var(--ic-text-2); font:400 12px/18px var(--ic-font); }
.xp-summary .hot { border-color:var(--ic-status-error); }
.xp-summary .hot strong { color:var(--ic-status-error); }
.xp-groups,.xp-findings { list-style:none; margin:0; padding:0; border:1px solid var(--ic-separator); border-radius:8px; max-height:260px; overflow:auto; }
.xp-groups li { display:flex; justify-content:space-between; gap:12px; padding:10px 16px; border-top:1px solid var(--ic-separator-faint); font-size:13px; overflow-wrap:anywhere; }
.xp-groups li:first-child,.xp-findings li:first-child { border-top:0; }
.xp-table-wrap { overflow-x:auto; max-height:260px; border:1px solid var(--ic-separator); border-radius:8px; }
.xp-table { width:100%; border-collapse:collapse; font-size:13px; }
.xp-table th,.xp-table td { padding:8px 12px; text-align:left; border-bottom:1px solid var(--ic-separator-faint); }
.xp-table th { color:var(--ic-text-2); font-weight:500; position:sticky; top:0; background:var(--ic-surface, var(--ic-bg)); }
.xp-table .n { text-align:right; white-space:nowrap; }
.xp-table code,.xp-path { font:400 13px/20px var(--ic-font-mono); overflow-wrap:anywhere; }
.xp-all { display:flex; align-items:center; gap:8px; font-size:13px; }
.xp-findings li { display:grid; gap:4px; padding:10px 16px; border-top:1px solid var(--ic-separator-faint); }
.xp-findings label { display:flex; align-items:flex-start; gap:8px; min-width:0; }
.xp-findings input,.xp-all input { accent-color:var(--ic-accent); margin-top:3px; flex:none; }
.xp-tags { display:flex; flex-wrap:wrap; gap:6px; padding-left:24px; }
.xp-tag { padding:0 8px; border-radius:999px; background:var(--ic-status-error-bg); color:var(--ic-status-error); font:500 12px/20px var(--ic-font); }
.xp-tag.muted { background:var(--ic-status-wait-bg); color:var(--ic-status-wait); }
.xp-who { color:var(--ic-text-2); font:400 12px/20px var(--ic-font); }
.xp-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; width:100%; }
.xp-spacer { flex:1; }
@media(max-width:767px) { .xp-actions .xp-spacer { display:none; } .xp-actions button { flex:1; } }
</style>
