package com.keplura.server.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central error mapping for the admin surface (docs/adr/0008 §6 — same
 * posture as {@link com.keplura.server.gateway.GatewayErrorAdvice}):
 * {@link AdminException} carries a fixed code + generic message by
 * construction, so no upstream/internal detail can ride out. The body shape
 * ({@code {"error","code"}}) mirrors the existing gate bodies. Unexpected
 * failures answer a generic 500 — the exception message never leaves.
 */
@RestControllerAdvice(assignableTypes = {AdminProviderController.class, AdminModelController.class,
        AdminSystemController.class,
        // OIDC-M2-Tail: the admin OIDC surface reuses this advice verbatim —
        // same fixed-code vocabulary, same generic-500 posture (ADR-0009 §2).
        com.keplura.server.oidc.OidcAdminController.class})
public class AdminErrorAdvice {

    public record ErrorResponse(String error, String code) {
    }

    @ExceptionHandler(AdminException.class)
    public ResponseEntity<ErrorResponse> adminFailure(AdminException e) {
        HttpStatus status = switch (e.code()) {
            case AdminException.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AdminException.MASTER_KEY_MISSING -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(e.getMessage(), e.code()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        // class NAME only — foreign messages could echo internal URLs/hosts.
        org.slf4j.LoggerFactory.getLogger(AdminErrorAdvice.class)
                .warn("admin request failed: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("admin operation failed", "admin_failure"));
    }
}
