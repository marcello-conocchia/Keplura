package com.keplura.server.org;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * OWNER-facing invite management (SEC-2). Every route sits under the
 * session-gated {@code /api/v1/orgs/**} prefix and re-checks the caller's
 * live OWNER role in the service — the gate proves identity, never privilege.
 *
 * <p>Error vocabulary (contract, docs/development.md):
 * 404 uniform for foreign/unknown org (never distinguishes),
 * 403 for a member who is not OWNER,
 * 201 with the plaintext token ONCE, 200 list (pending only), 204 revoke.
 * The revoke 404 also covers "id belongs to another org" — tenant-pinned by
 * the repository delete (org_id AND id), same shape as every other IDOR
 * defence in this codebase (docs/adr/0004).
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/invites")
public class InviteController {

    /** Public invite shape — deliberately NO token field: the hash never leaves the DB. */
    public record InviteResponse(Long id, Long orgId, String role,
                                 String expiresAt, String createdAt, String inviterName) {
    }

    /** Error body — same generic shape as the auth/org endpoints. */
    public record ErrorResponse(String error) {
    }

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable("orgId") Long orgId, HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            InviteService.CreatedInvite created = inviteService.create(principal.userId(), orgId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "invite", toResponse(created.invite(),
                            // the inviter is the caller — no second read needed
                            principal.displayName()),
                    "token", created.token()));
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (OrgService.NotOwnerException e) {
            return forbidden();
        }
    }

    @GetMapping
    public ResponseEntity<?> listPending(@PathVariable("orgId") Long orgId, HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            List<InviteResponse> body = inviteService.listPending(principal.userId(), orgId).stream()
                    .map(v -> toResponse(v.invite(), v.inviterName()))
                    .toList();
            return ResponseEntity.ok(body);
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (OrgService.NotOwnerException e) {
            return forbidden();
        }
    }

    @DeleteMapping("/{inviteId}")
    public ResponseEntity<?> revoke(@PathVariable("orgId") Long orgId,
                                    @PathVariable("inviteId") Long inviteId,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            if (!inviteService.revoke(principal.userId(), orgId, inviteId)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.noContent().build();
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (OrgService.NotOwnerException e) {
            return forbidden();
        }
    }

    private static InviteResponse toResponse(OrgInvite invite, String inviterName) {
        return new InviteResponse(invite.getId(), invite.getOrgId(),
                invite.getRole().name(),
                invite.getExpiresAt().toString(), invite.getCreatedAt().toString(),
                inviterName);
    }

    private static ResponseEntity<ErrorResponse> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("owner role required"));
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
