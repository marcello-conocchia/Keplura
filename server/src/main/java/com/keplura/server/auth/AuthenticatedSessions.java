package com.keplura.server.auth;

import com.keplura.server.identity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Shared lifetime and cookie policy for local/directory and OIDC login. */
@Service
public final class AuthenticatedSessions {
    static final String EXPIRES_AT = "keplura.auth.expiresAt";
    private final CsrfService csrf;
    private final int lifetimeSeconds;

    public AuthenticatedSessions(CsrfService csrf,
            @Value("${keplura.auth.session-lifetime-seconds:2592000}") int lifetimeSeconds) {
        if (lifetimeSeconds <= 0) throw new IllegalArgumentException("session lifetime must be positive");
        this.csrf = csrf;
        this.lifetimeSeconds = lifetimeSeconds;
    }

    public String create(User user, HttpServletRequest request, HttpServletResponse response,
                         boolean rememberMe) {
        var previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        var session = request.getSession(true);
        session.setAttribute(AuthService.SESSION_PRINCIPAL_ATTR,
                new AuthPrincipal(user.getId(), user.getDisplayName()));
        session.setAttribute(EXPIRES_AT, Instant.now().plusSeconds(lifetimeSeconds));
        // Container cleanup for abandoned sessions. The absolute deadline is
        // enforced before every request, so inactivity never shortens the lifetime.
        session.setMaxInactiveInterval(lifetimeSeconds);
        if (user.isMustChangePassword()) {
            session.setAttribute(AuthService.SESSION_MUST_CHANGE_PASSWORD_ATTR, Boolean.TRUE);
        }
        var config = request.getServletContext().getSessionCookieConfig();
        String name = config.getName() == null ? "JSESSIONID" : config.getName();
        // Start with the container's actual header: Boot applies SameSite
        // through its Tomcat cookie processor, not SessionCookieConfig.
        String cookie = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "=")).findFirst()
                .orElseThrow(() -> new IllegalStateException("session cookie was not emitted"))
                .replaceAll("(?i);\\s*(Max-Age|Expires)=[^;]*", "");
        if (rememberMe) cookie += "; Max-Age=" + lifetimeSeconds;
        // Tomcat already emitted its default cookie when getSession created the
        // session. Replace only that header, avoiding two competing lifetimes.
        List<String> others = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> !header.startsWith(name + "=")).toList();
        response.setHeader(HttpHeaders.SET_COOKIE, cookie);
        others.forEach(header -> response.addHeader(HttpHeaders.SET_COOKIE, header));
        String token = csrf.generateToken();
        csrf.bindAndAddCookie(request, response, session, token);
        return token;
    }
}
