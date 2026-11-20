package com.keplura.server.knowledge;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases/{kbId}/documents/{documentId}/preview")
public class DocumentPreviewController {
    private final DocumentPreviewService service;
    public DocumentPreviewController(DocumentPreviewService service) { this.service=service; }

    @GetMapping
    public ResponseEntity<?> preview(@PathVariable("orgId") Long orgId, @PathVariable("spaceId") Long spaceId,
            @PathVariable("kbId") Long kbId, @PathVariable("documentId") Long documentId,
            @RequestParam(value="chunkIdx",defaultValue="0") int chunkIdx,
            @RequestParam(value="startOffset",required=false) Integer startOffset,
            @RequestParam(value="pageNumber",required=false) Integer pageNumber, HttpServletRequest request) {
        var session=request.getSession(false);
        var principal=session==null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (!(principal instanceof AuthPrincipal user)) throw new IllegalStateException("missing session principal");
        try {
            return ResponseEntity.ok().header("Cache-Control","no-store")
                    .header("X-Content-Type-Options","nosniff")
                    .body(service.preview(user.userId(),orgId,spaceId,kbId,documentId,chunkIdx,startOffset,pageNumber));
        } catch (DocumentService.DocumentNotFoundException e) {
            return ResponseEntity.notFound().header("Cache-Control","no-store").build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().header("Cache-Control","no-store")
                    .body(Map.of("error","invalid source position"));
        }
    }
}
