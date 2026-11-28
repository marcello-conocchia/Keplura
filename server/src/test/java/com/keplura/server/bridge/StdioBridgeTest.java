package com.keplura.server.bridge;

import com.keplura.server.mcp.McpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.LINUX)
class StdioBridgeTest {
    private static final String TOKEN = "bridge-fixture-key-0123456789-0123456789";
    private static final String KEY = "runner-fixture-key-0123456789-0123456789";
    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);

    @Test void proofBindsTheSessionAndNeverReachesTheRunnerAsATransportSecret(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("runner.sock");
        var starts = new AtomicInteger();
        var payload = new AtomicReference<String>();
        try (var runner = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            runner.bind(UnixDomainSocketAddress.of(path));
            Thread fixture = Thread.ofVirtual().start(() -> {
                try (var channel = runner.accept();
                     var input = new DataInputStream(Channels.newInputStream(channel));
                     var output = new DataOutputStream(Channels.newOutputStream(channel))) {
                    var hello = BridgeWire.read(input, 2048);
                    starts.incrementAndGet();
                    payload.set(new String(hello.bytes(), StandardCharsets.UTF_8));
                    BridgeWire.write(output, BridgeWire.OK, new byte[0]);
                    var call = BridgeWire.read(input, 2048);
                    var rpc = BridgeWire.JSON.readTree(call.bytes());
                    var response = BridgeWire.JSON.createObjectNode().put("jsonrpc", "2.0");
                    response.set("id", rpc.path("id"));
                    response.putObject("result").put("protocolVersion", McpClient.PROTOCOL);
                    BridgeWire.write(output, BridgeWire.OK, BridgeWire.JSON.writeValueAsBytes(response));
                    input.read(); // EOF when the owning connection closes the bridge session.
                } catch (Exception ignored) { }
            });
            var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            try (var bridge = new StdioBridge(http, path, bytes(TOKEN), bytes(KEY)); var client = HttpClient.newHttpClient()) {
                bridge.start();
                URI origin = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
                assertThat(send(client, origin, "files", "POST", null, FIRST, null).statusCode()).isEqualTo(401);
                assertThat(send(client, origin, "files", "POST", KEY, FIRST, null).statusCode()).isEqualTo(401);
                assertThat(starts).hasValue(0);
                var start = send(client, origin, "files", "POST", TOKEN, FIRST, null);
                assertThat(start.statusCode()).isEqualTo(200);
                String session = start.headers().firstValue("Mcp-Session-Id").orElseThrow();
                assertThat(session).matches("[a-f0-9]{32}");
                assertThat(payload.get()).contains(KEY).doesNotContain(TOKEN, FIRST, SECOND);
                assertThat(start.body()).doesNotContain(TOKEN, KEY, FIRST, SECOND);
                assertThat(send(client, origin, "files", "DELETE", TOKEN, SECOND, session).statusCode()).isEqualTo(404);
                assertThat(send(client, origin, "files", "DELETE", TOKEN, null, session).statusCode()).isEqualTo(404);
                assertThat(send(client, origin, "other", "DELETE", TOKEN, FIRST, session).statusCode()).isEqualTo(404);
                assertThat(send(client, origin, "files", "DELETE", TOKEN, FIRST, session).statusCode()).isEqualTo(204);
                assertThat(send(client, origin, "files", "DELETE", TOKEN, FIRST, session).statusCode()).isEqualTo(404);
                assertThat(starts).hasValue(1);
            }
            fixture.join(Duration.ofSeconds(5));
            assertThat(fixture.isAlive()).isFalse();
        }
    }

    private static HttpResponse<String> send(HttpClient http, URI origin, String name, String method, String token,
                                             String proof, String session) throws Exception {
        var builder = HttpRequest.newBuilder(origin.resolve("/mcp/" + name)).timeout(Duration.ofSeconds(5));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (proof != null) builder.header(McpClient.BRIDGE_PROOF_HEADER, proof);
        if (session != null) builder.header("Mcp-Session-Id", session);
        var payload = method.equals("POST")
                ? HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
                : HttpRequest.BodyPublishers.noBody();
        return http.send(builder.method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
