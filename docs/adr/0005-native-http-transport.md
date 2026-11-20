# ADR-0005: Native HTTP transport for the packaged desktop app (no SameSite=None)

Date: 2026-09-04
Status: Accepted (M2-POLISH, PO decision 2026-09-04, Option B)

## Context

ADR-0003 put the desktop session behind an `HttpOnly; SameSite=Lax` cookie.
That works whenever the webview origin and the API origin are same-site —
Vite dev (`http://localhost:1420`) and `tauri dev` against `localhost:8080`
are. The **packaged** app is different: its origin is `tauri://localhost`
(macOS/Linux) or `http://tauri.localhost` (Windows), which the cookie spec's
scheme rule treats as cross-site to `http(s)://server-host`. A browser
`fetch()` from that webview will not attach a SameSite=Lax cookie, so the
login flow would break in the built app. The M2d security gate filed this as
finding F4 with two candidate fixes, and the product owner decided (2026-09-04):

- **Option A — `SameSite=None; Secure`**: rejected. Requires TLS everywhere
  (including dev), and None removes the Lax CSRF mitigation ADR-0003 relies
  on, compounding the already-deferred CSRF guard.
- **Option B — Tauri-native HTTP path**: chosen. `tauri-plugin-http` runs
  requests in Rust (reqwest) outside the webview's cookie rules; the cookie
  stays `HttpOnly; SameSite=Lax` and the cross-site surface disappears.

PO frame binding this implementation: the Vue/Pinia UI stays untouched; ONLY
the transport layer is refactored; no business logic moves to Rust;
`SameSite=Lax` remains; the plugin's Tauri capability must restrict reachable
URLs to the configured Keplura server; the session cookie must never be
visible to JS. If safe cookie handling in the native client cannot be
guaranteed, the fallback is escalation (PO/security decision), not an
improvised token scheme.

## Decision

1. **`desktop/src/transport.ts` is the single wire-level entry point.**
   Inside a Tauri webview (detected via the injected `__TAURI_INTERNALS__`)
   it delegates to `@tauri-apps/plugin-http`'s drop-in `fetch()`; in a plain
   browser it falls back to `fetch(..., { credentials: "include" })` — the
   documented dev/QA flow from M2d, unchanged. `api.ts` and `auth-store.ts`
   keep their exact outcome-mapping logic; only the call target changed from
   `fetch` to `desktopFetch`. The credential-less connectivity probe
   (`/api/v1/system/info`) deliberately stays on plain fetch.
2. **Cookie persistence is the server's job.** The plugin's cookie jar
   (reqwest + `cookie_store`) only persists **persistent** cookies — a bare
   session cookie (no Max-Age) is kept in memory and lost on quit, which
   would silently break restart-restore. The server therefore sets
   `server.servlet.session.cookie.max-age=8h` (28800s). This is a lifetime
   **cap for the cookie copy, not the session expiry**: the server session
   still dies after 30 minutes of idleness (`server.servlet.session.timeout`
   unchanged), and a stale restored cookie simply 401s the orgs probe back to
   the login form — the same path the webview flow already handles. A
   successful logout destroys the server session; the orphaned client copy is
   inert (401), and the native jar drops it on the next Set-Cookie for the
   same name.
3. **`set-cookie` is stripped from the JS-visible response.** The plugin
   exposes ALL raw response headers through the `Response` it builds
   (documented in its guest layer as "not Fetch-spec compliant"), which would
   leak the HttpOnly session cookie value into webview JS — exactly what
   HttpOnly exists to prevent. `desktopFetch` rebuilds the Response without
   that header; the native jar remains the only cookie holder. `Cookie` and
   `Set-Cookie` header names are also filtered out of caller-supplied request
   headers, and reqwest rejects the forbidden `Cookie` header from JS anyway.
4. **Two walls on the reachable URL set.** The capability
   (`desktop/src-tauri/capabilities/default.json`) grants `http:default` only
   for the dev server origins `http://localhost:8080` /
   `http://127.0.0.1:8080`; the plugin rejects any other URL before the
   network is touched. Independently, `transport.ts` pins every request to
   the build-time `VITE_SERVER_URL` origin and throws on anything else. A
   deployment pointing the app at a real server must ship a matching
   capability scope (URL patterns are compile-time app config, same as the
   Tauri CORS CSP) — noted below as an operational requirement, not
   something the running app can widen itself.
5. **CORS allow-list completes the packaged origins (finding F2):**
   `DesktopCorsConfig` defaults now include `http://tauri.localhost`
   (Windows) next to `tauri://localhost` (macOS/Linux) and
   `http://localhost:1420` (dev). The native Rust client sends the webview's
   origin as `Origin` (`tauri://localhost` on all packaged platforms), which
   the allow-list echoes. `Retry-After` is added to the login mapping's
   exposed headers so the webview can read the 429 hint on credentialed
   responses (QA finding).

## Consequences

- The packaged app logs in, survives restarts (restore probe), and logs out
  with the cookie staying `HttpOnly; SameSite=Lax`; no TLS requirement is
  added anywhere, and the ADR-0003 CSRF posture is untouched.
- Session state now lives in TWO cookie jars by design: webview (dev/QA
  browser flow) and native Rust jar (Tauri flow). `desktopFetch` is the one
  dispatcher, so a request can never half-use both.
- An 8h-persistent cookie widens the local theft window slightly compared to
  a session cookie (attacker with filesystem read on the user's account can
  replay it, though the server still 401s after 30m idle — the practical
  window is the session timeout). Accepted deliberately as the price of
  restart-restore without SameSite=None; H2 file is dev-only.
- Hardening (M3-HARDEN, security-gate follow-up): tauri-plugin-http 2.6
  persists `.cookies` (plaintext session id) in `app_cache_dir` without ever
  touching file permissions — measured 0644 world-readable on a umask-022
  host. The app now tightens the jar to 0600 (user-only) on unix from its
  `RunEvent` handler (`Ready` / `MainEventsCleared` / after the plugin's
  final `Exit` persist — plugin hooks run before the builder callback, see
  `desktop/src-tauri/src/lib.rs`), so the window is no longer left to the
  runner's umask. Windows: std cannot set NTFS ACLs, so it is a documented
  no-op and confidentiality rests on the default `%LOCALAPPDATA%` ACL
  (user-only); re-verify the filename/behaviour on any plugin version bump
  (`cookie_persistence` + `cookie_jar_permissions` cargo tests).
- Deviations from browser fetch semantics we now own: no HTTP cache (raw
  headers are forwarded, so `Retry-After` reads fine on the native path and
  `Access-Control-Expose-Headers` on the browser path), SSE/streaming
  responses not supported (none used yet — later streaming milestone must
  revisit), and `response.url`/redirect metadata come from reqwest.
- The plugin adds ~reqwest+rustls to the Rust link surface; default features
  (rustls-tls, cookies, http2, charset, system-proxy) are kept.
- Operational: a deployment changing `VITE_SERVER_URL` must ship the matching
  capability URL scope in the build. Mismatch fails closed — the plugin
  rejects out-of-scope URLs and the UI shows the existing offline/error copy.

## Verification (evidence lives in the M2-POLISH PR)

- Server: `DesktopCorsIntegrationTest` (packaged-origin preflights, 429
  expose-header over real HTTP), `AuthApiIntegrationTest` (Set-Cookie flags
  incl. `Max-Age=28800`).
- Desktop: `vue-tsc` + `vite build` green; plugin chunk dynamically imported.
- Rust: `cargo check` + release `cargo build` of the app with the plugin.
- Packaged E2E on Linux/Xvfb (real `tauri build` deb binary driving the real
  webview against a real server): login → orgs → restart → restore → logout →
  stale cookie → server unreachable. Windows/macOS: CI runner coverage is
  build-level only; documented as such.

## Amendment 2026-11-18 — explicit remember-me choice

PO requested the login checkbox and removal of the 30-minute inactivity logout.
See [AUTH-REMEMBER](../tasks/AUTH-REMEMBER.md): authenticated sessions now have
a configurable absolute lifetime (default 30 days). The cookie is non-persistent
by default, persistent only with `rememberMe=true`. In-memory server sessions
still end on server restart; this supersedes the earlier 8-hour cookie cap and
30-minute authenticated idle timeout described above.
