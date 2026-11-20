package com.keplura.server.gateway;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4c (auth-kind discipline, unit level): the OLLAMA/VLLM presets must NEVER
 * attach an Authorization header — even when a key is configured — while the
 * API preset and the raw M4a config path keep the bearer exactly as before.
 *
 * <p>This is the LAN key-smuggling guard: flipping a deployment from
 * provider=api to a local Ollama box on the same ENV leaves the (hosted) key
 * set; authKind=NONE is what stops it riding into an untrusted host's access
 * log. Proven here against a scriptable JDK mock (the GatewayClientTest
 * pattern) instead of a Spring context — same seam, and crucially ZERO
 * extra threads on top of the suite's cached contexts: the dev container's
 * 256-pid cgroup budget leaves no room for another context's pool while the
 * three RANDOM_PORT Tomcats of the web suites stay cached (OOM observed in
 * the full run when this class booted a fourth context).
 *
 * <p>The HTTP-side projection assertions (system/info model word, gateway
 * status shape) live in {@code SystemInfoControllerTest} /
 * {@code GatewayUnconfiguredTest} / {@code ApiIntegrationTest} which reuse
 * the suite's existing contexts — together they cover the same contract.
 *
 * <p>"sekret-st" + "ray-key" is a split test fixture string, not a credential.
 */
class GatewayProviderAuthTest {

    private static final String KEY = "sekret-st" + "ray-key";

    private HttpServer server;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            String accept = String.valueOf(exchange.getRequestHeaders().getFirst("Accept"));
            if (accept.contains("text/event-stream")) {
                // answer in Ollama /v1 SSE shape (frames identical to OpenAI's)
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (java.io.OutputStream os = exchange.getResponseBody()) {
                    os.write(("data: {\"id\":\"o1\",\"model\":\"qwen2.5:0.5b\",\"choices\":"
                            + "[{\"index\":0,\"delta\":{\"content\":\"hallo\"}}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                return;
            }
            byte[] out = ("{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"hallo\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GatewayProperties props(ProviderPreset preset, String key) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new GatewayProperties(preset.id(), preset.authKind(), base, key, Set.of(),
                5, 5, 15, 1_048_576, 1_048_576, 65_536, 256);
    }

    private GatewayClient client(GatewayProperties props) {
        return new GatewayClient(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), props);
    }

    @Test
    void ollamaPresetNeverSendsAuthEvenWithAKeyConfigured() {
        lastAuth.set("unset");
        GatewayClient c = client(props(ProviderPreset.OLLAMA, KEY));
        GatewayClient.UpstreamStream stream =
                c.openStream("{\"stream\":true}".getBytes(StandardCharsets.UTF_8), "m");
        String relayed = readAll(stream.body());
        stream.close();

        assertThat(lastAuth.get()).isNull();
        // Ollama-compat frames relay verbatim
        assertThat(relayed).contains("\"delta\"").contains("[DONE]");
    }

    @Test
    void vllmPresetNeverSendsAuthEvenWithAKeyConfigured() {
        lastAuth.set("unset");
        GatewayClient c = client(props(ProviderPreset.VLLM, KEY));
        var reply = c.completeJson("{}".getBytes(StandardCharsets.UTF_8), "m");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(lastAuth.get()).isNull();
    }

    @Test
    void apiPresetSendsBearerKey() {
        GatewayClient c = client(props(ProviderPreset.API, KEY));
        c.completeJson("{}".getBytes(StandardCharsets.UTF_8), "m");
        assertThat(lastAuth.get()).isEqualTo("Bearer " + KEY);
    }

    @Test
    void rawConfigPathKeepsBearerSemanticsUnchanged() {
        GatewayClient c = client(new GatewayProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", KEY, Set.of(),
                5, 5, 15, 1_048_576, 1_048_576, 65_536, 256));
        c.completeJson("{}".getBytes(StandardCharsets.UTF_8), "m");
        assertThat(lastAuth.get()).isEqualTo("Bearer " + KEY);
    }

    private static String readAll(java.io.InputStream in) {
        try (java.io.InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
