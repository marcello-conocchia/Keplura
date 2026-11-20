package com.keplura.server.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * CSRF protection for the session-authenticated API (SEC-2, task design:
 * double-submit cookie + session-bound token). Rationale: the desktop talks
 * to the server with session cookies (ADR-0003); any site the user visits
 * could otherwise forge a credentialed POST/PUT/DELETE. The gate is the
 * {@code X-CSRF-Token} request header on every state-changing method under
 * the protected prefixes — a cross-origin attacker can send cookies with a
 * forged request but can never read or mirror a value only this app knows.
 *
 * <p>The token lives in THREE places by design:
 * <ol>
 *   <li>the {@value #SESSION_ATTR} attribute of the authenticated HttpSession
 *       — the server-side truth, generated once per session at login;</li>
 *   <li>a non-Security-sensitive cookie {@value #COOKIE_NAME}
 *       (HttpOnly=false, SameSite=Lax) — the double-submit half: the interceptor
 *       requires header == cookie so a subdomain/cookie-tossing attacker
 *       cannot pair an old forged cookie with a matching header;</li>
 *   <li>the login response body ({@code csrfToken}) — the native Tauri
 *       webview runs on a tauri:// origin where document.cookie is empty,
 *       so the JS side can ONLY get the value from the body (ADR-0005
 *       transport notes).</li>
 * </ol>
 * Validation compares the header against BOTH the cookie and the session
 * attribute; all three must agree.
 *
 * <p>Token generation is 32 fresh {@link SecureRandom} bytes rendered as hex
 * (64 chars) — well under the 128-char ingestion cap below.
 */
@Service
public class CsrfService {

    /** Header the client must echo on state-changing requests. */
    public static final String HEADER_NAME = "X-CSRF-Token";

    /** Double-submit cookie name. Never HttpOnly — the client must read it. */
    public static final String COOKIE_NAME = "keplura_csrf";

    /** HttpSession attribute holding the session's CSRF token. */
    public static final String SESSION_ATTR = "KEPLURA_CSRF_TOKEN";

    /** A stored/echoed token longer than this is corrupt or hostile — refuse. */
    static final int MAX_TOKEN_CHARS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Fresh high-entropy token (hex), not yet bound to any session. */
    public String generateToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /**
     * Binds {@code token} to the freshly-created authenticated session and
     * writes the double-submit Set-Cookie via the raw servlet response
     * (addHeader, not a ResponseEntity header): when the controller returns a
     * ResponseEntity carrying its own Set-Cookie value, Tomcat/Spring ends up
     * emitting only ONE of the two cookies on login — the manually-set CSRF
     * cookie won and the session cookie vanished (verified by A/B: same code
     * with the header removed brings the session cookie back). addHeader
     * before the response is committed keeps both. Lax, path {@code /},
     * Secure iff the request arrived over TLS. Called by the login handler
     * only — the token's lifetime is the session's lifetime by construction.
     */
    public void bindAndAddCookie(HttpServletRequest request, HttpServletResponse response,
            HttpSession session, String token) {
        session.setAttribute(SESSION_ATTR, token);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader(token, -1, request.isSecure()));
    }

    /**
     * Re-affirms (and binds when absent) the CSRF cookie on an ALREADY
     * authenticated session — the GET /auth/csrf restore path. Idempotent:
     * a bound session keeps its token, so header/cookie/session never drift.
     * Same raw-response reasoning as {@link #bindAndAddCookie}.
     */
    public void reaffirmCookie(HttpServletRequest request, HttpServletResponse response,
            HttpSession session) {
        Object bound = session.getAttribute(SESSION_ATTR);
        String token = (bound instanceof String existing && sane(existing))
                ? existing : generateToken();
        session.setAttribute(SESSION_ATTR, token);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader(token, -1, request.isSecure()));
    }

    /** Cookie-erase via the raw response (logout): same addHeader reasoning. */
    public void clearCookieOn(HttpServletResponse response, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader("", 0, secure));
    }

    /**
     * Full double-submit check for one state-changing request: the header, the
     * cookie and the session attribute must all be present and byte-equal
     * (constant-time compares). The caller has already resolved the session.
     */
    public boolean validate(HttpServletRequest request, HttpSession session) {
        String header = request.getHeader(HEADER_NAME);
        String cookie = readCsrfCookie(request);
        Object bound = session.getAttribute(SESSION_ATTR);
        if (!(bound instanceof String token) || !sane(token)) {
            return false;
        }
        return sane(header) && sane(cookie)
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        header.getBytes(StandardCharsets.UTF_8))
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        cookie.getBytes(StandardCharsets.UTF_8));
    }

    /** Presence + shape check before any compare (empty/oversized = reject). */
    private static boolean sane(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_TOKEN_CHARS;
    }

    private static String readCsrfCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static String cookieHeader(String value, int maxAge, boolean secure) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(false)
                .sameSite("Lax")
                .secure(secure);
        if (maxAge >= 0) {
            builder.maxAge(maxAge);
        }
        return builder.build().toString();
    }
}
