package com.keplura.server.chat;

import org.springframework.http.HttpStatus;

/**
 * Every user-visible chat failure travels as exactly this one exception type,
 * carrying only an HTTP status and a GENERIC message the advice may echo —
 * the {@link com.keplura.server.gateway.GatewayException} discipline
 * applied to the chat surface: no field can hold an upstream body, an
 * upstream URL, or the API key (leak-proofing is the type design), and no
 * cause chain is attached because JDK IO messages embed host:port.
 *
 * <p>Status mapping: 400 prompt/body invalid, 404 uniform (conversation,
 * space or kb invisible to this caller — foreign vs unknown indistinguishable,
 * ADR-0004), 503 gateway not configured, 502 upstream/embedding failure.
 */
public class ChatException extends RuntimeException {

    private final HttpStatus status;

    public ChatException(HttpStatus status, String genericMessage) {
        super(genericMessage);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
