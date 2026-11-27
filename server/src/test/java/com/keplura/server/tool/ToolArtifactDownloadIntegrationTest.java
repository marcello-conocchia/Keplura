package com.keplura.server.tool;

import com.keplura.server.auth.CsrfService;
import com.keplura.server.auth.CsrfTestSupport;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding 9: the artifact download through the real HTTP stack (session interceptor, routing, Tomcat):
 * anonymous 401, an authenticated person of another tenant 404, and the fixed security headers on the actual
 * response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:artifact-download-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "server.tomcat.threads.min-spare=0",
        "server.tomcat.threads.max=8",
        "spring.http.client.factory=simple"
})
class ToolArtifactDownloadIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private record Login(String cookie, long userId) { }

    private Login login(String label) {
        String email = label + System.nanoTime() + "@example.com";
        var user = users.saveAndFlush(User.create(email, HASHER.hash("pw-" + label), label));
        ResponseEntity<String> response = rest.exchange(RequestEntity.post(URI.create("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("email", email, "password", "pw-" + label)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return new Login(CsrfTestSupport.cookiePair(response), user.getId());
    }

    private String post(String path, Login who, Object body) {
        var response = rest.exchange(RequestEntity.post(URI.create(path)).header(HttpHeaders.COOKIE, who.cookie())
                .header(CsrfService.HEADER_NAME, CsrfTestSupport.csrfValue(who.cookie()))
                .contentType(MediaType.APPLICATION_JSON).body(body), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path).isTrue();
        return String.valueOf((Object) JsonPath.read(response.getBody(), "$.id"));
    }

    private ResponseEntity<byte[]> get(String path, Login who) {
        var builder = RequestEntity.get(URI.create(path));
        if (who != null) builder.header(HttpHeaders.COOKIE, who.cookie());
        return rest.exchange(builder.build(), byte[].class);
    }

    private long id(String sql, Object... args) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> {
            var ps = c.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** One stored artifact of the owner's chat; returns its public id. */
    private String artifact(long owner, long org, long space, long chat, String name, String type, byte[] data) {
        long call = id("""
                insert into tool_call(public_id, conversation_id, user_id, org_id, space_id, adapter, tool_id, function_name, tool_name,
                  source_name, risk, definition_hash, status, needs_confirmation, created_at)
                values (?,?,?,?,?,'files','files:1:get','files_get','get','Dateien','READ','h','DONE',false,current_timestamp)""",
                UUID.randomUUID().toString(), chat, owner, org, space);
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into tool_artifact(public_id, tool_call_id, conversation_id, user_id, org_id, space_id, name, media_type, size_bytes,
                  sha256, data, internal, created_at) values (?,?,?,?,?,?,?,?,?,'x',?,false,current_timestamp)""",
                publicId, call, chat, owner, org, space, name, type, data.length, data);
        return publicId;
    }

    @Test void anonymousIs401OtherTenantIs404AndTheOwnerGetsTheFixedHeaders() {
        Login owner = login("owner");
        Login stranger = login("stranger");
        long org = Long.parseLong(post("/api/v1/orgs", owner, Map.of("name", "Org " + System.nanoTime())));
        long space = Long.parseLong(post("/api/v1/orgs/" + org + "/spaces", owner, Map.of("name", "Raum")));
        long chat = id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) "
                + "values (?,?,?, 'Chat', current_timestamp, current_timestamp)", org, space, owner.userId());
        byte[] png = ToolArtifactTest.png(2, 2);
        String image = artifact(owner.userId(), org, space, chat, "ergebnis-1.png", "image/png", png);
        String pdf = artifact(owner.userId(), org, space, chat, "ergebnis-2.pdf", "application/pdf",
                "%PDF-1.4 x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String base = "/api/v1/orgs/" + org + "/chat/conversations/" + chat + "/tool-artifacts/";

        assertThat(get(base + image, null).getStatusCode().value()).isEqualTo(401);
        assertThat(get(base + image, stranger).getStatusCode().value()).isEqualTo(404);
        // The stranger in an org of their own still cannot reach the owner's artifact through that org.
        long strangerOrg = Long.parseLong(post("/api/v1/orgs", stranger, Map.of("name", "Fremd " + System.nanoTime())));
        assertThat(get("/api/v1/orgs/" + strangerOrg + "/chat/conversations/" + chat + "/tool-artifacts/" + image, stranger)
                .getStatusCode().value()).isEqualTo(404);

        var ok = get(base + image, owner);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).isEqualTo(png);
        HttpHeaders h = ok.getHeaders();
        assertThat(h.getContentType().toString()).isEqualTo("image/png");
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("Content-Security-Policy")).isEqualTo("sandbox; default-src 'none'");
        assertThat(h.getFirst("Cache-Control")).isEqualTo("private, no-store");
        assertThat(h.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(h.getFirst("Content-Disposition")).startsWith("inline");

        var file = get(base + pdf, owner);
        assertThat(file.getStatusCode().value()).isEqualTo(200);
        assertThat(file.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(file.getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
        assertThat(file.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
