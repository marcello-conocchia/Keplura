package com.keplura.server.template;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.MembershipRole;
import com.keplura.server.space.SpaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Vorlagen / gespeicherte Fragen. Lesen und Anlegen: alle Mitglieder der Organisation.
 * Ändern/Löschen: Ersteller oder Verwalter:in (OWNER). Nicht-Mitglieder: einheitlich 404.
 * Liegt unter {@code /api/v1/orgs/**} (Session + CSRF).
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/templates")
public class PromptTemplateController {

    public record TemplateInput(Long spaceId, String title, String body) {
    }

    public record TemplateView(Long id, Long spaceId, String title, String body, boolean canEdit) {
    }

    public record ErrorResponse(String error) {
    }

    static class Status extends RuntimeException {
        final HttpStatus status;

        Status(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final PromptTemplateRepository repository;
    private final MembershipRepository memberships;
    private final SpaceRepository spaces;

    public PromptTemplateController(PromptTemplateRepository repository, MembershipRepository memberships,
                                    SpaceRepository spaces) {
        this.repository = repository;
        this.memberships = memberships;
        this.spaces = spaces;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<TemplateView> list(@PathVariable("orgId") Long orgId,
                                   @RequestParam(value = "spaceId", required = false) Long spaceId,
                                   HttpServletRequest request) {
        Long user = user(request);
        MembershipRole role = role(orgId, user);
        if (spaceId != null) requireSpace(orgId, spaceId);
        return repository.findVisible(orgId, spaceId).stream().map(t -> view(t, user, role)).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<TemplateView> create(@PathVariable("orgId") Long orgId, @RequestBody TemplateInput in,
                                               HttpServletRequest request) {
        Long user = user(request);
        MembershipRole role = role(orgId, user);
        if (in == null) throw new Status(HttpStatus.BAD_REQUEST, "Angaben fehlen.");
        if (in.spaceId() != null) requireSpace(orgId, in.spaceId());
        PromptTemplate t = repository.saveAndFlush(new PromptTemplate(orgId, in.spaceId(),
                text(in.title(), "Titel", 120), text(in.body(), "Text", 4000), user));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(t, user, role));
    }

    @PatchMapping("/{id}")
    @Transactional
    public TemplateView update(@PathVariable("orgId") Long orgId, @PathVariable("id") Long id,
                               @RequestBody TemplateInput in, HttpServletRequest request) {
        Long user = user(request);
        MembershipRole role = role(orgId, user);
        PromptTemplate t = editable(orgId, id, user, role);
        if (in == null) throw new Status(HttpStatus.BAD_REQUEST, "Angaben fehlen.");
        if (in.spaceId() != null) requireSpace(orgId, in.spaceId());
        t.update(in.title() == null ? t.getTitle() : text(in.title(), "Titel", 120),
                in.body() == null ? t.getBody() : text(in.body(), "Text", 4000), in.spaceId());
        return view(repository.saveAndFlush(t), user, role);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable("orgId") Long orgId, @PathVariable("id") Long id,
                                       HttpServletRequest request) {
        Long user = user(request);
        repository.delete(editable(orgId, id, user, role(orgId, user)));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(Status.class)
    public ResponseEntity<ErrorResponse> status(Status e) {
        return ResponseEntity.status(e.status).body(new ErrorResponse(e.getMessage()));
    }

    // ------------------------------------------------------------------ helpers

    private PromptTemplate editable(Long orgId, Long id, Long user, MembershipRole role) {
        PromptTemplate t = repository.findById(id).filter(x -> x.getOrgId().equals(orgId))
                .orElseThrow(() -> new Status(HttpStatus.NOT_FOUND, "not found"));
        if (!user.equals(t.getCreatedBy()) && role != MembershipRole.OWNER) {
            throw new Status(HttpStatus.FORBIDDEN, "Nur wer die Vorlage angelegt hat oder Verwalter:innen dürfen sie ändern.");
        }
        return t;
    }

    private MembershipRole role(Long orgId, Long user) {
        return memberships.findByOrgIdAndUserId(orgId, user)
                .orElseThrow(() -> new Status(HttpStatus.NOT_FOUND, "not found")).getRole();
    }

    private void requireSpace(Long orgId, Long spaceId) {
        if (spaces.findById(spaceId).filter(s -> s.getOrgId().equals(orgId)).isEmpty()) {
            throw new Status(HttpStatus.NOT_FOUND, "not found");
        }
    }

    private static TemplateView view(PromptTemplate t, Long user, MembershipRole role) {
        return new TemplateView(t.getId(), t.getSpaceId(), t.getTitle(), t.getBody(),
                user.equals(t.getCreatedBy()) || role == MembershipRole.OWNER);
    }

    private static String text(String v, String label, int max) {
        if (v == null || v.isBlank()) throw new Status(HttpStatus.BAD_REQUEST, label + " fehlt.");
        String t = v.strip();
        if (t.length() > max) throw new Status(HttpStatus.BAD_REQUEST, label + " ist zu lang (höchstens " + max + " Zeichen).");
        return t;
    }

    private static Long user(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object p = session == null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (p instanceof AuthPrincipal ap) return ap.userId();
        throw new IllegalStateException("protected request without principal");
    }
}
