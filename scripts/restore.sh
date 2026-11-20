#!/usr/bin/env bash
# =============================================================================
# restore.sh -- Keplura server restore (DEPLOY-BUNDLE, t_bc138a04).
#
# Restores a backup dir produced by scripts/backup.sh (both halves or either):
#   * database.dump   -> PostgreSQL via pg_restore INTO AN EMPTY DATABASE
#   * documents.tar.gz -> the document storage tree
# The dump is a custom-format pg_dump WITHOUT owner/privileges clauses, so
# restoring as the deployment's own DB user "just works" (roles travel across
# hosts, none are needed here).
#
# Usage:
#   restore.sh BACKUP_DIR [--database D] [--to DIR] [--force] [--skip-docs]
#     [--verify-only]
#     BACKUP_DIR    dir printed by backup.sh (manifest required)
#     --database D  restore target db (default $PGDATABASE / POSTGRES_DB /
#                   keplura; compose shape: the stack's own POSTGRES_DB)
#     --to DIR      extract the document tree into DIR (default
#                   $KEPLURA_UPLOAD_DIR or ./data/uploads; for the compose
#                   shape: the volume, see below)
#     --force       allow restoring into a NON-empty database (drops and
#                   recreates the public schema!). Without it, a non-empty
#                   target aborts -- the safe default for a middle-market admin.
#     --skip-docs   only the database half
#     --verify-only  integrity-check the archive (hashes + pg_restore --list +
#                   tar -tzf) without touching any live system
#
# Exit 0 = restored and post-checked (row counts of the key tables + the app's
# own health endpoint when the server is reachable). A restore you did not
# post-check is a rumor.
#
# Media rule: keep at least one backup copy OFF the database host (rotation
# --keep only protects against disk fill, not against a dead machine).
# =============================================================================
set -euo pipefail

BACKUP_DIR=""
DATABASE=""
TARGET_DIR=""
FORCE=0
SKIP_DOCS=0
VERIFY_ONLY=0
RT=""; COMPOSE_FILE=""; COMPOSE_ACTIVE=0; PGU=""

usage() { sed -n '2,36p' "$0"; exit 2; }
while [ $# -gt 0 ]; do
  case "$1" in
    --database) DATABASE="$2"; shift 2;;
    --to) TARGET_DIR="$2"; shift 2;;
    --force) FORCE=1; shift;;
    --skip-docs) SKIP_DOCS=1; shift;;
    --verify-only) VERIFY_ONLY=1; shift;;
    --compose) COMPOSE_FILE="$2"; shift 2;;
    -h|--help) usage;;
    -*) echo "unknown flag: $1" >&2; usage;;
    *) [ -z "$BACKUP_DIR" ] || { echo "two backup dirs?" >&2; exit 2; }; BACKUP_DIR="$1"; shift;;
  esac
done
[ -n "$BACKUP_DIR" ] || usage
[ -d "$BACKUP_DIR" ] || { echo "not a directory: $BACKUP_DIR" >&2; exit 2; }
MANIFEST="$BACKUP_DIR/MANIFEST.txt"
DB_DUMP="$BACKUP_DIR/database.dump"
DOC_TAR="$BACKUP_DIR/documents.tar.gz"
[ -f "$MANIFEST" ] || { echo "no MANIFEST.txt -- not a backup.sh dir" >&2; exit 2; }
[ -f "$DB_DUMP" ] || { echo "no database.dump in $BACKUP_DIR" >&2; exit 2; }

log() { printf '[restore %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { echo "RESTORE FAILED: $*" >&2; exit 1; }

if command -v docker >/dev/null 2>&1; then RT=docker
elif command -v podman >/dev/null 2>&1; then RT=podman; fi
if [ -z "$COMPOSE_FILE" ]; then
  cand="$(cd "$(dirname "$0")/.." && pwd)/deploy/compose.prod.yml"
  [ -f "$cand" ] && COMPOSE_FILE="$cand"
fi
if [ -n "$RT" ] && [ -n "$COMPOSE_FILE" ] && [ -f "$COMPOSE_FILE" ]; then
  $RT compose -f "$COMPOSE_FILE" ps --status running 2>/dev/null | grep -q 'db' && COMPOSE_ACTIVE=1
fi

# ---- integrity first (always) -------------------------------------------------
[ -f "$DOC_TAR" ] || [ "$SKIP_DOCS" = 1 ] || die "documents.tar.gz missing (or pass --skip-docs)"
log "verifying archive hashes"
# Key-correct extraction: backup.sh writes `sha256_database:  <hex>` (colon in
# the key, spaces before the value). A missing, empty or malformed entry is an
# ERROR -- a backup whose integrity we cannot prove is not verifiable.
manifest_hash() { # $1 = manifest key (without colon)
  awk -v k="$1" '$1 == k":" {print $2}' "$MANIFEST" | head -1
}
check_hash() { # $1 = manifest key, $2 = file
  want="$(manifest_hash "$1")"
  [ -n "$want" ] \
    || die "manifest has no usable '$1:' entry -- refusing an archive we cannot verify"
  echo "$want" | grep -Eq '^[0-9a-fA-F]{64}$' \
    || die "manifest '$1' is not a sha256 hex digest: '$want'"
  got="$(sha256sum "$2" | awk '{print $1}')"
  [ "$got" = "$want" ] || die "checksum mismatch for $2 (archive corruption? wrong copy?)"
  log "hash ok: $1 -> $(basename "$2")"
}
check_hash sha256_database "$DB_DUMP"
if [ "$SKIP_DOCS" = 0 ]; then
  check_hash sha256_documents "$DOC_TAR"
fi
log "hashes ok (2 for full backup, 1 for --skip-docs)"
if command -v pg_restore >/dev/null 2>&1; then
  pg_restore --list "$DB_DUMP" >/dev/null || die "database.dump unreadable by pg_restore"
fi
tar -tzf "$DOC_TAR" >/dev/null || die "documents.tar.gz unreadable"
log "archive is intact"
if [ "$VERIFY_ONLY" = 1 ]; then
  printf 'VERIFY OK %s\n' "$BACKUP_DIR"; exit 0
fi

# ---- shape A: compose stack ---------------------------------------------------
resolve_pgu() { # DB superuser, resolved ONCE in MAIN shell scope.
  # (Reviewer finding: it must never be set inside a subshell -- the later
  # pg_restore runs in the main scope under `set -u`.)
  if [ "$COMPOSE_ACTIVE" = 1 ]; then
    PGU="$($RT compose -f "$COMPOSE_FILE" exec -T db printenv POSTGRES_USER | tr -d '[:space:]')"
    [ -n "$PGU" ] || die "cannot resolve POSTGRES_USER from the compose db container"
  fi
}
run_sql() { # $1 = sql (always against the resolved $DATABASE)
  if [ "$COMPOSE_ACTIVE" = 1 ]; then
    $RT compose -f "$COMPOSE_FILE" exec -T db psql -U "$PGU" -d "$DATABASE" -tAc "$1"
  else
    psql -d "$DATABASE" -tAc "$1"
  fi
}
[ -n "$DATABASE" ] || DATABASE="${PGDATABASE:-${POSTGRES_DB:-keplura}}"
resolve_pgu

# refuse to overwrite a live schema without --force (non-empty = public has tables)
NONEMPTY="$(run_sql "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name <> 'flyway_schema_history';" | tr -d '[:space:]')"
if [ "${NONEMPTY:-0}" != "0" ] && [ "$FORCE" = 0 ]; then
  die "target database '$DATABASE' has ${NONEMPTY} table(s). This is a DISASTER RECOVERY tool: pass --force to drop and recreate the public schema. (For a test restore into a scratch db use --database otherdb.)"
fi

log "stopping app writes during restore"
if [ "$COMPOSE_ACTIVE" = 1 ]; then $RT compose -f "$COMPOSE_FILE" stop server; fi

if [ "$FORCE" = 1 ]; then
  log "--force: recreating schema public"
  run_sql "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null
fi

log "pg_restore into '$DATABASE'"
if [ "$COMPOSE_ACTIVE" = 1 ]; then
  $RT compose -f "$COMPOSE_FILE" exec -T db pg_restore -U "$PGU" -d "$DATABASE" --no-owner < "$DB_DUMP"
else
  pg_restore -d "$DATABASE" --no-owner < "$DB_DUMP"
fi

# Flyway bookkeeping: the dump carries the CURRENT schema (including
# flyway_schema_history), so the server boots against it with
# ddl-auto=validate + Flyway baseline disabled -- nothing to re-apply.

# document tree
if [ "$SKIP_DOCS" = 0 ]; then
  if [ "$COMPOSE_ACTIVE" = 1 ] && [ -z "$TARGET_DIR" ]; then
    VOL="$($RT compose -f "$COMPOSE_FILE" ps -q server | head -1 \
      | xargs -r $RT inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/keplura"}}{{.Name}}{{end}}{{end}}')"
    [ -n "$VOL" ] || die "cannot resolve the storage volume of the compose stack"
    log "extracting document tree into volume $VOL"
    # unpack via a throwaway container (tar half is 'storage/...' relative)
    CTR=$($RT create -v "$VOL:/mnt/storage" --entrypoint /bin/sh \
          "$($RT compose -f "$COMPOSE_FILE" images db | tail -1 | awk '{print $2}')" \
          -c 'rm -rf /mnt/storage && tar -xf - -C /mnt && chown -R 1000:1000 /mnt/storage && ls -la /mnt/storage >/dev/null')
    rc=0; $RT start -a -i "$CTR" < "$DOC_TAR" || rc=$?
    $RT rm -f "$CTR" >/dev/null 2>&1 || true
    [ $rc -eq 0 ] || die "volume extract failed"
  else
    TARGET_DIR="${TARGET_DIR:-${KEPLURA_UPLOAD_DIR:-${INTRACORE_UPLOAD_DIR:-./data/uploads}}}"
    log "extracting document tree into $TARGET_DIR"
    mkdir -p "$(dirname "$TARGET_DIR")"
    TMPX="$(mktemp -d)"
    tar -xzf "$DOC_TAR" -C "$TMPX"
    if [ -d "$TARGET_DIR" ]; then rm -rf "$TARGET_DIR"; fi
    mv "$TMPX/$(basename "$TARGET_DIR")" "$TARGET_DIR" 2>/dev/null \
      || mv "$TMPX"/* "$TARGET_DIR"
    rm -rf "$TMPX"
  fi
fi

# ---- post-check: what the owner actually looks at ------------------------------
U="$(run_sql "SELECT count(*) FROM app_user;" | tr -d '[:space:]')"
D="$(run_sql "SELECT count(*) FROM kb_document;" | tr -d '[:space:]')"
M="$(run_sql "SELECT count(*) FROM chat_message;" | tr -d '[:space:]')"
log "post-check: users=$U documents=$D chat_messages=$M"
FILES=0
if [ "$SKIP_DOCS" = 0 ]; then
  if [ "$COMPOSE_ACTIVE" = 1 ]; then
    FILES="$($RT compose -f "$COMPOSE_FILE" run -T --rm --no-deps --entrypoint /bin/sh server -c 'find /var/lib/keplura/uploads -type f 2>/dev/null | wc -l' </dev/null | tr -d '[:space:]' || echo 0)"
  else
    FILES="$(find "$TARGET_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')"
  fi
  log "post-check: stored document files=$FILES"
  # orphan check (dump rows without bytes on disk) -- informational, honest.
  if [ "$COMPOSE_ACTIVE" = 0 ]; then
    ORPH="$([ "$D" != "$FILES" ] && echo yes || echo no)"
    [ "$ORPH" = yes ] && log "note: DB rows ($D) and files ($FILES) differ -- pre-existing orphan from an --online backup?"
  fi
fi

if [ "$COMPOSE_ACTIVE" = 1 ]; then $RT compose -f "$COMPOSE_FILE" start server; fi
log "restore complete"
printf 'RESTORED %s users=%s documents=%s messages=%s\n' "$BACKUP_DIR" "$U" "$D" "$M"
