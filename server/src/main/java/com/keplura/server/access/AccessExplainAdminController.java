package com.keplura.server.access;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Rechte-Nachweis in der Administration. Unter {@code /api/v1/admin/**}:
 * Session + globale ADMIN-Rolle (ADR-0008); nur lesende GET-Aufrufe.
 */
@RestController
@RequestMapping("/api/v1/admin/access")
public class AccessExplainAdminController {

    public record ErrorResponse(String error) {
    }

    static final int MAX_QUERY = 200;

    private final AccessExplainService service;

    public AccessExplainAdminController(AccessExplainService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public List<AccessExplainService.UserHit> users(@RequestParam(value = "q", defaultValue = "") String q) {
        return service.findUsers(limit(q));
    }

    @GetMapping("/documents")
    public List<AccessExplainService.DocumentHit> documents(@RequestParam(value = "q", defaultValue = "") String q) {
        return service.findDocuments(limit(q));
    }

    @GetMapping("/explain")
    public AccessExplainService.Explanation explain(@RequestParam("userId") Long userId,
                                                   @RequestParam("documentId") Long documentId) {
        return service.explain(userId, documentId);
    }

    @GetMapping("/overview")
    public AccessExplainService.Overview overview(@RequestParam("userId") Long userId) {
        return service.overview(userId);
    }

    @ExceptionHandler(AccessExplainService.NotFound.class)
    public ResponseEntity<ErrorResponse> notFound(AccessExplainService.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    private static String limit(String q) {
        String t = q.trim();
        if (t.length() > MAX_QUERY) throw new IllegalArgumentException("query too long");
        return t;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("Suchbegriff ist zu lang."));
    }
}
