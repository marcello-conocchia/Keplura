#!/usr/bin/env bash
# UI-REFRESH-2 AC1 evidence: programmatic testid audit old (baseline main
# commit this branch forked from) vs new (working tree) for the CHAT surface.
# Exit 0 + "IDENTICAL" only when both sets match exactly (multi-set incl.
# occurrence counts AND the imperatively-set chat-code-copy testid).
set -euo pipefail
cd "$(dirname "$0")/.."
REF="${UI2_REF:-df44eab}"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
git show "$REF:desktop/src/components/ChatView.vue" > "$tmp/chat-old.vue"
git show "$REF:desktop/src/components/ui/MarkdownAnswer.vue" > "$tmp/md-old.vue"
ids() { grep -ohE 'data-testid="[a-zA-Z0-9_-]+"' "$@" | sed -E 's/data-testid="([a-zA-Z0-9_-]+)"/\1/' | LC_ALL=C sort; }
# imperative chat-code-copy testid (setAttribute) counts as an anchor too
imp() { grep -ohE 'setAttribute\("data-testid", "[a-zA-Z0-9_-]+"' "$@" | grep -oE '[a-zA-Z0-9_-]+"$' | tr -d '"' | LC_ALL=C sort; }
{ ids "$tmp/chat-old.vue" "$tmp/md-old.vue"; imp "$tmp/chat-old.vue"; } > "$tmp/all-old.ids"
{ ids src/components/ChatView.vue src/components/ui/MarkdownAnswer.vue; imp src/components/ChatView.vue; } > "$tmp/all-new.ids"
if diff -u "$tmp/all-old.ids" "$tmp/all-new.ids"; then
  echo "chat+markdown: IDENTICAL ($(grep -c . "$tmp/all-new.ids") testid occurrences, $(LC_ALL=C sort -u "$tmp/all-new.ids" | wc -l | tr -d ' ') unique)"
else
  echo "chat+markdown: DIFFERS"; exit 1
fi
# E2E text-anchor classes of chat_flow_e2e.cjs must still exist byte-identically
for c in ucontent acontent ameta caret; do
  if grep -qE "class=\"[^\"]*\b${c}\b" src/components/ChatView.vue; then
    echo "anchor class ${c}: present"
  else
    echo "anchor class ${c}: MISSING"; exit 1
  fi
done
