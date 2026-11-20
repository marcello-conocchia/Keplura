# UI-REFRESH-2 — Chat-Flaeche auf Tailwind-Utilities ueber --ic-*-Tokens (Phase 2)

Kanban: t_11f53ad9 (PO-'Mach 2' 10.09.). Basis: main@df44eab. Scope: NUR Chat-Flaeche — ChatView.vue + ui/MarkdownAnswer.vue (Style-Ebene) + tailwind.css Phase-2-Block + Evidenz-Skripte; UI-Freeze fuer alle anderen Flaeschen gehalten. SessionView.vue hat KEINEN Chat-Strang (Wissensseite) -> ungeaendert.

## Muster-Treue (Phase-1-Rezept, PR #47)

- Tailwind v4.3.3 + @tailwindcss/vite + reka-ui 2.10.4 — Versionen gehalten, KEINE neuen Deps.
- KEIN Preflight; Utilities bewusst UNLAYERED (Kaskade gegen theme.css, Phase-1-Begruendung gilt); Content-Scan per `source(none)` + explizite `@source` NUR auf die migrierten Dateien. Nachlaufend verifiziert: generiertes CSS traegt ausschliesslich Utility-Namen aus ChatView/MarkdownAnswer-Markup.
- Theme-Bridge bleibt `@theme inline var(--ic-*)`; Dark-Switch-Mechanismus unveraendert. theme.css, Route, State, Server: ungeaendert.
- Primitive wiederverwendet statt dupliziert: ChatView nutzt jetzt `ui/Button.vue` (Phase-1) statt `AppButton.vue`.
- Namens-Disziplin gegen die Freeze-Flaechen: kein neuer globaler Name kollidiert mit Selektoren/Klassen anderer Komponenten, mit dem Basis-Bundle oder mit E2E-Selektoren — maschinell gepruft: `desktop/e2e/ui-refresh-2-name-collision.sh` (61 Phase-2-Namen inert) + `desktop/e2e/ui-refresh-2-token-audit.py` (neu generierte Tokens vs. gefrorene DOM-Class-Attribute: NONE). Die E2E-Anker-Klassen ucontent/acontent/ameta/caret bleiben byte-identisch erhalten.

## AC1 — Testids + Sicherheitswand

- `bash desktop/e2e/ui-refresh-2-testid-audit.sh` -> **`chat+markdown: IDENTICAL (30 testid occurrences, 30 unique)`** — Audit df44eab vs. Head, inkl. imperativem `chat-code-copy`-Anker (setAttribute) und der vier E2E-Anker-Klassen (present: ucontent/acontent/ameta/caret).
- Volle Tabelle alt->neu (maschinell erzeugt): `desktop/e2e/UI-REFRESH-2-testid-audit.md`.
- MarkdownAnswer.vue: der `<script lang="ts">`-Block (DOMPurify-Konfig, SAFE_URI, afterSanitizeAttributes-Hook, marked-Renderer, `renderAnswerMarkdown`) ist **byte-identisch** zum Basis-Commit (diff-geprueft). Der gesamte Diff der Datei ist Style-Transport: Root-Klasse `.md` -> `.md-answer` + scopeloser 1:1-Umzug des scoped-Blocks nach tailwind.css. `v-html` traegt weiterhin ausschliesslich den Pipeline-Ausgang.

## AC2 — Visuelle Nicht-Regression: 8/8 PIXEL-IDENTICAL

Vorher/Nachher mit identischem Seeded-Jar+Preview+Mock-Stapel (Chromium 1181 headless, 1200x1400, prefers-reduced-motion), 8 States: empty/plain/rag/stream x hell/dunkel. Evidence committed: `desktop/e2e/evidence/ui-refresh-2/{base,new}/` (PNGs + geometry.json + text.json + shots.log) + `run-summary.log` + `geomdiff.log` + `textdiff.log` + `bundledelta.txt`. Reproduzierbar: `bash desktop/e2e/ui-refresh-2-evidence-run.sh <out>`.

- Pixel-Diff (`ui-refresh-1-pixdiff.py`, stdlib): **alle 8 Screenshots 0 differing pixels** (`PIXEL-IDENTICAL`).
- Geometrie (`ui-refresh-1-geomdiff.cjs`, Tol 2px): **110 verglichene Anker, 0 findings**.
- Gegenprobe `ui-refresh-2-textdiff.py`: 0 Text-Divergenzen pro State — beweist, dass beide Beine denselben Inhalt erfasst haben (Pixel-Null ist damit kein Zufall unterschiedlicher Inhalte).

### Try-and-revert-Protokoll (Root-Cause-Fixes aus der Evidenz)

1. **`font: inherit`-Kaskade**: erste Portierung schrieb die geerbte Zeilenhoehe ABSOLUT (21.6px) in conv-open/src-chip/es-ex. Geometrie-Evidenz bewies Streckung (Konversationszeile +4.8px, Leerzustand +3.2px): der Legacy-Weg vererbt die EINHEITSLOSE 1.6, Kinder rechnen neu (13px->20.8, 11px->17.6). Fix auf ratio 1.6 -> nachher 0px Diff. (Doku im Code-Kommentar.)
2. **Habit-Bug-Treue**: `background: var(--ic-hover)` war Legacy-toter Code (Token existiert nie -> IACVT/transparent). ersatzlos entfernt; Hover-Flaeche der Liste war unsichtbar — kein UX-Effekt.
3. **Harness-Determinismus** (v4->v10, jeweils root-cause): Fixture-Namen ohne Zeitstempel; `page.clock.setFixedTime` NICHT `install` (install fror App-Polling ein); Intl.DateTimeFormat-Konstruktor-Stub (Prototype-Getter greifen bei ECMA-402 nicht); RAG-Stream-Park-Protokoll (Mock hält nach GENAU einem Chunk, `/release`-Endpunkt) statt Zeitfenster-Raten; `quiesce()` vor jedem statischen Shot — v6/v9-Forensik fand echten Out-of-Order-Race: ein conversation-GET repainted die Transcript des naechsten Legs; Stream-Teardown ohne Stop-Klick (der Abort-Pfad bleibt vollstaendig durch chat_flow_e2e abgedeckt, AC3 — dort gruen).

## AC3 — Bestehende E2E-Suiten

Alle Suite-Selukte aus dem Binding-Gate unten — **Assertions unveraendert** (git diff df44eab..HEAD unter desktop/e2e/ enthaelt ausschliesslich NEUE ui-refresh-2-* Dateien, 0 geloeschte Zeilen in bestehendenSuiten; Selektoren blieben giltig, weil die E2E-Anker-Klassen ucontent/acontent/ameta/caret + alle 30 Chat-testids erhalten sind): chat_flow **24/24**, dark_mode_suite (`dark_flow`) **10/10**, dark-packaged **8/8**, auth **7/7**, knowledge **15/15**, invite **20/20**, admin **19/19**, oidc **28/28** — plus die packaged-Leg (echtes Tauri-Binary gegen finalen dist) und Server-Tests 391/391. Kein Wartezeit-/Selektor-Tweak noetig.

## AC4 — Binding gate (Merge-Gate #1)

**`bash scripts/ci-local.sh --with-e2e` (via scripts/ci-local-e2e-gate.sh, inotify-Shim) — EXIT 0, 18/18 Stufen PASS** auf dem finalen PR-Head `83a9756`; Evidence committed unter `ci/evidence/20260910-145537/` (MATRIX.md + ci-local.log + alle Suite-Logs). Gate-Integritaet: Matrix-HEAD ist `83a9756`; die zwei folgenden Commits ergaenzen ausschliesslich dieses Evidence-Verzeichnis, den PR-Text und das Testid-Audit (git diff 83a9756..HEAD betrifft weder desktop/src noch Server noch bestehende E2E-Suiten — Gate-Inputs byte-identisch; gleiches Muster wie PR #47). Matrix: server:package + chunk-1..5 (391 Tests, 53 Klassen je genau einmal im Manifest-Audit) + desktop:build (npm ci + vue-tsc + vite) + desktop:cargo-check + 9 E2E-Suiten + e2e:packaged-build — siehe MATRIX.md in der Evidence.

## AC5 — Bundle-Groesse (dokumentiert)

Entry-Bundles (index-*.js + index-*.css) df44eab -> Head: raw 285 657 -> 285 509 B (−148), gz 90 434 -> 90 900 B = **+466 B gz** gegen Limit +40 kB gz. Detail pro Asset: `desktop/e2e/ui-refresh-2-bundledelta.sh` (Output committed in der Evidence). Begruendung Schwelle: Chat-Flaeche traegt deutlich mehr Markup als Login; Utility-Extraktion haelt das CSS sogar klein (die alten scoped-Regeln waren im JS-Chunk).

## Nicht getan (bewusst)

- Kein Merge (developer finalisiert nicht), kein Release/Deploy.
- Knowledge-/Admin-/Mitglieder-Flaeche = Phase 3, nur nach PO-Wort; dafuer bewusst KEINE Follow-Up-Karte von hier (Orchestrator-Routing).
