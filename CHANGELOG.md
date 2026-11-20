# Changelog

All notable changes to Keplura are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Until 1.0.0, minor versions may
contain breaking changes; they are marked **Breaking**.

No version has been released yet. Everything below is on `main` and will form
the first tagged release (planned `0.1.0`, see [docs/RELEASING.md](docs/RELEASING.md)).

## [Unreleased]

### Added

- Licensed under the Apache License 2.0 (`LICENSE`, `NOTICE`, license
  metadata in `server/pom.xml`, `desktop/package.json` and both `Cargo.toml`).
- Open-source preparation: public README with screenshots, installation and
  operations guides, contribution and security policies, configuration example
  (`.env.example`), release process, third-party license inventory and
  generator script (`scripts/third-party-licenses.cjs`), open-source readiness
  report.

### Changed

- New app icon (orbit mark), all desktop icon sizes regenerated from
  `desktop/src-tauri/icons/app-icon.svg`.
- Security reports go only through GitHub private vulnerability reporting.
- GitHub Actions workflow runs with read-only repository permissions.
- `.gitignore` covers local agent and browser-automation folders.

## Pre-release history (2026-09 to 2026-11)

Summary of the work on `main` before the first release, newest first. This
work happened in an earlier, private repository; the pull request numbers
below refer to that repository and are not publicly visible.

### Interface

- Redesigned interface in one design system ("catalogue style"): chat, sources
  panel, knowledge bases, data source wizard, connections, AI administration,
  system status, team, sign-in and first-run setup (#52, #64, #85, #87, #89).
- Dark mode and full-screen mode; browser-first web UI delivered by the server,
  native desktop app kept (ADR-0011).
- "Stay signed in" option, no forced logout after 30 minutes idle (#80).

### Knowledge and chat

- Chat with streaming answers (SSE), persisted conversations, Markdown rendering
  with XSS protection, copy/retry/feedback, numbered source citations with
  document date ("Stand") and passage preview (#67).
- Retrieval with embeddings and PostgreSQL pgvector, optional reranker.
- Document types: text, Markdown, CSV, PDF, DOCX, XLSX, PPTX (#70); scanned PDFs
  via local Tesseract OCR (#69, #91).
- Optional web search and MCP tool servers with per-tool approval.

### Data sources and permissions

- Data sources for Windows/SMB shares, Linux folders (read through a local Rust
  helper that respects POSIX ACLs) and SharePoint, with rights mirroring so
  people only find documents they may open at the source (ADR-0012).
- Oversharing check before import (#68), rights explanation for admins (#74),
  understandable reasons for blocked Linux files (#73).
- LDAP / Active Directory directory connection.

### Accounts and security

- Local accounts with Argon2 password hashing, server-side sessions, CSRF
  double-submit protection, organisation invites, OWNER/MEMBER roles and a
  global ADMIN role.
- OIDC single sign-on (authorization code + PKCE).
- Security review fixes: authentication, file-descriptor-bound ACL snapshots,
  OCR limits, MCP tool trust (#72).
- SSRF protection for all outbound connections; secrets encrypted at rest with
  a per-installation master key.

### Operations

- One-command installer (`deploy/install.sh`) with generated secrets, model
  download and health wait; hardened installer and acceptance check (#76).
- Production Compose stack (PostgreSQL + pgvector, server, Ollama, reranker,
  optional GPU/vLLM), backup and restore with integrity checks, pilot and
  answer-quality checks.
- Model providers: Ollama, vLLM and any OpenAI-compatible API.

### Renamed

- **Breaking:** product renamed from IntraCore to Keplura (#78, #82, #83): Java
  packages, app identifier `com.keplura.desktop`, cookies, `X-Keplura-Org`
  header, `keplura.*` properties and `KEPLURA_*` environment variables. The
  server still reads legacy `INTRACORE_*` variables with a warning;
  `install.sh` migrates `.env.production`. Desktop users must reinstall the
  app and sign in again (ADR-0013).
