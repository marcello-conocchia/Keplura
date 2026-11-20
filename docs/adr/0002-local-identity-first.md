# ADR-0002: Local identity first, OIDC as a second login path

Date: 2026-09-04
Status: Accepted (PO decision Marcello, 2026-09-03 — option "A")

## Context

M2 (Identity & Tenancy) needs a user model. ADR-0001 fixed OIDC
(authorization code + PKCE) as the eventual auth strategy, but the planned
OIDC infrastructure is not available yet, and blocking all identity work on it
would stall M2.

## Decision

Build the identity base locally first:

- A persistent `User` entity in the server (id, unique email, passwordHash,
  displayName, createdAt) with a JPA repository.
- Password storage uses a modern, established one-way hash — Argon2 via the
  crypto-only `spring-security-crypto` artifact. No full Spring Security
  integration, no filter chain, no sessions (that stays out of scope until an
  auth endpoint is actually built in M2b).
- OIDC (auth code + PKCE) becomes a **second** login path in its own later
  milestone, layered on top of the same local identity — not a replacement.

Datasource configuration lives only in the dev and prod profiles (never in the
base file). Both point at a real database when `KEPLURA_DB_URL` /
`KEPLURA_DB_USER` / `KEPLURA_DB_PASSWORD` / `KEPLURA_DB_DRIVER` are set
(prod deployments must set them); their no-env fallback is embedded H2 (dev:
file under `target/`, prod: in-memory) so builds and tests run without any
external database.

## Rejected

- **B — OIDC-only from the start**: requires IdP infrastructure that does not
  exist yet; no local fallback, blocks every M2 sub-milestone.
- Full Spring Security / session stack already: scope creep ahead of any
  login endpoint (Non-Goal of M2a).

## Consequences

- The `User` table and the Argon2 hash format become the contract M2b
  (login endpoint) and the later OIDC milestone build on; a user may end up
  with a password hash, an OIDC subject, or both.
- Password hashing parameters are centralised in `PasswordHasher`; changing
  them later needs a re-hash strategy, not a silent switch.
- Schema management stays minimal (`ddl-auto=update` in dev, explicit env
  config in prod); a real migration tool (Flyway) is deferred until the schema
  has more than one consumer.
