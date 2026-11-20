package com.keplura.server.space;

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
 * Space API (M3b) — nested under the organization path. The session gate
 * already covers every route here: {@code SessionAuthConfig} protects
 * {@code /api/v1/orgs/**}, and spaces deliberately live inside that prefix.
 * The caller id comes from the session principal ONLY; the orgId/spaceId path
 * variables are tenant COORDINATES that are checked, never trusted
 * (uniform 404 for foreign/unknown — see {@link SpaceService}).
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/spaces")
public class SpaceController {

    /** Public shape of a space in API responses. */
    public record SpaceResponse(Long id, Long orgId, String name) {
    }

    /** Request body of POST /api/v1/orgs/{orgId}/spaces. */
    public record CreateSpaceRequest(String name) {
    }

    /** Error body — same generic shape as the org/auth endpoints. */
    public record ErrorResponse(String error) {
    }

    private final SpaceService spaceService;

    public SpaceController(SpaceService spaceService) {
        this.spaceService = spaceService;
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable("orgId") Long orgId,
                                    @RequestBody(required = false) CreateSpaceRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));
        }
        try {
            Space space = spaceService.createSpace(principal.userId(), orgId, request.name());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new SpaceResponse(space.getId(), space.getOrgId(), space.getName()));
        } catch (SpaceService.InvalidSpaceNameException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is invalid"));
        } catch (SpaceService.DuplicateSpaceNameException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("name already taken in this organization"));
        } catch (SpaceService.SpaceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Spaces of one organization — visible only to members. A foreign org
     * answers the same 404 as on the single-space route: "not yours" and
     * "not there" stay indistinguishable, so the list endpoint cannot be used
     * to probe which org ids exist either.
     */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable("orgId") Long orgId,
                                  HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            return ResponseEntity.ok(spaceService.listSpaces(principal.userId(), orgId).stream()
                    .map(space -> new SpaceResponse(space.getId(), space.getOrgId(), space.getName()))
                    .toList());
        } catch (SpaceService.SpaceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Single space by id under its org path — visible only to org members.
     * Foreign space, wrong org path and unknown id all answer the identical
     * 404, so this endpoint cannot enumerate space ids (the M3b IDOR probe).
     */
    @GetMapping("/{spaceId}")
    public ResponseEntity<?> getOne(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            Space space = spaceService.getSpace(principal.userId(), orgId, spaceId);
            return ResponseEntity.ok(new SpaceResponse(space.getId(), space.getOrgId(), space.getName()));
        } catch (SpaceService.SpaceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Session principal for a protected request — identical guard as in
     * {@link com.keplura.server.org.OrgController}: the interceptor 401s
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
    @PatchMapping("/{spaceId}")
    public ResponseEntity<?> rename(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    @RequestBody(required = false) CreateSpaceRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));
        }
        try {
            Space s = spaceService.renameSpace(principal.userId(), orgId, spaceId, request.name());
            return ResponseEntity.ok(new SpaceResponse(s.getId(), s.getOrgId(), s.getName()));
        } catch (SpaceService.InvalidSpaceNameException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is invalid"));
        } catch (SpaceService.DuplicateSpaceNameException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("space name already taken in this organization"));
        } catch (SpaceService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("owner role required"));
        } catch (SpaceService.SpaceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** DELETE — space with all knowledge bases, documents and chats (org OWNER): 204 / 403 / 404. */
    @DeleteMapping("/{spaceId}")
    public ResponseEntity<?> delete(@PathVariable("orgId") Long orgId,
                                    @PathVariable("spaceId") Long spaceId,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            spaceService.deleteSpace(principal.userId(), orgId, spaceId);
            return ResponseEntity.noContent().build();
        } catch (SpaceService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("owner role required"));
        } catch (SpaceService.SpaceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
