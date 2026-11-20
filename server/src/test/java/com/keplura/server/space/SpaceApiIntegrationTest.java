package com.keplura.server.space;

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
 * M3b: the space vertical over REAL HTTP (docs/development.md harness pattern,
 * same shape as OrgApiIntegrationTest). Proves the headline guarantees
 * end-to-end:
 * <ul>
 *   <li>session gate: every /api/v1/orgs/{orgId}/spaces route 401s without a
 *       valid session (the M2c interceptor covers the nested prefix);</li>
 *   <li>tenant isolation / IDOR: a member of ANOTHER org can neither list nor
 *       fetch a foreign org's spaces — and even a user who is himself an org
 *       OWNER cannot pull a victim space id through his own org path; foreign
 *       and unknown ids answer byte-identical 404s;</li>
 *   <li>name validation (blank/&gt;200 -> 400) and the (org, name) uniqueness
 *       rule (duplicate -> 409).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:space-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SpaceApiIntegrationTest {

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

    /** Creates an org for the cookie holder and returns its id. */
    private String createOrg(String cookie, String name) {
        ResponseEntity<String> created = post("/api/v1/orgs", cookie, Map.of("name", name));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return extractId(created);
    }

    private ResponseEntity<String> get(String path, String cookie) {
        var builder = RequestEntity.get(URI.create(path));
        if (cookie != null) {
            builder = builder.header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie));
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
    void spaceEndpointsRequireSession() {
        List<RequestEntity<?>> probes = List.of(
                RequestEntity.get(URI.create("/api/v1/orgs/1/spaces")).build(),
                RequestEntity.get(URI.create("/api/v1/orgs/1/spaces/1")).build(),
                RequestEntity.post(URI.create("/api/v1/orgs/1/spaces"))
                        .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "x")));
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

    // ---- CRUD happy path ----

    @Test
    void memberCreatesListsAndFetchesSpaceThroughItsOrg() {
        String cookie = loginFreshUser("s-owner-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Space Owner Co " + NAME_SEQ.get());

        ResponseEntity<String> created = post("/api/v1/orgs/" + orgId + "/spaces", cookie,
                Map.of("name", "  Produktentwicklung  "));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String spaceId = extractId(created);
        assertThat(created.getBody()).contains("\"name\":\"Produktentwicklung\"")
                .contains("\"orgId\":" + orgId);

        ResponseEntity<String> list = get("/api/v1/orgs/" + orgId + "/spaces", cookie);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains("Produktentwicklung");

        ResponseEntity<String> one = get("/api/v1/orgs/" + orgId + "/spaces/" + spaceId, cookie);
        assertThat(one.getStatusCode().value()).isEqualTo(200);
        assertThat(one.getBody()).contains("\"id\":" + spaceId).contains("Produktentwicklung");
    }

    @Test
    void emptyOrgListIsAnEmptyArrayForMembers() {
        String cookie = loginFreshUser("s-empty-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Quiet Co " + NAME_SEQ.get());

        ResponseEntity<String> list = get("/api/v1/orgs/" + orgId + "/spaces", cookie);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).isEqualTo("[]");
    }

    // ---- name validation ----

    @Test
    void createSpaceValidatesName() {
        String cookie = loginFreshUser("s-valid-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Valid Co " + NAME_SEQ.get());
        String path = "/api/v1/orgs/" + orgId + "/spaces";

        assertThat(post(path, cookie, Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(post(path, cookie, Map.of("name", "   ")).getStatusCode().value()).isEqualTo(400);
        assertThat(post(path, cookie, Map.of("name", "x".repeat(201))).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void duplicateNameWithinOneOrgAnswers409OtherOrgMayReuseIt() {
        String anna = loginFreshUser("s-anna-" + NAME_SEQ.incrementAndGet());
        String bena = loginFreshUser("s-bena-" + NAME_SEQ.incrementAndGet());
        String annaOrg = createOrg(anna, "Anna GmbH " + NAME_SEQ.get());
        String benaOrg = createOrg(bena, "Bena GmbH " + NAME_SEQ.get());

        assertThat(post("/api/v1/orgs/" + annaOrg + "/spaces", anna, Map.of("name", "Dev"))
                .getStatusCode().value()).isEqualTo(201);
        // same name, same org -> conflict
        ResponseEntity<String> dup = post("/api/v1/orgs/" + annaOrg + "/spaces", anna,
                Map.of("name", "Dev"));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        // same name, different org -> fine (uniqueness is PER org)
        assertThat(post("/api/v1/orgs/" + benaOrg + "/spaces", bena, Map.of("name", "Dev"))
                .getStatusCode().value()).isEqualTo(201);
    }

    // ---- tenant isolation / IDOR ----

    @Test
    void foreignOrgSpaceRoutesAreUniform404() {
        String victim = loginFreshUser("s-victim-" + NAME_SEQ.incrementAndGet());
        String attacker = loginFreshUser("s-mallory-" + NAME_SEQ.incrementAndGet());
        String victimOrg = createOrg(victim, "Victor Industries " + NAME_SEQ.get());
        String attackerOrg = createOrg(attacker, "Mallory Ltd " + NAME_SEQ.get());

        ResponseEntity<String> space = post("/api/v1/orgs/" + victimOrg + "/spaces", victim,
                Map.of("name", "Geheimprojekt"));
        assertThat(space.getStatusCode().value()).isEqualTo(201);
        String spaceId = extractId(space);

        // attacker lists the VICTIM's org spaces -> 404 (not "[] with a hint")
        ResponseEntity<String> foreignList = get("/api/v1/orgs/" + victimOrg + "/spaces", attacker);
        assertThat(foreignList.getStatusCode().value()).isEqualTo(404);

        // victim's space id through the victim's org path (attacker not a member) -> 404
        ResponseEntity<String> foreignOne = get(
                "/api/v1/orgs/" + victimOrg + "/spaces/" + spaceId, attacker);
        assertThat(foreignOne.getStatusCode().value()).isEqualTo(404);
        assertThat(foreignOne.getBody() == null ? "" : foreignOne.getBody())
                .doesNotContain("Geheimprojekt");

        // byte-identical to a never-existing id -> no existence leak
        ResponseEntity<String> unknown = get("/api/v1/orgs/" + victimOrg + "/spaces/99999999", attacker);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody()).isEqualTo(foreignOne.getBody());

        // unknown org -> same 404 shape (foreign org indistinguishable from absent org)
        ResponseEntity<String> unknownOrg = get("/api/v1/orgs/99999999/spaces", attacker);
        assertThat(unknownOrg.getStatusCode().value()).isEqualTo(404);
        assertThat(unknownOrg.getBody()).isEqualTo(foreignList.getBody());

        // cross-path probe: victim's SPACE id under the ATTACKER's own org path -> 404
        ResponseEntity<String> crossPath = get(
                "/api/v1/orgs/" + attackerOrg + "/spaces/" + spaceId, attacker);
        assertThat(crossPath.getStatusCode().value()).isEqualTo(404);
        assertThat(crossPath.getBody() == null ? "" : crossPath.getBody())
                .doesNotContain("Geheimprojekt");
    }

    @Test
    void memberOfOneOrgNeverSeesAnotherOrgsSpacesInHisOwnList() {
        String victim = loginFreshUser("s-wendy-" + NAME_SEQ.incrementAndGet());
        String attacker = loginFreshUser("s-eve-" + NAME_SEQ.incrementAndGet());
        String victimOrg = createOrg(victim, "Wendy AG " + NAME_SEQ.get());
        String attackerOrg = createOrg(attacker, "Eve Corp " + NAME_SEQ.get());

        assertThat(post("/api/v1/orgs/" + victimOrg + "/spaces", victim, Map.of("name", "W-Geheim"))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(post("/api/v1/orgs/" + attackerOrg + "/spaces", attacker, Map.of("name", "E-Offen"))
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> attackerList = get("/api/v1/orgs/" + attackerOrg + "/spaces", attacker);
        assertThat(attackerList.getStatusCode().value()).isEqualTo(200);
        assertThat(attackerList.getBody()).contains("E-Offen").doesNotContain("W-Geheim");
    }

    /** Pulls the "id":N value out of a created-resource response body (same helper style as OrgApiIntegrationTest). */
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
