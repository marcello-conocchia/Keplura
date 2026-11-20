#!/usr/bin/env bash
# =============================================================================
# ci-local.sh — the BINDING local CI gate for Keplura (PO decision 2026-09-06,
# kanban t_71473c70). GitHub Actions is NOT the merge gate any more (billing
# dependency); this script is: run it on our own infra against the PR branch
# and commit/paste the evidence (ci/evidence/<timestamp>/) into the PR body.
#
# Stages (all run unless skipped by a flag):
#   [1/4] server  : mvn compile/package once, then DETERMINISTIC chunked
#                   surefire runs over the FULL test-class manifest (every
#                   class exactly once, no skips, no -fae tolerance).
#                   NOTE on chunking: this host caps at 2 CPUs / 2048 pids
#                   (PO-NOTE; container thread headroom is far lower — see
#                   cgroup cpu.max) — ~50 Spring/Tomcat test classes booting
#                   in one surefire JVM hit that ceiling (M4c diagnosis:
#                   "environment, not product"). The documented chunked
#                   Surefire pattern from M4c is therefore the STANDARD here:
#                   fixed chunk size + -DforkCount=1 + thread/RAM-tolerant
#                   surefire config, identical for identical code.
#   [2/4] desktop : npm ci + npm run build (vue-tsc --noEmit typecheck included)
#   [3/4] desktop : cargo check --all-targets in src-tauri (Linux)
#   [4/4] e2e     : the EXISTING desktop/e2e harnesses, selected per suite
#                   (wrapper only — zero test-script refactoring):
#                     knowledge -> knowledge_flow_e2e.cjs (browser flow)
#                     dark      -> dark_flow_e2e.cjs (browser, DARK-FS tokens
#                                  + persistence + Fullscreen-API fallback)
#                     dark-packaged -> packaged_dark_e2e.py (native Tauri leg,
#                                  DARK-FS sidebar controls + F11/Escape)
#                     chat      -> chat_flow_e2e.cjs (browser + mock upstream)
#                     invite    -> invite_flow_e2e.cjs (browser, SEC-2 flow)
#                     admin     -> admin_flow_e2e.cjs (browser, ADMIN-KI panel)
#                     oidc      -> oidc_flow_e2e.cjs (browser, OIDC second
#                                  login path vs. the REAL stub IdP incl.
#                                  PKCE exchange + negative legs)
#                     auth      -> packaged_login_e2e.py (native Tauri leg)
#                     packaged  -> alias of auth (kept for clarity)
#                   Default: none (expensive). --with-e2e selects all nine
#                   (both dark legs included);
#                   --suite <name> (repeatable) selects specific ones.
#
# Evidence: every run writes to ci/evidence/<YYYYmmdd-HHMMSS>/:
#   ci-local.log         full stage output of this script
#   MATRIX.md            per-stage PASS/FAIL/SKIP result matrix (the gate table)
#   results.json         machine-readable twin of the matrix
#   manifest.txt         the sorted server test-class manifest (chunk proof)
#   server-<n>.log       per-chunk mvn output
#   desktop-build.log / cargo-check.log / e2e-<suite>/ per-stage detail
# ci/evidence/latest is re-pointed at the newest run.
#
# Exit codes (PO-NOTE binding):
#   0  all selected stages PASS (real test-level green)
#   1  a REAL test/build failure (hard — never tolerated, never retried)
#   2  gate-integrity problem: manifest mismatch (a selected test class did not
#      run / ran twice), or usage/config error (missing tools/credentials)
#   3  resource/infrastructure error (native-thread ceiling, cgroup pids,
#      OOM-killed fork, host RAM) — hard failure too, but clearly distinguished
#      from a product test failure. NEVER hidden by silent retries.
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CI_LOCAL_ARGV="bash scripts/ci-local.sh $*"
SERVER_DIR="$REPO_ROOT/server"
DESKTOP_DIR="$REPO_ROOT/desktop"
TAURI_DIR="$DESKTOP_DIR/src-tauri"
E2E_DIR="$DESKTOP_DIR/e2e"
EVIDENCE_ROOT="$REPO_ROOT/ci/evidence"

RUN_TS="$(date +%Y%m%d-%H%M%S)"
EVIDENCE_DIR="$EVIDENCE_ROOT/$RUN_TS"
MASTER_LOG="$EVIDENCE_DIR/ci-local.log"

CHUNK_SIZE=12          # fixed: deterministic 12/12/.../rest split (PO-NOTE)
FORK_COUNT=1           # fixed: one surefire fork per chunk (thread ceiling)
MVN_CHUNK_TIMEOUT="${CI_MVN_CHUNK_TIMEOUT:-2400}"   # seconds per chunk
E2E_TIMEOUT="${CI_E2E_TIMEOUT:-1200}"               # seconds per e2e suite

RUN_SERVER=1; RUN_DESKTOP=1; RUN_TAURI=1; RUN_E2E=0
E2E_SUITES=()

say() { printf '%s\n' "$*" | tee -a "$MASTER_LOG"; }
now() { date +%H:%M:%S; }

usage() {
  sed -n '2,57p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  cat <<'EOF'
Usage flags:
  --suite <knowledge|dark|dark-packaged|chat|invite|admin|oidc|auth|packaged>  select an e2e suite (repeatable)
  --with-e2e                                     select ALL e2e suites
  --skip-server --skip-desktop --skip-tauri --skip-e2e
  --chunk-size <N>                               mvn chunk size (default 12)
  -h, --help                                     this text
E2E credentials (required only when e2e suites are selected):
  CI_E2E_EMAIL / CI_E2E_PASSWORD  (plus optional CI_E2E_EMAIL_B for invite)
EOF
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --suite) [ $# -ge 2 ] || usage; E2E_SUITES+=("$2"); shift 2;;
    --with-e2e) RUN_E2E=1; shift;;
    --skip-server) RUN_SERVER=0; shift;;
    --skip-desktop) RUN_DESKTOP=0; shift;;
    --skip-tauri) RUN_TAURI=0; shift;;
    --skip-e2e) RUN_E2E=0; E2E_SUITES=(); shift;;
    --chunk-size) [ $# -ge 2 ] || usage; CHUNK_SIZE="$2"; shift 2;;
    -h|--help) usage;;
    *) echo "unknown flag: $1" >&2; usage;;
  esac
done
if [ "${#E2E_SUITES[@]}" -gt 0 ]; then RUN_E2E=1; fi
if [ "$RUN_E2E" = 1 ] && [ "${#E2E_SUITES[@]}" -eq 0 ]; then
  E2E_SUITES=(knowledge dark dark-packaged chat invite admin oidc auth)
fi
for s in "${E2E_SUITES[@]}"; do
  case "$s" in knowledge|dark|dark-packaged|chat|invite|admin|oidc|auth|packaged) ;; *) echo "bad --suite: $s" >&2; usage;; esac
done

mkdir -p "$EVIDENCE_DIR"

declare -a STAGE_NAME STAGE_RESULT STAGE_NOTE
# exit-code precedence for diagnosis: 2 (gate integrity) > 3 (resource/infra) > 1 (test)
FAIL_CODE=0
add_result() { # $1 stage, $2 PASS|FAIL|SKIP, $3 note
  STAGE_NAME+=("$1"); STAGE_RESULT+=("$2"); STAGE_NOTE+=("${3:-}")
}
set_fail_code() {
  local c="$1"
  case "$c" in
    2) FAIL_CODE=2;;
    3) [ "$FAIL_CODE" = 2 ] || FAIL_CODE=3;;
    1) [ "$FAIL_CODE" = 0 ] && FAIL_CODE=1;;
  esac
}

finish() { # $1 exit code to end with — prints matrix, writes evidence, exits
  local code="$1"
  local i n=0
  {
    echo ""
    echo "================= RESULT MATRIX ================="
    printf '%-28s %-6s %s\n' "STAGE" "RESULT" "NOTE"
    for i in "${!STAGE_NAME[@]}"; do
      printf '%-28s %-6s %s\n' "${STAGE_NAME[$i]}" "${STAGE_RESULT[$i]}" "${STAGE_NOTE[$i]}"
      [ "${STAGE_RESULT[$i]}" = "FAIL" ] && n=$((n+1))
    done
    echo "-------------------------------------------------"
    echo "failed stages : $n"
    echo "exit code     : $code  (0 green / 1 test-fail / 2 gate-integrity / 3 resource-infra)"
    echo "evidence      : $EVIDENCE_DIR"
    echo "================================================="
  } | tee -a "$MASTER_LOG"
  {
    echo "# ci-local result matrix — run $RUN_TS"
    echo ""
    echo "- repo HEAD: $(git -c safe.directory='*' -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo '?')"
    echo "- branch: $(git -c safe.directory='*' -C "$REPO_ROOT" branch --show-current 2>/dev/null || echo '?')"
    echo "- command: ${CI_LOCAL_ARGV:-bash scripts/ci-local.sh}"
    echo "- result: **$([ "$code" = 0 ] && echo 'PASS' || echo "FAIL (exit $code)")**"
    echo ""
    printf '| stage | result | note |\n|---|---|---|\n'
    for i in "${!STAGE_NAME[@]}"; do
      printf '| %s | %s | %s |\n' "${STAGE_NAME[$i]}" "${STAGE_RESULT[$i]}" "${STAGE_NOTE[$i]}"
    done
  } > "$EVIDENCE_DIR/MATRIX.md"
  {
    printf '{"run":"%s","exit":%d,"head":"%s","stages":[' "$RUN_TS" "$code" \
      "$(git -c safe.directory='*' -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
    for i in "${!STAGE_NAME[@]}"; do
      [ "$i" = 0 ] || printf ','
      printf '{"stage":"%s","result":"%s","note":"%s"}' \
        "${STAGE_NAME[$i]}" "${STAGE_RESULT[$i]}" "$(printf '%s' "${STAGE_NOTE[$i]}" | tr -d '"')"
    done
    printf ']}\n'
  } > "$EVIDENCE_DIR/results.json"
  ln -sfn "$RUN_TS" "$EVIDENCE_ROOT/latest" 2>/dev/null || true
  exit "$code"
}

# resource-vs-product diagnosis for mvn logs (PO-NOTE rule 2: infra failures
# must surface as exit 3 with a diagnostic, never be papered over by retries).
classify_mvn_log() { # $1 logfile -> echo "resource|test|unknown"
  local f="$1"
  if grep -qiE "unable to create native thread|could not fork|fork failed|resource temporarily unavailable" "$f" \
     || grep -qiE "java\.lang\.OutOfMemoryError(:? Java heap space| Metaspace| GC overhead| unable to allocate)" "$f" \
     || grep -qE "Process Exit Code: (137|143|-9|-15)\b" "$f" \
     || grep -qiE "terminated without properly saying goodbye" "$f"; then
    echo resource
  elif grep -qE "^\[(ERROR|WARNING)\].*Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$f" \
     || grep -qE "^\[INFO\] Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$f" \
     || grep -qE "^\[ERROR\] (Failures|Errors):" "$f" \
     || grep -qE "There are test failures|<<< FAILURE!|<<< ERROR!" "$f" \
     || grep -qE "^\[ERROR\].*compilation failure|BUILD FAILURE" "$f"; then
    echo test
  else
    echo unknown
  fi
}

# ---------------------------------------------------------------- preflight
command -v mvn  >/dev/null || { echo "mvn not found" >&2; exit 2; }
command -v node >/dev/null || { echo "node not found" >&2; exit 2; }
# single-instance guard: e2e harnesses SIGTERM foreign jars and vite owns a
# fixed port — refuse to start next to leftovers instead of racing them.
if [ "$RUN_E2E" = 1 ]; then
  if ss -ltn 2>/dev/null | grep -qE ':1420\b'; then
    echo "port 1420 already in use — stop the stray vite before running the gate" >&2; exit 3; fi
  if ss -ltn 2>/dev/null | grep -qE ':8080\b'; then
    echo "port 8080 already in use — stop the stray server jar before running the gate" >&2; exit 3; fi
  if [ -z "${CI_E2E_EMAIL:-}" ] || [ -z "${CI_E2E_PASSWORD:-}" ]; then
    echo "e2e selected but CI_E2E_EMAIL/CI_E2E_PASSWORD unset (see --help)" >&2; exit 2; fi
fi

say "ci-local gate — run $RUN_TS  (repo: $REPO_ROOT)"
say "head: $(git -c safe.directory='*' -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo '?') | suites: ${E2E_SUITES[*]:-none}"

# Native source reader is a server runtime prerequisite, so build/test it before Java.
if [ "$RUN_SERVER" = 1 ]; then
  native_log="$EVIDENCE_DIR/server-local-reader.log"
  say "[native reader] $(now): cargo build + cargo test --locked"
  if ! command -v cargo >/dev/null; then
    add_result "server:local-reader" "FAIL" "cargo missing (server runtime helper)"
    finish 2
  fi
  if ! ( cargo build --manifest-path "$SERVER_DIR/native/local-reader/Cargo.toml" --target-dir "$SERVER_DIR/target/native-reader" --locked \
      && cargo test --manifest-path "$SERVER_DIR/native/local-reader/Cargo.toml" --target-dir "$SERVER_DIR/target/native-reader" --locked ) > "$native_log" 2>&1; then
    add_result "server:local-reader" "FAIL" "see server-local-reader.log"
    finish 1
  fi
  export KEPLURA_LOCAL_READER="$SERVER_DIR/target/native-reader/debug/keplura-local-reader"
  add_result "server:local-reader" "PASS" "locked Rust build and FD snapshot regressions"
fi

# =============================================================== [1/4] server
if [ "$RUN_SERVER" = 1 ]; then
  say "[1/4] server  $(now): mvn package (skipTests) + chunked surefire, forkCount=$FORK_COUNT, chunk=$CHUNK_SIZE"
  rm -f "$SERVER_DIR"/target/*.lock.db 2>/dev/null || true   # stale H2 AUTO_SERVER locks (documented dev-host pitfall)
  pkg_log="$EVIDENCE_DIR/server-package.log"
  if ! ( cd "$SERVER_DIR" && timeout 1200 mvn -B -q package -DskipTests ) > "$pkg_log" 2>&1; then
    kind=$(classify_mvn_log "$pkg_log")
    add_result "server:package" "FAIL" "$kind (see server-package.log)"
    say "  mvn package FAILED ($kind) — tail:"; tail -20 "$pkg_log" | tee -a "$MASTER_LOG" || true
    case "$kind" in resource) set_fail_code 3;; *) set_fail_code 1;; esac
    finish "$FAIL_CODE"
  fi
  add_result "server:package" "PASS" "compile + fat jar"

  # ---- deterministic manifest (PO-NOTE rule 1/3) --------------------------
  # Every top-level *Test.class under target/test-classes, sorted; abstract
  # classes and nested class files ($-names) are excluded — surefire's own
  # default includes cover exactly the *Test pattern we enumerate here.
  MANIFEST="$EVIDENCE_DIR/manifest.txt"
  find "$SERVER_DIR/target/test-classes" -name '*Test.class' -not -name '*$*' \
    | sed "s|.*/||; s|\.class\$||" | LC_ALL=C sort > "$MANIFEST"
  TOTAL=$(wc -l < "$MANIFEST" | tr -d ' ')
  if [ "$TOTAL" -eq 0 ]; then
    add_result "server:manifest" "FAIL" "no test classes found"; set_fail_code 2; finish 2
  fi
  say "  manifest: $TOTAL test classes -> chunks of $CHUNK_SIZE"

  RAN_LIST="$EVIDENCE_DIR/.ran_classes"
  : > "$RAN_LIST"
  chunk_no=0
  offset=0
  while [ "$offset" -lt "$TOTAL" ]; do
    chunk_no=$((chunk_no+1))
    csv=$(tail -n "+$((offset+1))" "$MANIFEST" | head -n "$CHUNK_SIZE" | paste -sd, -)
    names=$(tail -n "+$((offset+1))" "$MANIFEST" | head -n "$CHUNK_SIZE")
    want=$(printf '%s\n' "$names" | wc -l | tr -d ' ')
    clog="$EVIDENCE_DIR/server-chunk-$chunk_no.log"
    say "  chunk $chunk_no ($want classes) $(now)"
    set +e
    ( cd "$SERVER_DIR" && timeout "$MVN_CHUNK_TIMEOUT" mvn -B surefire:test \
        -Dtest="$csv" \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -DforkCount="$FORK_COUNT" \
        -Dsurefire.rerunFailingTestsCount=0 \
        -DargLine="-Xmx768m -XX:MaxMetaspaceSize=512m -XX:CompressedClassSpaceSize=192m" \
    ) > "$clog" 2>&1
    rc=$?
    set -e
    # classes surefire actually booted, normalised to simple names
    got=$(grep -E "^\[INFO\] Running " "$clog" | awk '{print $NF}' | sed 's|\$.*||' | awk -F. '{print $NF}' | LC_ALL=C sort)
    printf '%s\n' "$got" >> "$RAN_LIST"
    got_n=$(printf '%s\n' "$got" | grep -c . || true)
    sum=$(grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$clog" | tail -1 || true)
    if [ "$rc" -eq 124 ]; then
      add_result "server:chunk-$chunk_no" "FAIL" "timeout ${MVN_CHUNK_TIMEOUT}s"
      set_fail_code 3; finish "$FAIL_CODE"
    fi
    if [ "$rc" -ne 0 ]; then
      kind=$(classify_mvn_log "$clog")
      add_result "server:chunk-$chunk_no" "FAIL" "$([ -n "$sum" ] && echo "${sum#\[.*\] }" || echo "$kind") — $kind"
      say "    chunk $chunk_no FAILED ($kind): ${sum:-no surefire summary}"
      grep -E "^\[ERROR\]" "$clog" | head -15 | tee -a "$MASTER_LOG" || true
      case "$kind" in resource) set_fail_code 3;; *) set_fail_code 1;; esac
      finish "$FAIL_CODE"
    fi
    if [ "$got_n" -ne "$want" ]; then
      add_result "server:chunk-$chunk_no" "FAIL" "manifest: ran $got_n of $want"
      set_fail_code 2; finish "$FAIL_CODE"
    fi
    add_result "server:chunk-$chunk_no" "PASS" "$want classes — ${sum:-summary missing}"
    offset=$((offset+CHUNK_SIZE))
  done

  # cross-chunk manifest audit: ran-set must equal manifest exactly (PO rule 3)
  LC_ALL=C sort -u "$RAN_LIST" | grep -v '^$' > "$EVIDENCE_DIR/.ran_sorted" || true
  if ! diff -q "$MANIFEST" "$EVIDENCE_DIR/.ran_sorted" > /dev/null 2>&1; then
    say "  MANIFEST MISMATCH — classes that did not run exactly once:"
    comm -3 "$MANIFEST" "$EVIDENCE_DIR/.ran_sorted" | tee -a "$MASTER_LOG" || true
    add_result "server:manifest-audit" "FAIL" "classes missing or duplicated across chunks"
    set_fail_code 2; finish 2
  fi
  add_result "server:manifest-audit" "PASS" "$TOTAL classes, each exactly once in $chunk_no chunks"
  rm -f "$RAN_LIST" "$EVIDENCE_DIR/.ran_sorted"
else
  add_result "server" "SKIP" "--skip-server"
fi

# ============================================================== [2/4] desktop
if [ "$RUN_DESKTOP" = 1 ]; then
  say "[2/4] desktop $(now): npm ci + npm run build (vue-tsc typecheck)"
  b_log="$EVIDENCE_DIR/desktop-build.log"
  if ! ( cd "$DESKTOP_DIR" && npm ci && npm run build ) > "$b_log" 2>&1; then
    add_result "desktop:build" "FAIL" "see desktop-build.log"
    tail -20 "$b_log" | tee -a "$MASTER_LOG" || true
    set_fail_code 1; finish 1
  fi
  add_result "desktop:build" "PASS" "npm ci + vue-tsc + vite build"
else
  add_result "desktop:build" "SKIP" "--skip-desktop"
fi

# =============================================================== [3/4] tauri
if [ "$RUN_TAURI" = 1 ]; then
  say "[3/4] tauri   $(now): cargo check --all-targets (src-tauri, Linux)"
  c_log="$EVIDENCE_DIR/cargo-check.log"
  if ! ( cd "$TAURI_DIR" && cargo check --all-targets ) > "$c_log" 2>&1; then
    add_result "desktop:cargo-check" "FAIL" "see cargo-check.log"
    tail -20 "$c_log" | tee -a "$MASTER_LOG" || true
    set_fail_code 1; finish 1
  fi
  add_result "desktop:cargo-check" "PASS" "cargo check --all-targets"
else
  add_result "desktop:cargo-check" "SKIP" "--skip-tauri"
fi

# ================================================================== [4/4] e2e
VITE_PID=""
cleanup() {
  if [ -n "$VITE_PID" ] && kill -0 "$VITE_PID" 2>/dev/null; then kill "$VITE_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT

start_vite() { # browser suites need :1420 (knowledge expects an external one;
  # wrapper-started here so ALL browser legs share one identical origin)
  [ -n "$VITE_PID" ] && return 0
  local vlog="$EVIDENCE_DIR/vite.log"
  cd "$DESKTOP_DIR"
  VITE_SERVER_URL="http://localhost:8080" CHOKIDAR_USEPOLLING=1 \
    nohup node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 1420 --strictPort > "$vlog" 2>&1 &
  VITE_PID=$!
  cd "$REPO_ROOT"
  for _ in $(seq 1 60); do
    if curl -sf -o /dev/null http://localhost:1420/; then return 0; fi
    sleep 1
  done
  return 1
}

run_e2e() { # $1 suite label, $2.. command
  local suite="$1"; shift
  local out="$EVIDENCE_DIR/e2e-$suite"
  mkdir -p "$out"
  say "  e2e [$suite] $(now) -> $out"
  set +e
  ( cd "$E2E_DIR" && E2E_OUT="$out" timeout "$E2E_TIMEOUT" "$@" ) > "$out/run.log" 2>&1
  local rc=$?
  set -e
  local steps ok
  steps=$(grep -c '"step"' "$out/run.log" 2>/dev/null || true)
  ok=$(grep -c '"ok": *true' "$out/run.log" 2>/dev/null || true)
  if [ "$rc" -ne 0 ]; then
    add_result "e2e:$suite" "FAIL" "exit $rc ($ok/${steps:-0} steps ok) — see e2e-$suite/run.log"
    grep -E '"ok": *false' "$out/run.log" | head -3 | tee -a "$MASTER_LOG" || true
    set_fail_code 1; finish 1
  fi
  add_result "e2e:$suite" "PASS" "$ok/$steps steps"
}

if [ "$RUN_E2E" = 1 ]; then
  say "[4/4] e2e     $(now): suites: ${E2E_SUITES[*]}"
  JAR=$(ls "$SERVER_DIR"/target/keplura-server-*.jar 2>/dev/null | head -1 || true)
  [ -n "$JAR" ] || { add_result "e2e" "FAIL" "server jar missing (run without --skip-server)"; set_fail_code 2; finish 2; }
  export E2E_JAR="$JAR"
  # Cache seit der Umbenennung (ADR-0013) unter keplura-e2e; vorhandene Hosts
  # haben ihn noch unter intracore-e2e -> dorthin zurückfallen.
  if [ -z "${NODE_PATH:-}" ]; then
    NODE_PATH="$HOME/.cache/keplura-e2e/node_modules"
    if [ ! -d "$NODE_PATH" ] && [ -d "$HOME/.cache/intracore-e2e/node_modules" ]; then
      NODE_PATH="$HOME/.cache/intracore-e2e/node_modules"
    fi
  fi
  export NODE_PATH
  if ! ls "${NODE_PATH%%:*}"/playwright-core/package.json >/dev/null 2>&1; then
    add_result "e2e" "FAIL" "playwright-core missing in NODE_PATH (see desktop/e2e/README.md)"
    set_fail_code 2; finish 2; fi
  command -v curl >/dev/null || { add_result "e2e" "FAIL" "curl missing (vite health probe)"; set_fail_code 2; finish 2; }

  # java processes running OUR exact jar path — safe, no cmdline pattern kill
  jar_pids() {
    local e raw
    for e in /proc/[0-9]*; do
      raw=$(cat "$e/cmdline" 2>/dev/null | tr '\0' ' ') || continue
      case " $raw " in *" $JAR "*) basename "$e";; esac
    done
  }
  wait_health() { # $1 up|down, $2 seconds
    local want="$1" limit="$2" t=0 code
    while [ "$t" -lt "$limit" ]; do
      # curl already writes 000 when no connection is possible. Appending
      # another 000 would hide a successful shutdown until the timeout.
      code=$(curl -s --max-time 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/health 2>/dev/null || true)
      if [ "$want" = up ] && [ "$code" = 200 ]; then return 0; fi
      if [ "$want" = down ] && { [ "$code" = 000 ] || [ "$code" = 503 ]; }; then return 0; fi
      sleep 2; t=$((t+2))
    done
    return 1
  }
  reap_jars() { # leave NO harness-booted server between suites: a leaked
    # seeded jar on :8080 would hijack the next suite's health probes
    local pids p
    pids=$(jar_pids || true)
    for p in $pids; do kill "$p" 2>/dev/null || true; done
    [ -z "$pids" ] || wait_health down 40 || say "  WARN: jar(s) $pids still answering after SIGTERM"
  }
  boot_seeded_jar() { # knowledge/dark flows expect an EXTERNALLY booted, seeded jar
    local workdir="/tmp/ci-local-$RUN_TS/knowledge-jar"
    local logf="${1:-$EVIDENCE_DIR/e2e-knowledge/seeded_server.log}"
    mkdir -p "$workdir" "$(dirname "$logf")"
    nohup java -jar "$JAR" \
      --server.port=8080 \
      --spring.profiles.active=dev \
      --spring.flyway.enabled=true --spring.jpa.hibernate.ddl-auto=validate \
      --spring.datasource.url="jdbc:h2:file:$workdir/db;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" \
      --keplura.upload.dir="$workdir/uploads" \
      --keplura.dev.seed.email="$CI_E2E_EMAIL" \
      --keplura.dev.seed.password="$CI_E2E_PASSWORD" \
      --keplura.dev.seed.display-name="CI Local Tester" \
      > "$logf" 2>&1 &
    disown
    wait_health up 120
  }

  ensure_packaged_bin() { # $1 suite label — build the --no-bundle release
    # binary if absent OR STALE (auth and dark-packaged share this preamble).
    # Staleness rule (t_6e8e1a6c diagnosis 09.09): the binary embeds
    # desktop/dist; [2/4] rebuilds dist from the current HEAD every run, so
    # a binary older than dist/index.html predates the code under test and
    # MUST be relinked. Without this, a cached pre-feature binary silently
    # replaces the packaged subject (dark-packaged failed exactly that way:
    # Sep-7 binary vs Sep-9 DARK-FS sidebar → 'Darstellung wechseln' absent).
    local label="$1"
    BIN="$TAURI_DIR/target/release/keplura-desktop"
    if [ -x "$BIN" ] && [ "$BIN" -ot "$DESKTOP_DIR/dist/index.html" ]; then
      say "  packaged binary OLDER than dist (stale embed) -> rebuild"
      rm -f "$BIN"
    fi
    if [ ! -x "$BIN" ]; then
      say "  packaged binary missing -> npm run tauri build -- --no-bundle (first run: slow, then cached)"
      ( cd "$DESKTOP_DIR" && npm run tauri build -- --no-bundle ) > "$EVIDENCE_DIR/tauri-build.log" 2>&1 \
        || { add_result "e2e:packaged-build" "FAIL" "see tauri-build.log"; set_fail_code 1; finish 1; }
      add_result "e2e:packaged-build" "PASS" "tauri build --no-bundle"
    fi
    [ -x "$BIN" ] || { add_result "e2e:$label" "FAIL" "binary $BIN still missing"; set_fail_code 1; finish 1; }
    for req in xvfb-run dbus-run-session; do
      command -v "$req" >/dev/null || { add_result "e2e:$label" "FAIL" "$req missing"; set_fail_code 2; finish 2; }
    done
  }

  for suite in "${E2E_SUITES[@]}"; do
    case "$suite" in
      knowledge)
        mkdir -p "$EVIDENCE_DIR/e2e-knowledge"
        start_vite || { add_result "e2e:knowledge" "FAIL" "vite :1420 never came up"; set_fail_code 3; finish 3; }
        boot_seeded_jar || { add_result "e2e:knowledge" "FAIL" "seeded jar never became healthy"; set_fail_code 3; finish 3; }
        export E2E_EMAIL="$CI_E2E_EMAIL"
        run_e2e knowledge node knowledge_flow_e2e.cjs "$CI_E2E_PASSWORD"
        reap_jars
        ;;
      dark)
        # DARK-FS browser leg: token switching, persistence, pre-paint,
        # sidebar controls, Fullscreen-API fallback (needs a REAL session
        # => externally seeded jar, same shape as the knowledge suite).
        start_vite || { add_result "e2e:dark" "FAIL" "vite :1420 never came up"; set_fail_code 3; finish 3; }
        boot_seeded_jar "$EVIDENCE_DIR/e2e-dark/seeded_server.log" || { add_result "e2e:dark" "FAIL" "seeded jar never became healthy"; set_fail_code 3; finish 3; }
        run_e2e dark node dark_flow_e2e.cjs "$CI_E2E_EMAIL" "$CI_E2E_PASSWORD"
        ;;
      dark-packaged)
        # DARK-FS native leg (AT-SPI + XTEST on the packaged binary): sidebar
        # controls in the a11y tree, theme cycle landing dark in the
        # webview, F11/Escape driving the native fullscreen path. Reuses the
        # seeded jar if the browser leg left one answering; boots a clean one
        # otherwise (foreign jars always reaped first — the packaged app
        # needs OUR server on :8080, no matter who booted it).
        if [ -n "$VITE_PID" ]; then kill "$VITE_PID" 2>/dev/null || true; wait "$VITE_PID" 2>/dev/null || true; VITE_PID=""; fi
        ensure_packaged_bin dark-packaged
        if ! curl -sf -o /dev/null http://127.0.0.1:8080/api/v1/health; then
          reap_jars
          boot_seeded_jar "$EVIDENCE_DIR/e2e-dark-packaged/seeded_server.log" || { add_result "e2e:dark-packaged" "FAIL" "seeded jar never became healthy"; set_fail_code 3; finish 3; }
        fi
        run_e2e dark-packaged dbus-run-session -- xvfb-run -a -s "-screen 0 1280x800x24" \
          python3 packaged_dark_e2e.py --bin "$BIN" \
          --email "$CI_E2E_EMAIL" --password "$CI_E2E_PASSWORD"
        reap_jars
        ;;
      chat)
        start_vite || { add_result "e2e:chat" "FAIL" "vite :1420 never came up"; set_fail_code 3; finish 3; }
        # chat_flow OWNS the jar lifecycle (mock gateway env injected at boot;
        # its bootJar SIGTERMs foreign java -jar processes first)
        run_e2e chat node chat_flow_e2e.cjs "$CI_E2E_EMAIL" "$CI_E2E_PASSWORD"
        reap_jars
        ;;
      invite)
        # invite_flow OWNS jar AND its own vite — free :1420 first, restart after
        if [ -n "$VITE_PID" ]; then kill "$VITE_PID" 2>/dev/null || true; wait "$VITE_PID" 2>/dev/null || true; VITE_PID=""; fi
        reap_jars
        run_e2e invite node invite_flow_e2e.cjs "$CI_E2E_EMAIL" "$CI_E2E_PASSWORD" "${CI_E2E_EMAIL_B:-e2e-invite-b@example.com}"
        ;;
      admin)
        # admin_flow OWNS jar AND its own vite (invite pattern): ADMIN-KI
        # panel E2E incl. seed offer, SSRF refusal, mask, CSRF+role gates
        if [ -n "$VITE_PID" ]; then kill "$VITE_PID" 2>/dev/null || true; wait "$VITE_PID" 2>/dev/null || true; VITE_PID=""; fi
        reap_jars
        run_e2e admin node admin_flow_e2e.cjs "$CI_E2E_EMAIL" "$CI_E2E_PASSWORD" "${CI_E2E_EMAIL_B:-e2e-admin-b@example.com}"
        ;;
      oidc)
        # oidc_flow OWNS jar, vite AND a real stub IdP process (invite
        # pattern): second login path E2E vs. genuine RSA IdP incl. PKCE
        # verifier exchange, negative legs, provider toggle. Owns its H2 db
        # under /tmp — nothing else to inject (credentials positional).
        if [ -n "$VITE_PID" ]; then kill "$VITE_PID" 2>/dev/null || true; wait "$VITE_PID" 2>/dev/null || true; VITE_PID=""; fi
        reap_jars
        run_e2e oidc node oidc_flow_e2e.cjs "$CI_E2E_EMAIL" "$CI_E2E_PASSWORD"
        reap_jars
        ;;
      auth|packaged)
        if [ -n "$VITE_PID" ]; then kill "$VITE_PID" 2>/dev/null || true; wait "$VITE_PID" 2>/dev/null || true; VITE_PID=""; fi
        reap_jars
        BIN="$TAURI_DIR/target/release/keplura-desktop"
        # same stale-embed rule as ensure_packaged_bin (see dark-packaged
        # diagnosis): a binary older than dist/index.html predates HEAD.
        if [ -x "$BIN" ] && [ "$BIN" -ot "$DESKTOP_DIR/dist/index.html" ]; then
          say "  packaged binary OLDER than dist (stale embed) -> rebuild"
          rm -f "$BIN"
        fi
        if [ ! -x "$BIN" ]; then
          say "  packaged binary missing -> npm run tauri build -- --no-bundle (first run: slow, then cached)"
          ( cd "$DESKTOP_DIR" && npm run tauri build -- --no-bundle ) > "$EVIDENCE_DIR/tauri-build.log" 2>&1 \
            || { add_result "e2e:packaged-build" "FAIL" "see tauri-build.log"; set_fail_code 1; finish 1; }
          add_result "e2e:packaged-build" "PASS" "tauri build --no-bundle"
        fi
        [ -x "$BIN" ] || { add_result "e2e:auth" "FAIL" "binary $BIN still missing"; set_fail_code 1; finish 1; }
        for req in xvfb-run dbus-run-session; do
          command -v "$req" >/dev/null || { add_result "e2e:auth" "FAIL" "$req missing"; set_fail_code 2; finish 2; }
        done
        run_e2e auth dbus-run-session -- xvfb-run -a -s "-screen 0 1280x800x24" \
          python3 packaged_login_e2e.py --bin "$BIN" --jar "$JAR" \
          --email "$CI_E2E_EMAIL" --password "$CI_E2E_PASSWORD"
        reap_jars
        ;;
    esac
  done
else
  add_result "e2e" "SKIP" "no suite selected (--suite/--with-e2e)"
fi

if [ "$FAIL_CODE" -ne 0 ]; then finish "$FAIL_CODE"; fi
say "all selected stages green — evidence: $EVIDENCE_DIR"
finish 0
