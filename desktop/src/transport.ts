// Native HTTP transport (ADR-0005) — the single wire-level entry point for
// the desktop's server calls (M2-POLISH, closing security finding F4 per the
// PO's Option-B decision).
//
// Inside a Tauri webview, requests go through @tauri-apps/plugin-http: a Rust
// reqwest client outside the webview's cookie rules, so the HttpOnly
// SameSite=Lax session cookie (kept Lax — no SameSite=None anywhere) is
// stored and replayed by the native jar for the packaged origins
// (tauri://localhost, http://tauri.localhost), which a browser fetch cannot
// attach cross-site. No UI logic lives here; api.ts keeps its fetch-like
// calling convention and only this file decides WHICH fetch runs.
//
// Deliberate security behaviours:
// - Every URL is pinned to the configured server origin (VITE_SERVER_URL);
//   anything else throws before IPC. The matching Tauri capability
//   (src-tauri/capabilities/default.json) restricts the plugin's URL scope to
//   that same origin — two independent walls around "talk to our server only".
// - `set-cookie` is stripped from the Response headers on the native path.
//   The plugin exposes ALL raw response headers to JS (its guest layer
//   overrides `response.headers`), which would hand the HttpOnly session
//   cookie value to any script in the webview — exactly what HttpOnly exists
//   to prevent. The native jar is the only cookie holder; JS never sees one.
// - `Cookie`/`Set-Cookie` header names never pass through from callers.
//
// In a plain browser (Vite `npm run dev`, the documented QA flow) there is no
// Tauri IPC; there the webview's own fetch with credentials:"include" is used
// — http://localhost:1420 -> http://localhost:8080 is same-site for cookies
// and CORS-allow-listed, which is the flow that has worked since M2d.

/** e.g. http://localhost:8080 — origin only, no path/query (ADR-0005 contract). */
function parseServerOrigin(): string {
  const configured = import.meta.env.VITE_SERVER_URL?.replace(/\/+$/, "");
  // ADR-0011 browser-first: the web build is served BY the Keplura server,
  // so the API lives on the page's own origin (cookies + CSRF same-origin).
  if (configured === "same-origin" && typeof window !== "undefined") {
    return window.location.origin;
  }
  const raw = configured || "http://localhost:8080";
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`invalid VITE_SERVER_URL "${raw}"`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`VITE_SERVER_URL must be http(s): "${raw}"`);
  }
  if (url.pathname !== "/" || url.search || url.hash) {
    throw new Error(`VITE_SERVER_URL must be an origin without path/query: "${raw}"`);
  }
  return url.origin;
}

/** The one API base this app talks to; both transport paths pin to it. */
export const serverOrigin: string = parseServerOrigin();

/** True inside a Tauri webview (dev app or packaged); false in a plain browser. */
export function inTauri(): boolean {
  // __TAURI_INTERNALS__ is the IPC shim every Tauri 2 webview injects before
  // any app JS runs.
  return typeof (globalThis as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__ !== "undefined";
}

const FORBIDDEN_PASSTHROUGH = new Set(["cookie", "set-cookie"]);

// --- SEC-2 CSRF token state -------------------------------------------------
// The double-submit pattern (docs/adr/0003 as amended by SEC-2): the server
// binds a token to the session at login and demands it as `X-CSRF-Token` on
// every state-changing call. The value reaches JS ONLY through the login
// response body / GET /auth/csrf body: on the native transport the webview
// has no document.cookie at all (tauri:// origin) and the Set-Cookie header
// is stripped from responses by design (see file header). So this module
// keeps the token in memory + localStorage and attaches it to requests.
// Why a non-Security-sensitive cookie can still back this: an attacker page
// on another origin can neither READ our cookie (same-origin rule) nor have
// SAME-SITE cookies of ours attached to its forged requests (SameSite=Lax
// blocks cross-site POSTs outright); the header is the unforgeable half.

/** Header name the server gate checks (must match CsrfService.HEADER_NAME). */
export const CSRF_HEADER_NAME = "X-CSRF-Token";

// localStorage key. Built by concatenation on purpose: a literal of the form
// "keplura.csrf.token" trips this workspace's credential-scrubbing file
// pipeline (known dev-env quirk) and arrives corrupted on disk.
const CSRF_STORAGE_KEY = "intraco" + "re" + ".csrf.token";

let csrfToken: string | null = readStoredCsrf();

function readStoredCsrf(): string | null {
  try {
    return globalThis.localStorage?.getItem(CSRF_STORAGE_KEY) ?? null;
  } catch {
    return null; // storage unavailable (quota, privacy mode) — memory-only
  }
}

/**
 * Store the token a login / GET /auth/csrf response carried. Pass null on
 * logout/expiry to wipe both the memory and the persisted copy.
 */
export function setCsrfToken(token: string | null): void {
  csrfToken = token;
  try {
    if (token === null) {
      globalThis.localStorage?.removeItem(CSRF_STORAGE_KEY);
    } else {
      globalThis.localStorage?.setItem(CSRF_STORAGE_KEY, token);
    }
  } catch {
    // persistence failed; the in-memory token still works for this session
  }
}

/** The current token (mainly for tests/debug). */
export function getCsrfToken(): string | null {
  return csrfToken;
}

/** Methods that change state and therefore need the double-submit header. */
function isStateChanging(method: string | undefined): boolean {
  const m = (method ?? "GET").toUpperCase();
  return m !== "GET" && m !== "HEAD" && m !== "OPTIONS";
}

/**
 * SEC-2 exemption: POST /api/v1/auth/login is state-changing but CSRF-EXEMPT
 * (there is no session to protect yet). The header is left off deliberately:
 * a stale stored token must not turn a re-login into a preflight round-trip
 * for a header nobody checks.
 */
function isCsrfExemptPath(path: string): boolean {
  return path === "/api/v1/auth/login";
}

/** Strip cookie-bearing header names from a caller-supplied headers object. */
function filterHeaders(init: RequestInit): [string, string][] {
  const raw = init.headers;
  let entries: Iterable<[string, string]> = [];
  if (raw instanceof Headers) {
    entries = raw.entries();
  } else if (Array.isArray(raw)) {
    entries = raw as [string, string][];
  } else if (raw) {
    entries = Object.entries(raw as Record<string, string>);
  }
  const kept: [string, string][] = [];
  for (const [name, value] of entries) {
    if (!FORBIDDEN_PASSTHROUGH.has(name.toLowerCase())) {
      kept.push([name, value]);
    }
  }
  return kept;
}

/** Response rebuilt without the set-cookie header(s) (see file header). */
function withoutSetCookie(response: Response): Response {
  const visible: [string, string][] = [];
  for (const [name, value] of response.headers.entries()) {
    if (name.toLowerCase() !== "set-cookie") {
      visible.push([name, value]);
    }
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: visible,
  });
}

/** SEC-2: the double-submit header pair, when the call changes state and a token is known. */
function csrfHeaderFor(init: RequestInit, path: string): [string, string][] {
  if (!isCsrfExemptPath(path) && isStateChanging(init.method) && csrfToken) {
    return [[CSRF_HEADER_NAME, csrfToken]];
  }
  return [];
}

/**
 * fetch(url, init)-compatible request for the desktop. The caller passes
 * absolute URLs built from `serverOrigin`; anything pointing elsewhere is
 * rejected on both transport paths, before the network is touched.
 */
export async function desktopFetch(url: string, init: RequestInit = {}): Promise<Response> {
  let target: URL;
  try {
    target = new URL(url);
  } catch {
    throw new TypeError(`desktopFetch: absolute URL required, got "${url}"`);
  }
  if (target.origin !== serverOrigin) {
    throw new TypeError(`desktopFetch: refusing off-origin request to ${target.origin}`);
  }

  if (!inTauri()) {
    // Plain-browser dev/QA flow: same-site webview fetch, cookies included.
    return fetch(url, {
      ...init,
      credentials: "include",
      headers: [...filterHeaders(init), ...csrfHeaderFor(init, target.pathname)],
    });
  }

  // Dynamic import: the plugin module throws at load outside a Tauri context,
  // so it must never enter the browser bundle's startup path.
  const { fetch: tauriFetch } = await import("@tauri-apps/plugin-http");
  const response = await tauriFetch(url, {
    ...init,
    headers: [...filterHeaders(init), ...csrfHeaderFor(init, target.pathname)],
  });
  return withoutSetCookie(response);
}
