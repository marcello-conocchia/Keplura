#!/usr/bin/env bash
# =============================================================================
# backup.sh -- Keplura server backup (DEPLOY-BUNDLE, t_bc138a04).
#
# One backup = ONE consistent pair, taken together in one run:
#   * PostgreSQL dump      (pg_dump -Fc, verified with pg_restore --list)
#   * document storage tar (tar.gz of the upload dir, verified with tar -tzf)
# A restore must take BOTH halves from the SAME backup dir: document rows in
# the DB reference files on disk and the bytes are never re-derivable.
#
# Runs against EITHER deployment shape:
#   A) compose.prod.yml stack -- detected via docker/podman compose ps.
#   B) bare-metal server process + local PostgreSQL (PG* / KEPLURA_* env).
#
# Usage:
#   backup.sh [--out DIR] [--keep N] [--online] [--compose FILE] [--yes]
#     --out DIR    backup root (default /var/backups/keplura)
#     --keep N     rotation: keep the newest N backup dirs, delete older
#                  (default 0 = keep everything)
#     --online     skip the default server-stop around the storage snapshot.
#                  The DB dump is transactionally consistent either way; with
#                  --online the storage tree may miss ONE upload in flight
#                  (DocumentStorage renames into place atomically, so the
#                  worst case is a missing file, never a torn one) and the
#                  dump may reference it -- restore.sh reports such orphans.
#     --compose F  compose file for the container stack (default:
#                  <repo>/deploy/compose.prod.yml next to this script)
#     --yes        skip the interactive confirmation (for cron)
#
# Exit 0 = backup complete AND both halves verified. Secrets never enter the
# archive, the manifest or the log: credentials come from the process
# environment / compose env-file only.
#
# Rotate it like any backup policy (daily + weekly keep), and remember the
# media rule from restore.sh: the copies that survive are the ones you have
# test-restored.
# =============================================================================
set -euo pipefail

OUT="/var/backups/keplura"
KEEP=0
ONLINE=0
COMPOSE_FILE=""
RT=""   # docker | podman | ""

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2;;
    --keep) KEEP="$2"; shift 2;;
    --online) ONLINE=1; shift;;
    --compose) COMPOSE_FILE="$2"; shift 2;;
    --yes) shift;;
    -h|--help) sed -n '2,40p' "$0"; exit 0;;
    *) echo "unknown flag: $1" >&2; exit 2;;
  esac
done

log() { printf '[backup %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { echo "BACKUP FAILED: $*" >&2; exit 1; }

command -v sha256sum >/dev/null 2>&1 || die "sha256sum missing"

if command -v docker >/dev/null 2>&1; then RT=docker
elif command -v podman >/dev/null 2>&1; then RT=podman; fi

# repo-relative compose file default (script lives in <repo>/scripts/)
if [ -z "$COMPOSE_FILE" ]; then
  cand="$(cd "$(dirname "$0")/.." && pwd)/deploy/compose.prod.yml"
  [ -f "$cand" ] && COMPOSE_FILE="$cand"
fi

# does a compose stack with our services actually run? (probe, tolerant)
COMPOSE_ACTIVE=0
if [ -n "$RT" ] && [ -n "$COMPOSE_FILE" ] && [ -f "$COMPOSE_FILE" ]; then
  if $RT compose -f "$COMPOSE_FILE" ps --status running 2>/dev/null | grep -q 'db'; then
    COMPOSE_ACTIVE=1
  fi
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$OUT/keplura-$STAMP"
mkdir -p "$BACKUP_DIR"
# Tenant data + user table inside: lock the bits from the very first write.
chmod 700 "$OUT" "$BACKUP_DIR"
DB_DUMP="$BACKUP_DIR/database.dump"
DOC_TAR="$BACKUP_DIR/documents.tar.gz"
MANIFEST="$BACKUP_DIR/MANIFEST.txt"

# ---------------------------------------------------------------------------
# Shape A: compose.prod.yml stack
# ---------------------------------------------------------------------------
if [ "$COMPOSE_ACTIVE" = 1 ]; then
  log "compose stack: $RT -f $COMPOSE_FILE"
  PGU="$($RT compose -f "$COMPOSE_FILE" exec -T db printenv POSTGRES_USER)"
  PGD="$($RT compose -f "$COMPOSE_FILE" exec -T db printenv POSTGRES_DB)"
  # Named volume of the storage mount, resolved from the RUNNING stack itself.
  # NO guessed fallback name (reviewer finding): `create -v <unknown>` silently
  # auto-creates a fresh EMPTY volume -- a 'green' backup with zero document
  # bytes. If inspect cannot resolve it, we hard-abort instead.
  FULLVOL="$($RT compose -f "$COMPOSE_FILE" ps -q server | head -1 \
    | xargs -r $RT inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/keplura"}}{{.Name}}{{end}}{{end}}' 2>/dev/null || true)"
  [ -n "$FULLVOL" ] || die "cannot resolve the storage volume of the running server container (compose stack changed? bind mount instead of a named volume? then back up that host directory directly) -- refusing to guess a volume name"

  if [ "$ONLINE" = 0 ]; then
    log "stopping server container (storage snapshot consistency)"
    $RT compose -f "$COMPOSE_FILE" stop server
  fi
  log "pg_dump -Fc inside db container"
  $RT compose -f "$COMPOSE_FILE" exec -T db pg_dump -U "$PGU" -d "$PGD" -Fc > "$DB_DUMP"
  log "document tree from volume $FULLVOL"
  # run -T (NOT --rm): --rm detaches stdout and truncates the pipe (measured
  # with both engines); the temp container is cleaned up afterwards. The :ro
  # mount also gives the tar an internally consistent point-in-time view.
  CTR=$($RT create -v "$FULLVOL:/mnt/storage:ro" --entrypoint /bin/sh \
        "$($RT compose -f "$COMPOSE_FILE" images db | tail -1 | awk '{print $2}')" \
        -c 'tar -cf - -C /mnt storage')
  rc=0; $RT start -a -i "$CTR" > "$DOC_TAR.raw" || rc=$?
  $RT rm -f "$CTR" >/dev/null 2>&1 || true
  [ $rc -eq 0 ] || die "volume read failed (container $CTR)"
  gzip -6c "$DOC_TAR.raw" > "$DOC_TAR"   # pg_dump -Fc is already compressed
  rm -f "$DOC_TAR.raw"                   # (plain `gzip file.raw` would yield file.raw.gz!)
  if [ "$ONLINE" = 0 ]; then
    log "restarting server container"
    $RT compose -f "$COMPOSE_FILE" start server
  fi
else
  # -------------------------------------------------------------------------
  # Shape B: bare-metal -- the server runs as a plain process on this machine.
  # -------------------------------------------------------------------------
  command -v pg_dump >/dev/null 2>&1 || die "no compose stack AND no pg_dump -- install postgresql-client or run this next to the stack"
  UPLOAD_DIR="${KEPLURA_UPLOAD_DIR:-${INTRACORE_UPLOAD_DIR:-./data/uploads}}"
  [ -d "$UPLOAD_DIR" ] || die "upload dir '$UPLOAD_DIR' missing (set KEPLURA_UPLOAD_DIR)"
  : "${PGDATABASE:=${POSTGRES_DB:-keplura}}"
  : "${PGUSER:=${POSTGRES_USER:-keplura}}"
  # PGPASSWORD may legitimately be empty (peer/trust auth on a socket).
  log "bare-metal pg_dump (host=${PGHOST:-socket} port=${PGPORT:-5432} db=$PGDATABASE user=$PGUSER)"
  pg_dump -Fc > "$DB_DUMP"
  log "tarring document tree $UPLOAD_DIR"
  # relative-path tar from the parent dir -> relocatable extraction in restore.sh
  ( cd "$(dirname "$UPLOAD_DIR")" && tar -czf "$DOC_TAR" "$(basename "$UPLOAD_DIR")" )
fi
chmod 600 "$DB_DUMP" "$DOC_TAR"

# ---- verification: a backup you never read is a rumor -----------------------
if command -v pg_restore >/dev/null 2>&1; then
  pg_restore --list "$DB_DUMP" >/dev/null || die "database.dump fails pg_restore --list"
elif [ "$COMPOSE_ACTIVE" = 1 ]; then
  $RT compose -f "$COMPOSE_FILE" exec -T db pg_restore --list - < "$DB_DUMP" >/dev/null \
    || die "database.dump fails pg_restore --list (in-container check)"
else
  die "pg_restore not found -- cannot verify the dump"
fi
tar -tzf "$DOC_TAR" >/dev/null || die "documents.tar.gz unreadable"

{
  echo "created_utc:  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "shape:        $([ "$COMPOSE_ACTIVE" = 1 ] && echo compose || echo bare-metal)"
  echo "online:       $ONLINE"
  echo "pg_format:    custom(-Fc)"
  echo "sha256_database:  $(sha256sum "$DB_DUMP" | awk '{print $1}')"
  echo "sha256_documents: $(sha256sum "$DOC_TAR" | awk '{print $1}')"
  echo "pg_dump_version:  $( (pg_dump --version || $RT compose -f "$COMPOSE_FILE" exec -T db pg_dump --version) 2>/dev/null | head -1)"
} > "$MANIFEST"
chmod 600 "$MANIFEST"

# ---- rotation (only ever touches this script's own prefix) ------------------
if [ "$KEEP" -gt 0 ]; then
  log "rotation: keeping newest $KEEP in $OUT"
  ls -1d "$OUT"/keplura-[0-9]*/ 2>/dev/null | sort | head -n -"$KEEP" | while read -r old; do
    log "deleting old backup $old"; rm -rf "$old"
  done
fi

# ---- status for the admin UI (PILOT 4e) ---------------------------------------
# A small, secret-free JSON next to the document storage. The server only READS
# it (GET /api/v1/admin/backup); a failed write never fails the backup itself.
STATUS_JSON=$(printf '{"finishedAt":"%s","backupName":"%s","databaseBytes":%s,"documentsBytes":%s,"verified":true,"online":%s}' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(basename "$BACKUP_DIR")" \
  "$(stat -c %s "$DB_DUMP")" "$(stat -c %s "$DOC_TAR")" \
  "$([ "$ONLINE" = 1 ] && echo true || echo false)")
if [ "$COMPOSE_ACTIVE" = 1 ]; then
  printf '%s\n' "$STATUS_JSON" | $RT compose -f "$COMPOSE_FILE" exec -T server \
    sh -c 'cat > /var/lib/keplura/backup-status.json' 2>/dev/null \
    || log "note: could not write backup status for the admin UI (server not running?)"
else
  printf '%s\n' "$STATUS_JSON" > "$(dirname "$UPLOAD_DIR")/backup-status.json" 2>/dev/null \
    || log "note: could not write backup status for the admin UI"
fi

log "backup complete + verified: $BACKUP_DIR"
printf '%s\n' "$BACKUP_DIR"
