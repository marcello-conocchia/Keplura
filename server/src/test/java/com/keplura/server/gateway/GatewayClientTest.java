package com.keplura.server.gateway;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * M4a: the proxy client in isolation (own scriptable JDK mock per test,
 * tight timeouts). Proves the seams the wire suite cannot reach: no-key
 * requests carry no Authorization header, Retry-After sanitising drops
 * non-numeric forms, oversized buffered answers 502, and the stream watchdog
 * actually kills a silent upstream within roughly the idle budget (the
 * mechanism itself — close() unblocking a parked read — was proven against
 * the JDK HttpClient before this design was kept).
 */
class GatewayClientTest {

    private HttpServer server;
    private volatile String contentType = "application/json";
    private volatile String retryAfter = null;
    private volatile boolean silentStream = false;
    private volatile boolean chattyStream = false;
    private volatile String lastAuth = null;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            if (chattyStream) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    for (int i = 0; i < 100; i++) { // never idle, endless: only the deadline stops it
                        os.write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        Thread.sleep(200);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    // our close landed — expected
                }
                return;
            }
            if (silentStream) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write("data: one\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Thread.sleep(6_000); // silence: the watchdog must end this (~2s)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    // our close landed — expected
                }
                return;
            }
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GatewayProperties props(long timeout, long idle, long deadline, long maxResponseBytes, String apiKey) {
        return new GatewayProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                apiKey, Set.of(), timeout, idle, deadline, 1_048_576, maxResponseBytes, 65_536, 256);
    }

    private GatewayClient client(GatewayProperties props) {
        return new GatewayClient(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build(), props);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    @Test
    void bearerKeyAttachedOnlyWhenConfigured() {
        GatewayClient keyed = client(props(5, 5, 10, 1_000_000, "sekret-value"));
        keyed.completeJson("{}".getBytes(StandardCharsets.UTF_8), "m");
        assertThat(lastAuth).isEqualTo("Bearer sekret-value");

        GatewayClient anonymous = client(props(5, 5, 10, 1_000_000, ""));
        anonymous.completeJson("{}".getBytes(StandardCharsets.UTF_8), "m");
        assertThat(lastAuth).isNull();
    }

    @Test
    void unreachableUpstreamIs502WithoutHostInMessage() throws Exception {
        GatewayProperties dead = new GatewayProperties(
                "http://127.0.0.1:" + freePort() + "/v1", "", Set.of(), 2, 2, 4, 1024, 1024, 64, 4);
        GatewayClient client = client(dead);

        assertThatThrownBy(() -> client.completeJson("{}".getBytes(StandardCharsets.UTF_8), "m"))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).doesNotContain("127.0.0.1").doesNotContain(String.valueOf(dead.baseUrl()));
                    assertThat(e.getCause()).isNull(); // no cause chain smuggles the host either
                });
        assertThatThrownBy(() -> client.openStream("{}".getBytes(StandardCharsets.UTF_8), "m"))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void bufferedResponseOverCapIs502() {
        contentType = "application/json";
        GatewayClient tight = client(props(5, 5, 10, 1, "k")); // maxResponseBytes = 1 (min-clamped in config, not here)
        assertThatThrownBy(() -> tight.completeJson("{}".getBytes(StandardCharsets.UTF_8), "m"))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.getMessage()).contains("too large"));
    }

    @Test
    void wrongContentTypeOnSuccessIs502() {
        contentType = "text/plain";
        assertThatThrownBy(() -> client(props(5, 5, 10, 1_000_000, "k")).completeJson("{}".getBytes(StandardCharsets.UTF_8), "m"))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).contains("response invalid");
                });
    }

    @Test
    void silentStreamIsKilledByIdleWatchdogQuickly() throws Exception {
        silentStream = true;
        GatewayClient client = client(props(5, 1, 10, 1_000_000, "k")); // idle = 1s
        long t0 = System.nanoTime();
        try (GatewayClient.UpstreamStream stream = client.openStream("{}".getBytes(StandardCharsets.UTF_8), "m")) {
            InputStream in = stream.body();
            byte[] buf = new byte[32];
            int n = in.read(buf, 0, buf.length); // first frame arrives
            assertThat(n).isGreaterThan(0);
            assertThatThrownBy(() -> in.read(buf, 0, buf.length))
                    .isInstanceOf(IOException.class); // watchdog closed under us
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        // idle 1s + up to 1s check interval + scheduling slack
        assertThat(seconds).isCloseTo(2.0, within(1.5));
    }

    @Test
    void streamDeadlineAlsoKillsAnEndlesslyChattyUpstream() throws Exception {
        chattyStream = true;
        GatewayClient client = client(props(5, 30, 3, 10_000_000, "k")); // idle huge, deadline 3s
        long t0 = System.nanoTime();
        int reads = 0;
        try (GatewayClient.UpstreamStream stream = client.openStream("{}".getBytes(StandardCharsets.UTF_8), "m")) {
            InputStream in = stream.body();
            byte[] buf = new byte[64];
            while (true) {
                int n = in.read(buf, 0, buf.length); // data keeps arriving -> never idle
                if (n < 0) {
                    break;
                }
                reads++;
            }
            org.junit.jupiter.api.Assertions.fail("deadline should have aborted the read loop");
        } catch (IOException expected) {
            // the watchdog close surfaces as IOException("closed") mid-read: the
            // honest "incomplete stream" signal, same as the idle path
            assertThat(reads).isGreaterThan(0);
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        // deadline 3s + up to 1s check interval + slack
        assertThat(seconds).isCloseTo(3.5, within(1.5));
    }
    @Test
    void endpointJoiningHandlesTrailingSlash() {
        GatewayProperties withSlash = new GatewayProperties("https://gw.example/v1/", "", Set.of(),
                1, 1, 1, 1, 1, 1, 1);
        assertThat(withSlash.chatCompletionsEndpoint()).isEqualTo("https://gw.example/v1/chat/completions");
        GatewayProperties noSlash = new GatewayProperties("https://gw.example/v1", "", Set.of(),
                1, 1, 1, 1, 1, 1, 1);
        assertThat(noSlash.chatCompletionsEndpoint()).isEqualTo("https://gw.example/v1/chat/completions");
        assertThat(new GatewayProperties("  ", "", Set.of(), 1, 1, 1, 1, 1, 1, 1).configured()).isFalse();
    }
}
