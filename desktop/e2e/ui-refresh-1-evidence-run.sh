#!/usr/bin/env bash
# UI-REFRESH-1 evidence run (AC4): boot seeded jar + vite preview for one
# tree, capture before/after screenshots + control geometry, tear down.
# Usage: ui-refresh-1-evidence-run.sh <tag> <desktop-dir> <out-dir>
set -uo pipefail
TAG="$1"; DESKTOP_DIR="$2"; OUT="$3"
SELF="$(cd "$(dirname "$0")" && pwd)"
WORK="/tmp/ui1-jar-$TAG"
mkdir -p "$WORK" "$OUT"

JAR=/home/hermes/workspaces/IntraCore/.worktrees/ui-refresh-1/server/target/keplura-server-0.1.0.jar
nohup java -jar "$JAR" \
  --server.port=8080 \
  --spring.profiles.active=dev \
  --spring.datasource.url="jdbc:h2:file:$WORK/db;AUTO_SERVER=TRUE" \
  --keplura.upload.dir="$WORK/uploads" \
  --keplura.dev.seed.email="qa@ui1.e2e.example" \
  --keplura.dev.seed.password="Ui1-Evidence-Secret-2026" \
  --keplura.dev.seed.display-name="UI1 Evidence Tester" \
  > "$WORK/server.log" 2>&1 &
JAR_PID=$!
VITE_PID=""
cleanup() {
  [ -n "$VITE_PID" ] && kill "$VITE_PID" 2>/dev/null
  kill "$JAR_PID" 2>/dev/null
  sleep 2; kill -9 "$JAR_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT

for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/health || echo 000)
  [ "$code" = "200" ] && break
  sleep 2
done
[ "$code" = "200" ] || { echo "jar not healthy (see $WORK/server.log)"; exit 3; }
echo "jar healthy"

cd "$DESKTOP_DIR"
nohup node node_modules/vite/bin/vite.js preview --port 1420 --strictPort > "$WORK/vite.log" 2>&1 &
VITE_PID=$!
for i in $(seq 1 30); do
  if curl -sf -o /dev/null http://localhost:1420/; then break; fi
  sleep 1
done
curl -sf -o /dev/null http://localhost:1420/ || { echo "preview never came up"; tail -5 "$WORK/vite.log"; exit 3; }
echo "preview up (serving $DESKTOP_DIR/dist)"

export HOME=/cache/home
export NODE_PATH="$HOME/.cache/keplura-e2e/node_modules"
cd "$SELF"
node ui-refresh-1-shots.cjs "$OUT" "qa@ui1.e2e.example" "Ui1-Evidence-Secret-2026"
rc=$?
echo "shots rc=$rc -> $OUT"
exit $rc
