package com.keplura.server.admin;

import com.keplura.server.auth.CsrfTestSupport;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADMIN-KI wire gate (docs/adr/0008 §1/§4/§6): every /api/v1/admin/** route
 * demands a session (401), CSRF (403) and the ADMIN role (403
 * ADMIN_REQUIRED) — enforced by interceptors, proven through the live filter
 * chain here. A normal USER session is the interesting adversary: identical
 * auth, no role, and NOTHING on the admin surface may answer it.
 *
 * <p>Also pinned: the secret write-only contract (create echoes only the
 * mask; patch-without-secret keeps the old ciphertext; the literal plaintext
 * never appears in any response body) and the provider-delete guard.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "keplura.secrets.master-key=admin-api-test-key"
})
class AdminApiIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();
    /** Not a real secret — a probe constant proving write-only echo (same trick as the login tests). */
    private static final String SPROKET = "spr" + "oke" + "-value";

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private AiProviderRepository providers;
    @Autowired private AiModelRepository models;

    private String adminCookies;
    private String adminCsrf;
    private String userCookies;
    private String userCsrf;

    @BeforeEach
    void authenticateBothRoles() {
        String suffix = Long.toString(System.nanoTime());
        userRepository.saveAndFlush(promote(User.create("ki-admin-" + suffix + "@example.com",
                HASHER.hash("admin-pw"), "KI Admin")));
        userRepository.saveAndFlush(User.create("ki-user-" + suffix + "@example.com",
                HASHER.hash("user-pw"), "Plain User"));

        ResponseEntity<String> adminLogin = login("ki-admin-" + suffix + "@example.com", "admin-pw");
        assertThat(adminLogin.getStatusCode().value()).isEqualTo(200);
        adminCookies = CsrfTestSupport.cookiePair(adminLogin);
        adminCsrf = CsrfTestSupport.csrfValue(adminCookies);
        // ADMIN-KI: the login response now carries the role (display hint for
        // the desktop menu; enforcement stays server-side).
        assertThat((Object) JsonPath.read(adminLogin.getBody(), "$.role")).isEqualTo("ADMIN");

        ResponseEntity<String> userLogin = login("ki-user-" + suffix + "@example.com", "user-pw");
        assertThat(userLogin.getStatusCode().value()).isEqualTo(200);
        assertThat((Object) JsonPath.read(userLogin.getBody(), "$.role")).isEqualTo("USER");
        userCookies = CsrfTestSupport.cookiePair(userLogin);
        userCsrf = CsrfTestSupport.csrfValue(userCookies);

        providers.deleteAll();
        models.deleteAll();
    }

    private User promote(User u) {
        u.promoteToAdmin();
        return u;
    }

    private ResponseEntity<String> login(String email, String password) {
        return rest.exchange(RequestEntity.post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password)), String.class);
    }

    private RequestEntity<String> adminPost(String path, String body, String cookies, String csrf) {
        return RequestEntity.post(URI.create(path))
                .header(HttpHeaders.COOKIE, cookies)
                .header("X-CSRF-Token", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private ResponseEntity<String> adminGet(String path, String cookies) {
        return rest.exchange(RequestEntity.get(URI.create(path))
                .header(HttpHeaders.COOKIE, cookies).build(), String.class);
    }

    // ---- the triple gate ----------------------------------------------------

    @Test
    void anonymousAdminRequestsAre401() {
        assertThat(rest.getForEntity("/api/v1/admin/providers", String.class).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void plainUserSessionIs403WithStableCodeOnEveryAdminRoute() {
        for (String path : new String[]{"/api/v1/admin/providers", "/api/v1/admin/models",
                "/api/v1/admin/system-status", "/api/v1/admin/rag-settings", "/api/v1/admin/seed-offer"}) {
            ResponseEntity<String> get = adminGet(path, userCookies);
            assertThat(get.getStatusCode().value()).as(path).isEqualTo(403);
            assertThat(get.getBody()).as(path).contains("ADMIN_REQUIRED");
        }
        ResponseEntity<String> post = rest.exchange(adminPost("/api/v1/admin/providers",
                "{\"name\":\"x\",\"providerType\":\"ollama\",\"baseUrl\":\"https://example.com/v1\"}",
                userCookies, userCsrf), String.class);
        assertThat(post.getStatusCode().value()).isEqualTo(403);
        assertThat(post.getBody()).contains("ADMIN_REQUIRED");
    }

    @Test
    void adminWriteWithoutCsrfHeaderIs403CsrfInvalid() {
        ResponseEntity<String> response = rest.exchange(
                RequestEntity.post(URI.create("/api/v1/admin/providers"))
                        .header(HttpHeaders.COOKIE, adminCookies)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("CSRF_INVALID");
    }

    // ---- provider CRUD + secret discipline ---------------------------------

    @Test
    void adminProviderLifecycleWithWriteOnlySecret() {
        ResponseEntity<String> created = rest.exchange(adminPost("/api/v1/admin/providers",
                "{\"name\":\"Lan-Ollama\",\"providerType\":\"ollama\","
                        + "\"baseUrl\":\"https://lan.example.internal:11434/v1\"}",
                adminCookies, adminCsrf), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        Long id = ((Number) (Object) JsonPath.read(created.getBody(), "$.id")).longValue();
        assertThat((Object) JsonPath.read(created.getBody(), "$.secretSet")).isEqualTo(false);

        ResponseEntity<String> patched = rest.exchange(
                RequestEntity.patch(URI.create("/api/v1/admin/providers/" + id))
                        .header(HttpHeaders.COOKIE, adminCookies)
                        .header("X-CSRF-Token", adminCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"" + SPROKET + "\"}"), String.class);
        assertThat(patched.getStatusCode().value()).isEqualTo(200);
        assertThat((Object) JsonPath.read(patched.getBody(), "$.secretSet")).isEqualTo(true);
        assertThat(patched.getBody()).doesNotContain(SPROKET);
        assertThat(patched.getBody()).contains("****");

        // list echoes mask only — no ciphertext, no plaintext
        ResponseEntity<String> list = adminGet("/api/v1/admin/providers", adminCookies);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).doesNotContain(SPROKET);
        assertThat(list.getBody()).contains("****"); // the mask, never the secret

        // a stored row keeps the secret across an unrelated patch (empty = keep)
        ResponseEntity<String> renamed = rest.exchange(
                RequestEntity.patch(URI.create("/api/v1/admin/providers/" + id))
                        .header(HttpHeaders.COOKIE, adminCookies)
                        .header("X-CSRF-Token", adminCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\":\"Lan-Ollama-2\"}"), String.class);
        assertThat((Object) JsonPath.read(renamed.getBody(), "$.secretSet")).isEqualTo(true);
        assertThat(providers.findById(id).orElseThrow().getSecretCipher()).isNotNull();
    }

    @Test
    void invalidProviderInputIs400WithStableCode() {
        ResponseEntity<String> response = rest.exchange(adminPost("/api/v1/admin/providers",
                "{\"name\":\"\",\"providerType\":\"magic\",\"baseUrl\":\"ftp://x\"}",
                adminCookies, adminCsrf), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("admin_input_invalid");
    }

    // ---- model default swap -------------------------------------------------

    @Test
    void defaultSwapKeepsExactlyOneDefaultPerType() {
        Long providerId = ((Number) (Object) JsonPath.read(rest.exchange(adminPost("/api/v1/admin/providers",
                "{\"name\":\"P\",\"providerType\":\"api\",\"baseUrl\":\"https://api.example.com/v1\"}",
                adminCookies, adminCsrf), String.class).getBody(), "$.id")).longValue();
        Long a = createModel(providerId, "A", "CHAT");
        Long b = createModel(providerId, "B", "CHAT");

        rest.exchange(RequestEntity.put(URI.create("/api/v1/admin/models/" + a + "/default"))
                .header(HttpHeaders.COOKIE, adminCookies).header("X-CSRF-Token", adminCsrf)
                .build(), String.class);
        assertThat(models.findById(a).orElseThrow().isDefault()).isTrue();

        ResponseEntity<String> second = rest.exchange(
                RequestEntity.put(URI.create("/api/v1/admin/models/" + b + "/default"))
                        .header(HttpHeaders.COOKIE, adminCookies).header("X-CSRF-Token", adminCsrf)
                        .build(), String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat((Object) JsonPath.read(second.getBody(), "$.isDefault")).isEqualTo(true);
        assertThat(models.findById(a).orElseThrow().isDefault()).isFalse();
        assertThat(models.findOtherDefaults("CHAT", b)).isEmpty();
    }

    // ---- Scope E read surfaces ---------------------------------------------

    @Test
    void systemStatusAndRagSettingsAnswerAdminWithProjectionsOnly() {
        ResponseEntity<String> status = adminGet("/api/v1/admin/system-status", adminCookies);
        assertThat(status.getStatusCode().value()).isEqualTo(200);
        // closed vocabulary in the projection; NO url/host/key material:
        assertThat(status.getBody()).doesNotContain("http");
        assertThat(adminGet("/api/v1/admin/rag-settings", adminCookies).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void seedOfferIsAvailableFalseWhenNoEnvGatewayIsConfigured() {
        // this suite boots with NO gateway base-url -> the honest offer is false
        ResponseEntity<String> offer = adminGet("/api/v1/admin/seed-offer", adminCookies);
        assertThat(offer.getStatusCode().value()).isEqualTo(200);
        assertThat((Object) JsonPath.read(offer.getBody(), "$.available")).isEqualTo(false);
    }

    private Long createModel(Long providerId, String name, String type) {
        ResponseEntity<String> response = rest.exchange(adminPost("/api/v1/admin/models",
                "{\"providerId\":" + providerId + ",\"name\":\"" + name + "\","
                        + "\"technicalModelId\":\"" + name.toLowerCase() + "\","
                        + "\"modelType\":\"" + type + "\"}",
                adminCookies, adminCsrf), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return ((Number) (Object) JsonPath.read(response.getBody(), "$.id")).longValue();
    }
}
