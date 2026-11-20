package com.keplura.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Session gate for protected API paths (M2c, docs/adr/0003/0004) extended by
 * SEC-2 with the CSRF gate. Registered as a handler interceptor — deliberately
 * NOT a servlet filter, so request bodies are never touched and the check runs
 * exactly on the paths listed in {@code SessionAuthConfig}.
 *
 * <p>Two checks, in order:
 * <ol>
 *   <li><b>Authentication</b> — a request carrying a valid session cookie whose
 *       session holds an {@link AuthPrincipal} passes through; everything else
 *       on a protected path gets a 401 with a generic body before the
 *       controller runs. The body never distinguishes "no cookie" from
 *       "expired session" from "bogus id", and protected paths stay protected
 *       even for HTTP methods the controller does not implement (defence in
 *       depth against method confusion).</li>
 *   <li><b>CSRF (SEC-2)</b> — once authenticated, every state-changing method
 *       (POST/PUT/DELETE/PATCH) must additionally carry the double-submit
 *       token pair validated by {@link CsrfService}; a mismatch or missing
 *       token answers 403 with a generic body. GET/HEAD/OPTIONS stay
 *       ungated (they change nothing). The login path under a protected
 *       prefix is exempt: it has no session to protect yet.</li>
 * </ol>
 *
 * <p>Exception: CORS preflight (OPTIONS with Origin +
 * Access-Control-Request-Method) passes the gate unconditionally — Spring
 * answers a preflight in its CORS layer and never reaches the controller, so
 * 401/403-gating it would only break the browser handshake for credentialed
 * cross-origin POSTs. Foreign origins still get no
 * {@code Access-Control-Allow-*} headers from that CORS layer.
 */
public class SessionAuthInterceptor implements HandlerInterceptor {

    /** Generic 401 body for protected paths without a valid session — same shape everywhere. */
    static final String UNAUTHORIZED_BODY = "{\"error\":\"authentication required\"}";

    /**
     * Fail-closed 403 body per the SEC-2 spec (AC: "403 CSRF_INVALID fuer
     * fehlenden/Mismatch"). Carries the stable code and nothing else — never
     * which half of the double-submit pair broke.
     */
    static final String CSRF_FORBIDDEN_BODY = "{\"error\":\"CSRF_INVALID\"}";

    /** Login lives under /api/v1/auth (unauthenticated by design) — no CSRF yet. */
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final CsrfService csrfService;

    private final com.keplura.server.identity.UserRepository users;

    public SessionAuthInterceptor(CsrfService csrfService, com.keplura.server.identity.UserRepository users) {
        this.users = users;
        this.csrfService = csrfService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        HttpSession session = request.getSession(false); // never mint a session on an unauthenticated probe
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (!(principal instanceof AuthPrincipal account)) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED_BODY);
            return false;
        }
        if (isStateChanging(request.getMethod()) && !LOGIN_PATH.equals(request.getRequestURI())
                && !csrfService.validate(request, session)) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, CSRF_FORBIDDEN_BODY);
            return false;
        }
        // Re-read account restrictions on every write: legacy/OIDC sessions and
        // sessions created before an admin password reset cannot bypass this gate.
        if (isStateChanging(request.getMethod())) {
            var user = users.findById(account.userId()).orElse(null);
            if (user == null) {
                session.invalidate();
                deny(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED_BODY);
                return false;
            }
            if (user.isMustChangePassword()) {
                deny(response, HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"password change required\"}");
                return false;
            }
        }
        return true;
    }

    /** The unsafe set per RFC 9110 — everything that is not a safe method. */
    private static boolean isStateChanging(String method) {
        return switch (method.toUpperCase()) {
            case "GET", "HEAD", "OPTIONS" -> false;
            default -> true;
        };
    }

    private static void deny(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
