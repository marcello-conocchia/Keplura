#!/usr/bin/env bash
# Collision check: planned Phase-2 @utility names vs. legacy classes in frozen surfaces.
cd "$(dirname "$0")/.."
FROZEN="src/components/AdminPanel.vue src/components/KnowledgePanel.vue src/components/MembersPanel.vue src/components/ui/AppButton.vue src/components/ui/TextField.vue src/components/ui/AppDialog.vue src/components/ui/StatusPill.vue src/components/ui/AppIcon.vue src/styles/theme.css index.html"
for n in answer-hover conv-row conv-active conv-del hairline-pill notice-info msg-group msg-actions msg-action src-chip src-chip-open ctx-line caret-ic md-answer code-copy chat-hover chat-row-del chat-active-line; do
  hits=$(grep -lE "\.${n}[ ,{:.]" $FROZEN 2>/dev/null | tr '\n' ' ')
  echo "$n: ${hits:-clean}"
done
# also: which tailwind utility class names appear literally inside the FROZEN components' class attributes (source(none) means they generate nothing today; after @source they WOULD)
echo "--- frozen files scanned for @tailwind-style utility collisions below ---"
grep -ohE 'class="[^"]+"' $FROZEN | grep -oE '\b(bg-|text-|rounded|flex\b|items-|justify-|border-|shadow-|font-|w-\[|h-\[|p-|px-|py-|gap-)' | sort | uniq -c
