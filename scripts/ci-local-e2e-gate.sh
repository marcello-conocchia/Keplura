#!/usr/bin/env bash
# scripts/ci-local-e2e-gate.sh — wrapper um scripts/ci-local.sh --with-e2e
# fuer Hosts, deren inotify-Budget (fs.inotify.max_user_instances) erschöpft
# ist. Reine Dev-Gate-Infrastruktur: kein Produktbuild, keine Deploy-Pfade.
#
# WHY (UI-REFRESH-1, 2026-09-10): auf dem geteilten Host-Kernel schlägt
# `tauri build --no-bundle` mit Panic+core-dump in tauri-cli 2.11.4
# interface/rust.rs:146 (unwrap auf notify-Debouncer, der im Build-Pfad
# toter Code ist), sobald inotify_init EMFILE liefert — bookkeeping der
# max_user_instances ist HOST-weit (alle Agent-Container), von innen nicht
# heilbar (/proc/sys read-only). Details + Scope-Beweis: ci/shims/no-inotify.c.
#
# Nutzung wie ci-local.sh; Credentials (CI_E2E_EMAIL/CI_E2E_PASSWORD) wie
# üblich von aussen ins Environment setzen — dieses Skript enthält keine.
set -u
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
SHIM_SRC="$REPO_ROOT/ci/shims/no-inotify.c"
SHIM_SO="${TMPDIR:-/tmp}/keplura-no-inotify.so"

probe() { # 0 = inotify usable for this uid, 1 = exhausted
  python3 - <<'PY'
import ctypes, sys
try:
    libc = ctypes.CDLL("libc.so.6", use_errno=True)
except OSError:
    sys.exit(0)   # no python3 -> don't preload blindly; gate runs as-is
fd = libc.inotify_init1(0)
if fd >= 0:
    import os; os.close(fd)
    sys.exit(0)
sys.exit(1)
PY
}

if probe; then
  echo "gate: inotify available — running ci-local.sh unmodified"
else
  echo "gate: inotify instances exhausted — building + enabling shim" >&2
  command -v gcc >/dev/null || { echo "gcc missing, cannot build shim" >&2; exit 2; }
  if [ ! -e "$SHIM_SO" ] || [ "$SHIM_SRC" -nt "$SHIM_SO" ]; then
    gcc -shared -fPIC -O2 -o "$SHIM_SO" "$SHIM_SRC" || { echo "shim build failed" >&2; exit 2; }
  fi
  export LD_PRELOAD="$SHIM_SO"
fi

exec bash "$REPO_ROOT/scripts/ci-local.sh" --with-e2e "$@"
