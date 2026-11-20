// OIDC-M2-Tail (docs/adr/0009 §10): the desktop side of the second login
// path. Three tiny pieces live here, all on the login screen's critical
// path only — the rest of the app is frozen (UI-freeze, PO sequence 09.09):
//
//   fetchOidcCapabilities()  — does the server have an active corporate IdP?
//   startOidcLogin()         — top-level navigation to /begin (the browser
//                              handles the IdP leg; no fetch, no CORS, no
//                              plugin-http — a navigation is out of scope of
//                              the desktopFetch origin pin by design).
//   consumeOidcHandoff()     — read the #oidc fragment, exchange the one-time
//                              code for a session, strip the fragment.
//
// Why a fragment instead of a query param (ADR-0009 §7): fragments never
// reach any server, never enter access logs, never leak via Referer — the
// handoff code is 256-bit single-use with a 60s TTL even before that.

import { desktopFetch, serverOrigin, setCsrfToken } from "./transport";
import type { AuthUser } from "./api";

export interface OidcCapabilities {
  oidcEnabled: boolean;
  providers: { id: number; name: string }[];
}

/** GET /api/v1/auth/oidc/providers — coarse: enabled or not (button state). */
export async function fetchOidcCapabilities(): Promise<OidcCapabilities> {
  const off: OidcCapabilities = { oidcEnabled: false, providers: [] };
  try {
    const response = await desktopFetch(`${serverOrigin}/api/v1/auth/oidc/providers`, {
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) {
      return off; // any error hides the button — fail closed, no branching
    }
    const body = (await response.json()) as OidcCapabilities;
    return {
      oidcEnabled: body.oidcEnabled === true && (body.providers?.length ?? 0) > 0,
      providers: Array.isArray(body.providers) ? body.providers : [],
    };
  } catch {
    return off;
  }
}

/** Handoff code present in this URL's fragment (App consumes at boot). */
export function readOidcFragment(): string | null {
  const m = /^#oidc=([0-9a-f]{16,128})$/.exec(window.location.hash || "");
  return m ? m[1] : null;
}

/** True when the last OIDC attempt failed server-side (#oidc-error). */
export function readOidcErrorFragment(): boolean {
  return window.location.hash === "#oidc-error";
}

/** Wipe our fragment from the URL bar / history without a reload. */
export function stripOidcFragment(): void {
  if (window.location.hash.startsWith("#oidc")) {
    history.replaceState(null, "", window.location.pathname + window.location.search);
  }
}

/** Navigate the WEBVIEW to the server's begin endpoint (top-level nav).
 *  Returns false only when there is no provider id to navigate to. */
export function startOidcLogin(providerId: number, rememberMe = false): boolean {
  if (!Number.isInteger(providerId) || providerId <= 0) {
    return false;
  }
  // Only the non-sensitive preference crosses the top-level IdP navigation.
  try { sessionStorage.setItem("keplura.oidc.rememberMe", rememberMe ? "1" : "0"); } catch { /* optional */ }
  window.location.assign(`${serverOrigin}/api/v1/auth/oidc/${providerId}/begin`);
  return true;
}

export type OidcExchangeOutcome =
  | { kind: "ok"; user: AuthUser }
  | { kind: "invalid" }
  | { kind: "offline" };

/**
 * POST /api/v1/auth/oidc/exchange — turn the fragment handoff into a real
 * session. Mirrors login() in api.ts: the response's csrfToken is stored
 * BEFORE the outcome is handed out (the transport attaches it to every
 * later write; ADR-0009 §6). Any non-200 is the coarse "invalid": the
 * server distinguishes expired vs unknown deliberately for ITS logs, not
 * for the browser.
 */
export async function exchangeOidcHandoff(handoff: string): Promise<OidcExchangeOutcome> {
  let rememberMe = false;
  try {
    rememberMe = sessionStorage.getItem("keplura.oidc.rememberMe") === "1";
    sessionStorage.removeItem("keplura.oidc.rememberMe");
  } catch { /* unavailable storage defaults to a session cookie */ }
  let response: Response;
  try {
    response = await desktopFetch(`${serverOrigin}/api/v1/auth/oidc/exchange`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ handoff, rememberMe }),
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    return { kind: "offline" };
  }
  if (!response.ok) {
    return { kind: "invalid" };
  }
  try {
    const user = (await response.json()) as AuthUser;
    setCsrfToken(user.csrfToken && user.csrfToken.length > 0 ? user.csrfToken : null);
    return { kind: "ok", user };
  } catch {
    return { kind: "invalid" };
  }
}
