package com.keplura.server.tool;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * TU-06d: download of a verified tool artifact. Behind Session + CSRF ({@code /api/v1/orgs/**}); only the person who
 * owns the chat, in that organization and chat, gets the bytes — everyone else gets 404. The content type is the one
 * verified by magic bytes, never sniffed; non-images are always attachments; a sandbox CSP keeps even a direct
 * navigation inert.
 */
@RestController
public class ToolArtifactController {

    private final ToolArtifactStore store;

    public ToolArtifactController(ToolArtifactStore store) { this.store = store; }

    @GetMapping("/api/v1/orgs/{orgId}/chat/conversations/{conversationId}/tool-artifacts/{artifactId}")
    public ResponseEntity<byte[]> download(@PathVariable("orgId") long orgId, @PathVariable("conversationId") long conversationId,
                                           @PathVariable("artifactId") String artifactId, HttpServletRequest request) {
        var session = request.getSession(false);
        Object principal = session == null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (!(principal instanceof AuthPrincipal p)) return ResponseEntity.status(401).build();
        var found = store.owned(artifactId, p.userId(), orgId, conversationId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        var a = found.get();
        boolean image = ArtifactInspector.IMAGES.contains(a.mediaType());
        ContentDisposition disposition = (image ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(a.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .contentLength(a.data().length)
                .body(a.data());
    }
}
