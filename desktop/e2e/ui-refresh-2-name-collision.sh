#!/usr/bin/env bash
# UI-REFRESH-2 (Kollisionschutz, AC1-Regel wie Phase 1):
#  1) Jeder von der Phase-2-Migration eingefuehrte GLOBAL-Utility-/Klassen-
#     name muss in allen gefrorenen Flaechen INERT sein — weder als Class-
#     Attribut-Wort noch als CSS-Selektor-Wort (word-boundary).
#  2) Nach dem Build zusaetzlich vom Post-Build-Teil unten nicht abgedeckt:
#     neue Tailwind-Kern-Tokens im Bundle duerfen keine class-Woerter der
#     gefrorenen DOMs matchen -> ui-refresh-2-token-audit.sh.
# ausgenommen: die E2E-Anker-Namen (ucontent/acontent/ameta/caret/code-copy)
# werden bewusst byte-identisch weitergefuehrt; sie kommen in KEINER
# gefrorenen Datei vor (Pruefung deckt genau das ab).
set -uo pipefail
cd "$(dirname "$0")/.."
FROZEN="src/components/AdminPanel.vue src/components/KnowledgePanel.vue src/components/MembersPanel.vue src/components/SessionView.vue src/components/AppSidebar.vue src/components/LoginView.vue src/components/ui/Button.vue src/components/ui/Input.vue src/components/ui/Label.vue src/components/ui/AppButton.vue src/components/ui/TextField.vue src/components/ui/AppDialog.vue src/components/ui/StatusPill.vue src/components/ui/AppIcon.vue src/styles/theme.css index.html src/App.vue"
NAMES="chat-view chat-head chat-title chat-sub chat-head-actions chat-split chat-list chat-list-error chat-thread chat-transcript conv-row conv-open conv-del conv-title conv-meta msg-urow msg-arow msg-who ucontent acontent ameta msg-head msg-model msg-actions msg-action src-block src-cap src-row src-chip src-main src-file src-sep src-where src-quiet src-detail src-score src-open-link src-caret src-caret-open caret caret-pulse code-copy msg-notice msg-notice-info msg-composer ctx-line ctx-quiet composer-input composer-bar composer-space composer-counter empty-state es-lead es-sub es-examples es-ex streamtext md-answer bg-msg-tint bg-notice-error shadow-hairline-only"
fail=0
for n in $NAMES; do
  # class attribute word OR .n as CSS selector word inside frozen files
  hits=$(grep -lE "class=\"[^\"]*[^a-zA-Z0-9_-]${n}[^a-zA-Z0-9_-]|class=\"(${n}[^a-zA-Z0-9_-]|[^\"]*[ ,]${n}([ ,\"]|$))|(^|[ ,{}>~+])\.${n}([^a-zA-Z0-9_-]|$)" $FROZEN 2>/dev/null | tr '\n' ' ')
  if [ -n "$hits" ]; then echo "COLLISION $n: $hits"; fail=1; fi
done
[ $fail -eq 0 ] && echo "all $(echo $NAMES | wc -w) phase-2 names inert in frozen surfaces"
exit $fail
