#!/usr/bin/env bash
# AC5: bundle size report baseline (main@df44eab prebuilt dist) vs migrated
# work tree dist — raw + gzip per asset, plus totals. Output suitable for
# pasting into the PR body.
set -euo pipefail
BASE="${KEPLURA_REPO:-$(git rev-parse --show-toplevel)}/.baseline/t_ui11f53ad9-main/desktop/dist/assets"
NEW="${KEPLURA_REPO:-$(git rev-parse --show-toplevel)}/.worktrees/t_11f53ad9/desktop/dist/assets"
sum() { # dir -> total raw, total gz for index-*.js + index-*.css
  local d="$1" raw=0 gz=0
  for f in "$d"/index-*.js "$d"/index-*.css; do
    [ -e "$f" ] || continue
    raw=$((raw + $(stat -c%s "$f")))
    gz=$((gz + $(gzip -c "$f" | wc -c)))
  done
  echo "$raw $gz"
}
echo "== index bundles (entry js + css) =="
read -r braw bgz <<<"$(sum "$BASE")"
read -r nraw ngz <<<"$(sum "$NEW")"
printf "baseline raw=%s gz=%s\nnew      raw=%s gz=%s\ndelta    raw=%+d gz=%+d (limit: +40 kB gz)\n" \
  "$braw" "$bgz" "$nraw" "$ngz" "$((nraw-braw))" "$((ngz-bgz))"
echo "== per asset =="
for d in "$BASE" "$NEW"; do
  echo "--- $d"
  for f in "$d"/*; do
    printf "%s raw=%s gz=%s\n" "$(basename "$f")" "$(stat -c%s "$f")" "$(gzip -c "$f" | wc -c)"
  done
done
