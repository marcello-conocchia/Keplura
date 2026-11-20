# Security policy

Keplura handles company documents, access rights and credentials. We take
security reports seriously and appreciate responsible disclosure.

## Reporting a vulnerability

**Please do not open a public issue, discussion or pull request for a
security problem.**

Report it privately through GitHub:
**Security → Advisories → "Report a vulnerability"** in this repository
(GitHub private vulnerability reporting). This is the only reporting
channel; there is no separate security e-mail address.

Please include:

- affected version or commit, and how Keplura was installed,
- a description of the issue and its impact,
- steps to reproduce or a proof of concept,
- whether the issue is already known to others.

Do not include real customer data, real credentials or production documents in
the report. Test only against installations you own or are authorised to test.

## What to expect

| Step | Target |
|---|---|
| Acknowledgement of your report | within 5 working days |
| First assessment (confirmed / not confirmed, severity) | within 10 working days |
| Fix or mitigation for confirmed high/critical issues | as fast as possible, usually within 30 days |
| Public advisory | after a fix is available, coordinated with you |

These are goals of a small team, not contractual guarantees. Companies with a
support agreement get the response times agreed there.

We credit reporters in the advisory unless you prefer to stay anonymous.
There is currently no bug bounty program.

## Supported versions

Keplura is pre-release (0.x). Security fixes are made on `main` and in the
latest release only.

| Version | Supported |
|---|---|
| latest `0.x` release / `main` | yes |
| older versions | no — please update |

## Scope

In scope: the server (`server/`), the web and desktop UI (`desktop/`), the
local rights reader (`server/native/local-reader/`), the installer and
deployment files (`deploy/`, `scripts/`).

Especially interesting: authentication and sessions, CSRF, tenant isolation,
document permission checks and rights mirroring, secret storage, SSRF in the
model gateway, web search, MCP and connectors, file upload and parsing (PDF,
Office, OCR).

Out of scope: vulnerabilities in third-party services or models you connect
(report those upstream), issues that need an already compromised server or
admin account, missing hardening on installations that ignore the
documentation (for example running without TLS), and denial of service by
uploading very large amounts of data as an authenticated admin.

## Security design in short

- The server is the only trusted component; every request is checked for
  session, CSRF token, organisation membership and document permission.
- Passwords are hashed with Argon2; provider and data source secrets are
  encrypted with a per-installation master key and never sent to clients.
- Outbound requests (model endpoints, web search, MCP) are checked against
  private network ranges (SSRF protection).
- Documents are parsed with size, page, entry-count and time limits.

More detail: [docs/development.md](docs/development.md) and the ADRs in
[docs/adr/](docs/adr/).
