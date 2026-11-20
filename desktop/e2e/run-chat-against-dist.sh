#!/usr/bin/env bash
# One-off: run the existing chat_flow_e2e.cjs against a given desktop tree's
# BUILT dist (vite preview), mirroring the ci-local wrapper contract:
# VITE_SERVER_URL=http://localhost:8080, vite on :1420, chat owns the jar.
# Usage: run-chat-against-dist.sh <desktop-dir> <out-dir>
set -uo pipefail
DESKTOP_DIR="$1"; OUT="$2"
SELF="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
JAR=/home/hermes/workspaces/IntraCore/server/target/keplura-server-0.1.0.jar
[ -f "$JAR" ] || { echo "jar missing"; exit 2; }
export HOME=/cache/home
export NODE_PATH="$HOME/.cache/keplura-e2e/node_modules"
SEED_MAIL="chatdist-$RANDOM@e2e.example"
SEED_PW="ChatDist-E2E-Secret-2026"
cd "$DESKTOP_DIR"
nohup node node_modules/vite/bin/vite.js preview --port 1420 --strictPort \
  --mode development > "$OUT/vite.log" 2>&1 &
VITE_PID=$!
export VITE_SERVER_URL="http://localhost:8080"
cleanup() { kill "$VITE_PID" 2>/dev/null; sleep 1; kill -9 "$VITE_PID" 2>/dev/null; return 0; }
trap cleanup EXIT
for i in $(seq 1 30); do curl -sf -o /dev/null http://localhost:1420/ && break; sleep 1; done
curl -sf -o /dev/null http://localhost:1420/ || { echo "preview down"; tail -5 "$OUT/vite.log"; exit 3; }
cd "$SELF"
E2E_JAR="$JAR" E2E_OUT="$OUT" node chat_flow_e2e.cjs "$SEED_MAIL" "$SEED_PW" 2>&1 | tee "$OUT/chat_flow.jsonl"
rc=${PIPESTATUS[0]}
echo "chat_flow rc=$rc"
exit $rc
