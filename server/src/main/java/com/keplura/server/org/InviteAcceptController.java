package com.keplura.server.org;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invite redemption (SEC-2): POST /api/v1/invites/accept {token}. Sits on its
 * own prefix (registered in SessionAuthConfig) because the accepting user is
 * by definition NOT yet a member of any org reachable from the org-scoped
 * routes — there is no {orgId} to hang it under without inventing an
 * existence leak (the token would have to name its org in the URL).
 *
 * <p>Identity is the SESSION, never the payload: the token selects the org,
 * the session selects the joining user. A valid token + a member caller is a
 * 409 (the invite survives for its next reader); an unknown, used, revoked
 * or expired token is the identical 404 (uniform, non-probing).
 */
@RestController
@RequestMapping("/api/v1/invites")
public class InviteAcceptController {

    /** Request body of POST /api/v1/invites/accept. */
    public record AcceptRequest(String token) {
    }

    /** Response of a successful accept — the org that was joined. */
    public record AcceptResponse(Long orgId, String orgName) {
    }

    /** Error body — same generic shape as everywhere else. */
    public record ErrorResponse(String error) {
    }

    private final InviteService inviteService;

    public InviteAcceptController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @PostMapping("/accept")
    public ResponseEntity<?> accept(@RequestBody(required = false) AcceptRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request == null || request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("token is required"));
        }
        try {
            Organization org = inviteService.accept(principal.userId(), request.token());
            return ResponseEntity.ok(new AcceptResponse(org.getId(), org.getName()));
        } catch (InviteService.InvalidInviteException e) {
            return ResponseEntity.notFound().build();
        } catch (InviteService.AlreadyMemberException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already a member of this organization"));
        }
    }

    /** Same wiring-consistency guard as OrgController — see there. */
    private AuthPrincipal currentPrincipal(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (principal instanceof AuthPrincipal p) {
            return p;
        }
        throw new IllegalStateException("protected request reached controller without session principal");
    }
}
