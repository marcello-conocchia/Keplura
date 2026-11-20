package com.keplura.server.auth;

import com.keplura.server.identity.UserRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the M2c session gate + the SEC-2 CSRF gate. Path policy (explicit
 * allow-by-default): {@code /api/v1/orgs}, {@code /api/v1/orgs/**}, the M4a
 * model gateway {@code /api/v1/gateway/**} and the SEC-2 accept endpoint
 * {@code /api/v1/invites/**} are protected (the M4b chat API lives under the
 * orgs prefix, so it is covered automatically).
 * {@code /api/v1/auth/*}, {@code /api/v1/health} and {@code /api/v1/system/info}
 * stay open by NOT matching — new protected surfaces must be added here
 * deliberately, and any future org-scoped endpoint placed under a different
 * prefix needs its own entry (documented in docs/adr/0004).
 *
 * <p>SEC-2 note: the logout + csrf-refresh endpoints live under the OPEN
 * {@code /api/v1/auth} prefix; logout reads its own session and 204s without
 * one, so its CSRF gate is enforced in the controller (it needs the cookie
 * clear + session invalidate anyway). The interceptor CSRF rule applies to
 * every protected path above.
 *
 * <p>M3b note: the space API lives UNDER the protected prefix
 * ({@code /api/v1/orgs/{orgId}/spaces}), so the gate covers it automatically —
 * no new pattern was needed (and adding one would only duplicate coverage).
 *
 * <p>M4a note: the gateway API lives outside the orgs prefix, so it carries its
 * own pattern. Org membership is NOT checked here (the interceptor has no
 * tenant argument to work with) — the controller enforces it per request via
 * the X-Keplura-Org header against OrgService, keeping the uniform-404 rule.
 */
@Configuration
public class SessionAuthConfig implements WebMvcConfigurer {

    private final CsrfService csrfService;
    private final UserRepository userRepository;

    public SessionAuthConfig(CsrfService csrfService, UserRepository userRepository) {
        this.csrfService = csrfService;
        this.userRepository = userRepository;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String[] protectedPaths = {"/api/v1/orgs", "/api/v1/orgs/**",
                "/api/v1/gateway/**", "/api/v1/invites", "/api/v1/invites/**",
                // ADMIN-KI: the admin surface rides the SAME session + CSRF gate
                // (docs/adr/0008 §6) — registering the prefix here is what makes
                // double-submit enforcement cover admin POST/PATCH automatically.
                "/api/v1/admin/**"};
        registry.addInterceptor(new SessionAuthInterceptor(csrfService, userRepository))
                .addPathPatterns(protectedPaths)
                .order(0);
        // Role gate strictly AFTER the session/CSRF gate: auth first, then role
        // (ADR-0008 §1). Reads the user row fresh per request.
        registry.addInterceptor(new AdminGuardInterceptor(userRepository))
                .addPathPatterns("/api/v1/admin/**")
                .order(1);
    }
}
