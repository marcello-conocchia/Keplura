package com.keplura.server.http;

import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.openapi.OpenApiClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review finding 6: the dial is pinned to the address that passed the route policy. A DNS answer that changes
 * between check and connection cannot reach another address, because the host is resolved exactly once.
 */
class PinnedHttpTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            requests.incrementAndGet();
            byte[] body = ("{\"host\":\"" + e.getRequestHeaders().getFirst("Host") + "\"}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            try (var out = e.getResponseBody()) { out.write(body); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private int port() { return server.getAddress().getPort(); }

    /** First answer: the vetted loopback address; every later answer: a forbidden metadata address. */
    private InetAddress[] rebinding(String host) throws java.net.UnknownHostException {
        return lookups.incrementAndGet() == 1
                ? new InetAddress[]{InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})}
                : new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
    }

    private static final PinnedHttp.Dialer DIRECT = (socket, address, port, millis) ->
            socket.connect(new InetSocketAddress(address, port), millis);

    @Test void dnsAnswerChangingAfterTheCheckCannotRedirectTheDial() throws Exception {
        var http = new PinnedHttp(this::rebinding, DIRECT);
        var response = http.send("GET", URI.create("http://api.example.test:" + port() + "/x"), Map.of(), null,
                h -> new PinnedHttp.Limit(1000, false), Duration.ofSeconds(5), InetAddress::isLoopbackAddress);
        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("api.example.test:" + port());
        assertThat(lookups).hasValue(1); // resolved once; the socket never asks DNS again
        assertThat(requests).hasValue(1);
    }

    @Test void aForbiddenAnswerIsRefusedBeforeAnyConnection() {
        lookups.set(1); // next answer is the forbidden one
        var http = new PinnedHttp(this::rebinding, DIRECT);
        assertThatThrownBy(() -> http.send("POST", URI.create("http://api.example.test:" + port() + "/token"), Map.of(),
                "secret=1".getBytes(StandardCharsets.UTF_8), h -> new PinnedHttp.Limit(1000, false), Duration.ofSeconds(5),
                InetAddress::isLoopbackAddress)).isInstanceOf(PinnedHttp.Refused.class);
        assertThat(requests).hasValue(0);
    }

    @Test void openApiClientUsesThePinnedTransport() {
        var client = new OpenApiClient(new SsrfGuard("127.0.0.0/8"), new PinnedHttp(this::rebinding, DIRECT));
        var response = client.call("GET", "http://api.example.test:" + port() + "/items", "BEARER", null, false);
        assertThat(response.status()).isEqualTo(200);
        assertThat(lookups).hasValue(1);
        // A second call resolves again: now to the forbidden address, refused, nothing sent.
        assertThatThrownBy(() -> client.call("GET", "http://api.example.test:" + port() + "/items", "BEARER", null, false))
                .isInstanceOf(OpenApiClient.OpenApiException.class)
                .satisfies(e -> assertThat(((OpenApiClient.OpenApiException) e).started()).isFalse());
        assertThat(requests).hasValue(1);
    }

    @Test void interimResponsesShareTheHeaderBudgetAndAreNotFinal() throws Exception {
        String hints = "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n";
        String response = hints + "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
        var input = new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII));
        var header = PinnedHttp.readFinalHeaders(input);
        assertThat(header.status()).isEqualTo(200);
        assertThat(header.values()).doesNotContainKey("link");
        assertThat(PinnedHttp.readBody(input, header.values(), 2)).isEqualTo(new byte[]{'O', 'K'});

        var oversized = new ByteArrayInputStream(hints.repeat(1000).getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> PinnedHttp.readFinalHeaders(oversized)).isInstanceOf(java.io.IOException.class);
        var upgrade = new ByteArrayInputStream("HTTP/1.1 101 Switching Protocols\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> PinnedHttp.readFinalHeaders(upgrade)).isInstanceOf(java.io.IOException.class)
                .hasMessage("unsupported response status");
    }

    @Test void oversizeBodiesFailOrAreReportedAsTruncated() throws Exception {
        var http = new PinnedHttp((host) -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})}, DIRECT);
        URI uri = URI.create("http://127.0.0.1:" + port() + "/");
        assertThatThrownBy(() -> http.send("GET", uri, Map.of(), null, h -> new PinnedHttp.Limit(3, false), Duration.ofSeconds(5),
                InetAddress::isLoopbackAddress)).isInstanceOf(java.io.IOException.class);
        var kept = http.send("GET", uri, Map.of(), null, h -> new PinnedHttp.Limit(3, true), Duration.ofSeconds(5),
                InetAddress::isLoopbackAddress);
        assertThat(kept.truncated()).isTrue();
        assertThat(kept.body()).isEmpty();
    }
}
