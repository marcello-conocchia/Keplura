package com.keplura.server.knowledge;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge-base API (M3c) — nested under the space path, which is nested
 * under the org path. The session gate already covers every route here:
 * {@code SessionAuthConfig} protects {@code /api/v1/orgs/**}. The caller id
 * comes from the session principal ONLY; orgId/spaceId/kbId are tenant
 * COORDINATES that are checked, never trusted (uniform 404 for
 * foreign/unknown at every level — see {@link KnowledgeBaseService}).
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/spaces/{spaceId}/knowledge-bases")
public class KnowledgeBaseController {

    /** Public shape of a knowledge base in API responses. */
    public record KnowledgeBaseResponse(Long id, Long spaceId, String name) {
    }

    /** Request body of POST .../knowledge-bases. */
    public record CreateKnowledgeBaseRequest(String name) {
    }

    /** Error body — same generic shape as the org/auth/space endpoints. */
    public record ErrorResponse(String error) {
    }

    private final KnowledgeBaseService kbService;

    public KnowledgeBaseController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @RequestBody(required = false) CreateKnowledgeBaseRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));
        }
        try {
            KnowledgeBase kb = kbService.create(principal.userId(), orgId, spaceId, request.name());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new KnowledgeBaseResponse(kb.getId(), kb.getSpaceId(), kb.getName()));
        } catch (KnowledgeBaseService.InvalidKbNameException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is invalid"));
        } catch (KnowledgeBaseService.DuplicateKbNameException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("name already taken in this space"));
        } catch (KnowledgeBaseService.KnowledgeBaseNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Knowledge bases of one space — visible only to members of the owning
     * org. Any denial at any level (foreign org, foreign space, space under
     * the wrong org) answers the same empty 404 as a never-existing id.
     */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable("orgId") Long orgId,
                                  @PathVariable("spaceId") Long spaceId,
                                  HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            return ResponseEntity.ok(kbService.list(principal.userId(), orgId, spaceId).stream()
                    .map(kb -> new KnowledgeBaseResponse(kb.getId(), kb.getSpaceId(), kb.getName()))
                    .toList());
        } catch (KnowledgeBaseService.KnowledgeBaseNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Single kb by id under its space path — the M3c IDOR probe target:
     * foreign kb, wrong space/org path and unknown id all answer the
     * identical 404, so this endpoint cannot enumerate kb ids.
     */
    @GetMapping("/{kbId}")
    public ResponseEntity<?> getOne(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @PathVariable("kbId") Long kbId,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            KnowledgeBase kb = kbService.get(principal.userId(), orgId, spaceId, kbId);
            return ResponseEntity.ok(new KnowledgeBaseResponse(kb.getId(), kb.getSpaceId(), kb.getName()));
        } catch (KnowledgeBaseService.KnowledgeBaseNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Session principal for a protected request — identical guard as in
     * {@link com.keplura.server.space.SpaceController}: the interceptor 401s
     * unauthenticated traffic before this runs, so the IllegalStateException
     * is a wiring-consistency guard, never wire-reachable.
     */
    private AuthPrincipal currentPrincipal(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (principal instanceof AuthPrincipal p) {
            return p;
        }
        throw new IllegalStateException("protected request reached controller without session principal");
    }

    /** PATCH {name} — rename (org OWNER): 200 / 400 / 403 / 404 / 409. */
    @PatchMapping("/{kbId}")
    public ResponseEntity<?> rename(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @PathVariable("kbId") Long kbId,
                                    @RequestBody(required = false) CreateKnowledgeBaseRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));
        }
        try {
            KnowledgeBase kb = kbService.rename(principal.userId(), orgId, spaceId, kbId, request.name());
            return ResponseEntity.ok(new KnowledgeBaseResponse(kb.getId(), kb.getSpaceId(), kb.getName()));
        } catch (KnowledgeBaseService.InvalidKbNameException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is invalid"));
        } catch (KnowledgeBaseService.DuplicateKbNameException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("a knowledge base with this name already exists in the space"));
        } catch (KnowledgeBaseService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("owner role required"));
        } catch (KnowledgeBaseService.KnowledgeBaseNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** DELETE — kb with all documents (org OWNER): 204 / 403 / 404. */
    @DeleteMapping("/{kbId}")
    public ResponseEntity<?> delete(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @PathVariable("kbId") Long kbId,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            kbService.delete(principal.userId(), orgId, spaceId, kbId);
            return ResponseEntity.noContent().build();
        } catch (KnowledgeBaseService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("owner role required"));
        } catch (KnowledgeBaseService.KnowledgeBaseNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
