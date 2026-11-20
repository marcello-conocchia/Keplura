package com.keplura.server.admin;

import com.keplura.server.search.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADMIN-KI Scope D: the primary {@link EmbeddingService} that honours the DB
 * configuration first (docs/adr/0008 §5.2 "Embedding analog"). Resolution per
 * call: an enabled default EMBEDDING model on an enabled provider dials its
 * provider's OpenAI-compatible {@code /embeddings}; otherwise the bean falls
 * through to whatever the ENV backend booted (local hash default or the
 * legacy {@code keplura.search.embedding-*} gateway config) — so the
 * system's embedding behaviour with zero DB rows is EXACTLY the pre-ADMIN-KI
 * one, and the documented Rückfall holds for search as well as chat.
 *
 * <p>Dimension honesty: the pgvector twin column pins the stored dimension
 * (ADR-0007 / V5). A DB model row declares its own {@code embeddingDim}; if
 * it disagrees with the store the write path fails LOUDLY at indexing time —
 * never silently half-indexed — and the RAG-settings surface shows the
 * mismatch plus the reindex hint. Choosing a default embedding model NEVER
 * triggers re-indexing (PO Scope D): it is an operator decision with an
 * explicit, separate action per document (re-upload), spelled out in the UI.
 *
 * <p>Leak discipline mirrors GatewayEmbeddingService: exception CLASS names
 * in logs only, no upstream bodies, the decrypted key rides the bearer header
 * and nothing else, redirects are never followed.
 */
@Primary
@Service
public class RuntimeEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEmbeddingService.class);

    private final EmbeddingService envBackend;
    private final RuntimeProviderConfigResolver resolver;
    private final SecretCipher secretCipher;
    /** Legacy ENV gateway-embedding settings: used when the ENV backend is the local hash
     *  but a DB row exists — the DB row's own provider/base-url carry everything needed,
     *  this only sizes the fallback timeouts. */
    private final long fallbackTimeoutSeconds;
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private final HttpClient client;

    public RuntimeEmbeddingService(
            org.springframework.beans.factory.ObjectProvider<com.keplura.server.search.GatewayEmbeddingService> gatewayBackend,
            org.springframework.beans.factory.ObjectProvider<com.keplura.server.search.LocalHashEmbeddingService> localBackend,
            RuntimeProviderConfigResolver resolver,
            SecretCipher secretCipher,
            @Value("${keplura.search.embedding-timeout-seconds:30}") long timeoutSeconds) {
        // The ENV-level backend is exactly ONE of the two conditional beans
        // (gateway or local hash, picked by their @ConditionalOnProperty
        // pair). Typed ObjectProviders (not ObjectProvider<EmbeddingService>)
        // sidestep any self-reference cycle for @Primary @Service itself.
        EmbeddingService backend = gatewayBackend.getIfAvailable();
        if (backend == null) {
            backend = localBackend.getIfAvailable();
        }
        if (backend == null) {
            throw new IllegalStateException("no ENV embedding backend present");
        }
        this.envBackend = backend;
        this.resolver = resolver;
        this.secretCipher = secretCipher;
        this.fallbackTimeoutSeconds = Math.max(1, timeoutSeconds);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.fallbackTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The ENV-level backend this bridge sits in front of (status projection reads it). */
    public EmbeddingService envBackend() {
        return envBackend;
    }

    @Override
    public String modelTag() {
        return resolver.embeddingConfig()
                .map(pair -> pair.model().getTechnicalModelId())
                .orElseGet(envBackend::modelTag);
    }

    @Override
    public int dimension() {
        return resolver.embeddingConfig()
                .map(pair -> pair.model().getEmbeddingDim() == null
                        ? envBackend.dimension() : pair.model().getEmbeddingDim())
                .orElseGet(envBackend::dimension);
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        Optional<RuntimeProviderConfigResolver.ResolvedModelConfig> db = resolver.embeddingConfig();
        if (db.isEmpty()) {
            return envBackend.embedAll(texts); // ENV/local path untouched (Rückfall)
        }
        AiProvider provider = db.get().provider();
        AiModel model = db.get().model();
        int dim = model.getEmbeddingDim() == null ? envBackend.dimension() : model.getEmbeddingDim();
        URI endpoint = embeddingsEndpoint(provider.getBaseUrl());
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model.getTechnicalModelId());
        ArrayNode input = req.putArray("input");
        texts.forEach(input::add);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(Math.max(1, Math.min(provider.getTimeoutSeconds(), 120))))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(req)));
        if (provider.hasSecret() && secretCipher.available()
                && "api".equalsIgnoreCase(provider.getProviderType())) {
            // AuthKind discipline (same as chat): ollama/vllm expose no auth
            // surface — a bearer would leak into a LAN host's access log.
            try {
                builder.header("Authorization", "Bearer " + secretCipher.decrypt(provider.getSecretCipher()));
            } catch (RuntimeException e) {
                log.warn("embedding provider {} secret undecryptable; dialing without auth", provider.getId());
            }
        }
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("embedding provider returned HTTP "
                        + response.statusCode() + " for " + texts.size() + " text(s)");
            }
            return parse(response.body(), dim);
        } catch (IOException e) {
            // class name only — JDK IO text embeds the upstream host
            throw new IllegalStateException("embedding provider unreachable ("
                    + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding call interrupted", e);
        }
    }

    private static URI embeddingsEndpoint(String baseUrl) {
        String base = baseUrl.strip();
        return URI.create(base.endsWith("/") ? base + "embeddings" : base + "/embeddings");
    }

    /** data[i].embedding arrays in request order, each EXACTLY dim long. */
    private List<float[]> parse(byte[] responseBody, int dim) {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("embedding response carried no data[]");
        }
        float[][] out = new float[data.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode vec = item.path("embedding");
            if (index < 0 || index >= out.length || !vec.isArray() || vec.size() != dim) {
                throw new IllegalStateException("embedding response malformed "
                        + "(index/dimension mismatch against configured dim " + dim + ")");
            }
            float[] v = new float[dim];
            for (int i = 0; i < dim; i++) {
                v[i] = (float) vec.get(i).asDouble();
            }
            out[index] = v;
        }
        List<float[]> ordered = new ArrayList<>(out.length);
        for (float[] v : out) {
            if (v == null) {
                throw new IllegalStateException("embedding response skipped an index");
            }
            ordered.add(v);
        }
        return ordered;
    }

}
