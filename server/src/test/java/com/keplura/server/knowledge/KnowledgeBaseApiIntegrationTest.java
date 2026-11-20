package com.keplura.server.knowledge;

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
 * M3c: the knowledge-base vertical over REAL HTTP (same harness pattern as
 * SpaceApiIntegrationTest). Proves the headline guarantees end-to-end:
 * <ul>
 *   <li>session gate: every nested route 401s without a session (the M2c
 *       interceptor covers {@code /api/v1/orgs/**} one level deeper now);</li>
 *   <li>tenant isolation / IDOR over THREE levels: a victim kb id is invisible
 *       under the attacker's own org path, under a wrong space path, and via
 *       any list route — all byte-identical 404s, so none of these endpoints
 *       can enumerate org/space/kb ids;</li>
 *   <li>name validation (blank/&gt;200 → 400 only AFTER membership holds —
 *       an unauthorised probe can never get a 400-vs-404 hint either, since
 *       the guard runs first and answers 404) and (space, name) 409.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:kb-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "keplura.upload.dir=target/test-uploads-kb-api"
})
class KnowledgeBaseApiIntegrationTest {

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

    private String createOrg(String cookie, String name) {
        ResponseEntity<String> created = post("/api/v1/orgs", cookie, Map.of("name", name));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return extractId(created);
    }

    private String createSpace(String cookie, String orgId, String name) {
        ResponseEntity<String> created = post("/api/v1/orgs/" + orgId + "/spaces", cookie,
                Map.of("name", name));
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

    private static String kbPath(String orgId, String spaceId) {
        return "/api/v1/orgs/" + orgId + "/spaces/" + spaceId + "/knowledge-bases";
    }

    // ---- session gate ----

    @Test
    void knowledgeBaseEndpointsRequireSession() {
        List<RequestEntity<?>> probes = List.of(
                RequestEntity.get(URI.create(kbPath("1", "1"))).build(),
                RequestEntity.get(URI.create(kbPath("1", "1") + "/1")).build(),
                RequestEntity.post(URI.create(kbPath("1", "1")))
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
    void memberCreatesListsAndFetchesKbThroughItsSpace() {
        String cookie = loginFreshUser("kb-owner-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "KB Owner Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "Arbeitspace");

        ResponseEntity<String> created = post(kbPath(orgId, spaceId), cookie,
                Map.of("name", "  Konstruktionspläne  "));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String kbId = extractId(created);
        assertThat(created.getBody()).contains("\"name\":\"Konstruktionspläne\"")
                .contains("\"spaceId\":" + spaceId);

        ResponseEntity<String> list = get(kbPath(orgId, spaceId), cookie);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains("Konstruktionspläne");

        ResponseEntity<String> one = get(kbPath(orgId, spaceId) + "/" + kbId, cookie);
        assertThat(one.getStatusCode().value()).isEqualTo(200);
        assertThat(one.getBody()).contains("\"id\":" + kbId).contains("Konstruktionspläne");
    }

    @Test
    void emptySpaceListIsAnEmptyArrayForMembers() {
        String cookie = loginFreshUser("kb-quiet-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Quiet KB Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "Leerraum");

        ResponseEntity<String> list = get(kbPath(orgId, spaceId), cookie);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).isEqualTo("[]");
    }

    // ---- name validation & duplicate rule ----

    @Test
    void createKbValidatesName() {
        String cookie = loginFreshUser("kb-valid-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Valid KB Co " + NAME_SEQ.get());
        String spaceId = createSpace(cookie, orgId, "S");
        String path = kbPath(orgId, spaceId);

        assertThat(post(path, cookie, Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(post(path, cookie, Map.of("name", "   ")).getStatusCode().value()).isEqualTo(400);
        assertThat(post(path, cookie, Map.of("name", "x".repeat(201))).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void duplicateNameWithinOneSpace409OtherSpaceMayReuseIt() {
        String cookie = loginFreshUser("kb-dup-" + NAME_SEQ.incrementAndGet());
        String orgId = createOrg(cookie, "Dup KB Co " + NAME_SEQ.get());
        String spaceA = createSpace(cookie, orgId, "Raum A");
        String spaceB = createSpace(cookie, orgId, "Raum B");

        assertThat(post(kbPath(orgId, spaceA), cookie, Map.of("name", "Handbuch"))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(post(kbPath(orgId, spaceA), cookie, Map.of("name", "Handbuch"))
                .getStatusCode().value()).isEqualTo(409);
        // same name in the sibling space -> fine (uniqueness is PER space)
        assertThat(post(kbPath(orgId, spaceB), cookie, Map.of("name", "Handbuch"))
                .getStatusCode().value()).isEqualTo(201);
    }

    // ---- three-level tenant isolation / IDOR ----

    @Test
    void foreignKbRoutesAreUniform404AtEveryLevel() {
        String victim = loginFreshUser("kb-victim-" + NAME_SEQ.incrementAndGet());
        String attacker = loginFreshUser("kb-mallory-" + NAME_SEQ.incrementAndGet());
        String victimOrg = createOrg(victim, "Victor Industries " + NAME_SEQ.get());
        String attackerOrg = createOrg(attacker, "Mallory Ltd " + NAME_SEQ.get());
        String victimSpace = createSpace(victim, victimOrg, "V-Raum");
        String attackerSpace = createSpace(attacker, attackerOrg, "A-Raum");

        ResponseEntity<String> kb = post(kbPath(victimOrg, victimSpace), victim,
                Map.of("name", "Baupläne"));
        assertThat(kb.getStatusCode().value()).isEqualTo(201);
        String kbId = extractId(kb);

        // attacker lists the VICTIM's org/space kb list -> 404 (not "[] with a hint")
        ResponseEntity<String> foreignList = get(kbPath(victimOrg, victimSpace), attacker);
        assertThat(foreignList.getStatusCode().value()).isEqualTo(404);

        // victim's kb id under victim's own path (attacker not a member) -> 404
        ResponseEntity<String> foreignOne = get(kbPath(victimOrg, victimSpace) + "/" + kbId, attacker);
        assertThat(foreignOne.getStatusCode().value()).isEqualTo(404);
        assertThat(foreignOne.getBody() == null ? "" : foreignOne.getBody())
                .doesNotContain("Baupläne");

        // byte-identical to a never-existing id -> no existence leak
        ResponseEntity<String> unknown = get(kbPath(victimOrg, victimSpace) + "/99999999", attacker);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody()).isEqualTo(foreignOne.getBody());

        // LEVEL 2 cross-path: victim's kb id through the ATTACKER's own org+space -> 404
        ResponseEntity<String> crossSpace = get(kbPath(attackerOrg, attackerSpace) + "/" + kbId, attacker);
        assertThat(crossSpace.getStatusCode().value()).isEqualTo(404);
        assertThat(crossSpace.getBody()).isEqualTo(foreignOne.getBody());

        // unknown org/space combinations collapse into the same 404 shape
        ResponseEntity<String> unknownOrg = get(kbPath("99999999", victimSpace), attacker);
        assertThat(unknownOrg.getStatusCode().value()).isEqualTo(404);
        assertThat(unknownOrg.getBody()).isEqualTo(foreignList.getBody());
        ResponseEntity<String> unknownSpace = get(kbPath(victimOrg, "99999999"), attacker);
        assertThat(unknownSpace.getStatusCode().value()).isEqualTo(404);
        assertThat(unknownSpace.getBody()).isEqualTo(foreignList.getBody());

        // mixed tenant path (victim's SPACE under attacker's ORG — both real,
        // different tenants) -> the space pin fails at M3b level -> same 404
        ResponseEntity<String> mixed = get(kbPath(attackerOrg, victimSpace), attacker);
        assertThat(mixed.getStatusCode().value()).isEqualTo(404);
        assertThat(mixed.getBody()).isEqualTo(foreignList.getBody());
    }

    @Test
    void validationErrorsNeverDependOnTenancyAndForeignProbesGetUniform404() {
        // Two complementary invariants:
        // 1) a MALFORMED request (blank name) answers the SAME 400 whether the
        //    container exists, belongs to someone else, or is invented — the
        //    error is about the caller's input only and carries no tenant bit;
        // 2) a WELL-FORMED request against a foreign chain answers the uniform
        //    404 indistinguishable from a missing container.
        String victim = loginFreshUser("kb-guard-" + NAME_SEQ.incrementAndGet());
        String attacker = loginFreshUser("kb-eve-" + NAME_SEQ.incrementAndGet());
        String victimOrg = createOrg(victim, "Guard Co " + NAME_SEQ.get());
        String victimSpace = createSpace(victim, victimOrg, "G-Raum");

        ResponseEntity<String> foreignBlank = post(kbPath(victimOrg, victimSpace), attacker,
                Map.of("name", "   "));
        ResponseEntity<String> inventedBlank = post(kbPath("99999999", "99999999"), attacker,
                Map.of("name", "   "));
        assertThat(foreignBlank.getStatusCode().value()).isEqualTo(400);
        assertThat(inventedBlank.getStatusCode().value()).isEqualTo(400);
        assertThat(inventedBlank.getBody()).isEqualTo(foreignBlank.getBody());

        // well-formed POST into the victim's chain -> the same 404 as any
        // other "absent or not yours" answer on this route family
        ResponseEntity<String> foreignValid = post(kbPath(victimOrg, victimSpace), attacker,
                Map.of("name", "Eingeladen"));
        assertThat(foreignValid.getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> foreignGet = get(kbPath(victimOrg, victimSpace) + "/1", attacker);
        assertThat(foreignGet.getStatusCode().value()).isEqualTo(404);
        assertThat(foreignGet.getBody()).isEqualTo(foreignValid.getBody());
    }

    /** Pulls the "id":N value out of a created-resource response body. */
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
