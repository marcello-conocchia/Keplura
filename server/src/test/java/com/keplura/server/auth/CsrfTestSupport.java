package com.keplura.server.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * Test-side pair extraction for the SEC-2 double-submit cookie. Since SEC-2
 * a login response carries TWO Set-Cookie headers (session + csrf) and the
 * CSRF token ALSO rides in the JSON body; the integration tests send the
 * pair as one Cookie header and echo the value as X-CSRF-Token.
 *
 * <p>{@link #cookiePair(ResponseEntity)} returns the ready-to-send
 * {@code "KEPLURASESSION=..; keplura_csrf=.."} string — deliberately
 * built from the SET_COOKIE headers (not the body) so the tests pin the
 * cookie half of the contract, exactly what a real jar would replay.
 */
public final class CsrfTestSupport {

    private CsrfTestSupport() {
    }

    /**
     * MockMvc-side retrofit (SEC-2): binds a fixed token to a hand-built
     * MockHttpSession exactly like login does, and returns the token so the
     * caller can echo it as cookie + header. Tests that never run a real
     * login (in-JVM fixtures) need this or the CSRF gate 403s them.
     */
    public static String bindToSession(org.springframework.mock.web.MockHttpSession session) {
        String token = "t".repeat(64);
        session.setAttribute(CsrfService.SESSION_ATTR, token);
        return token;
    }

    /** Session + CSRF cookie pair from a login response, semicolon-joined. */
    public static String cookiePair(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        org.assertj.core.api.Assertions
                .assertThat(cookies == null ? List.of() : cookies)
                .as("login must set BOTH the session and the csrf cookie")
                .size()
                .isGreaterThanOrEqualTo(2);
        String session = cookies.stream()
                .map(CsrfTestSupport::bare)
                .filter(pair -> !pair.startsWith(CsrfService.COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no session cookie in " + cookies));
        // The session cookie NAME is profile-dependent (KEPLURASESSION_DEV on
        // dev per application-dev.properties), so the selector is deliberately
        // "the one that is not the csrf cookie" — stable across profiles.
        String csrf = pickByName(cookies, CsrfService.COOKIE_NAME);
        return session + "; " + csrf;
    }

    /** Just the CSRF token VALUE (attributes stripped) — the X-CSRF-Token half.
     *  A string without a csrf cookie (forged-session probes) yields a fixed
     *  placeholder: the session gate 401s before the CSRF check ever runs, so
     *  the value is never inspected on those paths. */
    public static String csrfValue(String cookiePair) {
        for (String part : cookiePair.split("; ")) {
            if (part.startsWith(CsrfService.COOKIE_NAME + "=")) {
                return part.substring(CsrfService.COOKIE_NAME.length() + 1);
            }
        }
        return "no-csrf-cookie-in-probe";
    }

    /** The session-cookie half alone (for the negative: session without csrf). */
    public static String sessionOnly(String cookiePair) {
        for (String part : cookiePair.split("; ")) {
            if (!part.startsWith(CsrfService.COOKIE_NAME + "=")) {
                return part;
            }
        }
        throw new AssertionError("cookie pair carries no session cookie: " + cookiePair);
    }

    /** The csrf-cookie half alone (for the negative: csrf cookie without session). */
    public static String csrfOnly(String cookiePair) {
        for (String part : cookiePair.split("; ")) {
            if (part.startsWith(CsrfService.COOKIE_NAME + "=")) {
                return part;
            }
        }
        throw new AssertionError("cookie pair carries no csrf cookie: " + cookiePair);
    }

    private static String bare(String setCookieHeader) {
        return setCookieHeader.substring(0, setCookieHeader.indexOf(';'));
    }

    private static String pickByName(List<String> setCookies, String name) {
        return setCookies.stream()
                .map(CsrfTestSupport::bare)
                .filter(pair -> pair.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie in " + setCookies));
    }
}
