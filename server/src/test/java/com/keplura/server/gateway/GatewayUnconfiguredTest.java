package com.keplura.server.gateway;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.org.OrgService;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4a: the UNCONFIGURED state — no base URL (the shipped dev/prod default,
 * ENV-free checkout) must answer honestly and stop there:
 *
 * <ul>
 *   <li>{@code GET /status} returns exactly {@code {"configured": false}} —
 *       the session+org gate runs FIRST, so an anonymous probe cannot even
 *       learn whether a gateway exists;</li>
 *   <li>{@code POST /chat/completions} is a clean 503 before any upstream
 *       contact (no key, no URL, no half-call);</li>
 *   <li>the profile files carry no literal key or URL: both properties resolve
 *       from ENV placeholders with empty defaults.</li>
 * </ul>
 *
 * <p>Separate context from GatewayChatApiIntegrationTest on purpose: that one
 * configures the gateway against a mock; this one proves the shipped default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gateway-offline-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class GatewayUnconfiguredTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment env;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgService orgService;

    private MockHttpSession memberSession() {
        // unique email per call: same-servletContext tests must not collide on the
        // APP_USER email constraint when several tests need their own session
        User user = userRepository.saveAndFlush(User.create(
                "gw-offline-" + System.nanoTime() + "@example.com",
                new PasswordHasher().hash("pw"), "offline"));
        long orgId = orgService.createOrg(user.getId(), "Offline Org").getId();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.SESSION_PRINCIPAL_ATTR, new AuthPrincipal(user.getId(), "offline"));
        // SEC-2: this in-JVM session never went through a real login, so it has
        // no CSRF token bound — bind one and hand it back via attribute
        // readback (same trick as test.orgId) for the state-changing probes.
        session.setAttribute("test.csrf",
                com.keplura.server.auth.CsrfTestSupport.bindToSession(session));
        // remember the org id for header building via attribute readback
        session.setAttribute("test.orgId", String.valueOf(orgId));
        return session;
    }

    @Test
    void anonymousCallersGetTheSame401OnBothGatewayRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/gateway/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/gateway/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"m\",\"messages\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statusReportsConfiguredFalseAndNothingElse() throws Exception {
        MockHttpSession session = memberSession();
        mockMvc.perform(get("/api/v1/gateway/status").session(session)
                        .header(GatewayController.ORG_HEADER, (String) session.getAttribute("test.orgId")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // EXACT body: one boolean field, no key, no URL, no model list
                .andExpect(content().json("{\"configured\":false}", true));
    }

    @Test
    void chatAgainstUnconfiguredGatewayIs503BeforeAnyUpstreamContact() throws Exception {
        MockHttpSession session = memberSession();
        String csrf = (String) session.getAttribute("test.csrf");
        mockMvc.perform(post("/api/v1/gateway/chat/completions").session(session)
                        .cookie(new jakarta.servlet.http.Cookie(
                                com.keplura.server.auth.CsrfService.COOKIE_NAME, csrf))
                        .header(com.keplura.server.auth.CsrfService.HEADER_NAME, csrf)
                        .header(GatewayController.ORG_HEADER, (String) session.getAttribute("test.orgId"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("model gateway not configured"));
    }

    @Test
    void foreignOrUnknownOrgStillUniformly404sEvenWhenUnconfigured() throws Exception {
        MockHttpSession session = memberSession();
        mockMvc.perform(get("/api/v1/gateway/status").session(session)
                        .header(GatewayController.ORG_HEADER, "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("organization not found"));
    }

    @Test
    void shippedProfileDefaultsCarryNoLiteralSecretsOrUrls() {
        // the properties files are pure ENV indirection (checked at the env level,
        // which resolves the ${VAR:default} placeholders the same way a boot does)
        assertThat(env.getProperty("keplura.gateway.base-url")).isEmpty();
        assertThat(env.getProperty("keplura.gateway.api-key")).isEmpty();
        assertThat(env.getProperty("keplura.gateway.allowed-models")).isEmpty();
    }
}
