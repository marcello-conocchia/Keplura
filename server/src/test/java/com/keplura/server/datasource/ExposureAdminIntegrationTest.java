package com.keplura.server.datasource;

import com.keplura.server.auth.CsrfTestSupport;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RISK-01 wire gate: Oversharing-Check und Ausschluss liegen unter
 * /api/v1/admin/** (Session + CSRF + ADMIN) und ändern nichts außer der
 * Ausschlussliste der Quelle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:exposure-admin-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "keplura.secrets.master-key=exposure-admin-test-key"
})
class ExposureAdminIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSourceRepository sources;

    private String adminCookies, adminCsrf, userCookies, userCsrf;
    private Long sourceId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        User admin = User.create("risk-admin-" + suffix + "@example.com", HASHER.hash("admin-pw"), "Risk Admin");
        admin.promoteToAdmin();
        userRepository.saveAndFlush(admin);
        userRepository.saveAndFlush(User.create("risk-user-" + suffix + "@example.com", HASHER.hash("user-pw"), "User"));
        ResponseEntity<String> a = login("risk-admin-" + suffix + "@example.com", "admin-pw");
        adminCookies = CsrfTestSupport.cookiePair(a);
        adminCsrf = CsrfTestSupport.csrfValue(adminCookies);
        ResponseEntity<String> u = login("risk-user-" + suffix + "@example.com", "user-pw");
        userCookies = CsrfTestSupport.cookiePair(u);
        userCsrf = CsrfTestSupport.csrfValue(userCookies);

        DataSource ds = new DataSource(1L, 1L, 1L, admin.getId());
        ds.setKind(DataSource.KIND_LOCAL);
        ds.setDisplayName("Test");
        ds.setLocation("/nicht/freigegeben");
        ds.setIncludePatterns("");
        ds.setExcludePatterns("**/~$*");
        ds.setMaxFileBytes(0);
        ds.setMirrorPermissions(true);
        ds.setIntervalMinutes(15);
        ds.setEnabled(false);
        sourceId = sources.saveAndFlush(ds).getId();
    }

    private ResponseEntity<String> login(String email, String password) {
        return rest.exchange(RequestEntity.post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password)), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String cookies, String csrf) {
        return rest.exchange(RequestEntity.post(URI.create(path)).header(HttpHeaders.COOKIE, cookies)
                .header("X-CSRF-Token", csrf).contentType(MediaType.APPLICATION_JSON).body(body), String.class);
    }

    private ResponseEntity<String> get(String path, String cookies) {
        return rest.exchange(RequestEntity.get(URI.create(path)).header(HttpHeaders.COOKIE, cookies).build(), String.class);
    }

    private String base() {
        return "/api/v1/admin/datasources/" + sourceId;
    }

    @Test
    void plainUsersCannotScanReadOrExclude() {
        assertThat(post(base() + "/exposure", "{}", userCookies, userCsrf).getStatusCode().value()).isEqualTo(403);
        assertThat(get(base() + "/exposure", userCookies).getStatusCode().value()).isEqualTo(403);
        assertThat(post(base() + "/exclude", "{\"paths\":[\"a.pdf\"]}", userCookies, userCsrf).getStatusCode().value())
                .isEqualTo(403);
        assertThat(rest.getForEntity(base() + "/exposure", String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(sources.findById(sourceId).orElseThrow().getExcludePatterns()).isEqualTo("**/~$*");
    }

    @Test
    void writesNeedCsrf() {
        assertThat(post(base() + "/exclude", "{\"paths\":[\"a.pdf\"]}", adminCookies, "falsch").getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void reportIs404BeforeAScanAndForUnknownSources() {
        assertThat(get(base() + "/exposure", adminCookies).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/api/v1/admin/datasources/999999/exposure", adminCookies).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/v1/admin/datasources/999999/exposure", "{}", adminCookies, adminCsrf)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void scanOfAnInaccessibleFolderEndsAsFailedReport() throws InterruptedException {
        assertThat(post(base() + "/exposure", "{}", adminCookies, adminCsrf).getStatusCode().value()).isEqualTo(202);
        String state = "RUNNING";
        ResponseEntity<String> r = null;
        for (int i = 0; i < 100 && "RUNNING".equals(state); i++) {
            Thread.sleep(50);
            r = get(base() + "/exposure", adminCookies);
            state = JsonPath.read(r.getBody(), "$.state");
        }
        assertThat(state).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(r.getBody(), "$.message")).contains("Ordner");
    }

    @Test
    void excludeAppendsExactEscapedPatternsOnce() {
        String body = "{\"paths\":[\"Personal/Gehalt [2025], Kopie.pdf\",\"Personal/Gehalt [2025], Kopie.pdf\"]}";
        ResponseEntity<String> r = post(base() + "/exclude", body, adminCookies, adminCsrf);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(sources.findById(sourceId).orElseThrow().getExcludePatterns())
                .isEqualTo("**/~$*, personal/gehalt \\[2025\\]? kopie.pdf");
    }

    @Test
    void excludeRejectsTraversalEmptyAndTooManyPaths() {
        assertThat(post(base() + "/exclude", "{\"paths\":[\"../etc/passwd\"]}", adminCookies, adminCsrf)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(post(base() + "/exclude", "{\"paths\":[\"/abs.pdf\"]}", adminCookies, adminCsrf)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(post(base() + "/exclude", "{\"paths\":[]}", adminCookies, adminCsrf)
                .getStatusCode().value()).isEqualTo(400);
        String many = IntStream.range(0, 201).mapToObj(i -> "\"f" + i + ".pdf\"").collect(Collectors.joining(","));
        assertThat(post(base() + "/exclude", "{\"paths\":[" + many + "]}", adminCookies, adminCsrf)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(sources.findById(sourceId).orElseThrow().getExcludePatterns()).isEqualTo("**/~$*");
    }
}
