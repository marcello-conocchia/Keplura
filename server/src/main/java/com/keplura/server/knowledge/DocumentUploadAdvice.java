package com.keplura.server.knowledge;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Wire-level error shape for failures that happen BEFORE any controller is
 * bound: the servlet multipart parser rejects an oversized body while the
 * DispatcherServlet has no handler yet, so controller-local
 * {@code @ExceptionHandler}s cannot see it — only an advice without selector
 * conditions does (a {@code assignableTypes}-scoped advice is only consulted
 * once a handler type exists, and DefaultHandlerExceptionResolver would
 * otherwise answer the container 413 with an EMPTY body, breaking the API's
 * uniform {@code {"error": ...}} shape).
 *
 * <p>Deliberately narrow by TYPE, not by package: {@link
 * MaxUploadSizeExceededException} is only ever produced by multipart
 * parsing, which only this product surface does. Malformed/missing-part
 * errors happen WITH a bound handler and keep their controller-local
 * 400 handlers.
 */
@RestControllerAdvice
public class DocumentUploadAdvice {

    /** Same generic body as the DocumentController's own rejections. */
    public record ErrorResponse(String error) {
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("document exceeds the upload size limit"));
    }
}
