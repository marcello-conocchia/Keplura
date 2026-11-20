package com.keplura.server.admin;

import com.keplura.server.admin.AdminProviderService.ProviderCreateRequest;
import com.keplura.server.admin.AdminProviderService.ProviderDto;
import com.keplura.server.admin.AdminProviderService.ProviderPatchRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADMIN-KI Scope B wire surface. Everything under {@code /api/v1/admin/**}
 * is gated twice server-side (session+CSRF via SessionAuthConfig, role via
 * AdminGuardInterceptor) — this class carries NO client-trust logic of its
 * own; an identity guard in the UI would be decoration (the PO rule: server
 * enforcement PFLICHT).
 *
 * <p>There is deliberately no "test an arbitrary URL" endpoint: a probe may
 * only run against a STORED provider row (the create/edit shape validation
 * plus the operator review trail make that the smallest honest surface).
 */
@RestController
@RequestMapping("/api/v1/admin/providers")
public class AdminProviderController {

    private final AdminProviderService service;
    private final AiProviderRepository repository;
    private final ConnectionTester tester;

    public AdminProviderController(AdminProviderService service,
                                   AiProviderRepository repository,
                                   ConnectionTester tester) {
        this.service = service;
        this.repository = repository;
        this.tester = tester;
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
    public ProviderDto update(@PathVariable Long id, @RequestBody ProviderPatchRequest request) {
        return service.update(id, request);
    }

    /**
     * Connection test (Scope B "Verbindung testen" + Scope E SSRF rules):
     * route policy → dial → closed-vocabulary outcome. The response is
     * {@code {reachable, code}} — never a host, port or exception detail.
     */
    @PostMapping("/{id}/test")
    public ConnectionTester.TestOutcome test(@PathVariable Long id) {
        AiProvider provider = repository.findById(id).orElseThrow(AdminException::notFound);
        return tester.test(provider);
    }
}
