package com.keplura.server.admin;

/**
 * Stable error vocabulary for the ADMIN-KI surface (docs/adr/0008 §6/§7).
 * Like {@link com.keplura.server.gateway.GatewayException}, the type itself
 * cannot carry upstream content: only one of these fixed codes plus a generic
 * message ever leaves the JVM, never an internal host, port or the secret.
 */
public class AdminException extends RuntimeException {

    public static final String INVALID = "admin_input_invalid";
    public static final String NOT_FOUND = "admin_not_found";
    /** Secret write attempted with no KEPLURA_SECRETS_MASTER_KEY configured. */
    public static final String MASTER_KEY_MISSING = "MASTER_KEY_MISSING";
    /** SSRF guard refused the target (scheme, userinfo, literal-IP class, DNS result). */
    public static final String TARGET_REFUSED = "connection_target_refused";
    /** Connection test could not reach the (allowed) target — generic, no diagnostics. */
    public static final String UNREACHABLE = "connection_unreachable";

    private final String code;

    private AdminException(String code, String genericMessage) {
        super(genericMessage);
        this.code = code;
    }

    public static AdminException invalid(String message) {
        return new AdminException(INVALID, message);
    }

    public static AdminException notFound() {
        return new AdminException(NOT_FOUND, "not found");
    }

    public static AdminException masterKeyMissing() {
        return new AdminException(MASTER_KEY_MISSING, "secret storage unavailable: master key not configured");
    }

    public static AdminException targetRefused() {
        // Generic on purpose: the response must not reveal WHICH rule fired or
        // what the host resolved to (recon protection, ADR-0008 §7.4).
        return new AdminException(TARGET_REFUSED, "connection target refused by policy");
    }

    public String code() {
        return code;
    }
}
