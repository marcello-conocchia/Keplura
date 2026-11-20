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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4c provider acceptance: the hosted-API preset driven through the REAL
 * {@link GatewayConfiguration} → {@link GatewayProperties} →
 * {@link GatewayClient} → {@link GatewayController} chain against a scripted
 * OpenAI-compatible mock upstream.
 *
 * <p>Proves: preset=api with an EXPLICIT base URL override really dials the
 * mock (traffic lands there), the api preset attaches the bearer key (the
 * hosted API needs it — the counterpart to {@code GatewayProviderAuthTest}'s
 * NONE guard), {@code /api/v1/system/info} reports exactly
 * {@code model=configured} while carrying no key, no host:port, no model id,
 * no "openai" — and {@code /api/v1/gateway/status} stays the single boolean
 * the M4a desktop contract pinned (a preset adds nothing there).
 *
 * <p>Standalone MockMvc on purpose (like {@code GatewayProviderAuthTest}):
 * the suite's cgroup pids budget (256 threads) cannot hold another Spring
 * web context at the tail of the full run — the real-stack HTTP proof for
 * provider switching is {@code Akzeptanzlauf A} (live Ollama, documented in
 * docs/development.md + the PR), this class pins the wire contract.
 *
 * <p>"sekret-m4c-key" is a split-literal test fixture (repo convention),
 * not a credential.
 */
class GatewayProviderPresetApiTest {

    /** Must equal the key set below; used as leak sentinel. */
    private static final String KEY = "sekret-m4c-" + "key";

    private static HttpServer upstream;
    private static int upstreamPort;
    private static final AtomicInteger upstreamHits = new AtomicInteger();
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    private MockMvc mvc;

    @BeforeAll
    static void startMockUpstream() throws IOException {
        upstream = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        upstreamPort = upstream.getAddress().getPort();
        upstream.createContext("/v1/chat/completions", exchange -> {
            upstreamHits.incrementAndGet();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] out = ("{\"id\":\"c1\",\"model\":\"m4c-provider-model\",\"choices\":"
                    + "[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hallo\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
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
        // THE production wiring, minus Spring: preset resolution + override
        // precedence happen inside GatewayConfiguration itself.
        GatewayProperties props = new GatewayConfiguration().gatewayProperties(
                "api",
                "http://127.0.0.1:" + upstreamPort + "/v1", // beats the preset default
                KEY, "", 5, 5, 15, 1_048_576, 8_388_608, 65_536, 256);
        assertThat(props.provider()).isEqualTo("api"); // guard: preset stuck
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

    private static String minimalChat() {
        return "{\"model\":\"m4c-provider-model\",\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"sag hallo\"}]}";
    }

    @Test
    void presetApiRelaysToExplicitBaseUrlAndSendsBearerKey() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/gateway/chat/completions")
                        .session(session())
                        .header(GatewayController.ORG_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalChat()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(upstreamHits.get()).isEqualTo(1);
        // hosted-API preset MUST attach the bearer (unlike ollama/vllm presets)
        assertThat(lastAuth.get()).isEqualTo("Bearer " + KEY);
        assertThat(lastBody.get()).contains("m4c-provider-model");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("hallo");
    }

    @Test
    void presetApiDefaultBaseUrlIsTheOpenaiEndpoint() {
        GatewayProperties dflt = new GatewayConfiguration().gatewayProperties(
                "api", "", "", "", 30, 30, 120,
                1_048_576, 8_388_608, 65_536, 256);
        assertThat(dflt.baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(dflt.sendsAuth()).isTrue();
    }

    @Test
    void systemInfoReportsConfiguredForPresetAndLeaksNothing() throws Exception {
        // endpoint is PUBLIC (desktop handoff screen) — no session needed
        MvcResult info = mvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andReturn();
        String body = info.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"model\":\"configured\"");
        // closed vocabulary only — no key, no URL, no model id, no provider name
        assertThat(body).doesNotContain(KEY);
        assertThat(body).doesNotContain("127.0.0.1:" + upstreamPort);
        assertThat(body).doesNotContain("m4c-provider-model");
        assertThat(body).doesNotContain("openai");
    }

    @Test
    void gatewayStatusStaysSingleBooleanUnderPreset() throws Exception {
        MvcResult status = mvc.perform(get("/api/v1/gateway/status")
                        .session(session())
                        .header(GatewayController.ORG_HEADER, "1"))
                .andExpect(status().isOk())
                .andReturn();
        // EXACT body: the desktop contract is unchanged by M4c — one boolean,
        // no provider string, no URL, no key.
        assertThat(status.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"configured\":true}");
    }
}
