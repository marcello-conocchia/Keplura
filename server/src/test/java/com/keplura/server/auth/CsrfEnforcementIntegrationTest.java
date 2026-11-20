package com.keplura.server.auth;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.Map;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-2: wire-level proof of the CSRF gate through the live interceptor.
 * Login and GETs stay exempt; every other /api/v1/** method needs the
 * double-submit header matching the session-bound token. Deny answers are
 * the stable {error: CSRF_INVALID} — never a login redirect, never detail
 * about which half of the pair failed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CsrfEnforcementIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;

    private String cookiePair;
    private String csrf;

    @BeforeEach
    void authenticate() {
        String email = "csrf-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("csrf-pw"), "Csrf Tester"));
        ResponseEntity<String> login = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "csrf-pw")), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        cookiePair = CsrfTestSupport.cookiePair(login);
        csrf = CsrfTestSupport.csrfValue(cookiePair);
    }
    private ResponseEntity<String> createOrgWithoutCsrfHeader() {
        return rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, cookiePair)
                .body(Map.of("name", "csrf-probe")), String.class);
    }

    @Test
    void postWithoutCsrfHeaderIsRejected403WithStableCode() {
        ResponseEntity<String> response = createOrgWithoutCsrfHeader();
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("CSRF_INVALID");
    }

    @Test
    void wrongCsrfHeaderIsRejected403() {
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, cookiePair)
                .header(CsrfService.HEADER_NAME, "0".repeat(csrf.length()))
                .body(Map.of("name", "csrf-probe")), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("CSRF_INVALID");
    }

    /**
     * FINDING follow-up (PR #29 review, non-blocking): the logout branch
     * carries its OWN csrfService.validate call (it sits outside the
     * interceptor because it must read its own session). Until now only the
     * happy path (204) and the anonymous path (204) were covered — this pins
     * that a VALID session with a WRONG token is refused 403/CSRF_INVALID and
     * the session survives the rejected attempt (no forced-logout attack).
     */
    @Test
    void logoutWithWrongCsrfTokenIs403AndKeepsTheSessionAlive() {
        ResponseEntity<String> rejected = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/logout"))
                .header(HttpHeaders.COOKIE, cookiePair)
                .header(CsrfService.HEADER_NAME, "f".repeat(csrf.length()))
                .body(""), String.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(403);
        assertThat(rejected.getBody()).contains("CSRF_INVALID");

        // live-session proof: an authenticated GET behind the same cookies
        // still answers 200 — the invalidated-by-error assumption is false
        ResponseEntity<String> probe = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs"))
                .header(HttpHeaders.COOKIE, cookiePair)
                .build(), String.class);
        assertThat(probe.getStatusCode().value()).isEqualTo(200);

        // and the correct pair still logs out cleanly (204)
        ResponseEntity<String> ok = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/logout"))
                .header(HttpHeaders.COOKIE, cookiePair)
                .header(CsrfService.HEADER_NAME, csrf)
                .body(""), String.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void correctHeaderPassesTheGate() {
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, cookiePair)
                .header(CsrfService.HEADER_NAME, csrf)
                .body(Map.of("name", "csrf-ok")), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }
    @Test
    void getRemainsExemptFromTheHeaderRequirement() {
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs"))
                .header(HttpHeaders.COOKIE, cookiePair)
                .build(), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void sessionCookieAloneCannotPassAndCsrfCookieAloneCannotPass() {
        // The double-submit only holds when BOTH halves travel together. A
        // VALID session whose csrf cookie is missing passes the session gate
        // and then hits the CSRF deny (403); a csrf cookie with NO session is
        // rejected before CSRF even matters (401 — sessions gate first).
        ResponseEntity<String> sessionOnly = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, CsrfTestSupport.sessionOnly(cookiePair))
                .header(CsrfService.HEADER_NAME, csrf)
                .body(Map.of("name", "split-probe")), String.class);
        assertThat(sessionOnly.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> csrfOnly = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, CsrfTestSupport.csrfOnly(cookiePair))
                .header(CsrfService.HEADER_NAME, csrf)
                .body(Map.of("name", "split-probe")), String.class);
        assertThat(csrfOnly.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void csrfCookieIsDeliberatelyNotHttpOnlyWhileSessionIs() {
        String email = "csrf-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("csrf-pw3"), "Flags"));
        ResponseEntity<String> login = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "csrf-pw3")), String.class);
        List<String> setCookies = login.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).hasSize(2);
        String session = setCookies.stream().filter(c -> !c.startsWith("keplura_csrf=")).findFirst().orElseThrow();
        String csrfHalf = setCookies.stream().filter(c -> c.startsWith("keplura_csrf=")).findFirst().orElseThrow();
        assertThat(session).contains("; HttpOnly").contains("SameSite=Lax");
        // readable by design: Spring omits the attribute when httpOnly(false),
        // which is exactly the JS-readable contract of the csrf half
        assertThat(csrfHalf).doesNotContainIgnoringCase("httponly").contains("SameSite=Lax");
    }
    @Test
    void loginAndCsrfRefreshEndpointsBehaveAsContracted() {
        // a second login re-issues BOTH halves; the body token equals the csrf cookie
        String email = "csrf-" + System.nanoTime() + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("csrf-pw2"), "Refresh"));
        ResponseEntity<String> login = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "csrf-pw2")), String.class);
        String csrfFromBody = JsonPath.parse(login.getBody()).read("$.csrfToken", String.class);
        assertThat(csrfFromBody).isEqualTo(CsrfTestSupport.csrfValue(CsrfTestSupport.cookiePair(login)));

        // GET /auth/csrf (restore path) answers the SAME session-bound token
        ResponseEntity<String> refresh = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/auth/csrf"))
                .header(HttpHeaders.COOKIE, CsrfTestSupport.sessionOnly(CsrfTestSupport.cookiePair(login)))
                .build(), String.class);
        assertThat(refresh.getStatusCode().value()).isEqualTo(200);
        assertThat(JsonPath.parse(refresh.getBody()).read("$.csrfToken", String.class))
                .isEqualTo(csrfFromBody);
    }

    @Test
    void anonymousCsrfRefreshIsRejectedAndStateChangingToo() {
        ResponseEntity<String> refresh = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/auth/csrf")).build(), String.class);
        assertThat(refresh.getStatusCode().value()).isEqualTo(401);
        // fully anonymous POST (no cookies at all): sessions gate first -> 401
        ResponseEntity<String> anon = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "anon-probe")), String.class);
        assertThat(anon.getStatusCode().value()).isEqualTo(401);
    }
}
