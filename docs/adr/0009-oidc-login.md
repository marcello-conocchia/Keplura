# ADR-0009: OIDC login as a second path (authorization code + PKCE)

Date: 2026-09-09
Status: Accepted (implements kanban t_6e8e1a6c, PO sequence 09.09 step 2;
slot reserved by ADR-0002, ROADMAP M2-Tail)

## Context

ADR-0002 fixed local identity (Argon2) as the first login path and announced
OIDC (authorization code + PKCE, ADR-0001) as a later SECOND path layered on
the same user model. That milestone is this one. Hard constraints from the
task and the earlier ADRs:

- The local login path stays untouched and primary; sessions, cookies,
  roles, CSRF (SEC-2) keep their exact behaviour.
- The desktop is untrusted, the server is authoritative (ADR-0001).
- Provider config is an ADMIN concern with write-only secrets, exactly the
  ADMIN-KI pattern (ADR-0008): discovery URL + client id + client secret are
  stored server-side via admin CRUD, NOT as .env obligations. (The one ENV
  value that IS operational here — `keplura.oidc.desktop-redirect-uri` —
  is a deployment URL, not a secret, same class as the CORS origin list.)
- Every URL that entered through admin input and gets dialed by the server
  is an SSRF surface (ADR-0008 §7). OIDC discovery/token/JWKS endpoints are
  exactly that.
- No new dependencies (task GRENZEN), and the UI freeze allows changes to
  the login screen only.

## Decision

### 1. Flow: authorization code + PKCE only

`GET /api/v1/auth/oidc/{providerId}/begin` generates `state`, `nonce` and a
PKCE verifier (challenge method S256), stores all three as a single-use
binding in the caller's `HttpSession` (same container session machinery as
ADR-0003 — no new store; TTL enforced by timestamp, default 600 s, and the
binding is consumed by the callback regardless of outcome), then answers
302 to the IdP authorize URL with `response_type=code`, `scope=openid
email`, the challenge and the state.
`GET /api/v1/auth/oidc/{providerId}/callback` requires `state` to match the
session binding (this IS the stateful CSRF protection the task demanded —
double-submit practice reused: the HttpOnly session cookie plus a value only
the session knows), exchanges the code at the token endpoint with the stored
verifier (`POST` form-urlencoded; client auth = HTTP Basic with the client
secret when one is set, else `client_id` form param), validates the ID
token (§4), links the subject to a user (§5) and hands the session over
(§6).

Implicit and ROPC grants are not implemented anywhere, so they cannot be
configured. There is no `response_mode` and no fragment return from the IdP.

### 2. Provider storage: DB row, write-only secret (no .env obligation)

Table `oidc_provider` (V11): name, issuer, discovery_url, client_id,
`client_secret_cipher` (AES-GCM via the existing `SecretCipher` — the same
`KEPLURA_SECRETS_MASTER_KEY` boundary as AI providers), active flag,
timeout (1..300 s, same bounds as ai_provider). There is deliberately NO
`use_state` escape hatch: the state round-trip is always on — a toggle that
quietly disables CSRF protection is the wrong default.
"At most one active provider" is DB-enforced with the `active_slot`
unique-column trick copied from `ai_model` (NULL unless active; a unique
index makes a second active row impossible even under a race). Rationale
for a single ACTIVE provider: the desktop shows exactly one "Mit
Firmenkonto anmelden" button, and multi-IdP selection is new scope without
a PO request. The table can still HOLD retired rows; the id-keyed endpoints
make a future second active provider a policy flip, not a redesign.

Admin CRUD lives at `/api/v1/admin/oidc/providers` — the session + CSRF +
ADMIN-role interceptor gates cover the new prefix automatically because
SessionAuthConfig maps `/api/v1/admin/**`. Leak discipline is ADR-0008 §4:
the secret is write-only (`secretSet` + constant mask outbound, empty patch
field keeps the stored secret), the DTO never carries ciphertext, and
discovery is dialed only at save/activate — never on page load.

### 3. Endpoint surface (server)

- `GET  /api/v1/auth/oidc/providers` → `{oidcEnabled, providers:[{id,name}]}`
  — the capabilities flag that gates the desktop button. Unauthenticated by
  design (the login screen polls it); it returns only id + the operator's
  own label, never URLs or client ids. With no active provider it answers
  200 `{oidcEnabled:false, providers:[]}` (no error branching needed in the
  desktop).
- `GET  /api/v1/auth/oidc/{providerId}/begin` → 302 to the IdP; 404 for an
  unknown OR inactive provider (indistinguishable — the same anti-
  enumeration posture ADR-0003 uses for login); 503 with the generic body
  when discovery fails at begin time (IdP outage, never upstream detail).
- `GET  /api/v1/auth/oidc/{providerId}/callback` → 302 to the configured
  desktop redirect URI (§7) — on success with `#oidc=<handoff>`, on ANY
  failure (including IdP-side `error=` responses) with `#oidc-error`. The
  browser never learns WHY from the URL: every reason goes to the server
  log only, in the class-name-only style of GatewayClient (no PII, no
  upstream text).
- `POST /api/v1/auth/oidc/exchange` → body `{handoff}`; consumes the
  one-time handoff (§6) and mints the Keplura session. Same response
  shape as `POST /auth/login` (userId, displayName, csrfToken, role).
  CSRF-exempt like login (no session exists to protect yet); the
  unguessable 256-bit single-use handoff code is the authenticator.

begin/callback/exchange live under the OPEN `/api/v1/auth/*` prefix; GETs
are outside the SEC-2 state-changing rule, and CORS maps
`/api/v1/auth/oidc/**` like the other credentialed auth endpoints.

### 4. Token validation (hard rules) — zero new dependencies

JWKS are fetched from the discovery `jwks_uri` (SSRF-guarded, §8), cached
per provider with 5-minute TTL, refreshed at most once mid-validation on an
unknown `kid`. Signature algorithms: RS256/RS384/RS512 and PS256/PS384/
PS512 ONLY — a fixed whitelist checked against the JWS `alg` header BEFORE
any parsing. `none`, MAC algorithms (HS*) and everything else are refused
by construction: verification is implemented directly on the JDK
(`java.security.Signature` with SHA-{256,384,512}withRSA and
SHA{256,384,512}withRSAandMGF1) over RSA public keys rebuilt from JWKS
`n`/`e` — no HMAC code path exists at all, which is what kills the classic
alg-confusion attack (HS256-token-forged-from-the-public-RS-key) structurally.
Base64url decoding uses `java.util.Base64.getUrlDecoder` with strict
input checking; claim parsing uses Jackson (already on the classpath via
starter-webmvc).

**Dependency justification (task GRENZEN):** the established OIDC/JO routes
(nimbus-jose-jwt, Spring Security OAuth2) are NEW supply-chain entries;
the task allows only "etablierte OIDC/JO libs", and a self-implemented
verification on top of the JDK crypto + already-present Jackson adds none.
The attack surface this code owns (alg selection, key construction, claim
comparison) is deliberately ~200 lines with 10+ adversarial tests rather
than a transitive-dependency tree. If the security review calls this out,
the swap target is nimbus-jose-jwt.

Claims validated: `iss` equals the configured issuer EXACTLY, `aud`
contains the `client_id` (a multi-audience token must also carry `azp` ==
client_id), `exp`/`iat` sane with a fixed 60 s leeway, `nbf` — OPTIONAL per
OIDC, so an absent claim is valid — when present must be numeric and not
more than the same 60 s leeway into the future (refused like an expired
`exp` otherwise; added 13.09. as documented maintenance, t_d8fdaf51),
`nonce` equals the binding's nonce, `sub` present and ≤ 200 chars;
`at_hash` is verified (SHA-256/384/512 left half, base64url) whenever the
response carried an access token. `email_verified` (§5) must be the JSON
boolean true.

### 5. Account linking — no auto-provisioning

The documented decision the task demanded, following its stated default
(“nur Linking auf bestehenden User per Invite/Email-Bestätigung”): an OIDC
login NEVER creates a user. The persistent link lives in its own table
`oidc_account_link` (V11): UNIQUE `(provider_id, subject)` → `user_id`.
A dedicated table (not an `app_user.oidc_subject` column) is the stronger
shape: links are scoped per provider, so retiring an IdP and wiring its
successor NEVER inherits the old provider's links — a cross-provider `sub`
collision (both issuers minting "1234") cannot silently grant access.
Subject-identity wins over email forever after. Resolution order at
callback:

1. A link row `(provider, sub)` → that user. Done.
2. Otherwise: only if the token carries `email_verified == true` (the JSON
   boolean — a string "true" does NOT count) AND the normalized email
   matches an existing user WHOSE slot at this provider is still free →
   first-come claim: insert the link row, then log in. The invite flow is
   what created that user; the IdP's verified email is the confirmation.
3. Everything else → generic failure. No user is created, no session is
   minted for an unknown subject, and an email that already belongs to a
   user linked to a DIFFERENT subject at this provider is refused even if
   the IdP asserts it.

Subject uniqueness is enforced at the DB level (unique index), so a
second IdP identity can never steal an already-linked account silently —
and a losing claim race throws into the callback's generic failure.
Admin pairing (same table, same one-subject-one-user guards) exists as
the governed pre-link surface.
Self-service registration stays out of scope (PO decision, unchanged);
AdminBootstrapRunner / DevAccountSeeder / the invite flow remain the only
user-creation paths. When multi-provider or auto-provisioning is ever
approved, `(issuer, sub)` becomes a real link table (V-next) — the unique
column is the MVP shape with a single active provider.

### 6. Session minting: one-time handoff, then the NORMAL login dance

The OIDC browser legs run in the WEBVIEW's navigation cookie jar, while
the desktop's API calls run through the tauri-plugin-http reqwest jar
(ADR-0005) — two different jars. If the callback set the session cookie
directly, the packaged app's API transport would never see it. So the
callback does NOT mint the session; it stores the authentication result
under a fresh 256-bit one-time HANDOFF code (in-memory, 60 s TTL,
single-use, consumed on any exchange attempt) and redirects the webview to
the desktop origin (§7). The desktop then calls `POST /auth/oidc/exchange`
through its normal `desktopFetch` — and THAT request mints the session in
the jar the app actually uses, via the exact same code path as login:
invalidate any pre-existing session (anti session-fixation), create the
fresh one, store `AuthPrincipal` under the SAME attribute, bind + emit the
SEC-2 CSRF token through `CsrfService.bindAndAddCookie` (raw-response
addHeader — the Tomcat double-Set-Cookie pitfall from SEC-2 applies
identically). In the plain-browser QA flow the two jars are one jar, and
the mechanism still works unchanged, so browser and packaged share a
single code path.

The browser leg of the handoff code is fragment-only (`#oidc=<code>`):
fragments are never sent to any server, never logged by upstreams, and the
SPA strips it with `history.replaceState` before anything else runs.

Argon2 is untouched: local users keep their hashes; an OIDC-linked user
created via dev-seed/bootstrap carries a hash it never uses for the OIDC
path (and the local path still verifies normally).

### 7. Desktop-origin redirect (desktop untrusted, server authoritative)

The IdP's registered redirect URI is the SERVER's callback URL
(`…/api/v1/auth/oidc/{id}/callback`) — never a desktop URI; operators
register BOTH `tauri://localhost` and `http://tauri.localhost` as allowed
origins/redirect targets for the handoff leg (Tauri serves the packaged
app under `tauri://localhost` on macOS/Linux and
`http://tauri.localhost` on Windows). The handoff target itself is ENV
config `keplura.oidc.desktop-redirect-uri` — default
`http://localhost:1420/` (dev/browser QA); deployments set
`tauri://localhost/` (macOS/Linux) or `http://tauri.localhost/` (Windows).
Validated at startup: absolute, scheme http(s) or tauri, no userinfo, no
query, no fragment; a bad value fails the boot loudly (same posture as the
chunker bounds). The callback NEVER reflects a caller-supplied redirect —
that is the open-redirect wall; it appends only its own fixed fragment
(`#oidc=` or `#oidc-error`).

Known caveat, stated honestly: WebKitGTK custom-scheme navigation from an
http origin (`tauri://localhost`) is the one leg this repo cannot prove on
this Linux host without a real IdP; the browser leg exercises the complete
handoff logic, and the packaged e2e exercises `http://localhost:1420`
(dev-build binary). A Windows/macOS pass with the real corporate IdP is a
RELEASE gate (per AGENTS.md cross-platform policy), not a per-PR gate.

### 8. SSRF guard on discovery/token/JWKS (network egress via admin input)

Every dial of a URL stored on (or derived from) an `oidc_provider` row goes
through `SsrfGuard.validateForDial` — the exact ADMIN-KI route policy
(ADR-0008 §7): DNS resolved, every answer checked, loopback/link-local/
site-local/CGNAT/fc00::/7 refused unless ops allow-list them via
`keplura.admin.ssrf-allow-cidrs`; redirects NEVER followed (the dedicated
`oidcHttpClient` bean mirrors gatewayHttpClient); hard timeouts from the
row (1..300 s). Enforcement points: save/activate (discovery dial), begin
(discovery dial per call — endpoints must stay current; cheap and avoids
stale-redirect caches), callback (token endpoint), validation (jwks
endpoint). Response bodies are size-capped (64 KiB). A corporate IdP is by
definition routable, so "default dicht" costs nothing in the target
deployment; a loopback TEST IdP is exactly what the allow-list ENV is for —
which doubles as the proof the guard gates (the same tests without the
allow-list entry MUST see refusals).

### 9. Verification: real test IdP

The task demands a REAL flow through all steps including the PKCE verifier
exchange; WireMock-ish contract stubbing is explicitly insufficient.
Two consumers of the same minimal OIDC contract, both real HTTP with real
crypto (RSA-2048 generated at boot, genuinely RS256-signed ID tokens, a
token endpoint that REQUIRES the correct PKCE verifier + HTTP Basic client
auth), no new dependencies either way:

- The JVM integration test embeds its own stub IdP (a `com.sun.net.httpserver`
  app inside the test class — dies with the JVM, shares its random ports).
  It drives the full begin→callback→exchange chain through the real HTTP
  surface with two explicit cookie jars (the webview jar NEVER follows
  redirects — the exact shape of a browser), plus the negative
  legs: forged state, replaced/stale binding, replayed callback, alg
  downgrade (HS256 signed with the RSA public key as secret) and `alg:none`,
  wrong issuer, missing nonce, unknown kid, PKCE verifier mismatch refused
  at the token endpoint, unlinked subject, email collision with an
  already-linked user, disabled provider (button flag off + begin AND
  callback 404 + local login green), IdP `error=` param, garbage exchange
  bodies, and the admin surface (issuer-mismatch dial check, write-only
  secret, role gate). Tampering flips volatile flags the in-process stub
  reads when minting — the verifier under test is the product code, so the
  downgrade/none cases are real signature attempts, not mocked validations.
  SSRF: the stub dials loopback, so the test allow-lists 127.0.0.1/32 via
  the operator property (the same escape hatch production has); the guard's
  refusal logic lives in SsrfGuardTest (ADMIN-KI doctrine), and the OIDC
  admin create leg proves the wiring end-to-end: a 169.254.169.254
  discovery URL is refused (400 `connection_target_refused`) before any
  egress, since every OIDC dial (discovery at save+begin, token, JWKS)
  routes through the same `validateForDial`.
- `desktop/e2e/oidc_flow_e2e.cjs` boots IdP + real jar (dev-seeded provider
  + linked user via the property-gated OidcDevSeeder, §10) + vite, and
  drives Chromium through the REAL desktop login screen: button appears →
  click → IdP form → callback → fragment handoff → exchange → session →
  orgs visible. Negative UI leg: an IdP form field value that makes the IdP
  send `error=access_denied` → `#oidc-error` line appears on the login
  card. Local login stays green in the same run.

Keycloak-in-compose was rejected: this CI host cannot reliably run nested
multi-layer containers (documented since RELEASE-GATE-1), the binding gate
is `mvn test` + ci-local, and a controllable stub is the only way to reach
the hostile-token negative legs at all.

### 10. Desktop (UI freeze: login screen only)

App.vue polls `GET /auth/oidc/providers` next to the existing system-info
probe; when `oidcEnabled` is true, LoginView renders one quiet secondary
button "Mit Firmenkonto anmelden" (`data-testid="login-oidc-button"`)
under the local form — nothing else changes. Click = top-level webview
navigation to begin (no fetch, so the plugin-http URL scope is not
involved). On mount the SPA consumes `#oidc=`/`#oidc-error` BEFORE the
restore probe (oidc-handoff.ts, ~40 lines); success stores the returned
CSRF token exactly like login does, failure shows the same coarse line the
local form uses. The restore path, roles, org loading and every existing
testid are untouched.

## Rejected

- Callback-set session cookie (no handoff): breaks the packaged app due to
  the ADR-0005 jar split (§6).
- Keycloak in a compose test network: host cannot run nested containers
  for the binding gate; hostile-token negative legs are impossible without
  IdP source control.
- nimbus-jose-jwt / Spring Security OAuth2 client: new supply-chain
  entries; §4 explains why ~200 lines of JDK-only verification code is the
  smaller attack surface here (and names nimbus as the swap target if the
  security gate disagrees).
- Storing the OIDC link on the provider row: the link is user↔subject, not
  provider↔subject (§5).
- Auto-provisioning on unknown email: PO scope; §5 rule 3.
- Sessionless state binding keyed purely by state in a map: the task
  explicitly wants the stateful session-bound check (double-submit
  practice reused).
- Reflecting a caller-provided redirect param: open-redirect; §7 ENV
  allow-list only.

## Consequences

- `contracts/openapi.yaml` gains the four auth endpoints + admin CRUD.
- V11 adds `oidc_provider` and `oidc_account_link` (+ unique guards);
  dev `ddl-auto=update` converges, prod runs Flyway.
- A property-gated `OidcDevSeeder` (dev profile, same posture as
  DevAccountSeeder) lets e2e materialize provider+link+user without admin
  UI click-through.
- ci-local.sh gains the `oidc` e2e suite; `--with-e2e` runs nine suites.
- Security review (auth + network egress + secrets) and QA are REQUIRED
  gates before merge per the task card.
