package com.keplura.server;

import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2d (DesktopCorsConfig): the desktop webview origins must be able to make
 * credentialed calls to the auth/orgs endpoints, and no other origin may.
 * Real HTTP roundtrips per the M1b harness pattern — CORS is a wire-level
 * concern, MockMvc would not prove Tomcat applies it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:desktop-cors-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DesktopCorsIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();
    private static final String DEV_ORIGIN = "http://localhost:1420";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    private ResponseEntity<String> preflight(String path, String origin) {
        return rest.exchange(RequestEntity.method(HttpMethod.OPTIONS, URI.create(path))
                .header(HttpHeaders.ORIGIN, origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .build(), String.class);
    }

    @Test
    void preflightFromDevWebviewOriginIsAllowedWithCredentials() {
        ResponseEntity<String> response = preflight("/api/v1/auth/login", DEV_ORIGIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.POST);
    }

    @Test
    void preflightFromBothPackagedTauriOriginsIsAllowed() {
        // Security finding F2: macOS/Linux packaged origin is tauri://localhost,
        // Windows packaged origin is http://tauri.localhost — both on the default
        // allow-list, otherwise the built app fails preflight before login.
        for (String origin : new String[] {"tauri://localhost", "http://tauri.localhost"}) {
            ResponseEntity<String> response = preflight("/api/v1/auth/login", origin);

            assertThat(response.getStatusCode())
                    .as("preflight from %s", origin)
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
            assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        }
    }

    @Test
    void rateLimitedLoginExposesRetryAfterToTheWebview() {
        // QA finding (M2d gate): the desktop reads Retry-After to render the
        // 429 hint. On a credentialed cross-origin response fetch only exposes
        // headers listed in Access-Control-Expose-Headers — assert the real 429
        // (not just the preflight) carries the entry for the dev webview origin.
        userRepository.saveAndFlush(User.create("cors-ratelimit@example.com", HASHER.hash("pw"), "RL"));
        // LoginRateLimiter.MAX_ATTEMPTS is package-private; 10 is its documented budget.
        for (int i = 0; i < 10; i++) {
            rest.exchange(RequestEntity
                    .post(URI.create("/api/v1/auth/login"))
                    .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(Map.of("email", "cors-ratelimit@example.com", "password", "wrong" + i)),
                    String.class);
        }

        ResponseEntity<String> blocked = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Map.of("email", "cors-ratelimit@example.com", "password", "pw")),
                String.class);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .containsIgnoringCase("Retry-After");
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    void preflightFromForeignOriginGetsNoCorsHeaders() {
        ResponseEntity<String> response = preflight("/api/v1/auth/login", "https://evil.example");

        // The request itself is a browser-internal probe; what matters is that
        // NO allow-origin/allow-credentials header comes back for a foreign origin.
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void preflightForOrgCreationFromDevOriginIsAllowedWithCredentials() {
        // M2e: the desktop org-create form POSTs JSON with credentials — the
        // /api/v1/orgs mapping must allow POST + Content-Type, not just GET.
        ResponseEntity<String> response = preflight("/api/v1/orgs", DEV_ORIGIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.POST);
        // getAccessControlAllowHeaders() is a List<String> — join for the
        // case-insensitive contains (AssertJ has no containsIgnoringCase here).
        assertThat(String.join(",", response.getHeaders().getAccessControlAllowHeaders()))
                .containsIgnoringCase("Content-Type");
    }

    @Test
    void preflightForOrgCreationFromForeignOriginGetsNoCorsHeaders() {
        ResponseEntity<String> response = preflight("/api/v1/orgs", "https://evil.example");

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void rawOptionsWithoutPreflightHeadersStillHitsTheSessionGate() {
        // The interceptor exempts CORS preflight only (Origin +
        // Access-Control-Request-Method). A bare OPTIONS without the preflight
        // signature must still 401 on a protected path — method-confusion
        // defence in depth stays intact.
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .method(HttpMethod.OPTIONS, URI.create("/api/v1/orgs"))
                .build(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void simpleGetOnOrgsFromDevOriginEchoesAllowOriginWhenSessionValid() {
        userRepository.saveAndFlush(User.create("cors-dev@example.com", HASHER.hash("pw"), "Cors Dev"));
        ResponseEntity<String> login = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Map.of("email", "cors-dev@example.com", "password", "pw")), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie = com.keplura.server.auth.CsrfTestSupport.cookiePair(login);

        ResponseEntity<String> orgs = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs"))
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .header(HttpHeaders.COOKIE, cookie)
                .build(), String.class);

        assertThat(orgs.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Actual (non-preflight) response must ALSO carry the headers or the
        // browser blocks the body from the webview. The M2e expose-request:
        // Retry-After must be listed even on the orgs mapping.
        assertThat(orgs.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(orgs.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(orgs.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .containsIgnoringCase("Retry-After");
    }

    @Test
    void healthStaysOutsideTheCorsPolicy() {
        // /api/v1/health is operations-only and must not gain CORS by accident.
        ResponseEntity<String> response = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/health"))
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .build(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    // ---- M3c: nested org paths (spaces / knowledge-bases / documents) ----

    @Test
    void preflightForNestedUploadRouteFromDevOriginIsAllowed() {
        // The desktop uploads documents to the deepest nested path — the exact
        // M2e preflight-bug class ("/api/v1/orgs" does not match
        // "/api/v1/orgs/1/..."): the /** mapping must cover it.
        ResponseEntity<String> response = preflight(
                "/api/v1/orgs/1/spaces/1/knowledge-bases/1/documents", DEV_ORIGIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.POST);
        assertThat(String.join(",", response.getHeaders().getAccessControlAllowHeaders()))
                .containsIgnoringCase("Content-Type");
    }

    @Test
    void preflightForNestedRouteFromForeignOriginGetsNoCorsHeaders() {
        ResponseEntity<String> response = preflight(
                "/api/v1/orgs/1/spaces/1/knowledge-bases", "https://evil.example");

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void nestedGetExposesContentDispositionToTheWebview() {
        // Document downloads answer with Content-Disposition (attachment +
        // filename); fetch hides non-safelisted headers unless exposed — so
        // any credentialed nested response must list it (upload/list GETs
        // share the mapping; the spaces list is the lightest real route).
        userRepository.saveAndFlush(User.create("cors-nested@example.com", HASHER.hash("pw"), "Nested"));
        ResponseEntity<String> login = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "cors-nested@example.com", "password", "pw")), String.class);
        String cookie = com.keplura.server.auth.CsrfTestSupport.cookiePair(login);

        ResponseEntity<String> org = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                .body(Map.of("name", "CORS Nested Co")), String.class);
        assertThat(org.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        int idStart = org.getBody().indexOf("\"id\":") + 5;
        int idEnd = idStart;
        while (Character.isDigit(org.getBody().charAt(idEnd))) {
            idEnd++;
        }
        String orgId = org.getBody().substring(idStart, idEnd);

        ResponseEntity<String> spaces = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs/" + orgId + "/spaces"))
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .header(HttpHeaders.COOKIE, cookie)
                .build(), String.class);

        assertThat(spaces.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spaces.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(spaces.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(String.join(",", spaces.getHeaders().getAccessControlExposeHeaders()))
                .containsIgnoringCase("Content-Disposition");
    }

    // ---- M3e: the knowledge UI calls kb create/list, document upload/list
    // and the search route from the desktop webview. The /** mapping already
    // covered GET/POST + Content-Type + the exposed headers (M3c/M3d); these
    // tests PIN that for the exact routes the new UI hits, so a future
    // narrowing of the policy cannot silently break the knowledge flow. ----

    @Test
    void preflightForSearchRouteFromDevOriginIsAllowed() {
        // POST .../search is a JSON POST with explicit Content-Type -> always
        // preflighted. Same exact-match trap as M2e: "/api/v1/orgs/**" must
        // answer for the deepest path in the API.
        ResponseEntity<String> response = preflight(
                "/api/v1/orgs/1/spaces/1/knowledge-bases/1/search", DEV_ORIGIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.POST);
        assertThat(String.join(",", response.getHeaders().getAccessControlAllowHeaders()))
                .containsIgnoringCase("Content-Type");
    }

    @Test
    void preflightForSearchRouteFromForeignOriginGetsNoCorsHeaders() {
        ResponseEntity<String> response = preflight(
                "/api/v1/orgs/1/spaces/1/knowledge-bases/1/search", "https://evil.example");

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void kbListGetExposesIndexStatusToTheWebview() {
        // The knowledge UI renders indexStatus per document; the expose list
        // (X-Index-Status, M3d) must ride on the CREDENTIALED nested GET
        // response too, not only on preflight — same rule the M3d reviewers
        // applied to the download header. Real chain: org -> space -> kb.
        userRepository.saveAndFlush(User.create("cors-knowledge@example.com", HASHER.hash("pw"), "Kn"));
        ResponseEntity<String> login = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "cors-knowledge@example.com", "password", "pw")), String.class);
        String cookie = com.keplura.server.auth.CsrfTestSupport.cookiePair(login);

        ResponseEntity<String> org = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                .body(Map.of("name", "CORS Knowledge Co")), String.class);
        assertThat(org.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        int idStart = org.getBody().indexOf("\"id\":") + 5;
        int idEnd = idStart;
        while (Character.isDigit(org.getBody().charAt(idEnd))) {
            idEnd++;
        }
        String orgId = org.getBody().substring(idStart, idEnd);

        ResponseEntity<String> space = rest.exchange(RequestEntity
                .post(URI.create("/api/v1/orgs/" + orgId + "/spaces"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .header(HttpHeaders.COOKIE, cookie)
                    .header(com.keplura.server.auth.CsrfService.HEADER_NAME,
                            com.keplura.server.auth.CsrfTestSupport.csrfValue(cookie))
                .body(Map.of("name", "Wissen")), String.class);
        assertThat(space.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        int spaceStart = space.getBody().indexOf("\"id\":") + 5;
        int spaceEnd = spaceStart;
        while (Character.isDigit(space.getBody().charAt(spaceEnd))) {
            spaceEnd++;
        }

        ResponseEntity<String> kbs = rest.exchange(RequestEntity
                .get(URI.create("/api/v1/orgs/" + orgId + "/spaces/" + space.getBody().substring(spaceStart, spaceEnd)
                        + "/knowledge-bases"))
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .header(HttpHeaders.COOKIE, cookie)
                .build(), String.class);

        assertThat(kbs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kbs.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(kbs.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(String.join(",", kbs.getHeaders().getAccessControlExposeHeaders()))
                .containsIgnoringCase("X-Index-Status");
    }

    @Test
    void preflightForGatewayChatAllowsTenantHeaderAndForeignOriginStillFails() {
        // M4a: the desktop chat POST carries Content-Type PLUS the custom
        // X-Keplura-Org tenant header — custom headers are never CORS-simple,
        // so the gateway mapping must list it or the browser blocks every call.
        ResponseEntity<String> allowed = rest.exchange(RequestEntity
                .method(HttpMethod.OPTIONS, URI.create("/api/v1/gateway/chat/completions"))
                .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-keplura-org")
                .build(), String.class);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        assertThat(allowed.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(String.join(",", allowed.getHeaders().getAccessControlAllowHeaders()))
                .containsIgnoringCase("X-Keplura-Org");

        ResponseEntity<String> foreign = rest.exchange(RequestEntity
                .method(HttpMethod.OPTIONS, URI.create("/api/v1/gateway/chat/completions"))
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-keplura-org")
                .build(), String.class);
        assertThat(foreign.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void preflightForAdminRoutesAllowsWritesWithCsrfHeader() {
        // ADMIN-KI (found by the admin E2E): the admin panel runs in the
        // desktop webview, and pattern matching is EXACT — the gateway
        // mapping does NOT cover /api/v1/admin/**. Every admin write carries
        // Content-Type + X-CSRF-Token (pre-flighted), and provider/model
        // edits use PATCH/PUT: all four verbs must survive preflight or the
        // whole panel dies in the browser before the session gate is reached.
        for (String path : new String[] {"/api/v1/admin/providers", "/api/v1/admin/models/1/default"}) {
            ResponseEntity<String> allowed = rest.exchange(RequestEntity
                    .method(HttpMethod.OPTIONS, URI.create(path))
                    .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                    .header("Access-Control-Request-Method", "PATCH")
                    .header("Access-Control-Request-Headers", "content-type,x-csrf-token")
                    .build(), String.class);
            assertThat(allowed.getStatusCode()).as("preflight %s", path).isEqualTo(HttpStatus.OK);
            assertThat(allowed.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
            assertThat(allowed.getHeaders().getAccessControlAllowCredentials()).isTrue();
            assertThat(allowed.getHeaders().getAccessControlAllowMethods())
                    .as("verbs for %s", path)
                    .contains(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);
            assertThat(String.join(",", allowed.getHeaders().getAccessControlAllowHeaders()))
                    .containsIgnoringCase("X-CSRF-Token");
        }
        // Foreign origin still gets NOTHING (the gate never widens the list).
        ResponseEntity<String> foreign = rest.exchange(RequestEntity
                .method(HttpMethod.OPTIONS, URI.create("/api/v1/admin/providers"))
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-csrf-token")
                .build(), String.class);
        assertThat(foreign.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(foreign.getHeaders().getAccessControlAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }
}
