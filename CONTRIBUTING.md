# Contributing to Keplura

Thanks for your interest. Bug reports, documentation fixes and code changes are
welcome. This page explains how to set up the project, which checks a change
must pass and how pull requests are reviewed.

Keplura is licensed under the [Apache License 2.0](LICENSE). Unless you state
otherwise, any contribution you submit is licensed under the same terms
(section 5 of the license).

## Ground rules

- **Security issues are never reported in public issues.** Use the process in
  [SECURITY.md](SECURITY.md).
- Never commit secrets, real credentials, personal data or customer documents.
  Test data uses `example.com` / `example.test` addresses and invented content.
- One topic per pull request. Small PRs are merged faster than large ones.
- New product scope (new subsystems, new infrastructure such as message brokers
  or Kubernetes, new external services) needs agreement in an issue first.

## Project layout

| Path | Content |
|---|---|
| `server/` | Java 21 / Spring Boot server, Flyway migrations, tests |
| `server/native/local-reader/` | Rust helper that reads Linux folders with their POSIX ACLs |
| `desktop/` | Vue 3 + TypeScript UI (web and Tauri desktop), e2e harnesses in `desktop/e2e/` |
| `contracts/openapi.yaml` | REST API contract |
| `deploy/` | Compose stack, installer, operator tools |
| `scripts/` | backup/restore, local CI gate, design token sync, license report |
| `docs/` | development guide, ADRs, design system, operator docs |
| `ci/evidence/` | logs of local CI runs referenced by past pull requests |

## Development setup

Requirements: JDK 21, Maven 3.9+, Node.js 22 (20.19+ works), Rust stable, and on
Linux the [Tauri system libraries](https://v2.tauri.app/start/prerequisites/).
Docker or Podman is needed for PostgreSQL and the full stack.

```bash
# server tests (H2, no external services needed)
cd server && mvn -B verify

# UI: type check + production build
cd desktop && npm ci && npm run build

# desktop shell
cd desktop/src-tauri && cargo check --all-targets
```

How to run server and UI together, use PostgreSQL, seed a dev account and
configure a model: [docs/development.md](docs/development.md).

## Coding guidelines

- Follow the existing style of the file you touch; `.editorconfig` defines
  indentation and line endings. No mass reformatting.
- The server is authoritative. Every permission and tenant check happens on the
  server; the UI is treated as untrusted.
- Never log passwords, tokens, API keys or document content. Request logs
  carry path and status only.
- Database changes go through a new Flyway migration in
  `server/src/main/resources/db/migration` (portable SQL) or
  `db/migration-postgresql` (PostgreSQL-only). Never edit an applied migration.
- API changes update `contracts/openapi.yaml` in the same PR.
- Design tokens live in `docs/design/system-v1/tokens.json`; run
  `node scripts/sync-design-tokens.cjs` after changing them (the UI build
  checks they are in sync).
- New runtime dependencies need a reason in the PR description and a license
  compatible with the project license. Re-generate the inventory with
  `node scripts/third-party-licenses.cjs > docs/legal/THIRD_PARTY_LICENSES.md`.
- Architecture decisions with long-term impact get an ADR in `docs/adr/`.

## Tests

While working, run the tests of the area you change, for example:

```bash
cd server && mvn -B test -Dtest='DocumentServiceTest'
```

Before asking for review, run the full local CI gate once on the final commit:

```bash
bash scripts/ci-local.sh                 # server, UI build, Tauri check
bash scripts/ci-local.sh --suite chat    # plus a selected browser e2e suite
```

Run it on Linux as a **non-root** user with the `acl` package installed:
the rights tests check real file permissions, and root bypasses them. A
containerised example is in
[docs/development.md — Local CI gate](docs/development.md#local-ci-gate-verbindlich--scriptsci-localsh).
Each run writes a `MATRIX.md` and logs to `ci/evidence/<timestamp>/`.

GitHub Actions (`.github/workflows/ci.yml`) runs server build and tests, the UI
build on Linux/Windows/macOS and the Tauri checks for every pull request.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make your change with tests. Keep commits focused and use
   [Conventional Commit](https://www.conventionalcommits.org/) prefixes
   (`feat:`, `fix:`, `docs:`, `test:`, `chore:`), as the history does.
3. Add an entry under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) for
   user-visible changes.
4. Open a pull request and fill in the template: what changed, why, how it was
   tested, and the **risk class**:
   - **Class A — trust-critical:** authentication, sessions, CSRF, OIDC/LDAP,
     roles, tenant isolation, document permissions, data sources and rights
     mirroring, secrets, anything that contacts external hosts (model gateway,
     web search, MCP, connectors), upload/parsing/external processes (OCR),
     migrations that change or delete data, new runtime dependencies.
     Needs a green CI run plus an independent code review **and** security
     review by someone who did not write the change.
   - **Class B — everything else** (UI, texts, docs, tests, additive
     non-security features): needs a green CI run and a maintainer review;
     add a screenshot if the UI changed.
   When in doubt, it is Class A.
5. Reviews happen on the final commit. Pushing new product code after an
   approval requires a new review.
6. Maintainers squash-merge when all checks pass. Merges, releases and
   deployments are done by maintainers only.

## Commit sign-off

<!-- PO decision pending: DCO sign-off requirement, see docs/OPEN_SOURCE_READINESS.md -->
Contributions are accepted under the Apache License 2.0 (see the top of this
page). Whether commits must additionally carry a Developer Certificate of
Origin sign-off (`git commit -s`) will be documented here.

## Questions

Open a GitHub issue or discussion. Commercial installation, integration and
support are available separately and are never required to use or contribute
to Keplura.
