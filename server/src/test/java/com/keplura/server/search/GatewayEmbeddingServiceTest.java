package com.keplura.server.search;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3d: the OpenAI-compatible gateway client — request shape (model +
 * input[], bearer key), response mapping (data[i].index order, dimension
 * validation), and hard failure paths (HTTP error, wrong dimension,
 * malformed body). Stubbed with the JDK HttpServer: no network, no model.
 */
class GatewayEmbeddingServiceTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GatewayEmbeddingService service() {
        return new GatewayEmbeddingService("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "sekret-key", "test-model", 3, 5);
    }

    @Test
    void postsOpenAiShapeAndMapsVectorsInIndexOrder() {
        // deliberately out of order in the response to prove index handling
        response = """
                {"data":[{"index":1,"embedding":[0.4,0.5,0.6]},{"index":0,"embedding":[0.1,0.2,0.3]}]}""";
        List<float[]> vectors = service().embedAll(List.of("erster", "zweiter"));

        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);
        assertThat(lastAuth.get()).isEqualTo("Bearer sekret-key");
        assertThat(lastBody.get()).contains("\"model\":\"test-model\"")
                .contains("\"erster\"").contains("\"zweiter\"");
    }

    @Test
    void httpErrorIsAFailedCallNotSilentFallback() {
        status = 503;
        response = "unavailable";
        assertThatThrownBy(() -> service().embed("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void wrongDimensionResponseIsRejected() {
        response = """
                {"data":[{"index":0,"embedding":[0.1,0.2]}]}"""; // dim 2 != configured 3
        assertThatThrownBy(() -> service().embed("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void missingDataArrayIsRejected() {
        response = "{\"unexpected\":true}";
        assertThatThrownBy(() -> service().embed("x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructionRefusesGatewayWithoutBaseUrlOrModel() {
        assertThatThrownBy(() -> new GatewayEmbeddingService("", "k", "m", 64, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
        assertThatThrownBy(() -> new GatewayEmbeddingService("http://x/v1", "k", "", 64, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
    }

    @Test
    void endpointJoinsWithoutDoubleSlash() {
        GatewayEmbeddingService svc = new GatewayEmbeddingService(
                "http://127.0.0.1:1/v1/", "", "m", 64, 5);
        assertThat(svc.endpoint().toString()).isEqualTo("http://127.0.0.1:1/v1/embeddings");
    }
}
