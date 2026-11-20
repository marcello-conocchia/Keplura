package com.keplura.server.http;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class BoundedHttpBodyTest {
    @Test void rejectsSizeWhileReadingInsteadOfBufferingWholeResponse() throws Exception {
        var source = new ByteArrayInputStream(new byte[10000]);
        try (var body = new BoundedHttpBody(source, 100, Duration.ofSeconds(2))) {
            assertThatThrownBy(body::readAllBytes).isInstanceOf(java.io.IOException.class);
            assertThat(source.available()).isEqualTo(9899);
        }
    }

    @Test void deadlineClosesARealHttpBodyThatStallsAfterHeaders() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(65);
            exchange.getResponseBody().flush();
            try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var response = http.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            long start = System.nanoTime();
            try (var body = new BoundedHttpBody(response.body(), 100, Duration.ofMillis(150))) {
                assertThatThrownBy(body::readAllBytes).isInstanceOf(java.io.IOException.class);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
