package com.keplura.server.search;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Model-gateway embedding client (M3d): POSTs OpenAI-compatible {@code
 * /v1/embeddings} ({@code {model, input:[...]}}) to a deployment-configured
 * base URL with an optional bearer key — all through ENV, never a committed
 * value:
 *
 * <pre>
 *   KEPLURA_EMBEDDING_BACKEND=gateway
 *   KEPLURA_EMBEDDING_BASE_URL=https://gateway.internal/v1
 *   KEPLURA_EMBEDDING_API_KEY=*** (out-of-band)
 *   KEPLURA_EMBEDDING_MODEL=bge-m3
 *   KEPLURA_EMBEDDING_DIM=1024
 * </pre>
 *
 * <p><b>Dimension coupling:</b> the pgvector twin column must carry the
 * configured dimension. Since ADR-0010 an EMPTY index is retyped to
 * {@code KEPLURA_EMBEDDING_DIM} at boot ({@link PgVectorSchema}), so a
 * fresh install with bge-m3 (1024) needs no migration. A populated index is
 * never retyped: switching the model then requires a re-index first,
 * otherwise the probe rejects the twin (auto ⇒ memory fallback, pinned
 * pgvector ⇒ failed boot).
 *
 * <p>Deliberate limits (MVP): one blocking request per call, one retry on IO
 * failure, no token budgeting beyond the query cap, no streaming, no other
 * gateway endpoints. A failed gateway makes SEARCH/INDEXING fail loudly (500
 * / rolled-back upload) — a silently half-indexed corpus is worse than a
 * visible outage. The response is validated against the configured
 * {@code embedding-dim}: a model that suddenly changes dimension is a
 * corruption vector and gets rejected per call.
 *
 * <p>Active only when {@code keplura.search.embedding-backend=gateway};
 * the constructor refuses to start without a base URL (a gateway backend
 * with nowhere to call is a misconfiguration, not a fallback trigger).
 */
@Service
@ConditionalOnProperty(name = "keplura.search.embedding-backend", havingValue = "gateway")
public class GatewayEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(GatewayEmbeddingService.class);

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int dimension;
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();

    public GatewayEmbeddingService(
            @Value("${keplura.search.embedding-base-url:}") String baseUrl,
            @Value("${keplura.search.embedding-api-key:}") String apiKey,
            @Value("${keplura.search.embedding-model:}") String model,
            @Value("${keplura.search.embedding-dim:64}") int dimension,
            @Value("${keplura.search.embedding-timeout-seconds:30}") long timeoutSeconds) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("embedding-backend=gateway requires keplura.search.embedding-base-url");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("embedding-backend=gateway requires keplura.search.embedding-model");
        }
        this.endpoint = URI.create(baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model.trim();
        this.dimension = dimension;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NEVER) // an embedding endpoint does not redirect; following could leak the key
                .build();
        log.info("embedding backend: gateway model '{}' at {}", this.model, this.endpoint);
    }

    @Override
    public String modelTag() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        ArrayNode input = req.putArray("input");
        texts.forEach(input::add);

        byte[] body = mapper.writeValueAsBytes(req);

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<byte[]> response = sendWithOneRetry(builder.build());
            if (response.statusCode() / 100 != 2) {
                // never log the body (could echo prompts); status + class only
                throw new IllegalStateException("embedding gateway returned HTTP "
                        + response.statusCode() + " for " + texts.size() + " text(s)");
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("embedding gateway unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding call interrupted", e);
        }
    }

    private HttpResponse<byte[]> sendWithOneRetry(HttpRequest request)
            throws IOException, InterruptedException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("embedding gateway IO failure, retrying once");
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    /** data[i].embedding arrays in request order, each EXACTLY dimension() long. */
    private List<float[]> parse(byte[] responseBody) {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() == 0) {
            throw new IllegalStateException("embedding gateway response carried no data[]");
        }
        float[][] out = new float[data.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode vec = item.path("embedding");
            if (index < 0 || index >= out.length || !vec.isArray() || vec.size() != dimension) {
                throw new IllegalStateException("embedding gateway response malformed "
                        + "(index/dimension mismatch against configured dim " + dimension + ")");
            }
            float[] v = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                v[i] = (float) vec.get(i).asDouble();
            }
            out[index] = v;
        }
        List<float[]> ordered = new ArrayList<>(out.length);
        for (float[] v : out) {
            if (v == null) {
                throw new IllegalStateException("embedding gateway response skipped an index");
            }
            ordered.add(v);
        }
        return ordered;
    }

    /** Test seam: the resolved endpoint (sanity-check of base-url joining). */
    URI endpoint() {
        return endpoint;
    }
}
