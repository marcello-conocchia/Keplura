# UI-REFRESH-2 — Testid-/Anker-Audit (AC1)

Quelle: `desktop/e2e/ui-refresh-2-testid-audit.sh` (Audit alt `df44eab` vs. Arbeitsbaum, Sortierung+Zaehlung identisch, kein Mapping noetig).

Ergebnis: **chat+markdown: IDENTICAL (30 testid occurrences, 30 unique)** — alle Vorkommen byte-identisch erhalten, Anzahl vorher/nachher gleich. Zusaetzlich die vier imperativ/E2E-seitig selektierten Anker-Klassen: `ucontent` present, `acontent` present, `ameta` present, `caret` present (unabhaengig verifiziert durch `chat_flow_e2e` 24/24 im Binding-Gate).

## Vollstaendige Liste (30 Vorkommen, alt == neu — maschinell erzeugt)

Old-vs-new machine output (ui-refresh-2-testid-audit.sh): `chat+markdown: IDENTICAL (30 testid occurrences)`

| data-testid | Vorkommen alt | Vorkommen neu |
|---|---|---|
| chat-citation | x1 | x1 |
| chat-code-copy | x1 | x1 |
| chat-conv-delete | x1 | x1 |
| chat-conv-empty | x1 | x1 |
| chat-conv-item | x1 | x1 |
| chat-conv-list | x1 | x1 |
| chat-copy-user | x1 | x1 |
| chat-ctx-kb | x1 | x1 |
| chat-example | x1 | x1 |
| chat-link-hint | x1 | x1 |
| chat-md | x1 | x1 |
| chat-msg-assistant | x1 | x1 |
| chat-msg-copy | x1 | x1 |
| chat-msg-model | x1 | x1 |
| chat-msg-retry | x1 | x1 |
| chat-msg-user | x1 | x1 |
| chat-new-plain | x1 | x1 |
| chat-new-rag | x1 | x1 |
| chat-no-selection | x1 | x1 |
| chat-notice | x1 | x1 |
| chat-prompt | x1 | x1 |
| chat-send | x1 | x1 |
| chat-source-detail | x1 | x1 |
| chat-source-download | x1 | x1 |
| chat-sources | x1 | x1 |
| chat-sources-live | x1 | x1 |
| chat-stop | x1 | x1 |
| chat-streaming | x1 | x1 |
| chat-thread | x1 | x1 |
| chat-view | x1 | x1 |

## Mapping alt -> neu

Keins. Die Migration hat ausschliesslich CSS-Klassen von scoped-Regeln auf Tailwind-Utilities/`@utility`-Namen umgezogen (ChatView) bzw. die Root-Klasse `.md` -> `.md-answer` umbenannt (MarkdownAnswer); kein `data-testid` wurde umbenannt, entfernt oder neu erfunden, keine Selektor-Absicht (Rollen/Labels) geaendert.

## Sicherheitswand (MarkdownAnswer.vue)

- `<script lang="ts">`-Block: byte-identisch zu `df44eab` (diff-geprueft, `SCRIPT-BLOCK-IDENTICAL`).
- DOMPurify-Konfig (`SANITIZE_CONFIG`, `SAFE_URI`, `addHook("afterSanitizeAttributes", …)`, `renderer.code`, `marked.setOptions`) und `renderAnswerMarkdown`: unveraendert.
- `v-html` traegt weiterhin ausschliesslich den Ausgang von `renderAnswerMarkdown()`.
- Diff der Datei = Root-Klasse + Ersatzlos-Umzug des `<style scoped>`-Blocks nach `styles/tailwind.css` (1:1, keine neuen Werte; `:deep()` entfaellt, da das Kind-Markup ohnehin nie Scope-Attribute traegt).
