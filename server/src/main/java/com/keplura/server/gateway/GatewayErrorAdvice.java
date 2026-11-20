package com.keplura.server.gateway;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.regex.Pattern;

/**
 * Central error mapping for the M4a gateway (scoped to {@link GatewayController}
 * so the other controllers keep their own handling). EVERY gateway failure
 * leaves as a generic {@code {"error": "..."}} JSON with a fixed status —
 * never an upstream body, never upstream headers, never a stack trace. The
 * messages are constants chosen at the raise sites, all leak-free by review;
 * {@link GatewayException} cannot carry upstream content at all.
 */
@RestControllerAdvice(assignableTypes = GatewayController.class)
public class GatewayErrorAdvice {

    /** Upstream Retry-After is echoed ONLY in this sanitised numeric form. */
    private static final Pattern SECONDS_ONLY = Pattern.compile("\\d{1,5}");

    public record ErrorResponse(String error) {
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> gatewayFailure(GatewayException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status())
                .contentType(MediaType.APPLICATION_JSON);
        String retryAfter = e.retryAfterSeconds();
        if (retryAfter != null && SECONDS_ONLY.matcher(retryAfter).matches()) {
            builder = builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(new ErrorResponse(e.getMessage()));
    }

    /** Anything unexpected: honest 500 with a generic body; internals stay in the JVM. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        // class NAME only — messages of foreign exceptions could echo upstream
        // details, so they stay inside; the stack trace never leaves the JVM.
        org.slf4j.LoggerFactory.getLogger(GatewayErrorAdvice.class)
                .warn("gateway request failed: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("model gateway failure"));
    }
}
