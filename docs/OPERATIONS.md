# Operating Keplura

Day-to-day operation of a Keplura installation: hardware, what runs where,
monitoring, backups, security notes and known limitations. Installation and
updates are in [INSTALL.md](INSTALL.md); the German operator guide
[`deploy/README.md`](../deploy/README.md) has additional detail.

## Hardware requirements

| Component | CPU | RAM | Disk |
|---|---|---|---|
| Server (JVM 21, Spring Boot) | 1–2 cores | ~1–1.5 GB heap + ~300 MB | < 1 GB image + uploaded documents |
| PostgreSQL 16 + pgvector | 1 core | 256 MB shared buffers + ~10 MB per connection | database + WAL; grows with document count |
| Ollama (CPU inference) | 2+ cores while answering | model size + ~0.5 GB (0.5B ≈ 1 GB, 7B ≈ 8 GB+) | model weights, ~0.4–5 GB each |
| Reranker (text-embeddings-inference, CPU) | 1–2 cores | ~1–2 GB | ~1 GB model |
| OCR (Tesseract, inside the server image) | uses server CPU during scans | small | +~120 MB image size |

Rules of thumb:

- **Trial:** 2 cores / 4 GB RAM with a 0.5–1B model. Answers are weak but the
  whole flow works.
- **Pilot with `qwen2.5:7b` on CPU:** 4+ cores / 16 GB RAM / ~15 GB for models.
  Expect answers in tens of seconds to minutes.
- **Production with many users:** a GPU host with vLLM (`--gpu` /
  `compose.gpu.yml`), or a hosted OpenAI-compatible API if your data policy
  allows it.

Upload size defaults to 20 MiB per file (`KEPLURA_UPLOAD_MAX_BYTES`); at most
two documents are parsed in parallel.

## What runs where

| Service (Compose) | Purpose | Logs |
|---|---|---|
| `server` | API, web UI, indexing, permission checks, OCR | `docker compose -f compose.prod.yml logs -f server` |
| `db` | PostgreSQL + pgvector | `logs -f db` |
| `ollama` / `ollama-pull` | local model runtime / one-off model download | `logs -f ollama` |
| `reranker` | optional re-scoring of search hits | `logs -f reranker` |

Logs never contain passwords, keys or document content. HTTP access logs list
path and status only. For deeper debugging set
`LOGGING_LEVEL_COM_KEPLURA=DEBUG` on the server container and restart it.

## Monitoring

No extra monitoring stack is required:

1. **Liveness:** `curl -fsS http://127.0.0.1:8080/api/v1/health` → `{"status":"UP"}`.
   The same check is wired as a container health check with automatic restart.
2. **Database:** the `db` container health check (`pg_isready`). Schema drift
   makes the server refuse to start (Flyway validation).
3. **Disk:** watch the document, database and model volumes; act at ~80 %.
4. **Errors:** `docker compose ... logs --since 1h server | grep -E 'ERROR|WARN'`.
5. **Model reachable:** `GET /api/v1/system/info` reports only
   `configured|mock|none`, never provider details.
6. **Backups:** **Administration → Datensicherung** shows the last backup
   status.

## Backup and restore

Set this up before the first productive day.

```bash
# daily at 02:30, keep the newest 14 (crontab of a user allowed to run docker)
30 2 * * * cd /opt/keplura && scripts/backup.sh --out /var/backups/keplura --keep 14 --yes

# test restore into a scratch database
scripts/restore.sh /var/backups/keplura/keplura-<timestamp> --database scratchdb

# full restore (replaces the live database and documents)
scripts/restore.sh /var/backups/keplura/keplura-<timestamp> --force
```

- Each backup is one folder: `database.dump` (`pg_dump -Fc`), `documents.tar.gz`
  and `MANIFEST.txt` with checksums. Permissions are 0700/0600 because it
  contains all tenant data.
- By default the server is paused for a few seconds to get a consistent copy;
  `--online` avoids the pause at the risk of missing a document uploaded at
  that moment.
- `restore.sh` verifies checksums first, refuses to overwrite a non-empty
  database without `--force`, and compares row counts and files afterwards.
- Copy backups to a second device or NAS. Rotation only protects against a
  full disk, not against losing the host.
- Keep `deploy/.env.production` (especially `KEPLURA_SECRETS_MASTER_KEY`)
  in a safe place as well. Without the master key, stored provider and data
  source credentials cannot be decrypted after a restore.

## Security notes for operators

- Run the server behind TLS only; it binds to `127.0.0.1` by default.
- `deploy/.env.production` holds every secret of the installation: keep it at
  mode 600, never commit it, never paste it into tickets or chats.
- The server blocks outbound requests to private networks (SSRF protection)
  for model endpoints, web search and MCP servers. Allow internal ranges
  explicitly with `KEPLURA_ADMIN_SSRF_ALLOW_CIDRS` only when needed.
- With a hosted model API, questions and retrieved passages leave your network.
- Data sources are read-only; Keplura never changes the original files.
- Report vulnerabilities as described in [SECURITY.md](../SECURITY.md).

## Known limitations

Status at version 0.1.0 (pre-release):

- **No stable release yet.** Versions are `0.x`; updates may need manual steps
  described in the changelog. No long-term support commitment.
- **German UI only.** The interface and most operator texts are German.
- **Single instance.** Sessions live in server memory: a restart signs all
  users out (no data loss). No clustering or high availability.
- **Desktop app:** server URL fixed at build time; no auto-update; Windows and
  macOS packages are unsigned/not notarised; no prebuilt downloads.
- **Web UI:** no global Content-Security-Policy yet (planned follow-up of
  ADR-0011).
- **Data sources:** NFS is blocked for now; Linux folders are read through the
  local Rust rights reader (requires POSIX ACL support). SharePoint requires an
  Entra app registration ([howto](howto/sharepoint.md)).
- **OCR:** German and English only; very large scans are limited by page and
  time limits.
- **Answer quality** depends on the model. Small CPU models give short and
  sometimes incomplete answers; always check the cited sources.
- **Development profile (H2):** for local development only; H2 has no native
  vector search, so retrieval quality differs from PostgreSQL + pgvector. Use
  PostgreSQL for realistic testing.
- **Platforms:** the server is built and tested on Linux. Windows and macOS
  builds of the desktop app are a release gate, not checked on every change.
