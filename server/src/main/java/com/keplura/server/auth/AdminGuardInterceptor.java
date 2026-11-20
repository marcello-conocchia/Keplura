package com.keplura.server.auth;

import com.keplura.server.identity.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * ADMIN-KI SCOPE A (docs/adr/0008 §1): the server-side role gate for
 * {@code /api/v1/admin/**}. Registered AFTER {@link SessionAuthInterceptor}
 * on the same path patterns, so by the time this runs the session principal
 * exists and CSRF validated — this interceptor answers exactly one question:
 * is the authenticated user an ADMIN?
 *
 * <p>The role is read FRESH from the database per request (ADR-0004
 * principle — same posture as org membership). An ADMIN flag copied into the
 * session would survive revocation until the next login; a DB read makes
 * demotion effective on the very next request.
 *
 * <p>Deny shape mirrors the existing gates: one generic 403 body that does
 * not distinguish "user gone" from "user is not admin" (no existence oracle
 * on a protected surface), no redirect, no detail. A missing/expired session
 * never reaches here — the session gate already 401s it.
 */
public class AdminGuardInterceptor implements HandlerInterceptor {

    /** Generic 403 body — same form as the CSRF deny, carries the stable code. */
    static final String FORBIDDEN_BODY = "{\"error\":\"ADMIN_REQUIRED\"}";

    private final UserRepository userRepository;

    public AdminGuardInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true; // same carve-out as the session gate (see SessionAuthInterceptor)
        }
        HttpSession session = request.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        // Defence in depth: the session gate runs first, so a non-principal here
        // means the interceptor order was broken — deny as 401 (auth missing),
        // never fall through to the DB read.
        if (!(principal instanceof AuthPrincipal p)) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, SessionAuthInterceptor.UNAUTHORIZED_BODY);
            return false;
        }
        Optional<com.keplura.server.identity.User> user = userRepository.findById(p.userId());
        // Uniform 403: unknown/deleted user and non-admin are indistinguishable.
        if (user.isEmpty() || !user.get().isAdmin()) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_BODY);
            return false;
        }
        return true;
    }

    private static void deny(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
