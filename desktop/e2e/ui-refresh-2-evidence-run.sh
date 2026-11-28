#!/usr/bin/env bash
# UI-REFRESH-2 AC2 evidence: run the chat shots harness against BOTH trees —
# baseline (main@df44eab pre-migration, prebuilt dist) and the migrated work
# tree — so the same flow produces comparable screenshots + geometry.json.
# Usage: ui-refresh-2-evidence-run.sh <out-root>
set -uo pipefail
OUT_ROOT="${1:-/tmp/ui2-evidence}"
SELF="$(cd "$(dirname "$0")" && pwd)"   # desktop/e2e
BASE_TREE="${KEPLURA_REPO:-$(git rev-parse --show-toplevel)}/.baseline/t_ui11f53ad9-main/desktop"
NEW_TREE="$(cd "$SELF/.." && pwd)"
JAR=${KEPLURA_REPO:-$(git rev-parse --show-toplevel)}/server/target/keplura-server-0.1.0.jar
export HOME=/cache/home
export NODE_PATH="$HOME/.cache/keplura-e2e/node_modules"
[ -f "$JAR" ] || { echo "jar missing"; exit 2; }

run_tree() { # $1 tree dir, $2 tag, $3 mail
  local tree="$1" tag="$2" mail="$3"
  local out="$OUT_ROOT/$tag"
  mkdir -p "$out"
  cd "$tree"
  node node_modules/vite/bin/vite.js preview --port 1420 --strictPort > "$out/vite.log" 2>&1 &
  local vpid=$!
  for i in $(seq 1 30); do curl -sf -o /dev/null http://localhost:1420/ && break; sleep 1; done
  if ! curl -sf -o /dev/null http://localhost:1420/; then
    echo "preview ($tag) never came up"; tail -5 "$out/vite.log"; kill $vpid 2>/dev/null; return 3
  fi
  cd "$SELF"
  E2E_JAR="$JAR" E2E_DB="/tmp/ui2-jar-$tag" node ui-refresh-2-chat-shots.cjs "$out" "$mail" "Ui2-Evidence-Secret-2026" > "$out/shots.log" 2>&1
  local rc=$?
  kill $vpid 2>/dev/null
  # vite preview spawns no children; make sure the port is released
  sleep 2
  echo "tree=$tag rc=$rc out=$out"
  return $rc
}

run_tree "$BASE_TREE" "base" "qa-ui2-base@e2e.example" || { echo BASE-RUN-FAILED; exit 1; }
run_tree "$NEW_TREE"  "new"  "qa-ui2-new@e2e.example"  || { echo NEW-RUN-FAILED; exit 1; }

node ui-refresh-1-geomdiff.cjs "$OUT_ROOT/base/geometry.json" "$OUT_ROOT/new/geometry.json" 2 | tee "$OUT_ROOT/geomdiff.log"
g=$?
echo "geomdiff rc=$g (tol 2px)"
# state-divergence report first: pixel diffs only mean styling regressions
# when BOTH legs captured the SAME content (textdiff rc=0)
python3 ui-refresh-2-textdiff.py "$OUT_ROOT/base/text.json" "$OUT_ROOT/new/text.json" | tee "$OUT_ROOT/textdiff.log"
t=$?
echo "textdiff rc=$t"
[ $t -ne 0 ] && { echo "STATE-DIVERGENCE — pixel results below are NOT interpretable"; exit 4; }
for s in empty-light empty-dark plain-light plain-dark rag-light rag-dark stream-light stream-dark; do
  if [ -f "$OUT_ROOT/base/$s.png" ] && [ -f "$OUT_ROOT/new/$s.png" ]; then
    python3 ui-refresh-1-pixdiff.py "$OUT_ROOT/base/$s.png" "$OUT_ROOT/new/$s.png" | tail -1 | sed "s|^|$s: |"
  fi
done
exit $g
