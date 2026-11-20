package com.keplura.server.chat;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.identity.PasswordHasher;
import com.keplura.server.identity.User;
import com.keplura.server.identity.UserRepository;
import com.keplura.server.org.OrgService;
import com.keplura.server.space.Space;
import com.keplura.server.space.SpaceService;

import org.junit.jupiter.api.Test;
import org.hamcrest.Matchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4b: the shipped default state — NO model gateway configured (the dev and
 * prod profiles both leave base-url empty; ENV-free checkout). Chat must stay
 * honest there:
 *
 * <ul>
 *   <li>conversation CRUD + transcript keep working (persistence never needs
 *       the gateway);</li>
 *   <li>EVERY stream attempt is a clean 503 BEFORE the prompt is committed —
 *       a turn that cannot run must not leave a dangling user row behind;</li>
 *   <li>anonymous callers still hit the session gate (401) first.</li>
 * </ul>
 *
 * <p>MockMvc (no servlet container) on purpose: the suite runs under a tight
 * container pid budget and every extra live Tomcat costs threads; this class
 * needs no real socket (503 is a JSON error, not a stream). Separate property
 * keys keep it out of the shared context cache, mirroring the M4a
 * configured/unconfigured split.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-unconfigured-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ChatUnconfiguredGatewayTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgService orgService;

    @Autowired
    private SpaceService spaceService;

    private record Fixture(MockHttpSession session, String csrf, long orgId, long spaceId) {
    }

    /** Full chain in-JVM (no HTTP login): user + own org + space + session.
     *  SEC-2: the session additionally carries a CSRF token, echoed by each
     *  state-changing call as cookie + header (the MockMvc double-submit). */
    private Fixture fixture() {
        String label = "chat-off-" + System.nanoTime();
        User user = userRepository.saveAndFlush(User.create(
                label + "@example.com", new PasswordHasher().hash("pw"), label));
        long orgId = orgService.createOrg(user.getId(), "Chat Off Org " + System.nanoTime()).getId();
        Space space = spaceService.createSpace(user.getId(), orgId, "Chat Off Space");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthService.SESSION_PRINCIPAL_ATTR,
                new AuthPrincipal(user.getId(), label));
        String csrf = com.keplura.server.auth.CsrfTestSupport.bindToSession(session);
        return new Fixture(session, csrf, orgId, space.getId());
    }

    private static long conversationId(String createBody) {
        int start = createBody.indexOf("\"id\":") + 5;
        int end = start;
        while (end < createBody.length() && Character.isDigit(createBody.charAt(end))) {
            end++;
        }
        return Long.parseLong(createBody.substring(start, end));
    }

    @Test
    void crudWorksButStreamingRefuses503WithoutGhostRows() throws Exception {
        Fixture f = fixture();

        String create = mockMvc.perform(post("/api/v1/orgs/" + f.orgId() + "/spaces/" + f.spaceId()
                        + "/chat/conversations")
                        .session(f.session())
                        .cookie(new jakarta.servlet.http.Cookie(
                                com.keplura.server.auth.CsrfService.COOKIE_NAME, f.csrf()))
                        .header(com.keplura.server.auth.CsrfService.HEADER_NAME, f.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ohne Gateway\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long convId = conversationId(create);

        // transcript: the conversation exists and is EMPTY
        mockMvc.perform(get("/api/v1/orgs/" + f.orgId() + "/chat/conversations/" + convId)
                        .session(f.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", Matchers.empty()));

        // the stream route: clean 503 with the EXACT generic body (no key,
        // no URL, no internal names leak)
        mockMvc.perform(post("/api/v1/orgs/" + f.orgId() + "/chat/conversations/" + convId + "/stream")
                        .session(f.session())
                        .cookie(new jakarta.servlet.http.Cookie(
                                com.keplura.server.auth.CsrfService.COOKIE_NAME, f.csrf()))
                        .header(com.keplura.server.auth.CsrfService.HEADER_NAME, f.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"Hallo?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":\"model gateway not configured\"}", true));

        // the refused turn left NOTHING behind — no user row without a turn
        mockMvc.perform(get("/api/v1/orgs/" + f.orgId() + "/chat/conversations/" + convId)
                        .session(f.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(
                        Matchers.containsString("\"role\":"))));

        // delete still works without a gateway
        mockMvc.perform(delete("/api/v1/orgs/" + f.orgId() + "/chat/conversations/" + convId)
                        .session(f.session())
                        .cookie(new jakarta.servlet.http.Cookie(
                                com.keplura.server.auth.CsrfService.COOKIE_NAME, f.csrf()))
                        .header(com.keplura.server.auth.CsrfService.HEADER_NAME, f.csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void anonymousStreamIs401BeforeAnyTouch() throws Exception {
        mockMvc.perform(post("/api/v1/orgs/1/chat/conversations/1/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }
}
