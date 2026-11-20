package com.keplura.server.auth;

import com.jayway.jsonpath.JsonPath;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2b: the login/logout vertical over <b>real HTTP</b> (extends the M1b harness
 * pattern — docs/development.md "API integration harness"): a full embedded
 * server on a random port, wire-level Set-Cookie, exact JSON responses.
 *
 * <p>Session lifecycle is observed through an {@link HttpSessionListener} bean
 * (Spring Boot registers EventListener beans as servlet listeners): it tracks
 * every session id the container creates and destroys. That makes "logout
 * invalidates the session" and "a failed login never establishes a session"
 * directly assertable without inventing a protected probe endpoint (protected
 * endpoints are M2c, on purpose).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthApiIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class SessionSpy {

        /** Live (created, not yet destroyed) session ids, as reported by the container. */
        final Map<String, jakarta.servlet.http.HttpSession> liveSessions = new ConcurrentHashMap<>();

        @Bean
        HttpSessionListener httpSessionListener() {
            return new HttpSessionListener() {
                @Override
                public void sessionCreated(HttpSessionEvent event) {
                    liveSessions.put(event.getSession().getId(), event.getSession());
                }

                @Override
                public void sessionDestroyed(HttpSessionEvent event) {
                    liveSessions.remove(event.getSession().getId());
                }
            };
        }
    }

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionSpy sessionSpy;

    private ResponseEntity<String> login(String email, String password) {
        return rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password)), String.class);
    }

    /** The session-cookie half of a login response, with its flags asserted. */
    private static String sessionCookie(ResponseEntity<String> response) {
        String setCookie = pickSetCookie(response, false);
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax");
        // Missing rememberMe defaults to a non-persistent session cookie.
        assertThat(setCookie).doesNotContain("Max-Age=").doesNotContain("Expires=");
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    /** SEC-2: the csrf-cookie half — non-HttpOnly BY DESIGN (the client must
     read it for the double submit), SameSite=Lax, never carrying a session id. */
    private static String csrfCookie(ResponseEntity<String> response) {
        String setCookie = pickSetCookie(response, true);
        assertThat(setCookie).doesNotContain("HttpOnly").contains("SameSite=Lax");
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    /**
     * Picks the session vs. the CSRF Set-Cookie. Matched by "is/is not the
     * keplura_csrf cookie", not by session-cookie NAME — the name is
     * profile-dependent (KEPLURASESSION_DEV on dev), exactly like the
     * CsrfTestSupport selector.
     */
    private static String pickSetCookie(ResponseEntity<String> response, boolean csrf) {
        java.util.List<String> cookies = response.getHeaders()
                .get(org.springframework.http.HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("login Set-Cookie headers").isNotNull();
        return cookies.stream()
                .filter(sc -> sc.startsWith("keplura_csrf=") == csrf)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + (csrf ? "csrf" : "session")
                        + " cookie in " + cookies));
    }

    private static String sessionIdOf(String cookiePair) {
        return cookiePair.substring(cookiePair.indexOf('=') + 1);
    }

    @Test
    void loginWithCorrectCredentialsReturnsOnlyIdAndNameAndSetsSessionCookie() {
        userRepository.saveAndFlush(User.create("alice@example.com", HASHER.hash("wonderland"), "Alice"));

        ResponseEntity<String> response = login("alice@example.com", "wonderland");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(sessionSpy.liveSessions).containsKey(sessionIdOf(sessionCookie(response)));
        // Only the FOUR contracted fields — the User entity (passwordHash!)
        // is never serialised. SEC-2 added csrfToken (the double-submit value,
        // identical to the keplura_csrf cookie content — pinned below);
        // ADMIN-KI added role (USER|ADMIN — display-only: the server enforces
        // the admin surface via AdminGuardInterceptor, never via this value).
        Map<String, Object> body = JsonPath.parse(response.getBody()).read("$", Map.class);
        assertThat(body).containsOnlyKeys("userId", "displayName", "csrfToken", "role", "mustChangePassword");
        assertThat(body.get("mustChangePassword")).isEqualTo(false);
        assertThat(body.get("displayName")).isEqualTo("Alice");
        assertThat(body.get("role")).isEqualTo("USER");
        assertThat(body.get("userId").toString()).isEqualTo(
                userRepository.findByEmail("alice@example.com").orElseThrow().getId().toString());
        assertThat((String) body.get("csrfToken"))
                .isEqualTo(csrfCookie(response).substring("keplura_csrf=".length()));
        assertThat(response.getBody()).doesNotContain("argon2").doesNotContain("wonderland");
    }

    @Test
    void rememberMeSetsOnePersistentCookieAndAbsoluteLifetime() {
        userRepository.saveAndFlush(User.create("remember@example.com", HASHER.hash("secret"), "Remember"));
        var response = rest.exchange(RequestEntity.post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "remember@example.com", "password", "secret", "rememberMe", true)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(2); // exactly one session cookie plus CSRF
        String cookie = pickSetCookie(response, false);
        assertThat(cookie).contains("Max-Age=2592000", "HttpOnly", "SameSite=Lax", "Path=/");
        String id = sessionIdOf(cookie.substring(0, cookie.indexOf(';')));
        var session = sessionSpy.liveSessions.get(id);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(2592000);
        var deadline = session.getAttribute(AuthenticatedSessions.EXPIRES_AT);
        assertThat(deadline).isInstanceOf(java.time.Instant.class);
        var me = rest.exchange(RequestEntity.get(URI.create("/api/v1/auth/me"))
                .header(HttpHeaders.COOKIE, cookie.substring(0, cookie.indexOf(';'))).build(), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(session.getAttribute(AuthenticatedSessions.EXPIRES_AT)).isEqualTo(deadline);
        session.setAttribute(AuthenticatedSessions.EXPIRES_AT, java.time.Instant.now().minusSeconds(1));
        var expired = rest.exchange(RequestEntity.get(URI.create("/api/v1/auth/me"))
                .header(HttpHeaders.COOKIE, cookie.substring(0, cookie.indexOf(';'))).build(), String.class);
        assertThat(expired.getStatusCode().value()).isEqualTo(401);
        assertThat(sessionSpy.liveSessions).doesNotContainKey(id);
    }

    @Test
    void uncheckedLoginAlsoHasNoThirtyMinuteIdleLimit() {
        userRepository.saveAndFlush(User.create("temporary@example.com", HASHER.hash("secret"), "Temporary"));
        var response = login("temporary@example.com", "secret");
        var pair = sessionCookie(response);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).hasSize(2);
        assertThat(sessionSpy.liveSessions.get(sessionIdOf(pair)).getMaxInactiveInterval()).isEqualTo(2592000);
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() {
        userRepository.saveAndFlush(User.create("bob@example.com", HASHER.hash("secret-pw"), "Bob"));

        ResponseEntity<String> wrongPassword = login("bob@example.com", "not-the-password");
        ResponseEntity<String> unknownEmail = login("nobody@example.com", "secret-pw");

        // Identical status AND identical body — the AC-3 enumeration guard.
        assertThat(wrongPassword.getStatusCode()).isEqualTo(unknownEmail.getStatusCode());
        assertThat(wrongPassword.getStatusCode().value()).isEqualTo(401);
        assertThat(unknownEmail.getBody()).isEqualTo(wrongPassword.getBody());
        // Neither failure branch may establish a session.
        assertThat(wrongPassword.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(unknownEmail.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void unknownEmailStillPaysTheHashingCost() {
        // Dummy-hash verify: the unknown-user path must not return ~instantly
        // faster than the wrong-password path (timing side channel).
        userRepository.saveAndFlush(User.create("carol@example.com", HASHER.hash("carol-pw"), "Carol"));

        long wrongPw = medianLoginMillis("carol@example.com", "bad");
        long unknown = medianLoginMillis("ghost@example.com", "bad");

        // Both dominated by Argon2 (~50-150ms): generous band, flake-safe on CI,
        // but a missing dummy verify (~1ms DB lookup only) would be ~100x faster.
        assertThat(wrongPw).isGreaterThan(5);
        assertThat((double) unknown / wrongPw).isBetween(0.1, 10.0);
    }

    private long medianLoginMillis(String email, String password) {
        long[] times = new long[3];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            login(email, password);
            times[i] = (System.nanoTime() - start) / 1_000_000;
        }
        Arrays.sort(times);
        return times[1];
    }

    @Test
    void logoutDestroysTheSessionAndTheOldCookieNoLongerResolvesToIt() {
        userRepository.saveAndFlush(User.create("dave@example.com", HASHER.hash("hello"), "Dave"));

        ResponseEntity<String> loginResponse = login("dave@example.com", "hello");
        String cookie = com.keplura.server.auth.CsrfTestSupport.cookiePair(loginResponse);
        String sessionId = sessionIdOf(com.keplura.server.auth.CsrfTestSupport.sessionOnly(cookie));
        assertThat(sessionSpy.liveSessions).containsKey(sessionId);

        ResponseEntity<String> logoutResponse = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/logout"))
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                .body(""), String.class);

        assertThat(logoutResponse.getStatusCode().value()).isEqualTo(204);
        // The container destroyed exactly the session the cookie pointed at.
        assertThat(sessionSpy.liveSessions).doesNotContainKey(sessionId);

        // Re-using the old cookie against any endpoint must never resurrect it.
        rest.getForEntity("/api/v1/system/info", String.class);
        assertThat(sessionSpy.liveSessions).doesNotContainKey(sessionId);
    }

    @Test
    void logoutWithoutSessionIsHarmlessNoContent() {
        ResponseEntity<String> response = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/auth/logout")).body(""), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void reLoginReplacesTheSessionIdAndDestroysTheOldOne() {
        // Session fixation defence over the wire: authenticating while holding a
        // live session must mint a NEW id and destroy the pre-auth one.
        userRepository.saveAndFlush(User.create("erin@example.com", HASHER.hash("pw123"), "Erin"));

        ResponseEntity<String> first = login("erin@example.com", "pw123");
        String firstCookie = sessionCookie(first);
        String firstId = sessionIdOf(firstCookie);

        ResponseEntity<String> second = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, firstCookie)
                .body(Map.of("email", "erin@example.com", "password", "pw123")), String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        String secondId = sessionIdOf(sessionCookie(second));

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(sessionSpy.liveSessions).doesNotContainKey(firstId).containsKey(secondId);
    }

    @Test
    void repeatedFailuresForSameEmailAndClientGet429() {
        userRepository.saveAndFlush(User.create("frank@example.com", HASHER.hash("right-pw"), "Frank"));

        ResponseEntity<String> last = null;
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            last = login("frank@example.com", "wrong" + i);
            assertThat(last.getStatusCode().value())
                    .as("attempt %s must still be a plain 401", i)
                    .isEqualTo(401);
        }

        // Window exhausted: even the CORRECT password is now 429 (throttle, not auth).
        ResponseEntity<String> blocked = login("frank@example.com", "right-pw");
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
        // M2c hardening: 429 carries a numeric Retry-After (RFC 9110) within the window.
        String retryAfter = blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 15 * 60L);
        // and the 429 body is generic — no hint of what was right/wrong
        assertThat(blocked.getBody()).doesNotContain("frank");
    }

    @Test
    void blankCredentialsReturn400AndMalformedBodyDoesNot500() {
        ResponseEntity<String> blankEmail = login("", "x");
        assertThat(blankEmail.getStatusCode().value()).isEqualTo(400);
        assertThat(blankEmail.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();

        ResponseEntity<String> emptyPassword = login("someone@example.com", "");
        assertThat(emptyPassword.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> junk = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\": 7}"), String.class);
        // Coerced to "7" (password null -> 400) or rejected by Jackson (400) — never a 500.
        assertThat(junk.getStatusCode().value()).isEqualTo(400);
        assertThat(junk.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    }
}
