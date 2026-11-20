package com.keplura.server.oidc;

/**
 * Stable internal vocabulary for the OIDC login flow (ADR-0009 §3). Like the
 * gateway/admin exceptions, the message is a FIXED generic string — the
 * browser only ever sees {@code #oidc-error}; the code goes to the server
 * log (code names only, no upstream content, no PII).
 *
 * <ul>
 *   <li>{@link #NOT_CONFIGURED} — provider unknown or deactivated → HTTP 404
 *       on begin (uniform for both, anti-enumeration like ADR-0003 login).</li>
 *   <li>{@link #UPSTREAM} — the IdP refused or failed (discovery/token/JWKS
 *       dial, error= callback param) → 503 / generic redirect.</li>
 *   <li>{@link #REFUSED} — a validation refusal (state, nonce, alg, aud,
 *       exp, link) → generic redirect; deliberately indistinguishable from
 *       UPSTREAM in the browser.</li>
 *   <li>{@link #RATE_LIMITED} — begin flood guard tripped → 429 + Retry-After.</li>
 * </ul>
 */
public class OidcException extends RuntimeException {

    public static final String NOT_CONFIGURED = "oidc_not_configured";
    public static final String UPSTREAM = "oidc_upstream_unavailable";
    public static final String REFUSED = "oidc_validation_refused";
    public static final String RATE_LIMITED = "oidc_rate_limited";
    /**
     * The SSRF GUARD (policy) vetoed a dial target — only ever relevant on
     * the ADMIN surface (a saved row can point at a refused network: §8).
     * The BROWSER path treats it exactly like UPSTREAM (uniform #oidc-error
     * / 503); it exists so admin save answers ADMIN-KI's
     * `connection_target_refused` instead of the "unreachable" lie.
     */
    public static final String POLICY_REFUSED = "oidc_target_refused";

    private final String code;

    private OidcException(String code, String genericMessage) {
        super(genericMessage);
        this.code = code;
    }

    public static OidcException notConfigured() {
        return new OidcException(NOT_CONFIGURED, "OIDC provider is not available");
    }

    public static OidcException upstream() {
        return new OidcException(UPSTREAM, "identity provider is not reachable");
    }

    public static OidcException refused() {
        return new OidcException(REFUSED, "OIDC validation failed");
    }

    public static OidcException policyRefused() {
        return new OidcException(POLICY_REFUSED, "identity provider is not reachable");
    }

    public static OidcException rateLimited() {
        return new OidcException(RATE_LIMITED, "too many OIDC login attempts");
    }

    public String code() {
        return code;
    }
}
