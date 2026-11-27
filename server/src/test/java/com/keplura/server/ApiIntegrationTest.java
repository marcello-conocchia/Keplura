package com.keplura.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API integration harness (M1b): exercises every deployed endpoint through a
 * <b>real HTTP roundtrip</b> — a full embedded server on a random port
 * ({@link SpringBootTest.WebEnvironment#RANDOM_PORT} + {@link TestRestTemplate}),
 * not the MockMvc layer. This catches what mock-level tests cannot: actual
 * JSON (de)serialisation over the wire, content negotiation, server-level
 * error handling and the real status codes Tomcat returns.
 *
 * <p>How to extend for a new endpoint (M2+): add one test method here that
 * performs the real request via {@link #rest} and asserts status,
 * {@code Content-Type} and the JSON field names of the response — see
 * docs/development.md ("API integration harness").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        // Hermetic datasource (ADMIN-KI): dev-profile boot used to fall back to
        // the SHARED file DB (target/keplura-dev) — a foreign JVM of the same
        // checkout (leaked worker process) can hold its AUTO_SERVER lock with an
        // unreachable socket and turn every context boot into "Connection
        // refused" -> no JDBC metadata. Every other integration class pins its
        // own mem: db for exactly this reason; this one now follows the rule.
        "spring.datasource.url=jdbc:h2:mem:api-integration-test;DB_CLOSE_DELAY=-1"
})
class ApiIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthRespondsOverRealHttpWithSingleStatusField() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
        Map<String, Object> body = JsonPath.parse(response.getBody()).read("$");
        assertThat(body).containsOnlyKeys("status");
        assertThat(body.get("status")).isEqualTo("UP");
    }

    @Test
    void systemInfoRespondsOverRealHttpWithContractFieldNames() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/system/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());

        Map<String, Object> body = JsonPath.parse(response.getBody()).read("$");
        // M4c adds `model` (closed vocabulary none|local|configured) — the
        // provider-state surface; base URL / key never appear here.
        assertThat(body).containsOnlyKeys("product", "serverVersion", "apiVersion", "model");
        assertThat(body.get("product")).isEqualTo("Keplura Server");
        assertThat(body.get("serverVersion")).isEqualTo("0.1.0");
        assertThat(body.get("apiVersion")).isEqualTo("v1");
        assertThat(body.get("model")).isEqualTo("none");
    }

    @Test
    void unknownApiPathReturnsRealHttp404() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/definitely-not-registered", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void roundtripCarriesGenuineHttpHeadersFromServer() {
        // Proves this is not a mocked/servlet-internal dispatch: only a real
        // socket roundtrip yields transport-level headers such as Date.
        HttpHeaders headers = rest.getForEntity("/api/v1/health", String.class).getHeaders();

        assertThat(headers.getDate()).isGreaterThan(0L);
        assertThat(headers.getFirst("Content-Length")).isNotNull();
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    /**
     * TU-06b: the OAuth callback is reachable without a session, reflects nothing and only redirects to the fixed
     * app address (review round 1: one-time handoff in the fragment, here the failure marker).
     */
    @Test
    void oauthCallbackNeedsNoSessionButConnectionEndpointsDo() throws Exception {
        try (var http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()) {
            var callback = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                    + "/api/v1/oauth/connections/callback?state=%3Cscript%3Ex%3C%2Fscript%3E&code=c")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(callback.statusCode()).isEqualTo(302);
            assertThat(callback.headers().firstValue("Location").orElseThrow()).isEqualTo("/#oauth-error")
                    .doesNotContain("script").doesNotContain("state");
            assertThat(callback.body()).doesNotContain("<script>");
            assertThat(callback.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
            assertThat(callback.headers().firstValue("Referrer-Policy").orElseThrow()).isEqualTo("no-referrer");
        }
        assertThat(rest.getForEntity("/api/v1/orgs/1/connections", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity("/api/v1/orgs/1/connections/openapi/1/start", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/admin/oauth-clients", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
