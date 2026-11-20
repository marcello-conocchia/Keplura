package com.keplura.server.org;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2c: the organization vertical over REAL HTTP (docs/development.md harness
 * pattern). Proves the two headline guarantees end-to-end:
 * <ul>
 *   <li>AC: /api/v1/orgs/** requires a valid session (401 otherwise) while
 *       auth/health/system-info stay open;</li>
 *   <li>AC-2 IDOR: a logged-in NON-member can neither list nor fetch another
 *       tenant's organization — foreign id and unknown id answer identically
 *       (uniform 404), so the API leaks no existence information.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:org-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrgApiIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final AtomicInteger NAME_SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    /** Seeds a user, logs them in over the wire, returns the session cookie pair. */
    private String loginFreshUser(String label) {
        String email = label + "@example.com";
        userRepository.saveAndFlush(User.create(email, HASHER.hash("pw-" + label), label));
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "pw-" + label)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return com.keplura.server.auth.CsrfTestSupport.cookiePair(response);
    }

    private ResponseEntity<String> get(String path, String cookie) {
        var builder = RequestEntity.get(URI.create(path));
        if (cookie != null) {
            builder = builder.header(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(builder.build(), String.class);
    }

    private ResponseEntity<String> post(String path, String cookie, Map<String, Object> body) {
        var builder = RequestEntity
                .post(URI.create(path))
                .contentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            builder = builder.header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie));
        }
        return rest.exchange(builder.body(body), String.class);
    }

    // ---- session gate (401 without a valid session) ----

    @Test
    void orgEndpointsRequireSession() {
        List<RequestEntity<?>> probes = List.of(
                RequestEntity.get(URI.create("/api/v1/orgs")).build(),
                RequestEntity.post(URI.create("/api/v1/orgs"))
                        .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "x")),
                RequestEntity.get(URI.create("/api/v1/orgs/1")).build());
        for (RequestEntity<?> probe : probes) {
            ResponseEntity<String> response = rest.exchange(probe, String.class);
            assertThat(response.getStatusCode().value())
                    .as("%s %s must be 401 without a session", probe.getMethod(), probe.getUrl())
                    .isEqualTo(401);
            assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                    .as("401 must not mint a session").isNull();
            assertThat(response.getBody()).contains("\"error\"");
        }
    }

    @Test
    void orgEndpointsRejectUnknownStaleCookie() {
        // A cookie shaped like a session id but unknown to the container behaves
        // exactly like no cookie: 401, never 500, never a fallback identity.
        ResponseEntity<String> response = get("/api/v1/orgs", "KEPLURASESSION=nonsense123");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void authHealthAndSystemInfoStayOpen() {
        assertThat(get("/api/v1/health", null).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/api/v1/system/info", null).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/v1/auth/logout", null, Map.of()).getStatusCode().value()).isEqualTo(204);
    }

    // ---- create + list ----

    @Test
    void createOrgReturns201WithOwnerRoleAndListShowsIt() {
        String cookie = loginFreshUser("gina" + NAME_SEQ.incrementAndGet());

        ResponseEntity<String> created = post("/api/v1/orgs", cookie, Map.of("name", "Gina GmbH"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).contains("\"role\":\"OWNER\"").contains("Gina GmbH");

        ResponseEntity<String> list = get("/api/v1/orgs", cookie);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains("Gina GmbH");
    }

    @Test
    void createOrgValidatesName() {
        String cookie = loginFreshUser("val" + NAME_SEQ.incrementAndGet());

        assertThat(post("/api/v1/orgs", cookie, Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/v1/orgs", cookie, Map.of("name", "   ")).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/v1/orgs", cookie, Map.of("name", "x".repeat(201))).getStatusCode().value())
                .isEqualTo(400);
    }

    // ---- tenant isolation / IDOR (AC-2) ----

    @Test
    void listShowsOnlyOwnOrgsNeverForeignOnes() {
        String victimCookie = loginFreshUser("victor" + NAME_SEQ.incrementAndGet());
        String attackerCookie = loginFreshUser("attacker" + NAME_SEQ.incrementAndGet());

        ResponseEntity<String> victimOrg = post("/api/v1/orgs", victimCookie, Map.of("name", "Victor Corp"));
        assertThat(victimOrg.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> attackerList = get("/api/v1/orgs", attackerCookie);
        assertThat(attackerList.getStatusCode().value()).isEqualTo(200);
        // The victim's org name AND its id must both be absent from the attacker's list.
        assertThat(attackerList.getBody()).doesNotContain("Victor Corp");
        assertThat(attackerList.getBody()).doesNotContain("\"id\":" + extractId(victimOrg));
    }

    @Test
    void foreignOrgDirectFetchIsUniform404IdorProbe() {
        String victimCookie = loginFreshUser("wendy" + NAME_SEQ.incrementAndGet());
        String attackerCookie = loginFreshUser("mallory" + NAME_SEQ.incrementAndGet());

        ResponseEntity<String> victimOrg = post("/api/v1/orgs", victimCookie, Map.of("name", "Wendy AG"));
        assertThat(victimOrg.getStatusCode().value()).isEqualTo(201);
        String victimId = extractId(victimOrg);

        ResponseEntity<String> foreign = get("/api/v1/orgs/" + victimId, attackerCookie);
        // Logged in, but NOT a member -> 404, byte-identical to a never-existing id.
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> nonexistent = get("/api/v1/orgs/99999999", attackerCookie);
        assertThat(nonexistent.getStatusCode().value()).isEqualTo(404);
        assertThat(nonexistent.getBody()).isEqualTo(foreign.getBody());
        // And no org name leaks in either response.
        assertThat(foreign.getBody() == null ? "" : foreign.getBody()).doesNotContain("Wendy AG");
    }

    @Test
    void memberRoleIsReportedForOwnOrg() {
        String ownerCookie = loginFreshUser("owner" + NAME_SEQ.incrementAndGet());
        ResponseEntity<String> created = post("/api/v1/orgs", ownerCookie, Map.of("name", "Own Ltd"));
        String id = extractId(created);

        ResponseEntity<String> fetched = get("/api/v1/orgs/" + id, ownerCookie);
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody()).contains("\"role\":\"OWNER\"").contains("Own Ltd");
    }

    /** Pulls the "id":N value out of a created-org response body (integration-level, no JSON dep games). */
    private static String extractId(ResponseEntity<String> response) {
        String body = response.getBody();
        assertThat(body).isNotNull();
        int start = body.indexOf("\"id\":") + 5;
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return body.substring(start, end);
    }
}
