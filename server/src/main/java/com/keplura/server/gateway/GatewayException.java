package com.keplura.server.gateway;

import org.springframework.http.HttpStatus;

/**
 * Every gateway failure travels as exactly this one exception type, carrying
 * only the HTTP status and a GENERIC message the controller may echo. By
 * construction there is no field that could hold an upstream body, an upstream
 * URL, or the API key — leak-proofing is the type design, not a scrub later.
 *
 * <p>Status mapping (M4a AC): no session is answered by the interceptor (401);
 * invisible org is 404 (uniform, ADR-0004); malformed/oversized request is
 * 400/413; gateway not configured is 503; upstream rate limit is 429; upstream
 * unreachable or failing (incl. upstream-side auth 401/403) is 502 — the
 * caller cannot fix our server-side key, and echoing 401 would falsely tell a
 * session-holder to log in.
 */
public class GatewayException extends RuntimeException {

    /** Capability refusal only; carries no upstream response content. */
    public static final class ToolsUnsupported extends GatewayException {
        public ToolsUnsupported() {
            super(HttpStatus.BAD_GATEWAY, "Das gewählte Modell unterstützt keine Werkzeuge.");
        }
    }

    private final HttpStatus status;

    /** Numeric seconds only (sanitised before construction); null = no header. */
    private final String retryAfterSeconds;

    public GatewayException(HttpStatus status, String genericMessage) {
        this(status, genericMessage, null);
    }

    public GatewayException(HttpStatus status, String genericMessage, String retryAfterSeconds) {
        super(genericMessage);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
