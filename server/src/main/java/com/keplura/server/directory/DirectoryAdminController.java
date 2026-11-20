package com.keplura.server.directory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADR-0012 §5 — Verzeichnis-Anbindungen in der Administration. Liegt unter
 * {@code /api/v1/admin/**}: Session + CSRF + globale ADMIN-Rolle (ADR-0008).
 * Das Dienstkonto-Passwort ist write-only (nie in einer Antwort).
 */
@RestController
@RequestMapping("/api/v1/admin/directories")
public class DirectoryAdminController {

    public record ErrorResponse(String error) {
    }

    public record LookupRequest(String login) {
    }

    private final DirectoryService service;

    public DirectoryAdminController(DirectoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<DirectoryService.ConnectionView> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<DirectoryService.ConnectionView> create(@RequestBody DirectoryService.ConnectionInput in) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(in));
    }

    @PatchMapping("/{id}")
    public DirectoryService.ConnectionView update(@PathVariable("id") Long id,
                                                  @RequestBody DirectoryService.ConnectionInput in) {
        return service.update(id, in);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public DirectoryService.TestResult test(@PathVariable("id") Long id) {
        return service.test(id);
    }

    /** "Benutzer nachschlagen": zeigt aufgelöste Gruppen/Prinzipale (ohne Passwort des Benutzers). */
    @PostMapping("/{id}/lookup")
    public DirectoryService.LookupResult lookup(@PathVariable("id") Long id, @RequestBody(required = false) LookupRequest in) {
        return service.lookup(id, in == null ? null : in.login());
    }

    @ExceptionHandler(DirectoryService.DirectoryInputException.class)
    public ResponseEntity<ErrorResponse> invalid(DirectoryService.DirectoryInputException e) {
        if ("not-found".equals(e.getMessage())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not found"));
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }
}
