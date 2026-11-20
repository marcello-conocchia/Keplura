package com.keplura.server.chat;

import com.keplura.server.gateway.GatewayException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.regex.Pattern;

/**
 * Central error mapping for the M4b chat surface (scoped to
 * {@link ChatController} so every other controller keeps its own handling) —
 * the {@link com.keplura.server.gateway.GatewayErrorAdvice} precedent:
 * every failure leaves as a generic {@code {"error": "..."}} JSON with a
 * fixed status, messages are constants chosen at the raise sites, and a
 * {@link GatewayException} relayed from the streaming turn keeps its M4a
 * shape (incl. the sanitised numeric Retry-After) so the desktop sees ONE
 * consistent gateway-error contract across raw gateway and chat routes.
 *
 * <p>Like the gateway advice this only ever runs BEFORE the first stream
 * byte: once SSE frames flow, no exception path owns the response anymore.
 */
@RestControllerAdvice(assignableTypes = {ChatController.class, ChatContextController.class})
public class ChatErrorAdvice {

    /** Retry-After passes through ONLY in this sanitised numeric form. */
    private static final Pattern SECONDS_ONLY = Pattern.compile("\\d{1,5}");

    public record ErrorResponse(String error) {
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ErrorResponse> chatFailure(ChatException e) {
        return ResponseEntity.status(e.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(e.getMessage()));
    }

    /** Upstream/gateway failures surfacing through a chat turn: M4a shape as-is. */
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> gatewayFailure(GatewayException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String retryAfter = e.retryAfterSeconds();
        if (retryAfter != null && SECONDS_ONLY.matcher(retryAfter).matches()) {
            builder = builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(new ErrorResponse(e.getMessage()));
    }

    /** Anything unexpected: honest 500 with a generic body; internals stay in the JVM. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        // class NAME only — foreign exception messages can echo upstream details
        org.slf4j.LoggerFactory.getLogger(ChatErrorAdvice.class)
                .warn("chat request failed: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("chat failure"));
    }
}
