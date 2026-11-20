package com.keplura.server.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional dev-profile OIDC seed (same posture as DevAccountSeeder): lets
 * the e2e harness materialize provider + user + link without an admin-UI
 * click-through (the admin panel OIDC tab is a documented follow-up card —
 * the UI freeze allowed the login screen only). Active ONLY under dev AND
 * when the full property trio is present; idempotent (existing provider by
 * name is left untouched). NEVER in prod.
 *
 * <p>Dials discovery on purpose (SSRF-guarded): the loopback test IdP
 * requires the documented allow-list, so the seeder proves the whole policy
 * chain at boot instead of storing a config that could never work.
 */
@Component
@Profile("dev")
@org.springframework.core.annotation.Order(200) // after user seeders (order 100): links need an EXISTING user
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "keplura.dev.seed.oidc", name = {"discovery-url", "subject", "email"})
public class OidcDevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OidcDevSeeder.class);

    private final OidcAdminService adminService;
    private final String discoveryUrl;
    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final String subject;
    private final String email;
    private final String name;

    public OidcDevSeeder(OidcAdminService adminService,
                         @Value("${keplura.dev.seed.oidc.name:Firmenkonto}") String name,
                         @Value("${keplura.dev.seed.oidc.discovery-url}") String discoveryUrl,
                         @Value("${keplura.dev.seed.oidc.issuer:}") String issuer,
                         @Value("${keplura.dev.seed.oidc.client-id:keplura-desktop}") String clientId,
                         @Value("${keplura.dev.seed.oidc.client-secret:}") String clientSecret,
                         @Value("${keplura.dev.seed.oidc.subject}") String subject,
                         @Value("${keplura.dev.seed.oidc.email}") String email) {
        this.adminService = adminService;
        this.name = name;
        this.discoveryUrl = discoveryUrl;
        this.issuer = issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.subject = subject;
        this.email = email;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var existing = adminService.list().stream()
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst();
        OidcAdminService.ProviderDto provider;
        if (existing.isPresent()) {
            provider = existing.get();
        } else {
            String effectiveIssuer = issuer.isBlank() ? deriveIssuer(discoveryUrl) : issuer;
            provider = adminService.create(new OidcAdminService.ProviderCreateRequest(
                    name, effectiveIssuer, discoveryUrl, clientId,
                    clientSecret.isBlank() ? null : clientSecret, 15, true));
        }
        boolean linked = adminService.listLinks(provider.id()).stream()
                .anyMatch(l -> l.subject().equals(subject));
        if (!linked) {
            adminService.assignLink(provider.id(), subject, email);
        }
        log.info("dev OIDC seed applied: provider={} linked={}", provider.id(), subject.length() > 0);
    }

    private static String deriveIssuer(String discoveryUrl) {
        int idx = discoveryUrl.indexOf("/.well-known/");
        return idx > 0 ? discoveryUrl.substring(0, idx) : discoveryUrl;
    }
}
