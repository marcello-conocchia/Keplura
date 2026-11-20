# Installing and updating Keplura

This guide takes you from an empty Linux server to a first answer with
citations. The German operator guide [`deploy/README.md`](../deploy/README.md)
covers the same steps in more depth (manual setup, bare-metal operation,
answer-quality pilot).

- [1. Requirements](#1-requirements)
- [2. Install the server](#2-install-the-server)
- [3. TLS reverse proxy](#3-tls-reverse-proxy)
- [4. First login and first knowledge base](#4-first-login-and-first-knowledge-base)
- [5. Desktop app (optional)](#5-desktop-app-optional)
- [6. Choosing a model provider](#6-choosing-a-model-provider)
- [7. Verify the installation](#7-verify-the-installation)
- [8. Updating](#8-updating)
- [9. Uninstalling](#9-uninstalling)

## 1. Requirements

| | Minimum (trial) | Recommended (pilot) |
|---|---|---|
| OS | Linux x86_64 | Linux x86_64 (Ubuntu 22.04+/Debian 12+ tested in containers) |
| CPU | 2 cores | 4+ cores, or an NVIDIA GPU with vLLM |
| RAM | 4 GB with a 0.5–1B model | 16 GB for `qwen2.5:7b` on CPU |
| Disk | 10 GB | ~15 GB for models + space for documents, database and backups |
| Software | Docker or Podman with Compose, `git`, `openssl` | same, plus a TLS reverse proxy (e.g. Caddy, nginx) |

Details: [OPERATIONS.md — hardware requirements](OPERATIONS.md#hardware-requirements).
Docker or Podman must be running before you start the installer.

## 2. Install the server

```bash
git clone https://github.com/marcello-conocchia/Keplura.git /opt/keplura
cd /opt/keplura/deploy
./install.sh --admin-email admin@example.com      # add --gpu on an NVIDIA host
```

What the installer does:

1. On the first run, creates `deploy/.env.production` (mode 600) from
   [`.env.production.example`](../deploy/.env.production.example) with a
   random database password, a secrets master key and a first-admin password.
   It never prints the password; read it from the file.
2. Builds the server image (`deploy/Dockerfile.server`: web UI, server JAR,
   local rights reader, Tesseract OCR).
3. Starts PostgreSQL 16 + pgvector, the server, Ollama and the reranker
   (`deploy/compose.prod.yml`). The server publishes only on `127.0.0.1`.
4. Pulls the chat model `qwen2.5:7b` and the embedding model `bge-m3`.
5. Waits for `GET /api/v1/health` to return `{"status":"UP"}`.

Running it again is safe: an existing `.env.production` and its credentials
are never overwritten.

Options:

- Port 8080 taken: `./install.sh --port 18080`, or set
  `KEPLURA_SERVER_HOST_PORT=18080` in `.env.production` to make it permanent.
- `bash deploy/test-install.sh` checks argument handling, configuration and
  that the installer never prints secrets, without starting containers.

Manual alternative (same result):

```bash
cd /opt/keplura/deploy
cp .env.production.example .env.production && chmod 600 .env.production
# edit .env.production: POSTGRES_PASSWORD (e.g. `openssl rand -hex 24`),
# KEPLURA_SECRETS_MASTER_KEY and the bootstrap admin
docker compose -f compose.prod.yml --env-file .env.production up -d --build
curl -fsS http://127.0.0.1:8080/api/v1/health
```

## 3. TLS reverse proxy

The production profile marks the session cookie `Secure`. Browsers send it
only over HTTPS (or to `localhost`), so remote users can only sign in through
a TLS proxy. Minimal Caddy example on the same host:

```
keplura.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Any reverse proxy works as long as it passes `/api/...` including
Server-Sent Events (disable response buffering for `/api/v1/orgs/*/chat/...`).

## 4. First login and first knowledge base

1. Open `https://keplura.example.com/` and sign in with the admin e-mail and
   the password from `deploy/.env.production`. Store it in your password
   manager; the bootstrap values can stay in the file (they are applied
   idempotently and never reset an existing password).
2. Create an organisation, then a workspace (e.g. "HR") and a knowledge base
   (e.g. "Employee handbook").
3. Upload documents (txt, md, csv, pdf, docx, xlsx, pptx) or connect a data
   source under **Administration → Datenquellen**. Imported sources keep their
   access rights.
4. Start a new chat, add the knowledge base and ask a question. The answer
   lists its sources.

Invite colleagues under **Team**, or connect LDAP/Active Directory
(**Administration → Verzeichnis**) or an OIDC identity provider so people sign
in with their company account.

## 5. Desktop app (optional)

The browser UI is complete. The desktop app is the same UI in a native window.
There are no prebuilt packages yet; build them on the target platform:

```bash
cd desktop
npm ci
VITE_SERVER_URL=https://keplura.example.com npm run tauri build
```

The server URL is fixed at build time. Output: `.deb` (Linux), `.msi`/`.exe`
(Windows), `.app`/`.dmg` (macOS) under `desktop/src-tauri/target/release/bundle/`.
Windows and macOS builds are not signed; users will see an OS warning.

## 6. Choosing a model provider

Exactly one provider preset is active, set in `deploy/.env.production`:

| Preset | When | Key settings |
|---|---|---|
| `ollama` (default) | CPU-only host, simplest setup | `KEPLURA_CHAT_MODEL` (default `qwen2.5:7b`) |
| `vllm` | NVIDIA GPU host | `KEPLURA_GATEWAY_BASE_URL`, `KEPLURA_CHAT_MODEL` |
| `api` | Hosted OpenAI-compatible API | `KEPLURA_GATEWAY_BASE_URL`, `KEPLURA_GATEWAY_API_KEY`, `KEPLURA_GATEWAY_ALLOWED_MODELS` |

Admins can also add providers in the UI (**Administration → KI-Anbieter**);
stored keys are encrypted with `KEPLURA_SECRETS_MASTER_KEY`. With a hosted API,
questions and retrieved document passages leave your network. Decide that
consciously.

Changing the embedding model after documents are indexed requires a re-index.

## 7. Verify the installation

```bash
# Health
curl -fsS http://127.0.0.1:8080/api/v1/health

# End-to-end check with the real models (creates its own sample organisation;
# run it on a test or pilot instance). Needs Node.js 22+.
cd /opt/keplura
node deploy/pilot-check.cjs --base http://localhost:8080 \
  --env-file deploy/.env.production --out /tmp/keplura-pilot-check.json
```

The pilot check signs in as the first admin and tests anonymous access, CSRF,
indexing, vector search and a full model answer with sources. On a CPU the
model answer can take several minutes.

## 8. Updating

Always back up first, then update through the installer:

```bash
cd /opt/keplura
scripts/backup.sh --out /var/backups/keplura
git pull
cd deploy && ./install.sh
```

The installer rebuilds the image, restarts the stack and waits for health.
Database migrations (Flyway) run automatically when the server starts. Do not
run `docker compose ... up` directly for updates; the installer also migrates
configuration (for example the `INTRACORE_*` → `KEPLURA_*` rename, see
[ADR-0013](adr/0013-umbenennung-keplura.md)).

Until 1.0, minor versions may contain breaking changes; read
[CHANGELOG.md](../CHANGELOG.md) before updating. Desktop apps must be rebuilt
and reinstalled when the server API changes.

Rollback: restore the backup taken before the update with
`scripts/restore.sh <backup-dir> --force` after checking out the previous
version (see [OPERATIONS.md — backup and restore](OPERATIONS.md#backup-and-restore)).

## 9. Uninstalling

```bash
cd /opt/keplura/deploy
docker compose -f compose.prod.yml --env-file .env.production down       # keeps data
docker compose -f compose.prod.yml --env-file .env.production down -v    # DELETES database, documents and models
```

Take a backup before `down -v`; it cannot be undone.
