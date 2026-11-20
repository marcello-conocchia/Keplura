package com.keplura.server.org;

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

import java.util.List;

/**
 * Organization API (M2c). Every route here is behind the session gate
 * ({@code SessionAuthConfig} protects {@code /api/v1/orgs/**}); the caller id
 * is taken from the session principal ONLY, never from request data. Reads
 * are membership-filtered so foreign orgs are indistinguishable from
 * non-existent ones (uniform 404) — see docs/adr/0004.
 */
@RestController
@RequestMapping("/api/v1/orgs")
public class OrgController {

    /** Public shape of an organization in API responses. */
    public record OrganizationResponse(Long id, String name, String role) {
    }

    /** Request body of POST /api/v1/orgs. */
    public record CreateOrgRequest(String name) {
    }

    /** Error body — same generic shape as the auth endpoints. */
    public record ErrorResponse(String error) {
    }

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) CreateOrgRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));
        }
        try {
            Organization org = orgService.createOrg(principal.userId(), request.name());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new OrganizationResponse(org.getId(), org.getName(),
                            MembershipRole.OWNER.name()));
        } catch (OrgService.InvalidOrgNameException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is invalid"));
        }
    }

    @GetMapping
    public List<OrganizationResponse> listMine(HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        return orgService.listMyOrgs(principal.userId()).stream()
                .map(view -> new OrganizationResponse(view.organization().getId(),
                        view.organization().getName(), view.role().name()))
                .toList();
    }

    /**
     * Single org by id — visible only to members. Foreign id and unknown id
     * both answer the identical 404 so this endpoint cannot enumerate org ids
     * (the AC-2 IDOR probe).
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable("id") Long id, HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            OrgService.OrgMembership view = orgService.getMyOrg(principal.userId(), id);
            return ResponseEntity.ok(new OrganizationResponse(view.organization().getId(),
                    view.organization().getName(), view.role().name()));
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * SEC-2 roster: every member of the org with display name and role —
     * OWNER only. Same existence-then-privilege order as the invite surfaces
     * (404 uniform for foreign/unknown org, 403 for a non-OWNER member).
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<?> members(@PathVariable("id") Long id, HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            return ResponseEntity.ok(orgService.listMembers(principal.userId(), id));
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (OrgService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("owner role required"));
        }
    }

    /** PATCH {name} — rename (OWNER): 200 / 400 / 403 / 404. */
    @PatchMapping("/{id}")
    public ResponseEntity<?> rename(@PathVariable("id") Long id,
                                    @org.springframework.web.bind.annotation.RequestBody(required = false) CreateOrgRequest request,
                                    HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));
        }
        try {
            Organization org = orgService.renameOrg(principal.userId(), id, request.name());
            return ResponseEntity.ok(new OrganizationResponse(org.getId(), org.getName(), MembershipRole.OWNER.name()));
        } catch (OrgService.InvalidOrgNameException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is invalid"));
        } catch (OrgService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("owner role required"));
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** DELETE — org with all spaces, knowledge bases, documents, chats, invites (OWNER): 204 / 403 / 404. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long id, HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            orgService.deleteOrg(principal.userId(), id);
            return ResponseEntity.noContent().build();
        } catch (OrgService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("owner role required"));
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private MemberProvisioningService memberProvisioning;

    @org.springframework.beans.factory.annotation.Autowired
    void setMemberProvisioning(MemberProvisioningService memberProvisioning) {
        this.memberProvisioning = memberProvisioning;
    }

    /** PILOT 4e: body {email, displayName}. */
    public record AddMemberRequest(String email, String displayName) {
    }

    public record AddMemberResponse(Long userId, String displayName, boolean createdAccount, String startPassword) {
    }

    /**
     * PILOT 4e — OWNER nimmt ein Mitglied direkt auf: 201 (neues Konto, Startpasswort
     * einmalig in der Antwort) / 200 (bestehendes Konto) / 400 / 403 / 404 / 409.
     * Antwort ist no-store, damit das Startpasswort nicht im Cache landet.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable("id") Long id,
                                       @org.springframework.web.bind.annotation.RequestBody(required = false) AddMemberRequest request,
                                       HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        if (request == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("email is required"));
        }
        try {
            var p = memberProvisioning.addMember(principal.userId(), id, request.email(), request.displayName());
            return ResponseEntity.status(p.createdAccount() ? HttpStatus.CREATED : HttpStatus.OK)
                    .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new AddMemberResponse(p.userId(), p.displayName(), p.createdAccount(), p.startPassword()));
        } catch (MemberProvisioningService.InvalidMemberException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("email or name is invalid"));
        } catch (MemberProvisioningService.AlreadyMemberException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("already a member"));
        } catch (OrgService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("owner role required"));
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** DELETE member — OWNER removes anyone, a member may leave; last OWNER → 409. */
    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<?> removeMember(@PathVariable("id") Long id,
                                          @PathVariable("userId") Long userId,
                                          HttpServletRequest httpRequest) {
        AuthPrincipal principal = currentPrincipal(httpRequest);
        try {
            orgService.removeMember(principal.userId(), id, userId);
            return ResponseEntity.noContent().build();
        } catch (OrgService.NotOwnerException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("owner role required"));
        } catch (OrgService.LastOwnerException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("an organization needs at least one owner"));
        } catch (OrgService.OrgNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Session principal for a protected request. The interceptor already 401s
     * unauthenticated traffic before this runs; the IllegalStateException is
     * a wiring-consistency guard (interceptor and controller could otherwise
     * diverge silently), never reachable over the wire.
     */
    private AuthPrincipal currentPrincipal(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (principal instanceof AuthPrincipal p) {
            return p;
        }
        throw new IllegalStateException("protected request reached controller without session principal");
    }
}
