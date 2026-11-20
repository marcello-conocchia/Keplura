#!/usr/bin/env bash
# UI-REFRESH-1 AC3 evidence: programmatic testid audit old (main@e38c859) vs
# new (working tree) for the Sidebar + Login surfaces. Exit 0 + "IDENTICAL"
# only when the sets match exactly.
set -euo pipefail
cd "$(dirname "$0")/.."
REF=e38c859
ids() { grep -ohE 'data-testid="[a-zA-Z0-9_-]+"' "$1" | sed -E 's/data-testid="([a-zA-Z0-9_-]+)"/\1/' | LC_ALL=C sort -u; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git show "$REF:desktop/src/components/AppSidebar.vue" > "$tmp/side-old.vue"
git show "$REF:desktop/src/components/LoginView.vue" > "$tmp/login-old.vue"
ids "$tmp/side-old.vue" > "$tmp/side-old.ids"
ids "$tmp/login-old.vue" > "$tmp/login-old.ids"
ids src/components/AppSidebar.vue > "$tmp/side-new.ids"
ids src/components/LoginView.vue > "$tmp/login-new.ids"
fail=0
for pair in side login; do
  if diff -u "$tmp/$pair-old.ids" "$tmp/$pair-new.ids"; then
    echo "$pair: IDENTICAL ($(wc -l < "$tmp/$pair-new.ids" | tr -d ' ') testids)"
  else
    echo "$pair: DIFFERS"; fail=1
  fi
done
exit $fail
