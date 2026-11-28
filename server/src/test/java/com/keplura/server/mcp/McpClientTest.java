package com.keplura.server.mcp;

import com.keplura.server.admin.SsrfGuard;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class McpClientTest {
    private final tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    private com.sun.net.httpserver.HttpServer server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private McpClient client;
    private String url;
    private String mode = "sse";
    private boolean stateless;
    private volatile int requests;
    private volatile boolean notificationChecked;
    private volatile boolean proofSent;
    private final CountDownLatch release = new CountDownLatch(1);
    private static final String TOKEN = "fixture-token-" + "not-a-secret";

    @BeforeEach void start() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/mcp", exchange -> {
            requests++;
            proofSent |= exchange.getRequestHeaders().containsKey(McpClient.BRIDGE_PROOF_HEADER);
            exchange.getResponseHeaders().set(McpClient.BRIDGE_PROOF_HEADER, "untrusted-response-value");
            if (exchange.getRequestMethod().equals("DELETE")) {
                release.countDown(); exchange.sendResponseHeaders(204, -1); exchange.close(); return;
            }
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asString("");
            if (!method.equals("initialize")) {
                assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")).isEqualTo(McpClient.PROTOCOL);
                if (!stateless) assertThat(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")).isEqualTo("test-session");
            }
            if (method.equals("notifications/initialized")) {
                notificationChecked = true; exchange.sendResponseHeaders(202, -1); exchange.close(); return;
            }
            var response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            if (method.equals("initialize")) {
                if (!stateless) exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
                response.putObject("result").put("protocolVersion", McpClient.PROTOCOL);
            } else if (mode.equals("error")) {
                response.putObject("error").put("code", -32000).put("message", TOKEN);
            } else if (method.equals("tools/call")) {
                response.putObject("result").putArray("content").addObject().put("type", "text").put("text", "Antwort " + TOKEN);
            } else {
                response.putObject("result").putArray("tools").addObject().put("name", "echo").put("description", "Echo");
                if (mode.equals("wrong-id")) response.put("id", 99);
            }
            if (mode.equals("sse") && !stateless) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                String data = response.toString().replace(",\"id\"", ",\ndata: \"id\"");
                exchange.getResponseBody().write((": keepalive\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                        + "data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                // Client must finish on matching ID, not wait for stream EOF.
                if (!method.equals("initialize")) {
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] bytes = (mode.equals("oversize") && !method.equals("initialize")
                        ? "x".repeat(McpClient.MAX_BYTES + 1000) : response.toString()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try { exchange.getResponseBody().write(bytes); } catch (java.io.IOException ignored) { }
            }
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        client = new McpClient(new SsrfGuard("127.0.0.0/8"));
    }

    @AfterEach void stop() { release.countDown(); client.close(); server.stop(0); workers.shutdownNow(); }

    @Test void sseUsesSessionAndMultilineEventsAndReturnsBeforeEof() {
        long start = System.nanoTime();
        assertThat(client.listTools(url, TOKEN)).extracting(McpClient.Tool::name).containsExactly("echo");
        assertThat(notificationChecked).isTrue();
        assertThat(proofSent).isFalse();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
    }

    @Test void statelessRequestsStillCarryVersionAndToolTextCannotEchoToken() {
        stateless = true;
        assertThat(client.callTool(url, TOKEN, "echo", json.createObjectNode())).doesNotContain(TOKEN)
                .contains("ausgeblendet");
        assertThat(notificationChecked).isTrue();
    }

    @Test void remoteErrorsAndWrongIdsAreRejectedWithoutEchoingBody() {
        mode = "error";
        assertThatThrownBy(() -> client.listTools(url, TOKEN)).isInstanceOf(McpClient.McpException.class)
                .hasMessageNotContaining(TOKEN);
        mode = "wrong-id";
        assertThatThrownBy(() -> client.listTools(url, TOKEN)).isInstanceOf(McpClient.McpException.class);
    }

    @Test void oversizedBodiesAreRejectedDuringRead() {
        mode = "oversize";
        assertThatThrownBy(() -> client.listTools(url, TOKEN)).isInstanceOf(McpClient.McpException.class);
    }

    @Test void bridgeProofIsFreshIsUsedForCleanupAndCannotReturnInAResponse() throws Exception {
        var transport = org.mockito.Mockito.mock(com.keplura.server.http.PinnedHttp.class);
        var proofs = new java.util.ArrayList<String>();
        String session = "a".repeat(32);
        org.mockito.Mockito.when(transport.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            java.util.Map<String, String> headers = call.getArgument(2);
            String proof = headers.get(McpClient.BRIDGE_PROOF_HEADER);
            proofs.add(proof);
            assertThat(proof).matches("[a-f0-9]{64}");
            if ("DELETE".equals(call.getArgument(0))) {
                assertThat(headers.get("Mcp-Session-Id")).isEqualTo(session);
                return new com.keplura.server.http.PinnedHttp.Response(204, java.util.Map.of(), new byte[0], false);
            }
            byte[] body = call.getArgument(3);
            var request = json.readTree(body);
            var response = json.createObjectNode().put("jsonrpc", "2.0");
            if (!request.has("id")) return new com.keplura.server.http.PinnedHttp.Response(202, java.util.Map.of(), new byte[0], false);
            response.set("id", request.path("id"));
            var result = response.putObject("result");
            if (request.path("method").asString("").equals("initialize")) result.put("protocolVersion", McpClient.PROTOCOL);
            else result.putArray("tools").addObject().put("name", "echo").put("description", proof);
            return new com.keplura.server.http.PinnedHttp.Response(200, java.util.Map.of("mcp-session-id", session),
                    json.writeValueAsBytes(response), false);
        });
        var bridge = new McpClient(new SsrfGuard(""), transport);
        try {
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> bridge.listTools("https://bridge.example.test/mcp/files", "x".repeat(32), true))
                        .isInstanceOf(McpClient.McpException.class)
                        .hasMessage("Die stdio-Bridge hat keine gültige Antwort gesendet.");
            }
            assertThat(proofs).hasSize(8);
            assertThat(proofs.subList(0, 4)).containsOnly(proofs.getFirst());
            assertThat(proofs.subList(4, 8)).containsOnly(proofs.get(4));
            assertThat(proofs.get(4)).isNotEqualTo(proofs.getFirst());
        } finally { bridge.close(); }
    }

    @Test void unapprovedLocalTargetsAreRejectedBeforeAnyRequest() {
        var blocked = new McpClient(new SsrfGuard(""));
        try {
            assertThatThrownBy(() -> blocked.listTools(url, TOKEN)).isInstanceOf(McpClient.McpException.class);
            assertThat(requests).isZero();
        } finally { blocked.close(); }
    }
}
