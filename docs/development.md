# Keplura — Development Guide

The product is now called Keplura; new Linux bundles use
`Keplura_0.1.0_amd64.deb`. Historical verified outputs below retain their
original names. Technical identifiers remain compatible; see README.md.

All commands and results below were actually executed (and re-verified) in the
Linux development container (Ubuntu 24.04-based, JDK 21.0.12, Maven 3.8.7,
Node 22.23.2, npm 10.9.8, Rust/cargo 1.98.0, WebKitGTK 4.1 + GTK3 present for
Tauri, Xvfb available). Sections marked with a milestone reflect when they
were written; milestone status is tracked in `docs/ROADMAP.md`.

## Architecture boundary

Desktop and Server are separate products. The desktop webview talks to the
server **only** over the HTTP API defined in `contracts/openapi.yaml`
(currently 23 paths / 30 operations: health, system/info, auth login/logout/
csrf, orgs, org members + invites, invite accept, spaces, knowledge bases,
documents + download + search, gateway status/chat, chat conversations + SSE
stream). Transport is the native Tauri HTTP plugin (server-scoped capability);
the HttpOnly session cookie is never visible to JavaScript (the SEC-2 CSRF
token reaches JS via the login/`/auth/csrf` response BODIES — see the CSRF
section). There are no Tauri commands and no shared code. The server is
authoritative: every API path except the auth endpoints
(`/api/v1/auth/login`, `/api/v1/auth/logout`, `GET /api/v1/auth/csrf`) and the
public liveness/system endpoints (`GET /api/v1/health`,
`GET /api/v1/system/info`) is session-gated via `SessionAuthConfig` and
enforces tenant isolation (org/space membership) at query level. Since SEC-2
every state-changing call on a gated path additionally carries the
double-submit CSRF header.

## Server logging convention (M1a)

- Spring Boot default logging (Logback, console appender, `spring.application.name`
  in the pattern); no custom `logback-spring.xml`.
- Every handled `/api/**` request logs one info line via `RequestLoggingInterceptor`:
  `METHOD path -> status (Nms)` (e.g. `GET /api/v1/system/info -> 200 (2ms)`).
- Never logged: query strings, headers, bodies, cookies — anything that could carry
  secrets or user data. Exceptions are appended only via the logger throwable.
- `GET /api/v1/health` (liveness probe) is excluded from request logging so probes
  cannot drown the log.
- Level overrides go in `application.properties` (`logging.level.<package>=...`),
  not in code.

## Config profiles (M1c)

`application.properties` is the base; operational profiles extend it:

- `application-dev.properties` — development (DEBUG logging for `com.keplura`),
  **active by default** via `spring.profiles.active=dev` in the base file.
  Database: H2 file `server/target/keplura-dev-db` in PostgreSQL mode; the
  schema comes from the same Flyway migrations as prod and Hibernate only
  validates (since 20.11.2026 — before, `ddl-auto=update` left out tables that
  exist only in migrations, e.g. chat knowledge bases, MCP, web search). An old
  `target/keplura-dev` file from that time is no longer used.
  Tests also run under the dev profile but keep their previous Hibernate-built
  schema via the test-only override
  `server/src/test/resources/config/application-dev.properties`; tests that need
  the real schema enable Flyway explicitly (e.g. `ChatApiIntegrationTest`).
  `DevProfileSchemaTest` guards the real dev settings.
- `application-prod.properties` — production (INFO logging, port overridable via
  `KEPLURA_SERVER_PORT`), activated with `SPRING_PROFILES_ACTIVE=prod`.

An explicit `SPRING_PROFILES_ACTIVE` always overrides the dev default, so the
usual Spring mechanism is unchanged:

```bash
SPRING_PROFILES_ACTIVE=prod java -jar target/keplura-server-0.1.0.jar
```

The startup log line `The following 1 profile is active: "prod"` confirms the
selection. Profile files contain **no secrets** — machine-specific values come
from the environment.

## Health endpoint (M1a)

`GET /api/v1/health` returns `200 {"status":"UP"}` as soon as the HTTP stack serves —
operations-only, separate from the desktop-facing `GET /api/v1/system/info`, no CORS
and no actuator dependency in this milestone.

## Server (Java 21 + Spring Boot 4.1.1 + Maven)

```bash
cd server
mvn clean verify
```

Verified result (M1c era): `BUILD SUCCESS`, `Tests run: 16`. — **Current
state re-verified on `main` (6b6407c, 2026-09-06): `BUILD SUCCESS`,
`Tests run: 303, Failures: 0, Errors: 0, Skipped: 0`. SEC-2 (CSRF +
invites) raised the suite to `Tests run: 345, Failures: 0, Errors: 0,
Skipped: 0` (~270 s wall; includes auth, CSRF-enforcement, org/space,
invite-lifecycle, KB/upload, pgvector-path, gateway, M4c provider-preset and
chat/SSE suites)**, and
`spring-boot:repackage` produced the runnable fat jar
`server/target/keplura-server-0.1.0.jar`.

Spring Boot 4 note: the web stack was modularised. The project uses the current
(non-deprecated) starters `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test`,
and `@AutoConfigureMockMvc` now imports from
`org.springframework.boot.webmvc.test.autoconfigure`. `TestRestTemplate` lives in
`org.springframework.boot.resttestclient` and additionally needs the
`spring-boot-restclient` artifact (test scope) for `RestTemplateBuilder`.

## API integration harness (M1b)

`server/src/test/java/.../ApiIntegrationTest.java` boots the full app on a random
port (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureTestRestTemplate`
+ `TestRestTemplate`) and makes **real HTTP roundtrips** — over the wire, not via
MockMvc — against every deployed endpoint: status, `Content-Type`, exact JSON field
names, the 404 case for unknown `/api/**` paths, and transport headers (`Date`,
`Content-Length`) that only a genuine socket response carries.

To cover a new endpoint (M2+): add one `@Test` method here that calls the endpoint
through `rest` and asserts status + `Content-Type` + response field names (reuse
the existing `JsonPath.parse(response.getBody())` pattern). Keep MockMvc tests for
fast per-controller contract checks; the harness complements them with the wire-level
view. No new framework or CI change is needed — `mvn verify` already runs it.

Run locally:

```bash
java -jar target/keplura-server-0.1.0.jar   # listens on :8080
curl http://localhost:8080/api/v1/system/info
```

Verified response:

```json
{"product":"Keplura Server","serverVersion":"0.1.0","apiVersion":"v1"}
```

CORS for the webview origin is enabled on the controller (`Access-Control-Allow-Origin: *`,
verified with `curl -H "Origin: http://localhost:1420" -D-`).

## Desktop (Tauri 2 + Vue 3 + TypeScript + Vite)

```bash
cd desktop
npm install          # verified: 48 packages
npm run build        # verified: vue-tsc --noEmit + vite build, "✓ built"
```

Server URL configuration: `desktop/.env` → `VITE_SERVER_URL` (default
`http://localhost:8080`). Machine-specific overrides go in `.env.local` (git-ignored).
The app calls `GET {VITE_SERVER_URL}/api/v1/system/info` and renders
**Connected / Not reachable** plus the returned product, server version and API version.

Rust shell check (runs the full tauri crate graph, including WebKitGTK bindings):

```bash
cd desktop/src-tauri && cargo check
# verified: Finished `dev` profile ... in 1.09s
```

Tauri Linux app bundle (deb) — release builds use LTO and take very long, so the
debug bundle was built:

```bash
cd desktop
npm run tauri build -- --debug
```

Verified result: `Finished 1 bundle at:
desktop/src-tauri/target/debug/bundle/deb/Keplura_0.1.0_amd64.deb`
(package `intra-core` 0.1.0 amd64; contains the ELF binary at `usr/bin/keplura-desktop`
and the app icons).

Icons: generated once with `npx tauri icon <1024x1024 source png>`; only the
desktop-relevant outputs are committed under `desktop/src-tauri/icons/`
(android/ios trees removed — this app ships desktop only).

## Desktop ↔ Server communication

End-to-end verified with the **real compiled desktop binary** (not just a browser):

1. `java -jar target/keplura-server-0.1.0.jar` started with Tomcat access logging on.
2. The frontend dev server and any browser were confirmed **not** running (port 1420 closed),
   so only the desktop webview could hit the API.
3. The bundled debug binary `desktop/src-tauri/target/debug/keplura-desktop` was launched
   headless under Xvfb (WebKitGTK, software rendering via `WEBKIT_DISABLE_COMPOSITING_MODE=1`
   / `WEBKIT_DISABLE_DMABUF_RENDERER=1`).
4. The server access log recorded exactly the request the Vue app performs, answered 200:

   ```
   [03/Sep/2026:17:52:48 +0000]"Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/605.1.15
   (KHTML, like Gecko) Version/60.5 Safari/605.1.15""tauri://localhost"
   "GET /api/v1/system/info HTTP/1.1"200
   ```

   The WebKit user agent proves the caller was the Tauri webview itself, and the
   `Origin: tauri://localhost` shows CORS applies to the packaged-app origin (the dev
   origin is `http://localhost:1420`); the controller's `@CrossOrigin(origins = "*")`
   covers both. The response body (`{"product":"Keplura Server","serverVersion":"0.1.0",
   "apiVersion":"v1"}`, 72 bytes) was confirmed via curl against the same running server.

Additionally, `SystemInfoControllerTest` asserts the endpoint JSON contract and the
`Access-Control-Allow-Origin: *` header for the dev-webview origin
(`http://localhost:1420`) as automated regression coverage.

The only step not captured is a pixel screenshot of the window (no ImageMagick
`import`/scrot available in the container), but the webview demonstrably loaded the app
and executed its fetch lifecycle against the live server.

## Desktop login flow (M2d)

The desktop app now performs the first real user flow: login form →
`POST /api/v1/auth/login` (session cookie via `credentials: "include"`) →
signed-in view with user greeting + `GET /api/v1/orgs` list → logout. State
lives in a Pinia store (`desktop/src/auth-store.ts`); API shapes are in
`desktop/src/api.ts` and mirror `contracts/openapi.yaml`. Error copy is
deliberately coarse (401 → „Anmeldung fehlgeschlagen…“, 429 → „Zu viele
Versuche…“ with the server's `Retry-After` minutes) so the client never
re-introduces the user-enumeration the server removed (ADR-0003). All form
fields/buttons carry `data-testid` attributes (`login-email`, `login-password`,
`login-submit`, `login-message`, `session-user`, `logout-button`, `org-list`,
`org-empty`, `org-error`) for the QA gate.

### Credentialed CORS (`DesktopCorsConfig`)

`@CrossOrigin(origins = "*")` cannot cover the auth/orgs endpoints: the spec
forbids wildcard origins on credentialed requests. `DesktopCorsConfig`
therefore maps an explicit origin allow-list (dev:
`http://localhost:1420,tauri://localhost,http://tauri.localhost` — the last
two are the packaged origins on macOS/Linux and Windows; overridable via
`keplura.desktop.allowed-origins`) with `allowCredentials(true)` onto
exactly `/api/v1/auth/login|logout` and `/api/v1/orgs` (GET + POST since
M2e), and exposes `Retry-After` on the login and orgs paths so the webview
can read a 429 hint. Foreign
origins fail preflight (`403 Invalid CORS request`, no `Access-Control-Allow-*`
headers); `/api/v1/health` stays CORS-free, `/api/v1/system/info` keeps its
public `*`. Since M2e the session gate lets CORS *preflight* OPTIONS through
(`CorsUtils.isPreFlightRequest`) — Spring answers it in the CORS layer without
ever invoking the controller, and a 401 there would break the credentialed
POST handshake; a bare OPTIONS without the preflight signature still 401s.
Regression cover: `DesktopCorsIntegrationTest` (real HTTP, preflight +
credentialed GET + packaged origins + foreign origin + health + 429 expose +
org-create preflight + bare-OPTIONS gate).

### Dev account seeding (`DevAccountSeeder`)

There is no registration endpoint by design (ADR-0002), so a manual dev/QA
run needs one way to get a first user: the **dev profile only**, with **both**
`keplura.dev.seed.email` and `keplura.dev.seed.password` set on the command
line, creates that user once at startup (idempotent, never resets an existing
password, logs without PII). Out of the box nothing is seeded — no known
credential exists by default.

### Login manuell testen

```bash
# 1) Server with a seeded QA account (dev profile is the default):
cd server
mvn -B package -DskipTests    # or reuse the existing target/*.jar
java -jar target/keplura-server-0.1.0.jar \
  --keplura.dev.seed.email=qa@example.com \
  --keplura.dev.seed.password='Test-Pass-123' \
  --keplura.dev.seed.display-name='QA Tester'

# 2) Desktop (in a second terminal) — the same origin the Tauri dev webview uses:
cd desktop
npm install
npm run dev                   # http://localhost:1420

# 3) Open http://localhost:1420 in a browser: login card appears, sign in with
#    the seeded account -> greeting "QA Tester" + empty org list -> Abmelden.
#    Wrong password shows „Anmeldung fehlgeschlagen…“, >10 failed tries in 15
#    min show „Zu viele Versuche…“ (429 + Retry-After).
```

For the packaged app (`npm run tauri dev` / `npm run tauri build`) the flow is
identical from the user's side; the wire path is not (ADR-0005). The packaged
webview origins `tauri://localhost` (macOS/Linux) and `http://tauri.localhost`
(Windows) are *cross-site* to the server by the cookie spec's scheme rule, so
a plain webview `fetch` would never attach the `SameSite=Lax` session cookie.
The PO decision (04.09., closing security finding F4) routes the packaged
traffic through `tauri-plugin-http`: the request runs in Rust (reqwest) with
its own cookie jar outside the webview rules, so the cookie stays
`HttpOnly; SameSite=Lax` — **no `SameSite=None`, no TLS requirement added**.
For that jar to survive app restarts the cookie must be *persistent* (the
plugin only persists cookies with an explicit lifetime). Since AUTH-REMEMBER
(PO instruction 18.11.2026), the login checkbox controls this: unchecked means
an ordinary session cookie; checked means an explicit Max-Age, default 30 days.
Authenticated sessions have no 30-minute inactivity logout. The server enforces
an absolute deadline using `keplura.auth.session-lifetime-seconds` (default
2592000), also used for the persistent cookie and abandoned-session cleanup.
Activity cannot extend this deadline. Server restarts invalidate these in-memory
sessions; no database session persistence is added. Pending unauthenticated OIDC
sessions retain their short timeout. `desktop/src/transport.ts`
dispatches: Tauri webview -> native plugin fetch (capability-scoped to the
configured server origin, `set-cookie` stripped from JS-visible headers);
plain browser (`npm run dev`, the browser-based QA flow) -> `fetch` with
`credentials: "include"` exactly as before. The Vue/Pinia UI and all outcome
mapping are unchanged.
Wire-level proof of the whole cycle (seed → login 200 + Set-Cookie + ACAO
credentialed headers → orgs 200 `[]` → logout 204 → stale cookie 401) is
recorded in the M2d PR; the packaged-app E2E (built binary under Xvfb: login,
restart-restore, logout, stale session, server unreachable) is recorded in
the M2-POLISH PR.

## Desktop org creation flow (M2e)

Follows the M2d manual test above (server + seeded account + `npm run dev`):
after signing in, the „Neue Organisation“ form in the session view POSTs
`{name}` to `/api/v1/orgs` through the same session cookie and
`desktopFetch`. Outcomes:

- 201: green toast „…angelegt — du bist OWNER“, the org appears in the list
  with an OWNER badge (appended from the POST response — no second GET).
- 400 (blank/over-200-char name — the input has `maxlength` so this is the
  server's trim rule): error line INSIDE the create dialog (AppDialog
  `error` prop, `dialog-error` testid) with the name bounds, form stays
  open. FINDING 1 fix (UI-POLISH-1 review): dialog create errors must
  never render as page-level toasts — the native <dialog> top layer keeps
  the message visible above the backdrop. Same pattern for the space/KB
  dialogs (all three share the shell's single `dialog-error` anchor —
  only one dialog is ever open, so it is unambiguous).
- 401 (session expired server-side): dialog error, then the app falls back
  to the login card, like the orgs-load path.
- 429: dialog error with the seconds from the exposed `Retry-After` header
  (no org-path limiter exists yet — the branch is wired for when one lands).
- network/other: generic dialog error, nothing local changes.

Testids for the QA gate: `org-create-form`, `org-create-name`,
`org-create-submit`, `org-create-toast` (OK feedback only),
`dialog-error` (error line inside any open AppDialog), `org-role` (per row).

## CSRF-Schutz (SEC-2, double-submit)

SEC-2 closed the documented ADR-0003 deferral: SameSite=Lax alone stayed a
mitigation, not a defence (same-site requests and a same-origin XSS would
still ride the session cookie). The gate now lives in the session
interceptor itself (`SessionAuthInterceptor` + `CsrfService`, server):

- **Login** creates the session and binds a fresh 64-hex-token
  (`SecureRandom`, 32 bytes) to it. The token ships TWICE: as the
  non-HttpOnly cookie `keplura_csrf` (SameSite=Lax, path `/`, Secure iff
  TLS) and as `csrfToken` in the JSON response body. Both Set-Cookie headers
  go out via the raw servlet response (`addHeader`): measured on
  Tomcat 11/Boot 4.1, a `ResponseEntity`-carried Set-Cookie and Tomcat's own
  session cookie do NOT coexist — only one ships. Regression cover: the
  two-Set-Cookie assertion in `CsrfEnforcementIntegrationTest`.
- **Every state-changing request** (POST/PUT/DELETE/PATCH on the protected
  prefixes `/api/v1/orgs[/**]`, `/api/v1/gateway/**`, `/api/v1/invites/**`)
  must carry header `X-CSRF-Token` equal to BOTH the `keplura_csrf` cookie
  AND the session attribute — `MessageDigest.isEqual` (timing-safe) on all
  three, fail-closed 403 `{"error":"CSRF_INVALID"}`. The cookie half stops a
  cookie-tossing/subdomain attacker from pairing a forged cookie with a
  forged header; the session half stops a fixed token from being replayed
  across sessions.
- **Exemptions, each deliberate:** `POST /api/v1/auth/login` (no session to
  protect yet — the ONLY exempt state-changing route); CORS preflight
  (OPTIONS never reaches the CSRF check, see the M2e note above); and
  `POST /api/v1/auth/logout` which sits on the OPEN auth prefix, so its
  double-submit check runs in the controller (`403 CSRF_INVALID` only when a
  live session exists without a valid pair; no-session stays 204).
- **SSE streams stay plain GETs** (`.../chat/conversations/{id}/stream`):
  a same-site GET cannot mutate state per the RFC 9110 safety contract, and
  this endpoint's POST-free design means CSRF adds nothing there — forcing a
  header onto `EventSource`-style streams would only push the token into
  query strings, where it leaks via logs/referrer. Documented instead of
  silently skipped.
- **Why the JS-visible cookie stays safe** (the double-submit rationale):
  an attacker on another origin can *cause* the browser to attach our cookies
  to a forged request only on same-site navigations (Lax blocks cross-site
  POST attachment outright), and can never READ the cookie value
  (same-origin reads) nor SET one for our host (no domain write access
  without a shared parent domain, which localhost/tauri:// origins do not
  have). The header is therefore the unforgeable half: a request without the
  exact paired token fails.
- **Restore path:** `GET /api/v1/auth/csrf` re-emits the session's token
  (body + re-affirmed cookie, idempotent) — used by `auth-store.restoreSession`
  after webview reload; a session that cannot prove a token read is dropped
  fail-closed to the login form.

Desktop side (`desktop/src/transport.ts`): the native Tauri webview has no
`document.cookie` at all (tauri:// origin) and `set-cookie` is stripped from
JS-visible responses by the ADR-0005 design, so the transport keeps the token
from the login body in memory + localStorage and attaches `X-CSRF-Token`
automatically to every state-changing `desktopFetch` call (login path
excluded). Plain-browser (Vite dev/QA) uses the same interceptor over web
fetch. Testids and flows unchanged; the CSRF half is wire-level.

Regression cover (server): `CsrfEnforcementIntegrationTest` (happy pair,
missing header, wrong token, half-pair negatives, login exemption, preflight,
re-login re-pair) + every retrofitted session IT (all POST/PUT/DELETE now
travel with `CsrfTestSupport.cookiePair` / `bindToSession`). Negative tests
assert the EXACT `CSRF_INVALID` body, never a generic deny.

## Org-Mitglieder einladen (SEC-2 invites)

Beta-blocker "no way to add a second human to an org" closed — server-first,
Flyway `V8__org_invites.sql`, roles stay OWNER/MEMBER:

- `POST /api/v1/orgs/{id}/invites` (OWNER only) mints a one-time token:
  32 fresh `SecureRandom` bytes hex, **stored only as SHA-256 hash**
  (`org_invite.token_hash`, UNIQUE index), expires in 7 days
  (`INVITE_TTL`), role hard-pinned to MEMBER (CHECK constraint + structural
  service guard — an OWNER cannot be invited). The plaintext token appears in
  the ONE 201 response; the pending list shows metadata only.
- `POST /api/v1/invites/accept` `{token}` — the ACCEPTOR's session decides
  WHO joins, the token only selects the org (its own protected prefix, since
  the acceptor has no org path to hang under). Redeem flips `used_at`
  atomically; replay/unknown/revoked/expired are one uniform 404
  (non-probing). Already-a-member is 409 and the invite survives. No new user
  is ever created: the account must exist (dev-seeded until OIDC — deliberate
  scope boundary, ADR-0002 unchanged).
- `GET /api/v1/orgs/{id}/invites` (pending list) +
  `DELETE /api/v1/orgs/{id}/invites/{inviteId}` (revoke, tenant-pinned
  `org_id AND id` delete → foreign ids 404 like everywhere, ADR-0004).
- `GET /api/v1/orgs/{id}/members` (OWNER-only roster) for the panel.
- 404-before-403 order everywhere (existence then privilege), matching the
  org/space IDOR defence battery.

Desktop: `MembersPanel.vue` mounted above the knowledge panel in the session
view (Apple-Refined look, `auth-store` state): OWNER sees roster +
`invite-create` (token dialog shows the value exactly ONCE, copy-assist,
`invite-token`), pending list (`invite-list`, `invite-revoke-*`) and the
accept form (`invite-accept-form`, `invite-accept-token`,
`invite-accept-submit`) available to every user. QA testids: `invite-*`.
No email transport — the owner forwards the token org-internally (documented
non-goal).

## Spaces API (M3b)

The next tenant level: `POST/GET /api/v1/orgs/{orgId}/spaces` and
`GET /api/v1/orgs/{orgId}/spaces/{spaceId}` (session-gated — the nested prefix
is already covered by the M2c `SessionAuthConfig` patterns). Authorization
decision (documented in `SpaceService`): **org membership IS the space ACL** —
no `space_membership` table until a real per-space permission difference
exists (ADR-0004 carry-over: chain through org membership, don't duplicate it).
Tenant isolation lives in `SpaceRepository`'s JPQL: every read joins the
`membership` row for the PATH's org in the same statement AND pins
`s.orgId = :orgId`, so a victim space id probed through the attacker's own
org path 404s exactly like a never-existing id. Names are trimmed, max 200
chars (400) and unique per org (DB key `(org_id, name)` in
`V2__spaces.sql`; duplicate answers 409). Schema note: V1 stays frozen; V2 is
a NEW migration and the H2 PostgreSQL-mode replay
(`FlywayBaselineMigrationTest`) now asserts the full V1→V2 chain. Dev/test
H2 paths are unchanged (ddl-auto), prod stays Flyway + `validate` — the
PostgreSQL boot with V2 is proven per docs below / PR evidence. Manual smoke:
with the M2d seeded server running:

```bash
curl -s -c /tmp/j -H 'Content-Type: application/json' -d '{"email":"qa@example.com","password":"<seed>"}' \
  localhost:8080/api/v1/auth/login
curl -s -b /tmp/j -H 'Content-Type: application/json' -d '{"name":"Projekte"}' \
  localhost:8080/api/v1/orgs/<orgId>/spaces
curl -s -b /tmp/j localhost:8080/api/v1/orgs/<orgId>/spaces
```

## Knowledge Bases + Dokument-Upload (M3c)

The third tenant level: knowledge bases inside a space, documents inside a
kb. Routes (all session-gated automatically — `SessionAuthConfig` covers
`/api/v1/orgs/**`):

- `POST/GET /api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases`
- `GET  /api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases/{kbId}`
- `POST /api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases/{kbId}/documents` (multipart, single `file` part)
- `GET  /api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases/{kbId}/documents` (list)
- `GET  .../documents/{documentId}/download` (streams the stored bytes)

Authorization is the M3b pattern one level deeper — **org membership IS the
kb/document ACL** (ADR-0004 carry-over, no kb-level role table). The full
chain (membership → space pinned to org → kb pinned to space) is proven
BEFORE any file or metadata access, and every denial at every level collapses
into the same empty 404; the repositories (`KnowledgeBaseRepository`,
`DocumentRepository`) pin the parent ids inside the same JPQL statement, so a
victim kb/document id probed through the attacker's own path is
indistinguishable from absent. KB names: trimmed, ≤200 chars (400), unique
per space — DB key `(space_id, name)` in `V3__knowledge.sql`, duplicate
answers 409. DELETE (kb and document) is a documented Non-Goal of M3c, same
precedent as spaces.

Upload rules (`DocumentUploadPolicy` + `DocumentStorage`):
- Type whitelist by EXTENSION (pdf/txt/md/docx/csv, case-insensitive); the
  client's multipart Content-Type header is IGNORED — the served type is the
  server-mapped one. Anything else is 400.
- Size limit `keplura.upload.max-size-bytes` (ENV `KEPLURA_UPLOAD_MAX_BYTES`,
  default 20 MiB) checked on the declared part size BEFORE reading bytes and
  again on the measured size after streaming; the container limit
  `spring.servlet.multipart.max-file-size` is aligned so framing tricks cannot
  bypass the app rule (breach → 413 either way), and
  `server.tomcat.max-swallow-size=-1` lets Tomcat fully drain a rejected body so
  the 413 JSON is always delivered instead of the connection being reset.
- The client filename is reduced to a safe DISPLAY name (`[A-Za-z0-9._ -]`
  only) and NEVER shapes the storage path. On disk the leaf is a server-side
  UUID: `orgs/{orgId}/spaces/{spaceId}/kbs/{kbId}/{uuid}` below
  `keplura.upload.dir` (ENV `KEPLURA_UPLOAD_DIR`, default `./data/uploads`).
  Every dynamic segment is a `long`/`UUID.toString()` — traversal ("..", "%2F",
  NUL, unicode tricks) is impossible BY CONSTRUCTION; the read side
  (`resolveForRead`) additionally re-enforces canonical containment, so even a
  hand-corrupted DB row cannot make the download endpoint read outside the
  upload tree. sha256 is computed over the bytes as stored (M3d dedup/integrity
  anchor). Virus scanning is a conscious MVP gap (task Non-Goal); M3d's parser
  integration is the point where content validation must be revisited.
- Schema: V1/V2 stay frozen; `V3__knowledge.sql` is additive and the H2
  PostgreSQL-mode replay (`FlywayBaselineMigrationTest`) asserts the full
  V1→V2→V3 chain and the six domain tables. Dev/test H2 paths unchanged.

Manual smoke (seeded dev server, cookie jar as in the Spaces section):

```bash
curl -s -b /tmp/j -H 'Content-Type: application/json' -d '{"name":"Vertrag"}' \
  localhost:8080/api/v1/orgs/<orgId>/spaces/<spaceId>/knowledge-bases
curl -s -b /tmp/j -F 'file=@bericht.pdf;type=application/pdf' \
  localhost:8080/api/v1/orgs/<orgId>/spaces/<spaceId>/knowledge-bases/<kbId>/documents
curl -s -b /tmp/j -o /tmp/out.pdf -D - \
  localhost:8080/api/v1/orgs/<orgId>/spaces/<spaceId>/knowledge-bases/<kbId>/documents/<docId>/download
sha256sum bericht.pdf /tmp/out.pdf   # must match the sha256 in the upload response
```

## Server mit Postgres starten (M3a, ADR-0006)

Der Produktiv-Modus des Servers läuft gegen PostgreSQL 16 + pgvector
(`pgvector/pgvector:pg16`). Das Schema gehört Flyway: `V1__baseline.sql`
(`server/src/main/resources/db/migration`) migriert beim Boot, Hibernate
prüft nur noch (`ddl-auto=validate`) — Schema-Drift lässt den Boot
fehlerhaft durchlaufen statt still zu mutieren. Dev (H2-Datei im
PostgreSQL-Modus) nutzt seit 20.11.2026 dieselben Flyway-Migrationen mit
`ddl-auto=validate` (siehe „Config profiles“); die Tests bleiben auf ihrem
bisherigen Weg; CI läuft bewusst weiter H2-basiert (Flyway-Leg als
H2-PostgreSQL-Mode-Replay, siehe `FlywayBaselineMigrationTest`).

Voraussetzung: ein Container-Runtime (docker oder podman-compose).

```bash
cd deploy
cp .env.example .env      # lokale Werte eintragen — .env ist git-ignored,
                          # niemals Zugangsdaten committen
docker compose up -d db   # postgres:16 + pgvector, Port aus .env (5432)

cd ../server
mvn -B package -DskipTests
SPRING_PROFILES_ACTIVE=prod \
  KEPLURA_DB_URL=jdbc:postgresql://localhost:5432/keplura \
  KEPLURA_DB_USER=keplura \
  KEPLURA_DB_PASSWORD='<Wert aus deploy/.env>' \
  java -jar target/keplura-server-0.1.0.jar
```

Erwartetes Boot-Log (Auszug): `Migrating schema "public" to version
"1 - baseline"`, dann `"2 - spaces"` (M3b), dann `"3 - knowledge"` (M3c), dann
`"4 - chunks"` (M3d, portabel), dann `"5 - pgvector"` (M3d, nur PostgreSQL —
`db/migration-postgresql`, via `spring.flyway.locations=...,db/migration-{vendor}`),
danach `Successfully validated 5 migrations` und der
Hibernate-Validate-Durchlauf ohne DDL — der Server lauscht auf
`:8080/api/v1/health`. Ein `ddl-auto`-Override ist für PostgreSQL-Setups
nicht vorgesehen; nur der H2-Smoke-Fallback (ohne `KEPLURA_DB_*`) darf
bei Bedarf per `KEPLURA_DB_DDL_AUTO` ausbrechen. Eine Datenbank mit
Fremdtabelle aber ohne `flyway_schema_history` startet nicht (Boot-Fehler,
`baseline-on-migrate=false`) — bewusst, statt ein unbekanntes Schema
still zu übernehmen. pgvector wird seit M3d genutzt: `V5` installiert die
Extension und spiegelt die Chunk-Embeddings als native `vector(N)`-Spalte
(ADR-0007, Details im RAG-Abschnitt unten).

## RAG-Abfragepfad: Chunking, Embeddings, pgvector-Suche (M3d)

Route: `POST /api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases/{kbId}/search`
session-gated wie der Rest von `/api/v1/orgs/**`; die volle Kette
(Membership → Space → KB) wird vor jeder Embedding-Arbeit bewiesen (404 wie
überall). Request `{ "query": "...", "topK": 5 }`, Response
`{ query, topK, backend, model, results[] }` mit Treffern
`{ documentId, filename, chunkIdx, text, score }` — Retrieval MIT
Quellenangabe, kein generierter Antworttext (chat bleibt eigenes Feature,
Non-Goal laut Roadmap-M3d).

Pipeline (`search`-Paket): Upload → `DocumentIndexingService` (detached
Transaktion) liest die gespeicherten Bytes, wandelt striktes UTF-8
(`ChatCharacterDecoder`) und chunktet sliding-window in zeichenechten
Fenstern (`DocumentChunker`: `chunk-size-chars`/`chunk-overlap-chars`,
Standard 800/100, Schritt = Größe − Überlappung, Surrogat-Paare werden nie
gespalten). Pro Chunk: Embedding → portabler `kb_chunk`-Eintrag
(`ChunkRepository`+`EmbeddingCodec`, Embedding als pgvector-Textliteral
`[f,f,…]`) UND — nur wenn der pgvector-Store aktiv ist — die
Twin-Zeile in `kb_chunk_embedding` mit nativem `vector`. Identische
(document_id, chunk_idx)-Schlüssel, idempotent über Unique-Constraint.

Ehrliche Parser-Grenze (kein Fake-Index): txt/md/csv werden geparst und
sind sofort suchbar (`indexStatus=INDEXED` im Upload-/List-/Detail-Response);
pdf/docx extrahiert seit MVP-5 Apache Tika (PDFBox/POI) mit harten
Ressourcen-Grenzen (`keplura.parser.*`, s.u.) durch denselben Chunk-+
Embedding-Pfad — echte Treffer mit Zitat. Zwei ehrliche Auswege bleiben:
ein pdf/docx OHNE Textschicht wird — sofern die lokale Texterkennung
(OCR-01, s. u.) nicht verfügbar ist oder nichts erkennt — mit persistiertem
`indexStatus=NO_TEXT_LAYER` gespeichert (V7-Spalte,
Check-Constraint) und erscheint in keiner Ergebnisliste; ein nicht
extrahierbares Dokument (kaputt, verschlüsselt, über einer Grenze) lehnt
den Upload mit 400 ab und rollt die Transaktion vollständig zurück —
nie ein halb indizierter Zustand. Zeilen aus der PARSER_PENDING-Ära (vor
MVP-5) haben keine Chunks und antworten `NOT_INDEXED`; die API behauptet
niemals Suchbarkeit, die sie nicht mit Zeilen belegen kann.

Harte Grenzen (alle testgeprüft, alle hart statt truncierend):
Abfrage-Text trim ≥1 und ≤ `max-query-chars` (4000) → sonst 400 VOR
Embedding-Arbeit; topK > 50 → Clamp auf 50; Embedding-Dimension: der
Gateway-Client verwirft Antworten mit abweichender Dimension pro Aufruf, und
der pgvector-Startup-Probe vergleicht die Spalten-Dimension gegen
`embedding-dim` (Mismatch → Boot-Abbruch bei pinned store, sonst Fallback
mit Log). Uploads: die Stream-Kappung in `DocumentStorage` zählt BYTES
(nicht Reader-Zeichen) und verwirft alles über `max-doc-bytes`; Dekodierung
ist der Indexierungs-Schicht getrennt vom Storage-Limit.

Extraktions-Grenzen (MVP-5, `TikaDocumentTextExtractor`, alle ENV-überschreibbar
über `KEPLURA_PARSER_*`, gepinnt durch `DevProfileDefaultsTest`):
`extracted-chars` (2 Mio.) — Volltext-Limit, Überschreiten ist REJECT statt
stiller Kürzung; `pdf-max-pages` (500) — strukturelles Vorzählen (PDFBox, ohne
Textextraktion) + `PageRangeRequest`-Fenster als Verteidigungslinie gegen
falsche Zähler; `docx-max-pages` (200) — aus den OPC app-properties, ein
fehlender Wert vetiert nie (Zeichen-/Entry-Kappen greifen trotzdem);
`max-entry-count` (5000) + Mindest-Inflate-Ratio 0.01 — die Zip-Bomb-Kappen
(POI `ZipSecureFile`; sie werden bei JEDEM Parse gesetzt, und zwar
ausdrücklich NACH der Konstruktion des `OOXMLParser` — dessen statischer
Initialisierer schreibt beim ersten Klassen-Load selbst cap 10000 / Ratio -1.0
in dieselben JVM-globalen Statik-Felder und würde früher gesetzte Werte
still überschreiben; Reihung ist vertraglich, Regressionstest mit frischer
JVM im Extractor-Test); eingebettete
Ressourcen werden NIE gefolgt (reject-all `EmbeddedDocumentExtractor`).
Jeder Parse läuft auf boundedem Pool (`max-concurrency`, Standard 2) mit
Wanduhr-`timeout-seconds` (60) auf Temp-Kopie — Überlast antwortet sofort 503
(„später erneut"), Timeout/kaputt/verschlüsselt/kappenüberschreitend antwortet
400 mit vollständigem Rollback. Ehrliche Caveat: ein abgebrochener Worker kann
bis zum Parse-Ende weiterlaufen (Thread-Kill ist unsicher); er belegt dann
einen der Pool-Slots, mehr nicht.

DOC-02 ergänzt XLSX und PPTX in Upload, Datenquellen-Abgleich und Chat-Anhang.
Die zentrale `DocumentUploadPolicy` bestimmt auch die Filterung im unveränderten
Datenquellen-Code. Binärformate XLS/PPT/DOC und Makroformate XLSM/PPTM bleiben
abgelehnt. Es werden keine neuen Parser-Abhängigkeiten aktiviert.

Office-Vorprüfung (`OfficePackagePolicy`, innerhalb desselben Parser-Pools und
Zeitbudgets, nach Setzen der POI-ZIP-Gates): maximal **200 Folien**, **50 Blätter**
und **200.000 gespeicherte Zellen insgesamt** (auch formatierte leere Zellen).
Die Struktur wird per gehärtetem SAX gelesen, nicht aus unverlässlichen
app-properties abgeleitet. Pakettyp muss zur Erweiterung passen. Makro-Inhalte
und externe OPC-Beziehungen werden abgelehnt (einschließlich externer Hyperlinks).
Eingebettete Dokumente bleiben ungelesen. Zeichenlimit und ZIP-Grenzen können
auch eine Datei innerhalb der Strukturgrenzen ablehnen. Tika-Warnungen über
unvollständige Office-Extraktion werden als Fehler behandelt.

XLSX: ein Abschnitt je Blatt mit Blattname als Überschrift; Zellwerte zeilenweise,
Tabulatoren zwischen Zellen. Formeln liefern den **gespeicherten berechneten Wert**;
keine Neuberechnung und keine Aktualisierung externer Daten. Der Cache kann vom
letzten Speichern stammen. PPTX: „Folie n“ in Präsentationsreihenfolge, einschließlich
Sprechernotizen; Folientrenner bleiben wie PDF-Seitentrenner bis zur Chunk-Persistenz
erhalten. Tikas POI-Slideshow-Extraktor ist dafür explizit gewählt, weil der SAX-Zweig
OPC-Beziehungsreihenfolge statt Präsentationsreihenfolge verwenden kann. Mastertexte
sind ausgeschaltet. Das Wire-Feld `pageNumber` bezeichnet bei PPTX eine Folie;
Suche, Chat-Zitate, Vorschau und Antwortexport zeigen entsprechend „Folie n“.
Reflow-DOCX und XLSX erhalten keine erfundenen physischen Seitenzahlen.

Backends (ENV-gesteuert, keine Hardcodes, siehe application.properties):
`KEPLURA_EMBEDDING_BACKEND=local|gateway` — `local` ist ein
deterministischer Feature-Hash (`LocalHashEmbeddingService`, Modell-Tag
`local-hash-*`, NIEMALS semantisch; macht CI hermetisch), `gateway` spricht
OpenAI-kompatible `/v1/embeddings` (`KEPLURA_EMBEDDING_BASE_URL/_API_KEY/
_MODEL/_DIM/_TIMEOUT_SECONDS`). `KEPLURA_SEARCH_STORE=auto|memory|pgvector`
— auto nimmt den nativen pgvector-KNN-Store nur wenn der DataSource echt
PostgreSQL ist UND der Probe (Extension, Zwillingstabelle, Dimension)
erfolgt, sonst In-JVM-Cosine über die Kandidaten (`MemoryEmbeddingStore`);
`pgvector` gepinnt + Probe-Fehler ⇒ Boot-Abbruch (kein silent downgrade).
Beide Pfade rechnen dieselben Cosine-Scores (`1 - (e <=> q)` bzw.
`Similarity.cosine`) gleich innerhalb float32-Darstellungsrauschen
(Evidence-Lauf: identisches Ranking, Δ < 2e-8) — die H2-CI-Leg ist damit
ein treuer Stellvertreter.
Caching: MVP ohne (bewusster Non-Goal; Re-Embedding pro Query, pro-Kb-
Kandidaten sind klein — der `sha256`-Anker aus M3c liegt für Dedup bereit).

Schema: V1–V3 frozen; `V4__chunks.sql` ist portabel (PG UND H2-PostgreSQL-
Mode — die `FlywayBaselineMigrationTest`-Replay-Leg assertet V1→V4 und die
sieben Domain-Tabellen), `V5__pgvector.sql` läuft dank Vendor-Split
(`db/migration-postgresql`) ausschließlich auf PostgreSQL und macht die
Embedding-Spalte nativ (`ALTER … TYPE vector(64) USING …::vector`, keine
ANN-Index-Errichtung in MVP — exact KNN). Die H2-Pfade (dev-Datei + Tests)
bleiben bei ddl-auto; die H2-Replay-Leg validiert V5 nur als Resolution
(der Vendor-Auswahl-Mechanismus), nicht als Ausführung.

Manueller Smoke (Seed-Server, Cookie wie oben; nur txt/md/csv sind suchbar):

```bash
printf 'die turbine dreht mit 3000 u/min' > /tmp/betrieb.txt
curl -s -b /tmp/j -F 'file=@/tmp/betrieb.txt;type=text/plain' \
  localhost:8080/api/v1/orgs/<orgId>/spaces/<spaceId>/knowledge-bases/<kbId>/documents
curl -s -b /tmp/j -H 'Content-Type: application/json' \
  -d '{"query":"turbine drehzahl","topK":3}' \
  localhost:8080/api/v1/orgs/<orgId>/spaces/<spaceId>/knowledge-bases/<kbId>/search
# erwartet: backend "memory" (H2) bzw. "pgvector" (PG16), model local-hash-64,
# hits mit documentId/filename/chunkIdx/text/score — leer für fremde kb 404
```

Ressourcen-Hinweis (3,8-GB-Host): nach Prüfungen `docker compose stop`
und den Server-JVM-Prozess beenden.

## Knowledge-UI im Desktop (M3e)

Der letzte M3-Schritt ist rein desktop-seitig: `KnowledgePanel.vue` (in der
SessionView unterhalb des Org-Teils) zeigt Knowledge Bases des (auto-gewählten)
Org + Space, legt KBs an, lädt Dokumente hoch (Dateiauswahl,
Typ-/Größenhinweise, einfacher Fortschrittsstatus), rendert pro Dokument den
`indexStatus` aus der API (INDEXED / NO_TEXT_LAYER / NOT_INDEXED — ehrlich,
kein Fake-Indiziert) und sucht pro KB mit voller Quellenangabe pro Treffer
(Filename, Chunk-Referenz, Score, Snippet, Backend-/Modell-Tag).

Transport: ausschließlich über das Bestehende — `desktop/src/api.ts` erhält
`fetchSpaces/fetchKnowledgeBases/fetchDocuments/createSpace/
createKnowledgeBase/uploadDocument/searchKnowledgeBase`, jede Funktion läuft
durch `desktopFetch` (ADR-0005: nativer Plugin-Fetch im Tauri-Webview,
Browser-fetch im QA-Flow). Outcome-Mapping wie bei org-create: bewusst grob —
401 → „expired“ (Store wirft die Session lokal raus), die Uniform-404 des
Servers (Kette an irgendeiner Ebene unsichtbar) wird NIE differenziert
angezeigt, 400/409/413 haben eigene Toast-Copy. Kein Server-Fehlerdetail
und keine Tenant-Struktur leakt in die UI. Der Multipart-Upload setzt keinen
Content-Type — das Platform-fetch baut die multipart-Grenze selbst, und die
Server-Whitelist (Extension) bleibt die einzige Typ-Wahrheit.

Serverseitig war NUR die CORS-Seite zu prüfen: die M3c-Mapping
`/api/v1/orgs/**` deckt GET+POST + Content-Type + exposed
Retry-After/Content-Disposition/X-Index-Status bereits für alle KB-/Document-/
Search-Routen ab; M3e hat das mit Preflight-/Response-Regressionstests für die
Search-Route und den KB-List-GET festgenagelt (`DesktopCorsIntegrationTest`)
statt die Policy zu erweitern — weniger Angriffsfläche, gleiche Funktion.

QA-Testids (stabil, Präfix `knowledge-`): `knowledge-section`,
`knowledge-space-form/-name/-submit`, `knowledge-kb-list/-kb-item/
knowledge-kb-select-<id>`, `knowledge-kb-form/-name/-submit`,
`knowledge-doc-list/-doc-item/-doc-name/-doc-status` (`data-index-status`
Attribut für Assertions), `knowledge-upload-input` (File-Input — im
Browser-Flow per `set_input_files` steuerbar), `knowledge-upload-progress`,
`knowledge-search-form/-query/-submit`, `knowledge-search-summary`,
`knowledge-result-list/-result-item/-result-source/-result-snippet/
-result-score`, `knowledge-result-empty`, `knowledge-toast`,
`knowledge-error`, `knowledge-reload`.

Session-Teardown-Invariante (Review M3e, Finding 1): der Pinia-Store lebt so
lange wie die App-Instanz, nicht so lange die Views. Darum gilt im Store —
`dropExpiredSession()` ist der EINE Pfad für jeden „expired“-Outcome (orgs,
spaces, kbs, documents, creates, upload, search) und räumt
IMMER user + orgs + sämtliche Tenant-Refs; Logout nutzt denselben Pfad.
Zusätzlich darf keine Listen-Ref ihren Parent überleben: `resetSpaceScope()`
läuft bei Org-Wechsel und fehlgeschlagenem Space-GET, `resetKbScope()` räumt
bei Space-Wechsel/-Fehler die KB-Liste mit. Regression: die Stale-Session-
Leg von `desktop/e2e/knowledge_flow_e2e.cjs` (Account B sieht nach 401 +
Re-Login ohne Page-Reload exakt null Zeilen von Account A).

Manueller Flow (Seed-Server + `npm run dev` auf http://localhost:1420):
login → unten erscheint „Wissen“ → Bereich anlegen (wenn keiner existiert) →
Knowledge Base anlegen → .txt/.md/.csv oder (seit MVP-5) .pdf/.docx wählen →
Toast „hochgeladen … Status: INDEXED" (Scan-pdf: „NO_TEXT_LAYER") →
Suchbegriff (z. B. „turbine drehzahl“) → Trefferliste mit
`<datei> · Chunk <n>` + Score. Bewiesen im M3e-PR (Evidenz-Log + Screens);
der pdf/docx-Zweig zusätzlich im MVP-5-PR via `mvp5_flow_e2e.cjs` (s.u.).

## Texterkennung für Scans (OCR-01)

PDF-Seiten ohne Textschicht (weniger als 8 Buchstaben/Ziffern) werden nach dem
begrenzten Tika-Parse lokal per Tesseract gelesen: PDFBox rendert die Seite als
Graustufenbild (300 dpi, Pixelgrenze ~40 MP) in eine temporäre Datei, Tesseract
läuft mit festen Argumenten (`<bild> stdout -l deu+eng --psm 3`, keine Shell,
keine Datei-/Benutzernamen auf der Kommandozeile, `OMP_THREAD_LIMIT=1`).
Seitentrennung bleibt erhalten, Zitate nennen weiter die echte PDF-Seite.
Gemischte PDFs: Textseiten unverändert, nur Bildseiten per OCR.

- Genau ein OCR-Arbeiter; Zeitlimit je Seite (Prozess wird hart beendet) und je
  Dokument; `keplura.ocr.max-pages` (Standard 100) je Dokument, Chat-Anhänge
  höchstens 10 Seiten (synchrone Antwort).
- Fehlt Tesseract oder ein Sprachpaket, meldet das Log einmal
  „ocr not available“ und Scans bleiben `NO_TEXT_LAYER` wie bisher.
- Server-Image (`deploy/Dockerfile.server`) enthält `tesseract-ocr` mit `deu`
  und `eng`. Lokal: `sudo apt install tesseract-ocr tesseract-ocr-deu`.
- Schalter: `KEPLURA_OCR_ENABLED`, `KEPLURA_OCR_LANGS`, `KEPLURA_OCR_MAX_PAGES`,
  `KEPLURA_OCR_PAGE_TIMEOUT_SECONDS`, `KEPLURA_OCR_TOTAL_TIMEOUT_SECONDS`.
- Tests: `PdfOcrTest` (simulierte Erkennung: Scan, gemischt, Limits, Abschaltung)
  plus ein Test mit ECHTEM Tesseract, der nur läuft, wo `tesseract --list-langs`
  `deu` und `eng` meldet.
- Bewusst nicht: Handschrift, Tabellen-/Layoutrekonstruktion, Bilddateien
  (PNG/JPG/TIFF) als Upload-Typ, Cloud-OCR. Bereits vorhandene
  `NO_TEXT_LAYER`-Dokumente werden erst beim nächsten Neu-Indexieren erkannt.

## Modell-Provider: Ollama, vLLM, gehostete API (M4c)

PO-Entscheidung 06.09.: Alle drei Model-Backends sind first-class
konfigurierbar — A) **Ollama** (lokaler Rechner, CPU reicht für kleine
Modelle), B) **vLLM** (GPU-Server), C) **gehostete OpenAI-kompatible API**.
Alle drei sprechen dasselbe OpenAI chat-completions-Protokoll (Ollama über
dessen eigenen `/v1`-Kompatibilitäts-Endpoint), deshalb ist die Wahl des
Backends **reine Konfiguration, kein Code-Fork**: ein Preset ist ein Bundle
aus Defaults, das die rohe `KEPLURA_GATEWAY_*`-ENV pro Wert übersteuert.

Umschalten = eine ENV-Variable auf dem **Server-Prozess** ändern und neu
starten. Der Desktop sieht davon nichts außer einem Wort: `GET
/api/v1/system/info` liefert `model` aus der geschlossenen Menge
`none|local|configured` (UI: „kein Modell konfiguriert“ / „lokal“ /
„konfiguriert“). Basis-URL, Key und Modell-ID verlassen den Server NIE —
Keys leben ausschließlich serverseitig (ADR/M4a-Regel: desktop is untrusted).

| Preset (`KEPLURA_PROVIDER`) | Default-Base-URL | Key nötig? | Default-Modell (`KEPLURA_CHAT_MODEL` wenn leer) | Auth-Header |
|---|---|---|---|---|
| `ollama` | `http://127.0.0.1:11434/v1` | nein | `qwen2.5:0.5b` | keiner (auch bei gesetztem Key nicht) |
| `vllm` | `http://127.0.0.1:8000/v1` | nein | `default` | keiner (auch bei gesetztem Key nicht) |
| `api` | `https://api.openai.com/v1` | **ja** (Warnung im Boot-Log wenn leer) | `gpt-4o-mini` | `Authorization: Bearer …` |
| (leer) | keine — wie M4a: nur rohe `KEPLURA_GATEWAY_*` | per `KEPLURA_GATEWAY_API_KEY` | `local-chat-dev` | Bearer wenn Key gesetzt |

Regeln, die der Code erzwingt (testgeprüft in `ProviderConfigurationTest`,
`GatewayProviderAuthTest`, `GatewayProviderPresetOllamaTest`,
`GatewayProviderPresetApiTest`):

- **Unbekannter Preset-Name ⇒ Boot-Fehler** (`unknown model provider`),
  niemals stiller Fallback auf ein anderes Backend — Tippfehler dürfen den
  Traffic nicht heimlich zu einer Überraschungs-Upstream zeigen.
- **Lokale Presets senden nie einen Authorization-Header**, selbst wenn ein
  Key konfiguriert ist: ein Umschalten von `api` auf eine LAN-Ollama-Box
  darf den Hosted-Key nicht in fremde Access-Logs schmuggeln.
- Rohe ENV gewinnt: `KEPLURA_GATEWAY_BASE_URL` (und die übrigen
  Gateway-Vars) übersteuern die Preset-Defaults pro Wert; der Preset
  bestimmt nur noch Startpunkte und die Auth-Form.
- Ollama erhält chat-seitig automatisch `max_tokens=1024` (der Validator-
  Clamp), wenn `KEPLURA_CHAT_MAX_TOKENS` leer ist — Ollamas Kompat-Layer
  hat keinen Längen-Default, ohne Kap würde ein Tiny-Model bis zum Stream-
  Deadline plappern können. vLLM/API: Feld wird weggelassen, Backend
  entscheidet.

Beispiel-Ollama-Start (CPU, kein GPU; Compose-Profile sind opt-in):

```bash
cd deploy && cp .env.example .env
docker compose --profile ollama up -d ollama
docker compose exec ollama ollama pull qwen2.5:0.5b   # ~400 MB, passt in 4 GB RAM
KEPLURA_PROVIDER=ollama SPRING_PROFILES_ACTIVE=prod \
  java -jar server/target/keplura-server-*.jar
# Desktop-Handoff-Seite zeigt dann model=local; Chat streamed wie gehabt (SSE).
```

vLLM (GPU-Box ist PO-seitig — kein Repo-Deliverable): dasselbe Muster mit
`--profile vllm up -d vllm` und `KEPLURA_PROVIDER=vllm`; für eine ferne
GPU-Box zusätzlich `KEPLURA_GATEWAY_BASE_URL=http://gpu.internal:8000/v1`.
Hosted API: kein Container nötig — nur `KEPLURA_PROVIDER=api` und der Key
über die Variable `KEPLURA_GATEWAY_API_KEY` (ENV-only, nie in Dateien;
Beispielwerte in `.env.example` sind reine Platzhalter). Der Einsatz eines
echten Keys ist PO-Entscheidung; die Repo-Tests rufen NIEMALS eine
bezahlte API auf (hermetische Mocks, M4a-Muster).

Was gleich bleibt, egal welcher Preset dialt: SSE-Streaming, Session- und
Org-Gates, Body-/Antwort-Kappen, Watchdogs, die Leak-Disziplin (Upstream-
Fehler werden auf generische Meldungen gemappt, Key/URL/Host erscheinen in
keinem Response, Log oder Header).

## Local CI gate (verbindlich) — `scripts/ci-local.sh`

> **Umgebung (seit 16.11.2026, siehe AGENTS.md „Lean verification“):** als
> **Nicht-root**-Benutzer ausführen und das Paket `acl` (`setfacl`) installieren.
> Der Rust-Rechteleser und `LocalSourceConnectorTest` prüfen echte Dateirechte;
> root umgeht diese und lässt die Tests erwartbar scheitern. Einmal pro PR am Ende
> laufen lassen, nicht nach jedem konfliktfreien Angleichen an `main`.
> Beispiel mit dem lokalen CI-Image (Windows-Host, Docker Desktop):
>
> ```bash
> docker run --rm -v "$PWD:/w" -v keplura-ci-nonroot-m2:/home/ci/.m2 >   -v keplura-ci-nonroot-cargo:/home/ci/.cargo -w /w keplura-ci:local bash -c '
>   apt-get update -qq && apt-get install -y -qq acl >/dev/null
>   useradd -m -u 1500 ci; chmod 755 /root; chmod -R a+rX /root/.cargo /root/.rustup
>   chown -R ci:ci /w /home/ci
>   runuser -u ci -- env HOME=/home/ci CARGO_HOME=/home/ci/.cargo RUSTUP_HOME=/root/.rustup >     PATH=/root/.cargo/bin:$PATH bash scripts/ci-local.sh'
> ```

PO decision 2026-09-06: the BINDING CI gate is this reproducible local run on
our own infrastructure — GitHub Actions is optional supplementary evidence only
(its billing outages never block a task, merge or escalation). Every PR must
reference a green evidence run of this script against its head SHA.

```bash
bash scripts/ci-local.sh                 # stages 1–3 (server, desktop, tauri)
bash scripts/ci-local.sh --suite auth    # + selected e2e suite(s), repeatable
bash scripts/ci-local.sh --with-e2e      # everything incl. all e2e suites
bash scripts/ci-local-e2e-gate.sh        # --with-e2e + inotify-exhaustion shim
```

`ci-local-e2e-gate.sh` is the same binding gate with one host-workaround: if
the kernel's per-UID `fs.inotify.max_user_instances` budget is exhausted on
the shared host (rootless nested containers leak watchers host-wide; the
tauri CLI then panics unwrapping a dead-code build-mode file watcher), it
preloads `ci/shims/no-inotify.c` — an LD_PRELOAD shim for the glibc inotify
wrappers ONLY (dev-gate infrastructure; node/Chromium use raw syscalls and
stay untouched, no product code path uses it). On a healthy host the probe
passes and it execs `ci-local.sh --with-e2e` unmodified.

Stages: **[1/4] server** — `mvn package -DskipTests` once, then deterministic
chunked Surefire over the FULL test-class manifest (`target/test-classes` →
sorted `*Test` list, fixed chunk size 12, `-DforkCount=1`, capped fork heap/
metaspace). **Why chunked:** this container runs on a 2-CPU / 2048-pid host
(cgroup thread headroom ~256) — ~50 Spring-Boot/Tomcat test classes in one
surefire JVM exhaust it (M4c diagnosis). Chunking with forkCount=1 is the
documented, repeatable fix baked into the gate itself (identical code →
identical split; no human ad-hoc, no silent retries). **[2/4] desktop** —
`npm ci` + `npm run build` (vue-tsc typecheck included). **[3/4] tauri** —
`cargo check --all-targets` (Linux). **[4/4] e2e** — wrappers around the
existing `desktop/e2e` harnesses (no test refactoring): `knowledge`
(knowledge_flow_e2e.cjs), `chat` (chat_flow_e2e.cjs), `invite`
(invite_flow_e2e.cjs — SEC-2/CSRF journey), `admin`
(admin_flow_e2e.cjs — ADMIN-KI panel journey), `auth`/`packaged`
(packaged_login_e2e.py against the real Tauri binary under Xvfb). Default:
no e2e (expensive); `--suite`/`--with-e2e` opt in. Prereqs for e2e:
`CI_E2E_EMAIL`/`CI_E2E_PASSWORD` env, playwright-core under `NODE_PATH`
(scratch dir, see `desktop/e2e/README.md`), ports 1420/8080 free.

Exit codes (part of the contract):

| code | meaning |
|---|---|
| 0 | every selected stage passed — the gate is green |
| 1 | REAL test/build failure (hard; never tolerated, never retried) |
| 2 | gate integrity: manifest mismatch (a class missing/double-run) or config/usage error |
| 3 | resource/infrastructure failure (native-thread ceiling, cgroup pids, OOM, timeouts) — distinct from 1 so infra pain never masks or fakes product results |

Evidence: every run lands in `ci/evidence/<YYYYmmdd-HHMMSS>/` (`ci-local.log`,
`MATRIX.md` result table, `results.json`, per-chunk mvn logs, per-suite e2e
logs + screenshots; `ci/evidence/latest` symlink). Commit or reference the run
directory and paste the MATRIX.md table into the PR body; that reference IS the
CI evidence from now on.

Cross-platform release gate: Windows/macOS are NOT per-PR requirements —
full Win/mac builds/tests run before Beta/Release (unchanged from the earlier
policy; see AGENTS.md).

## Repo layout

```
desktop/    Tauri 2 + Vue 3 + TypeScript desktop app
server/     Spring Boot 4.1.x API (Java 21, Maven)
contracts/  openapi.yaml — the only shared surface between the products
deploy/     local infrastructure (docker-compose: PostgreSQL 16 + pgvector)
docs/       this guide
```

## Security-Review-Fixes (16.11.2026)

Der separate Branch `feat/security-fixes` adressiert S-1–S-7 des Reviews von
`main@530a3a5` (Spec `docs/tasks/SEC-REVIEW-FIXES.md`). Lokaler/LDAP- und OIDC-Login
verwenden denselben Sessionaufbau. Die Pflicht zum Passwortwechsel wird zusätzlich
bei jedem schreibenden API-Aufruf aus dem aktuellen Konto gelesen. Die Oberfläche
stellt eine Session nur mit bestätigtem `/auth/me`-Profil wieder her.

Für die lokale Rechte-Spiegelung ist der kleine Linux-Helfer
`keplura-local-reader` erforderlich (`server/native/local-reader`, Rust). Das
Server-Image baut ihn in einer separaten Rust-Stufe und installiert ihn root-owned
unter `/usr/local/bin`. Er führt keine Shell und keine Netzwerkzugriffe aus.
`libc` ist auf dieselbe Version wie im vorhandenen Desktop-Lockfile gepinnt.
Für lokale Maven-Läufe vorher `cargo build --manifest-path
server/native/local-reader/Cargo.toml --target-dir server/target/native-reader --locked`
ausführen und `KEPLURA_LOCAL_READER` auf die erzeugte Debug-Binärdatei setzen.
`scripts/ci-local.sh` baut und testet den Helfer selbst vor den Java-Tests. Das
Paket **acl** bleibt für Diagnose und die echten `setfacl`-Regressionen verfügbar.

Der Helfer traversiert jeden Pfadteil mit `openat`/`NOFOLLOW`, hält Datei- und
Verzeichnis-Handles offen und liest Modus, UID/GID, Dateisystemtyp und Access-ACL
von diesen Handles. Vor und nach der Kopie werden Identität, Berechtigungen,
Größe und Änderungsmarker erneut geprüft. Java verwirft die komplette Ausgabe,
wenn der Helfer fehlschlägt, unerwartete Bytes liefert oder sein Zeit-/Größenlimit
überschreitet. Erst ein erfolgreich abgeschlossener Snapshot liefert ACL und
unveränderte temporäre Inhaltskopie an die Indexierung. Damit können getrennte
pfadbasierte ACL- und open-Aufrufe keine unterschiedlichen Dateien mehr kombinieren.

Lokale Spiegelung liest pro Abgleich jede Datei zur SHA-256-Prüfung; gleiche Inhalte
werden nicht erneut geparst/eingebettet. Listing-Größe/mtime allein überspringen
keinen Snapshot. Ein Fehler bei der Snapshotaufnahme sperrt auch eine ältere breite
ACL der betroffenen Datei. Ein bereits vorher scheiterndes Verbinden oder Auflisten
behält die bisherige Offline-/Abgleichpolitik; dies ist keine allgemeine sofortige
Rechte-Revokation bei Quellenausfall.

Erweiterte ACLs oder ein fehlender Helfer führen zur Verweigerung. Unterstützt sind
lokale Linux-Dateisysteme ext2/ext3/ext4/xfs/btrfs/tmpfs/overlay; NFS/CIFS/ZFS und
andere nicht sicher auswertbare Typen bleiben hier gesperrt. SMB-Quellen verwenden
weiterhin ihren eigenen Connector. Der Helfer meldet den Grund als Exit-Code
(3 = Dateisystem nicht unterstützt, 4 = erweiterte ACL, sonst 1); der Server zeigt
ihn in Klartext in der Problemliste der Quelle, und der Probelauf im Assistenten
nennt den häufigsten Grund vor dem Aktivieren (PO-Entscheid 16.11.2026: NFS bleibt
vorerst gesperrt, sichere NFSv4-ACL-Auswertung ist eine eigene spätere Aufgabe). **Bereits gespiegelte Dateien erhalten die
korrigierten Rechte erst durch einen erneuten Abgleich**: vor weiterer
Nutzerfreigabe die betroffenen Quellen im Wartungsbetrieb vollständig abgleichen.
Die Codeänderung allein repariert keine Bestands-ACLs.

Interne SearXNG-Ziele benötigen wie MCP eine explizite Betreiberfreigabe über
`KEPLURA_ADMIN_SSRF_ALLOW_CIDRS`. Die Basis-URL darf keine Zugangsdaten,
Queryparameter oder Fragmente enthalten. Eine Suche folgt keinen Redirects.
Das Dokument-Zeichenlimit gilt nach dem OCR-Merge ebenfalls.

MCP-Aktionsplanung sieht ausschließlich den aktuellen direkten Nutzerauftrag,
keine alten Nachrichten, Dateien, RAG-/Webtreffer oder Werkzeugergebnisse. Es gibt
höchstens einen Batch mit bis zu acht Aufrufen. Nach dessen Ausführung darf das
Modell die Ergebnisse und übrigen Belege beantworten, hat aber keine Werkzeuge
mehr. Ein trotzdem zurückgegebener Toolcall wird serverseitig verweigert. Eine
neue Aktion aus einem Ergebnis benötigt einen neuen direkten Nutzerauftrag;
automatische mehrstufige Werkzeugketten entfallen. Werkzeug-/Bereichsfreigaben,
Audit und SSRF-Policy gelten weiterhin. Diese Grenze verhindert automatische
Folgeaktionen aus Fremdinhalt; sie garantiert nicht, dass ein Modell jede direkte
Nutzerabsicht oder eine bösartige Werkzeugbeschreibung richtig interpretiert.

Browser-Regression gegen einen **isolierten localhost-Fixture-Server**:
`desktop/e2e/security_fixes_e2e.cjs`. Benötigt playwright-core im NODE_PATH,
Chromium und SECURITY_FIXES_EMAIL/PASSWORD eines lokalen Bootstrap-Admins.
Standard: Oberfläche localhost:1420, API localhost:18090. Nur der negative
Profil-Ausfall wird simuliert; Login, Reload, Löschen und Passwortwechsel laufen
gegen den echten Server. Keine Zugangsdaten in Protokolle übernehmen.

Nachtrag aus dem unabhängigen Review: Das OCR-Seitenlimit liefert ebenfalls keinen
still gekürzten Erfolg mehr. Überschreitet stdout das Byte-/Zeichenbudget, wird der
Prozess abgebrochen und der Limitfehler durch Prozess-, Seiten- und Dokumentpfad
weitergereicht. Andere OCR-Seitenfehler behalten ihre bisherige Behandlung.
