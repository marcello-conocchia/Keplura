package com.keplura.server.search;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class LocalRerankerTest {
    private final List<SearchHit> hits = List.of(
            new SearchHit(1L, "handbuch.pdf", 0, "Die Schwelle beträgt 42 Prozent.", 0.8, 2, "Leitwarte", 0, 38),
            new SearchHit(2L, "urlaub.txt", 1, "Urlaub wird im Team geplant.", 0.7));
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void crossEncoderControlsRankingAndThresholdWithoutLosingSourceLocation() {
        try (var fixture = new Fixture(200, "[{\"index\":1,\"score\":0.02},{\"index\":0,\"score\":0.95}]")) {
            var ranked = fixture.client.rerank("Welche Schwelle gilt?", hits, 4);
            assertThat(ranked).hasSize(1);
            assertThat(ranked.getFirst().score()).isEqualTo(0.95);
            assertThat(ranked.getFirst().pageNumber()).isEqualTo(2);
            assertThat(ranked.getFirst().sectionTitle()).isEqualTo("Leitwarte");
        }
    }

    @Test
    void noRelevantEvidenceProducesAnEmptyResult() {
        try (var fixture = new Fixture(200, "[{\"index\":0,\"score\":0.02},{\"index\":1,\"score\":0.03}]")) {
            assertThat(fixture.client.rerank("Welcher Lieferant verkauft Kaffee?", hits, 4)).isEmpty();
        }
    }

    @Test
    void outageDoesNotMasqueradeAsNoEvidenceAndCannotLeakUpstreamDetails() {
        try (var fixture = new Fixture(500, "privater-inhalt-und-zugangsschluessel")) {
            assertThatThrownBy(() -> fixture.client.rerank("Frage", hits, 4))
                    .isInstanceOf(IllegalStateException.class).hasMessage("local reranking is currently unavailable")
                    .hasNoCause();
        }
    }

    @Test
    void malformedRankingsCannotIntroduceForeignOrDuplicateCandidates() {
        var client = new LocalReranker("", 0.5, 5);
        try {
            for (String malformed : List.of("[]", "[{\"index\":0,\"score\":0.9},{\"index\":0,\"score\":0.8}]",
                    "[{\"index\":9,\"score\":0.9},{\"index\":0,\"score\":0.8}]",
                    "[{\"index\":0,\"score\":3},{\"index\":1,\"score\":0.8}]")) {
                assertThatThrownBy(() -> client.validatedRanking(json.readTree(malformed), hits, 4))
                        .isInstanceOf(IllegalStateException.class);
            }
        } finally { client.close(); }
    }

    static class Fixture implements AutoCloseable {
        final HttpServer server;
        final LocalReranker client;
        Fixture(int status, String response) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/rerank", exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (var out = exchange.getResponseBody()) { out.write(bytes); }
                });
                server.start();
                client = new LocalReranker("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank", 0.5, 5);
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public void close() { client.close(); server.stop(0); }
    }
}
