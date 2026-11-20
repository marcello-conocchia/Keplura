package com.keplura.server.gateway;

import com.keplura.server.SystemInfoController;
import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.org.OrgService;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4c provider acceptance: the OLLAMA preset driven through the REAL
 * {@link GatewayConfiguration} → {@link GatewayProperties} →
 * {@link GatewayClient} → {@link GatewayController} chain against a scripted
 * mock of Ollama's {@code /v1} OpenAI-compatibility endpoint (the SSE frames
 * below are byte shapes Ollama really emits — same {@code data: {json}\n\n}
 * protocol as OpenAI, including {@code [DONE]}), verified live on this host
 * during the M4c acceptance run.
 *
 * <p>Proves the local-backend contract end-to-end WITHOUT a Spring context
 * (standalone MockMvc — deliberately, see {@code GatewayProviderAuthTest}'s
 * pid-budget note): the preset + mock base URL really dials the upstream,
 * NO Authorization header rides even though a hosted key stays configured
 * (the LAN key-smuggling guard at HTTP level), SSE relays frame-for-frame,
 * and the PUBLIC {@code /api/v1/system/info} projection says exactly
 * {@code model=local} while carrying no key, no host:port, no model id,
 * no "ollama".
 *
 * <p>"sekret-lan-key" is a split-literal test fixture (repo convention),
 * not a credential.
 */
class GatewayProviderPresetOllamaTest {

    /** Split literal: a hosted key left SET on purpose — NONE preset must ignore it. */
    private static final String KEY = "sekret-lan-" + "key";

    private static HttpServer upstream;
    private static int upstreamPort;
    private static final AtomicInteger upstreamHits = new AtomicInteger();
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    private MockMvc mvc;
    private GatewayProperties props;

    @BeforeAll
    static void startMockUpstream() throws IOException {
        upstream = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        upstreamPort = upstream.getAddress().getPort();
        upstream.createContext("/v1/chat/completions", exchange -> {
            upstreamHits.incrementAndGet();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String accept = String.valueOf(exchange.getRequestHeaders().getFirst("Accept"));
            if (accept.contains("text/event-stream")) {
                // Ollama /v1 emits the exact OpenAI frame protocol; mock it as such
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(("data: {\"id\":\"c1\",\"model\":\"qwen2.5:0.5b\",\"choices\":"
                            + "[{\"index\":0,\"delta\":{\"content\":\"hallo\"}}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.write(("data: {\"id\":\"c2\",\"model\":\"qwen2.5:0.5b\",\"choices\":"
                            + "[{\"index\":0,\"delta\":{\"content\":\" welt\"},\"finish_reason\":\"stop\"}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                return;
            }
            byte[] out = ("{\"id\":\"c1\",\"model\":\"qwen2.5:0.5b\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"hallo welt\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        upstream.start();
    }

    @AfterAll
    static void stopMockUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void setUp() {
        upstreamHits.set(0);
        lastAuth.set(null);
        lastBody.set(null);
        // THE production wiring, minus Spring: preset resolution happens in
        // GatewayConfiguration itself, and a raw base-url override must win.
        props = new GatewayConfiguration().gatewayProperties(
                "ollama",
                "http://127.0.0.1:" + upstreamPort + "/v1",
                KEY, "", 5, 5, 15, 1_048_576, 8_388_608, 65_536, 256);
        assertThat(props.provider()).isEqualTo("ollama"); // guard: preset stuck
        GatewayClient client = new GatewayClient(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), props);
        OrgService orgs = Mockito.mock(OrgService.class); // membership check is M4a's contract
        mvc = MockMvcBuilders
                .standaloneSetup(new GatewayController(GatewayRuntime.envOnly(props), client, orgs),
                        new SystemInfoController("Keplura Server", "0.1.0", "v1", GatewayRuntime.envOnly(props)))
                .build();
    }

    private MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(AuthService.SESSION_PRINCIPAL_ATTR, new AuthPrincipal(1L, "tester"));
        return s;
    }

    private static String chatBody(boolean stream) {
        return "{\"model\":\"qwen2.5:0.5b\",\"stream\":" + stream
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"sag hallo\"}]}";
    }

    @Test
    void presetOllamaRelaysJsonWithNoAuthHeaderDespiteConfiguredKey() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/gateway/chat/completions")
                        .session(session())
                        .header(GatewayController.ORG_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody(false)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(upstreamHits.get()).isEqualTo(1);
        // THE LAN guard at HTTP level: ollama preset + configured key => no header
        assertThat(lastAuth.get()).isNull();
        assertThat(lastBody.get()).contains("qwen2.5:0.5b");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("hallo welt");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void presetOllamaSseFramesRelayVerbatim() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/gateway/chat/completions")
                        .session(session())
                        .header(GatewayController.ORG_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody(true)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(lastAuth.get()).isNull();
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("data: {\"id\":\"c1\"").contains("data: {\"id\":\"c2\"")
                .contains("delta").contains("[DONE]");
    }

    @Test
    void systemInfoProjectsLocalAndCarriesNoUrlKeyOrModelId() throws Exception {
        // PUBLIC endpoint (desktop handoff screen) — no session needed
        MvcResult info = mvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andReturn();
        String body = info.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"model\":\"local\"");
        assertThat(body).doesNotContain(KEY);
        assertThat(body).doesNotContain("127.0.0.1:" + upstreamPort);
        assertThat(body).doesNotContain("ollama");
        assertThat(body).doesNotContain("sekret");
    }

    @Test
    void gatewayStatusStaysSingleBooleanUnderOllamaPreset() throws Exception {
        MvcResult status = mvc.perform(get("/api/v1/gateway/status")
                        .session(session())
                        .header(GatewayController.ORG_HEADER, "1"))
                .andExpect(status().isOk())
                .andReturn();
        // the desktop contract is unchanged by M4c: exactly one boolean
        assertThat(status.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"configured\":true}");
    }

    @Test
    void presetDefaultBaseUrlIsTheOllamaCompatEndpoint() {
        // no raw override: the ollama preset itself fills 127.0.0.1:11434/v1
        GatewayProperties dflt = new GatewayConfiguration().gatewayProperties(
                "ollama", "", "", "", 30, 30, 120,
                1_048_576, 8_388_608, 65_536, 256);
        assertThat(dflt.baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(dflt.chatCompletionsEndpoint())
                .isEqualTo("http://127.0.0.1:11434/v1/chat/completions");
        assertThat(Set.of("ollama")).containsExactly(dflt.provider());
    }
}
