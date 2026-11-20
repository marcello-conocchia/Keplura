package com.keplura.server.search;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Local TEI cross-encoder. An outage is an error, never a fabricated non-match. */
@Component
public class LocalReranker {
    private final URI endpoint;
    private final double minScore;
    private final int timeoutSeconds;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reranker-timeout"); t.setDaemon(true); return t;
    });

    public LocalReranker(@Value("${keplura.search.reranker-url:}") String url,
                         @Value("${keplura.search.reranker-min-score:0.5}") double minScore,
                         @Value("${keplura.search.reranker-timeout-seconds:60}") int timeoutSeconds) {
        this.endpoint = url == null || url.isBlank() ? null : URI.create(url);
        if (endpoint != null && (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null)) {
            throw new IllegalArgumentException("reranker URL must be an HTTP(S) endpoint without credentials");
        }
        if (!Double.isFinite(minScore) || minScore < 0 || minScore > 1 || timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("invalid reranker threshold or timeout");
        }
        this.minScore = minScore;
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean enabled() { return endpoint != null; }

    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        if (candidates.isEmpty() || !enabled()) return candidates.stream().limit(topK).toList();
        if (candidates.size() > 50) throw new IllegalArgumentException("too many reranker candidates");
        var body = mapper.createObjectNode();
        body.put("query", query);
        body.put("return_text", false);
        body.put("raw_scores", false);
        var texts = body.putArray("texts");
        candidates.forEach(hit -> texts.add(hit.text()));
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                var timeout = watchdog.schedule(() -> {
                    try { input.close(); } catch (Exception ignored) { }
                }, timeoutSeconds, TimeUnit.SECONDS);
                try {
                    if (response.statusCode() != 200) throw new IllegalStateException("reranker response rejected");
                    byte[] bytes = input.readNBytes(65537);
                    if (bytes.length > 65536) throw new IllegalStateException("reranker response too large");
                    return validatedRanking(mapper.readTree(bytes), candidates, topK);
                } finally { timeout.cancel(false); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("local reranking interrupted");
        } catch (Exception e) {
            // No upstream address, response body, query or document text in errors.
            throw new IllegalStateException("local reranking is currently unavailable");
        }
    }

    List<SearchHit> validatedRanking(JsonNode result, List<SearchHit> candidates, int topK) {
        if (!result.isArray() || result.size() != candidates.size()) {
            throw new IllegalStateException("incomplete reranker response");
        }
        var seen = new HashSet<Integer>();
        var ranked = new ArrayList<SearchHit>();
        for (JsonNode item : result) {
            if (!item.path("index").isIntegralNumber() || !item.path("index").canConvertToInt()
                    || !item.path("score").isNumber()) {
                throw new IllegalStateException("invalid reranker response");
            }
            int index = item.get("index").intValue();
            double score = item.get("score").doubleValue();
            if (index < 0 || index >= candidates.size() || !seen.add(index)
                    || !Double.isFinite(score) || score < 0 || score > 1) {
                throw new IllegalStateException("invalid reranker result");
            }
            if (score >= minScore) {
                SearchHit hit = candidates.get(index);
                ranked.add(hit.withScore(score));
            }
        }
        ranked.sort(Comparator.comparingDouble(SearchHit::score).reversed()
                .thenComparing(SearchHit::documentId).thenComparingInt(SearchHit::chunkIdx));
        return ranked.stream().limit(topK).toList();
    }

    @PreDestroy
    void close() { watchdog.shutdownNow(); client.close(); }
}
