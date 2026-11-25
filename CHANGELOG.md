# Changelog

All notable changes to Keplura are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Until 1.0.0, minor versions may
contain breaking changes; they are marked **Breaking**.

No version has been released yet. Everything below is on `main` and will form
the first tagged release (planned `0.1.0`, see [docs/RELEASING.md](docs/RELEASING.md)).

## [Unreleased]

### Added

- Model tool defaults (TU-05b): admins decide per chat model whether it gets
  tools at all (`AUTO`/`OFF`) and which tool kinds it offers by default while a
  chat has no own selection. `OFF` also blocks already planned calls.

- Tool search for large catalogs (TU-05c): above 12 approved tools the model
  first gets `tool_search` and makes at most 8 matching tools visible per search.
  It only searches the question's sealed catalog; the former silent cut at 32
  tools is gone.

- Structured questions (TU-05d): the model can ask the person one question with
  up to six options or a free answer. The question card interrupts the answer;
  the validated reply resumes it once. Afterwards at most one follow-up action is
  proposed, and only with its own confirmation.

- Chats can use the server's default tool selection or limit which approved tools
  the model receives. A changed selection cancels pending tool confirmations and
  applies from the next question.

- Public page reading as a chat tool (TU-04e): `web_fetch` follows web-search
  results or URLs from the question and reads bounded HTML/XHTML/plain text.
  It uses the existing organization and per-question web permissions, blocks
  internal addresses even with admin network exceptions, and asks for confirmation
  before sending URLs derived from private content. No browser or login is used.

- Bounded multi-step read tools (TU-03a): search, inspect results and read details
  within one question, with five model requests and eight total tool attempts.
  Follow-up mutations are blocked server-side; critical confirmations do not
  resume the loop automatically. Stopped buffered tool answers are marked aborted.

- Tool call cards in the chat (TU-02): every tool call shows tool, connection,
  risk class, inputs, status and the result or a generic error. Critical
  tools can now be approved by admins; each single call then waits for the
  person's explicit confirmation of exactly the shown call (10 minutes, once).
  Rejecting, expiry or a new message end it without a call.

- Oversharing check, stage 2: the content of broadly readable files is
  checked locally for IBANs, card numbers, German tax IDs, social security
  numbers and HR/health terms. The report shows only type and count, never
  the values. Files that cannot be read are listed as not checkable.
  Settings `keplura.exposure.content.*`.
- Server texts (errors, sync, check and access results) appear in English
  when the interface is English. Chat answers come in the language of the
  question.
- English user interface. The desktop app follows the system language and
  can be switched between English and German in the account menu or on the
  sign-in screen. `scripts/check-i18n.cjs` fails the build when an English
  text is missing. README screenshots now show the English interface
  (`desktop/e2e/readme_screenshots.cjs`).
- Licensed under the Apache License 2.0 (`LICENSE`, `NOTICE`, license
  metadata in `server/pom.xml`, `desktop/package.json` and both `Cargo.toml`).
- Open-source preparation: public README with screenshots, installation and
  operations guides, contribution and security policies, configuration example
  (`.env.example`), release process, third-party license inventory and
  generator script (`scripts/third-party-licenses.cjs`), open-source readiness
  report.

### Changed

- **Breaking (admins):** tool calls (MCP) now run through one central,
  audited execution path. Every MCP tool needs an admin-set risk class
  (read / write / critical) before it can be approved; unclassified and
  critical tools stay blocked, and arguments are checked against the tool's
  input schema before each call. Tools approved before this update are not
  classified automatically and stop working until an admin classifies and
  approves them again. See `docs/howto/mcp.md`.
- New app icon (orbit mark), all desktop icon sizes regenerated from
  `desktop/src-tauri/icons/app-icon.svg`.
- Security reports go only through GitHub private vulnerability reporting.
- GitHub Actions workflow runs with read-only repository permissions.
- `.gitignore` covers local agent and browser-automation folders.

### Fixed

- Development profile: the H2 schema is now built by the same Flyway
  migrations as production (PostgreSQL mode, Hibernate only validates).
  Attaching a knowledge base or file to a chat, MCP servers and web search
  settings no longer fail with HTTP 500 when running locally without
  PostgreSQL. The dev database file is now `server/target/keplura-dev-db`;
  the old `keplura-dev` file is no longer used.

### Security

- Desktop dependencies updated for published advisories: DOMPurify 3.4.17
  (sanitizer bypasses, used for chat Markdown), Vite 7.3.8 (dev-server and
  bundled launch-editor issues, build-time only) and source-map-js 1.2.4
  (denial of service, build-time only).
- Dependabot only opens security updates, grouped into one pull request per
  ecosystem (`.github/dependabot.yml`).

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
