package com.keplura.server.auth;

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

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Tomcat headers, including Boot's SameSite cookie-processor override. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:session-cookie-config;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "server.servlet.session.cookie.name=CUSTOMSESSION",
        "server.servlet.session.cookie.path=/api",
        "server.servlet.session.cookie.domain=example.com",
        "server.servlet.session.cookie.secure=true",
        "server.servlet.session.cookie.same-site=strict",
        "server.servlet.session.cookie.max-age=8h",
        "keplura.auth.session-lifetime-seconds=42"
})
@AutoConfigureTestRestTemplate
class SessionCookieConfigurationIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;

    @Test
    void persistenceOnlyChangesLifetimeAndPreservesContainerCookieAttributes() {
        users.saveAndFlush(User.create("cookie-config@example.com", new PasswordHasher().hash("secret"), "Cookie"));
        for (boolean rememberMe : new boolean[] {false, true}) {
            var response = rest.exchange(RequestEntity.post(URI.create("/api/v1/auth/login"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", "cookie-config@example.com", "password", "secret", "rememberMe", rememberMe)),
                    String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            var cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            assertThat(cookies).hasSize(2);
            String session = cookies.stream().filter(c -> c.startsWith("CUSTOMSESSION=")).findFirst().orElseThrow();
            assertThat(session).contains("Path=/api", "Domain=example.com", "Secure", "HttpOnly", "SameSite=Strict");
            if (rememberMe) assertThat(session).contains("Max-Age=42").doesNotContain("Max-Age=28800");
            else assertThat(session).doesNotContain("Max-Age=").doesNotContain("Expires=");
        }
    }
}
