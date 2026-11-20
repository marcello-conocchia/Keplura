package com.keplura.server.auth;

import com.keplura.server.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import java.net.URI;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:security-fixes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.http.client.factory=simple",
    "server.tomcat.threads.min-spare=0", "server.tomcat.threads.max=8"
})
class SecurityFixesIntegrationTest {
    private static final String ORIGIN = "http://localhost:1420";
    private static final String PASSWORD = "synthetic-fixture-password";
    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired JdbcTemplate jdbc;

    record Login(User user, String cookie, String token) { }
    private Login login(boolean admin, boolean mustChange) {
        var user = User.create("security-" + System.nanoTime() + "@example.test", hasher.hash(PASSWORD), "Security Fixture");
        if (admin) user.promoteToAdmin();
        if (mustChange) user.issueStartPassword(user.getPasswordHash());
        user = users.saveAndFlush(user);
        var response = rest.exchange(RequestEntity.post(URI.create("/api/v1/auth/login"))
                .header(HttpHeaders.ORIGIN, ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", user.getEmail(), "password", PASSWORD)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String cookie = response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .map(s -> s.substring(0, s.indexOf(';'))).collect(java.util.stream.Collectors.joining("; "));
        String token = com.jayway.jsonpath.JsonPath.read(response.getBody(), "$.csrfToken");
        return new Login(user, cookie, token);
    }

    private ResponseEntity<String> preflight(String path, String verb, String origin) {
        return rest.exchange(RequestEntity.options(URI.create(path)).header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, verb)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-csrf-token").build(), String.class);
    }

    @Test void restoredAdminProfileAndPasswordChangeAreReadableAcrossOrigins() {
        var login = login(true, true);
        var me = rest.exchange(RequestEntity.get(URI.create("/api/v1/auth/me"))
                .header(HttpHeaders.COOKIE, login.cookie()).header(HttpHeaders.ORIGIN, ORIGIN).build(), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ORIGIN);
        assertThat(me.getBody()).contains("Security Fixture", "\"role\":\"ADMIN\"", "\"mustChangePassword\":true");
        assertThat(preflight("/api/v1/auth/password", "POST", ORIGIN).getHeaders().getAccessControlAllowOrigin()).isEqualTo(ORIGIN);
        var changed = rest.exchange(RequestEntity.post(URI.create("/api/v1/auth/password"))
                .header(HttpHeaders.COOKIE, login.cookie()).header(HttpHeaders.ORIGIN, ORIGIN)
                .header(CsrfService.HEADER_NAME, login.token()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "changed-synthetic-password")), String.class);
        assertThat(changed.getStatusCode().value()).isEqualTo(204);
        assertThat(changed.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ORIGIN);
        assertThat(users.findById(login.user().getId()).orElseThrow().isMustChangePassword()).isFalse();
    }

    @Test void adminDeletePreflightDoesNotWeakenCsrfOrRoleGates() {
        String name = "fixture-" + System.nanoTime();
        jdbc.update("insert into mcp_server(name,url,enabled,created_at) values (?, ?, false, current_timestamp)", name, "https://example.test");
        Long id = jdbc.queryForObject("select id from mcp_server where name=?", Long.class, name);
        String path = "/api/v1/admin/mcp-servers/" + id;
        var preflight = preflight(path, "DELETE", ORIGIN);
        assertThat(preflight.getStatusCode().value()).isEqualTo(200);
        assertThat(preflight.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.DELETE);
        var admin = login(true, false);
        var plain = login(false, false);
        assertThat(rest.exchange(RequestEntity.delete(URI.create(path)).header(HttpHeaders.COOKIE, admin.cookie())
                .header(HttpHeaders.ORIGIN, ORIGIN).build(), String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(rest.exchange(RequestEntity.delete(URI.create(path)).header(HttpHeaders.COOKIE, plain.cookie())
                .header(CsrfService.HEADER_NAME, plain.token()).header(HttpHeaders.ORIGIN, ORIGIN)
                .build(), String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(rest.exchange(RequestEntity.delete(URI.create(path)).header(HttpHeaders.COOKIE, admin.cookie())
                .header(CsrfService.HEADER_NAME, admin.token()).header(HttpHeaders.ORIGIN, ORIGIN)
                .build(), String.class).getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select count(*) from mcp_server where id=?", Integer.class, id)).isZero();
    }

    @Test void foreignOriginsRemainBlockedOnEveryNewCorsSurface() {
        for (String path : new String[]{"/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/admin/mcp-servers/1"}) {
            String method = path.endsWith("/me") ? "GET" : path.endsWith("/password") ? "POST" : "DELETE";
            var response = preflight(path, method, "https://foreign.example.test");
            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        }
    }

    @Test void adminResetRestrictsAnAlreadyExistingSession() {
        var login = login(false, false);
        var user = users.findById(login.user().getId()).orElseThrow();
        user.issueStartPassword(hasher.hash("replacement-start-password"));
        users.saveAndFlush(user);
        var response = rest.exchange(RequestEntity.post(URI.create("/api/v1/orgs"))
                .header(HttpHeaders.COOKIE, login.cookie()).header(CsrfService.HEADER_NAME, login.token())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Must not be created")), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("password change required");
    }
}
