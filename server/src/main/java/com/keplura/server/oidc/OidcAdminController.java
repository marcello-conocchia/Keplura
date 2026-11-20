package com.keplura.server.oidc;

import com.keplura.server.oidc.OidcAdminService.LinkDto;
import com.keplura.server.oidc.OidcAdminService.ProviderCreateRequest;
import com.keplura.server.oidc.OidcAdminService.ProviderDto;
import com.keplura.server.oidc.OidcAdminService.ProviderPatchRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin wire surface for OIDC config (docs/adr/0009 §2/§6) — lives under
 * {@code /api/v1/admin/**} so the session + CSRF + ADMIN-role interceptor
 * gates (SessionAuthConfig) cover it with zero new wiring, same posture as
 * the AI provider admin API. Errors map through AdminErrorAdvice (extended
 * to this controller): AdminException vocabulary only, generic 500 otherwise.
 *
 * <p>The UI panel for this surface is deliberately NOT part of the login
 * milestone (UI freeze: only the login screen changed, task card); the
 * contract here is what a follow-up admin-UI card builds against, and what
 * the dev/e2e seeding path mirrors.
 */
@RestController
@RequestMapping("/api/v1/admin/oidc/providers")
public class OidcAdminController {

    private final OidcAdminService service;

    public OidcAdminController(OidcAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProviderDto> list() {
        return service.list();
    }

    @PostMapping
    public ProviderDto create(@RequestBody ProviderCreateRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public ProviderDto patch(@PathVariable Long id, @RequestBody ProviderPatchRequest request) {
        return service.update(id, request);
    }

    @GetMapping("/{id}/links")
    public List<LinkDto> links(@PathVariable Long id) {
        return service.listLinks(id);
    }

    /** Explicit admin pairing (subject ↔ existing user). */
    @PutMapping("/{id}/links")
    public LinkDto assign(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return service.assignLink(id, body.get("subject"), body.get("email"));
    }
}
