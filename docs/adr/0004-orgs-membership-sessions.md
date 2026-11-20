# ADR-0004: Organizations via membership table, session-gated API

Date: 2026-09-04
Status: Accepted (M2c implementation decision, within approved scope)

## Context

M2c introduces the first tenant boundary: logged-in sessions (ADR-0003) can
create organizations, and every organization access must be checked against
membership. The open design questions: where does the authorization
relationship live, how is "protected endpoint" enforced without Spring
Security, and how must reads be shaped so that tenant id guessing is useless.

## Decision

**Membership is a table, not a claim.**

- `Organization(id, name, createdAt)` and
  `Membership(orgId, userId, role ∈ {OWNER, MEMBER})` with the composite
  primary key `(org_id, user_id)` — the uniqueness of that pair IS the
  membership fact. `Membership` implements Spring Data `Persistable` with an
  explicit new-flag: with an `@IdClass` the id is never null, and the default
  merge heuristic would silently UPDATE the role of an existing grant instead
  of violating the key — the wrong failure mode for an authorization row.
- **Why not JWT/session claims:** roles and memberships change (later:
  invites, role edits, removals). Claims baked into a token or into the
  session at login are stale until re-authentication and cannot be revoked
  server-side without a blacklist — at which point you have reinvented the
  table, worse. The session stores ONLY the stable `AuthPrincipal(userId)`;
  every authorization decision reads the membership table fresh, so removal
  takes effect on the next request. This also keeps ADR-0003's "no tokens"
  stance intact.

**Session gate = handler interceptor, explicit path list.**

- `SessionAuthInterceptor` guards `/api/v1/orgs` and `/api/v1/orgs/**`:
  a request whose session lacks an `AuthPrincipal` gets `401` with a generic
  JSON body before the controller runs; the 401 never mints a session
  (`getSession(false)`). `/api/v1/auth/*`, `/api/v1/health` and
  `/api/v1/system/info` stay open by NOT matching.
- Chosen over a servlet filter (never touches request bodies, runs with MVC
  path matching) and over adding `spring-boot-starter-security` now (the
  ADR-0003 evaluation stands: one protected resource family does not need a
  `SecurityContext`; revisit when the protected surface grows, e.g. M3).
- Interceptor + controller BOTH resolve the principal from the session; the
  controller's "missing principal" branch is an `IllegalStateException`
  guard against silent wiring drift, not a wire-reachable path.

**Tenant isolation at query level; uniform 404.**

- Every org read goes through a membership-filtered path: the list endpoint
  starts from `Membership.findByUserId(caller)` (never from the org table),
  the single-org finder requires the membership row AND passes the
  membership-filtered repository query. The caller id always comes from the
  session principal — never from a body or path field.
- Foreign-but-existing id and non-existent id produce the byte-identical
  `404` with empty body. Deliberately NOT 403: 403 would confirm the org
  exists, which is exactly the enumeration signal the IDOR test forbids.

**Creation is atomic:** `POST /api/v1/orgs` writes the org and its OWNER
membership in one transaction — no window where an org exists nobody can
see. No invites exist yet (M2 scope note), so MEMBER is only reachable via
future work; the enum value is reserved deliberately.
**AMENDED by SEC-2 (2026-09-07):** invites landed — an OWNER mints a
single-use, 7-day, hash-stored token (`org_invite`, Flyway V8) and any
EXISTING user redeems it via `POST /api/v1/invites/accept` to join as
MEMBER. The M2 note holds in one way: no self-service user creation — the
invited account must already exist (dev-seeded until OIDC), and invites can
structurally only ever mint MEMBER, never OWNER.

## M2c hardening carried from M2b notes

- `429` login responses now carry `Retry-After` (seconds, RFC 9110).
- Rate limiting gained a **per-email** window (`MAX_ATTEMPTS_PER_EMAIL`,
  same 15 min): the per-`(ip, email)` key alone was useless against
  email-spray against one account from rotating IPs.

## Consequences

- New protected API surfaces must be added to `SessionAuthConfig`
  deliberately; anything under `/api/v1/orgs` is covered automatically.
- Membership rows are the single authorization truth: M3 space/document
  queries must chain through org membership the same way (per-resource ACLs
  on top of it, never instead of it).
- `ddl-auto` remains the schema mechanism (task non-goal); a real migration
  tool arrives with the PostgreSQL decision.
- OWNER/MEMBER only; no admin tier until a permission difference exists.
