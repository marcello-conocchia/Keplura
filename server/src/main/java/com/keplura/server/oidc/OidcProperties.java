package com.keplura.server.oidc;

/**
 * Resolved, boot-validated OIDC config (ADR-0009 §7). Values are operational,
 * never secrets: the desktop handoff target, the optional fixed public origin
 * for self-URL derivation, and the two lifetime caps (login binding / handoff
 * code) already clamped to sane ranges.
 *
 * @param desktopRedirectUri validated absolute URI, no query/fragment
 * @param publicBaseUrl      fixed server origin for redirect_uri derivation
 *                           ("" = derive from the incoming request)
 * @param bindingTtlSeconds  max age of a begin→callback session binding
 * @param handoffTtlSeconds  max age of an unconsumed one-time handoff code
 */
public record OidcProperties(String desktopRedirectUri, String publicBaseUrl,
                             long bindingTtlSeconds, long handoffTtlSeconds) {
}
