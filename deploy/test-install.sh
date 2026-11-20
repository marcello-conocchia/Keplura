#!/usr/bin/env bash
# Hermetic installer regression tests; no containers, downloads or credentials.
set -euo pipefail
SOURCE="$(cd "$(dirname "$0")" && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT
mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/deploy"
cp "$SOURCE/install.sh" "$SOURCE/.env.production.example" "$TEST_ROOT/deploy/"
export INSTALL_TEST_ROOT="$TEST_ROOT"
cat > "$TEST_ROOT/bin/docker" <<'EOF'
#!/usr/bin/env bash
if [ "$1" = info ]; then exit "${TEST_ENGINE_EXIT:-0}"; fi
if [ "$1 $2 $3" = 'compose version ' ]; then exit 0; fi
if [ "$1 $2" = 'compose version' ]; then exit 0; fi
printf '%s\n' "$*" >> "$INSTALL_TEST_ROOT/docker-calls"
EOF
cat > "$TEST_ROOT/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$INSTALL_TEST_ROOT/curl-calls"
printf '{"status":"UP"}\n'
EOF
chmod +x "$TEST_ROOT/bin/"*
export PATH="$TEST_ROOT/bin:$PATH"
run() { bash "$TEST_ROOT/deploy/install.sh" "$@" > "$TEST_ROOT/output" 2>&1; }
expect_failure() {
  if run "$@"; then echo "FAIL: expected rejection: $*"; exit 1; fi
}
expect_failure --admin-email
expect_failure --admin-email 'bad$(command)@example.test'
expect_failure --port
expect_failure --port 0
expect_failure --port 65536
expect_failure --port nope
[ ! -f "$TEST_ROOT/deploy/.env.production" ]
echo 'PASS: invalid arguments rejected before creating configuration'

TEST_ENGINE_EXIT=1 expect_failure
[ ! -f "$TEST_ROOT/deploy/.env.production" ]
echo 'PASS: stopped engine rejected before creating configuration'

run --admin-email pilot@example.test --port 18080
grep -q '^KEPLURA_ADMIN_BOOTSTRAP_EMAIL=pilot@example.test$' "$TEST_ROOT/deploy/.env.production"
grep -q '127.0.0.1:18080/api/v1/health' "$TEST_ROOT/curl-calls"
secret="$(sed -n 's/^KEPLURA_ADMIN_BOOTSTRAP_PASSWORD=//p' "$TEST_ROOT/deploy/.env.production")"
[ -n "$secret" ]
if grep -Fq "$secret" "$TEST_ROOT/output"; then echo 'FAIL: password leaked to output'; exit 1; fi
echo 'PASS: first install generates credentials without printing password'

cp "$TEST_ROOT/deploy/.env.production" "$TEST_ROOT/original-env"
run --admin-email other@example.test
cmp "$TEST_ROOT/original-env" "$TEST_ROOT/deploy/.env.production"
echo 'PASS: repeated install preserves existing credentials byte for byte'

printf 'KEPLURA_SERVER_HOST_PORT=19090\n' > "$TEST_ROOT/deploy/.env.production"
run
tail -n 1 "$TEST_ROOT/curl-calls" | grep -q '127.0.0.1:19090/api/v1/health'
run --port 18081
tail -n 1 "$TEST_ROOT/curl-calls" | grep -q '127.0.0.1:18081/api/v1/health'
KEPLURA_SERVER_HOST_PORT=18082 run
tail -n 1 "$TEST_ROOT/curl-calls" | grep -q '127.0.0.1:18082/api/v1/health'
echo 'PASS: port from configuration, CLI or environment matches health probe'

printf '# Existing minimal configuration without a port\n' > "$TEST_ROOT/deploy/.env.production"
run
tail -n 1 "$TEST_ROOT/curl-calls" | grep -q '127.0.0.1:8080/api/v1/health'
echo 'PASS: existing configuration without a port uses 8080'

printf 'KEPLURA_SERVER_HOST_PORT=invalid\n' > "$TEST_ROOT/deploy/.env.production"
expect_failure
echo 'PASS: invalid configured port rejected'

printf 'POSTGRES_DB=intracore\nINTRACORE_SECRETS_MASTER_KEY=keep-me\nINTRACORE_SERVER_HOST_PORT=19191\n' > "$TEST_ROOT/deploy/.env.production"
cp "$TEST_ROOT/deploy/.env.production" "$TEST_ROOT/legacy-env"
run
grep -q '^KEPLURA_SECRETS_MASTER_KEY=keep-me$' "$TEST_ROOT/deploy/.env.production"
grep -q '^POSTGRES_DB=intracore$' "$TEST_ROOT/deploy/.env.production"
if grep -q '^INTRACORE_' "$TEST_ROOT/deploy/.env.production"; then echo 'FAIL: legacy keys left'; exit 1; fi
cmp "$TEST_ROOT/legacy-env" "$TEST_ROOT/deploy/.env.production.vor-keplura"
tail -n 1 "$TEST_ROOT/curl-calls" | grep -q '127.0.0.1:19191/api/v1/health'
if grep -q 'keep-me' "$TEST_ROOT/output"; then echo 'FAIL: secret leaked to output'; exit 1; fi
echo 'PASS: legacy INTRACORE_ keys migrated to KEPLURA_ with backup, values kept'

printf 'KEPLURA_CHAT_MODEL=new\nINTRACORE_CHAT_MODEL=old\nexport INTRACORE_INDEXING_MODE=async\n' > "$TEST_ROOT/deploy/.env.production"
run
grep -q '^KEPLURA_CHAT_MODEL=new$' "$TEST_ROOT/deploy/.env.production"
if grep -q 'old' "$TEST_ROOT/deploy/.env.production"; then echo 'FAIL: legacy value survived next to the new name'; exit 1; fi
grep -q '^export KEPLURA_INDEXING_MODE=async$' "$TEST_ROOT/deploy/.env.production"
[ "$(stat -c %a "$TEST_ROOT/deploy/.env.production")" = "$(stat -c %a "$TEST_ROOT/deploy/.env.production.vor-keplura")" ]
echo 'PASS: new name wins, export lines migrated, file mode kept'

rm -f "$TEST_ROOT/deploy/.env.production.vor-keplura"
if ln -s "$TEST_ROOT/elsewhere" "$TEST_ROOT/deploy/.env.production.vor-keplura" 2>/dev/null; then
  printf 'INTRACORE_CHAT_MODEL=x\n' > "$TEST_ROOT/deploy/.env.production"
  run
  [ ! -e "$TEST_ROOT/elsewhere" ]
  grep -q '^INTRACORE_CHAT_MODEL=x$' "$TEST_ROOT/deploy/.env.production.vor-keplura."*
  echo 'PASS: symlinked backup target never followed, backup written beside it'
else
  echo 'SKIP: symlinks not available on this host'
fi

rm -f "$TEST_ROOT/deploy/.env.production".vor-keplura*
printf 'INTRACORE_CHAT_MODEL=first\n' > "$TEST_ROOT/deploy/.env.production"
run
printf 'INTRACORE_CHAT_MODEL=second\n' >> "$TEST_ROOT/deploy/.env.production"
run
grep -q '^INTRACORE_CHAT_MODEL=first$' "$TEST_ROOT/deploy/.env.production.vor-keplura"
ls "$TEST_ROOT/deploy/.env.production.vor-keplura."* >/dev/null
if ls "$TEST_ROOT/deploy/.env.production."?????? 2>/dev/null | grep -v vor-keplura; then echo 'FAIL: temp file left behind'; exit 1; fi
echo 'PASS: earlier backup kept, no temp file left'
