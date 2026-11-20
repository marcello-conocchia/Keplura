package com.keplura.server.auth;

import com.keplura.server.identity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session login/logout for the desktop (M2b, docs/adr/0003) plus the SEC-2
 * CSRF token surface. Deliberately plain servlet sessions — no Spring Security
 * filter chain yet; the rationale is recorded in docs/adr/0003 and the
 * milestone PR.
 *
 * <p>Anti-enumeration contract: a wrong password and an unknown email produce
 * the identical status (401) and identical JSON body — never which part failed.
 * Blocked attempts (rate limiter) answer 429 with its own generic body for
 * <i>any</i> key that hit the limit, known user or not.
 *
 * <p>Session-fixation defence: a successful login discards any pre-existing
 * session and stores the principal only in the fresh one. SEC-2 binds the
 * freshly generated CSRF token to that same fresh session, so a token can
 * never outlive (or predate) the session it guards.
 *
 * <p>SEC-2 token surfaces:
 * <ul>
 *   <li>{@code POST /auth/login} — response body gains {@code csrfToken} and
 *       the response sets the double-submit cookie (see {@link CsrfService}
 *       for why the body copy exists: the native Tauri jar cannot read
 *       cookies). Login itself stays CSRF-EXEMPT: no session exists to
 *       protect yet (credential stuffing is the rate limiter's job).</li>
 *   <li>{@code GET /auth/csrf} — re-emits the token for a restored session
 *       (webview reload: localStorage had a token bound to an OLD session id
 *       after session rotation, the store refreshes from here).</li>
 *   <li>{@code POST /auth/logout} — now CSRF-gated: logout is state-changing
 *       (session-termination CSRF is a real, if low-severity, attack —
 *       forced re-authentication). This endpoint is outside the interceptor's
 *       protected prefixes, so the check runs here, and a missing session
 *       stays a 204 (nothing to protect, nothing to leak).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * Response body of a successful POST /api/v1/auth/login.
     * SEC-2: {@code csrfToken} is the session-bound double-submit value —
     * identical to the one in the {@code keplura_csrf} cookie.
     */
    public record LoginResponse(Long userId, String displayName, String csrfToken, String role,
                                boolean mustChangePassword) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    /** Request body of POST /api/v1/auth/login. */
    public record LoginRequest(String email, String password, Boolean rememberMe) {
    }

    /** Response body of GET /api/v1/auth/csrf. */
    public record CsrfTokenResponse(String csrfToken) {
    }

    /** Response body of GET /api/v1/auth/me (ADMIN-KI restore path). */
    public record MeResponse(Long userId, String displayName, String role, boolean mustChangePassword) {
    }

    /** Generic error body — carries no information about which credential failed. */
    public record ErrorResponse(String error) {
    }

    private final AuthenticatedSessions sessions;
    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;
    private final CsrfService csrfService;
    private final com.keplura.server.identity.UserRepository userRepository;

    private com.keplura.server.directory.DirectoryService directoryService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDirectoryService(com.keplura.server.directory.DirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    public AuthController(AuthService authService, LoginRateLimiter rateLimiter, CsrfService csrfService,
            com.keplura.server.identity.UserRepository userRepository, AuthenticatedSessions sessions) {
        this.sessions = sessions;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.csrfService = csrfService;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        if (request.email() == null || request.email().isBlank() || request.email().length() > 320
                || request.password() == null || request.password().isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("email and password are required"));
        }

        String clientIp = httpRequest.getRemoteAddr(); // X-Forwarded-For is NOT trusted (see LoginRateLimiter)
        String email = User.normalizeEmail(request.email());

        if (rateLimiter.isBlocked(clientIp, email)) {
            // RFC 9110: a 429 must say when the client may retry (M2c hardening).
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER,
                            Long.toString(rateLimiter.retryAfterSeconds(clientIp, email)))
                    .body(new ErrorResponse("too many failed login attempts"));
        }

        var principalOpt = authService.authenticate(email, request.password());
        if (principalOpt.isEmpty() && directoryService != null) {
            // ADR-0012: lokale Konten haben Vorrang; danach AD/LDAP. Die Eingabe
            // darf ein Benutzername (z. B. sAMAccountName) sein, daher roh übergeben.
            principalOpt = directoryService.authenticate(request.email().trim(), request.password());
        }
        if (principalOpt.isEmpty()) {
            rateLimiter.recordFailure(clientIp, email);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("invalid credentials"));
        }

        AuthPrincipal principal = principalOpt.get();
        rateLimiter.recordSuccess(clientIp, email);
        var account = userRepository.findById(principal.userId()).orElse(null);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("invalid credentials"));
        }
        String csrfToken = sessions.create(account, httpRequest, httpResponse, Boolean.TRUE.equals(request.rememberMe()));
        return ResponseEntity.ok(new LoginResponse(account.getId(), account.getDisplayName(), csrfToken,
                account.role().name(), account.isMustChangePassword()));
    }

    /**
     * SEC-2: re-emits the CSRF token bound to the CALLER's existing session —
     * the desktop restore path (webview reload keeps its session but the JS
     * side must re-read the token after storage rotation). 401 without a
     * session, same generic body shape as the interceptor's. A session that
     * predates SEC-2 (no attribute yet) gets a token minted on the spot,
     * which is safe: the caller is already proven authenticated by that
     * session. The emission is IDEMPOTENT — a bound session keeps its token.
     */
    @GetMapping("/csrf")
    public ResponseEntity<?> csrf(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (!(principal instanceof AuthPrincipal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("authentication required"));
        }
        csrfService.reaffirmCookie(httpRequest, httpResponse, session);
        String token = (String) session.getAttribute(CsrfService.SESSION_ATTR);
        return ResponseEntity.ok(new CsrfTokenResponse(token));
    }

    /**
     * ADMIN-KI: the desktop RESTORE path (webview reload / app restart — no
     * login response to read a role from) learns its own profile here:
     * userId + displayName + the fresh DB role, from the caller's session.
     * Lives under the OPEN /auth prefix by design (same pattern as logout:
     * it reads its own session); 401 generic body without one. The role is
     * display-only — server enforcement stays on the admin interceptor, so
     * this endpoint can never authorise anything by itself.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (!(principal instanceof AuthPrincipal p)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("authentication required"));
        }
        return userRepository.findById(p.userId())
                .map(u -> ResponseEntity.<Object>ok(new MeResponse(u.getId(), u.getDisplayName(), u.role().name(),
                        u.isMustChangePassword())))
                // authenticated session whose user vanished (deleted/never existed):
                // same 401 as no session — the session is invalid in practice.
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body((Object) new ErrorResponse("authentication required")));
    }

    /**
     * PILOT 4e: eigenes Passwort ändern (Pflicht nach Startpasswort). Session +
     * CSRF wie jede schreibende Anfrage; das aktuelle Passwort wird geprüft und
     * Fehlversuche zählen in den Login-Rate-Limiter. Neues Passwort: 12–200
     * Zeichen und ungleich dem alten. 204 / 400 / 401.
     */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestBody(required = false) ChangePasswordRequest request,
                                            HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object principal = (session == null) ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (!(principal instanceof AuthPrincipal p)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("authentication required"));
        }
        // /api/v1/auth/** is outside the SessionAuthInterceptor — validate the
        // double-submit pair here, exactly like logout (SEC-2).
        if (!csrfService.validate(httpRequest, session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("CSRF_INVALID"));
        }
        if (request == null || request.currentPassword() == null || request.newPassword() == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("current and new password are required"));
        }
        String next = request.newPassword();
        if (next.length() < 12 || next.length() > 200 || next.equals(request.currentPassword())) {
            return ResponseEntity.badRequest().body(new ErrorResponse("new password does not meet the rules"));
        }
        var user = userRepository.findById(p.userId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("authentication required"));
        }
        String clientIp = httpRequest.getRemoteAddr();
        if (rateLimiter.isBlocked(clientIp, user.getEmail())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(rateLimiter.retryAfterSeconds(clientIp, user.getEmail())))
                    .body(new ErrorResponse("too many failed attempts"));
        }
        if (authService.authenticate(user.getEmail(), request.currentPassword()).isEmpty()) {
            rateLimiter.recordFailure(clientIp, user.getEmail());
            return ResponseEntity.badRequest().body(new ErrorResponse("current password is wrong"));
        }
        authService.changePassword(user, next);
        session.removeAttribute(AuthService.SESSION_MUST_CHANGE_PASSWORD_ATTR);
        return ResponseEntity.noContent().build();
    }

    /** Invalidates the caller's session if one exists and clears the CSRF
     * cookie. Always 204 for a caller with no (valid) session: logging out
     * without a session is not an error and leaks nothing. With a session the
     * double-submit pair must validate (SEC-2 — see class javadoc).
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            return ResponseEntity.noContent().build();
        }
        // SEC-2: the double-submit check is deliberately the EXACT one
        // CsrfService enforces on protected paths; logout sits outside the
        // interceptor because it must read its own session to kill it.
        if (!csrfService.validate(httpRequest, session)) {
            // SEC-2 vocabulary: the same stable code the interceptor answers.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(new ErrorResponse("CSRF_INVALID"));
        }
        session.invalidate();
        csrfService.clearCookieOn(httpResponse, httpRequest.isSecure());
        return ResponseEntity.noContent().build();
    }
}
