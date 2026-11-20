# ADR-0003: Server-side session cookies for login (no self-built tokens)

Date: 2026-09-04
Status: Accepted (M2b implementation decision, within approved scope)

## Context

M2b adds the first login endpoint (`POST /api/v1/auth/login`) on top of the
local identity base (ADR-0002). The authenticated state has to survive across
requests somehow. ADR-0001 fixed "HTTPS + REST/JSON" and ADR-0002 deferred the
session question to "when an auth endpoint is actually built" — this is that
decision.

## Decision

Use **standard servlet-container sessions with a cookie** — the Spring Boot /
embedded-Tomcat default — not a hand-rolled token:

- The login endpoint stores a minimal `AuthPrincipal(userId, displayName)` in
  the `HttpSession`; the session id travels only in a cookie:
  `HttpOnly`, `SameSite=Lax`, `Secure=true` in prod (dev allows plain-HTTP
  localhost), cookie name `KEPLURASESSION[_DEV]`, 30-minute timeout,
  `tracking-modes=cookie` (no `;jsessionid=` URL rewriting).
- **No JWT, no custom bearer tokens.** Signing, rotation, revocation and
  replay-guarding a token properly is strictly more work than sessions and is
  not needed: the desktop is one first-party client of one authoritative
  server (ADR-0001), and logout-without-coordination is exactly what sessions
  do trivially (invalidate server-side, done).
- **No `spring-boot-starter-security` yet.** The filter chain would add CSRF
  defaults, form-login plumbing and a `SecurityContext` we do not consume:
  M2b has only login/logout plus the response itself; there are no protected
  business endpoints until M2c. M2c will evaluate the minimal Spring Security
  configuration for authorization (and likely adopt it then — decided on the
  real requirements, not pre-emptively).
- Session fixation defence: login always discards any pre-existing session and
  reissues a fresh id.
- User enumeration defence: unknown-email and wrong-password take the same
  branch shape — Argon2 verification always runs (per-instance dummy hash for
  unknown emails), and the 401 status/body is identical. Both failure paths
  log the same generic line.
- Login rate limiting is a deliberate MVP stopgap: fixed-window counter per
  (remote addr, email) **in memory, single instance only**, documented in
  `LoginRateLimiter`. No Redis (directive). A multi-instance deployment must
  rate-limit at the edge instead.

## Rejected

- **Self-built JWT/bearer scheme**: invents revocation and rotation problems
  the platform does not need today; contradicts the task's explicit
  "kein Token-Selbstbau".
- **Spring Security default login chain**: brings a login page, CSRF lock-in
  for a native-webview client, and a filter chain that protects nothing yet.
- **Client-stored credentials re-sent per request (basic auth)**: no logout,
  no server-side revocation.

## Consequences

- M2c endpoints read the principal via the session (`HttpSession` attribute
  `keplura.auth.principal`); a proper `SecurityContext`-based access pattern
  can be introduced there behind the same contract.
- The desktop (M2d) must persist and send the cookie jar (WebView handles this
  natively).
- Sessions live only in this instance's memory: a restart logs everyone out
  (acceptable for MVP), and sticky routing / shared session storage is needed
  before any multi-instance deployment.

## CSRF status (documented in M2c, task requirement)

M2c put real state-changing, session-backed endpoints behind the cookie
(`POST /api/v1/orgs`), so the CSRF posture must be explicit, not implied:

- The session cookie is `SameSite=Lax` — cross-site *submissions* (POST from
  another origin's form/fetch) do not carry it, which blocks the classic
  cookie-CSRF flow for the first-party native client. This is a **conscious
  MVP decision**, valid because the only client is the Tauri webview talking
  to a configured server origin, not a general website.
- Lax is a mitigation, not a complete defence: same-site requests and a
  same-origin XSS would still ride the cookie. The proper token-based
  countermeasure (e.g. Spring Security's CsrfTokenRequestAttributeHandler or
  a synchronizer token double-submit on the API) is deliberately deferred —
  it is scheduled as a follow-up once the protected surface grows beyond the
  org endpoints, and is tracked as such, not silently forgotten.
  **AMENDED by SEC-2 (2026-09-07): the deferral is closed.** The protected
  surface did grow (spaces, knowledge, documents, gateway, chat), and the
  guard is now a synchronizer-style double-submit implemented on the same
  hand-rolled gate (`CsrfService` + `SessionAuthInterceptor`): login binds a
  session token delivered as non-HttpOnly cookie `keplura_csrf` PLUS the
  login body (ADR-0005's jar is JS-invisible), and every POST/PUT/DELETE on
  a protected prefix must echo it as `X-CSRF-Token`, timing-safe compared
  against cookie AND session attribute; fail-closed 403 `CSRF_INVALID`.
  Login itself, CORS preflights and GETs (incl. the SSE chat stream — a
  safe method per RFC 9110, cannot mutate) stay exempt. Spring Security
  remains out: the double-submit rides the existing interceptor with no new
  dependency. See docs/development.md "CSRF-Schutz (SEC-2)".
- No CORS is enabled anywhere in the server; `system/info`'s open
  `Access-Control-Allow-Origin: *` does not extend to credentialed routes
  (browsers block credentialed wildcard CORS outright).

## Amendment 2026-11-18 — explicit remember-me choice

PO requested the login checkbox and removal of the 30-minute inactivity logout.
See [AUTH-REMEMBER](../tasks/AUTH-REMEMBER.md): authenticated sessions now have
a configurable absolute lifetime (default 30 days). The cookie is non-persistent
by default, persistent only with `rememberMe=true`. In-memory server sessions
still end on server restart; this supersedes the earlier 8-hour cookie cap and
30-minute authenticated idle timeout described above.
