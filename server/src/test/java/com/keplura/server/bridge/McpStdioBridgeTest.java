package com.keplura.server.bridge;

import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.mcp.McpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TU-06c: the isolated stdio bridge with a small declared test process (echo, hang, large output, crash), reached
 * through Keplura's EXISTING MCP HTTP client. The Keplura server process itself never starts such processes.
 */
class McpStdioBridgeTest {

    private static final String TOKEN = "bridge-token-" + "0123456789abcdef0123456789abcdef";
    private static final JsonMapper M = JsonMapper.builder().build();
    @TempDir Path work;
    private McpStdioBridge bridge;
    private String base;
    private final McpClient client = new McpClient(new SsrfGuard("127.0.0.0/8"));

    private BridgeConfig.ServerSpec spec(String name, String mode, int timeoutSeconds, int maxOutput, Map<String, String> env) throws Exception {
        String java = ProcessHandle.current().info().command().orElse(System.getProperty("java.home") + "/bin/java");
        String classes = Path.of(FakeStdioMcpServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new BridgeConfig.ServerSpec(name, List.of(java, "-cp", classes, FakeStdioMcpServer.class.getName(), mode), env,
                work, Duration.ofSeconds(timeoutSeconds), maxOutput, 2, Duration.ofSeconds(60));
    }

    @BeforeEach void start() throws Exception {
        bridge = new McpStdioBridge(List.of(spec("echo", "echo", 20, 4_000_000, Map.of("DECLARED_SETTING", "1")),
                spec("hang", "hang", 2, 4_000_000, Map.of()), spec("big", "big", 20, 65_536, Map.of()),
                spec("crash", "crash", 20, 4_000_000, Map.of())), TOKEN);
        var address = bridge.start(new InetSocketAddress("127.0.0.1", 0));
        base = "http://127.0.0.1:" + address.getPort() + "/mcp/";
    }

    @AfterEach void stop() {
        bridge.stop();
    }

    private static long children() {
        return ProcessHandle.current().descendants()
                .filter(p -> p.info().commandLine().orElse("").contains(FakeStdioMcpServer.class.getName())).count();
    }

    private static void awaitNoChildren() throws InterruptedException {
        for (int i = 0; i < 100 && children() > 0; i++) Thread.sleep(50);
        assertThat(children()).isZero();
    }

    @Test void declaredServerIsReachableThroughTheExistingMcpClientAndEachSessionEnds() throws Exception {
        var tools = client.listTools(base + "echo", TOKEN);
        assertThat(tools).extracting(McpClient.Tool::name).containsExactly("echo", "env");
        assertThat(client.callTool(base + "echo", TOKEN, "echo", M.readTree("{\"text\":\"hallo\"}"))).isEqualTo("echo:hallo");
        assertThat(bridge.openSessions("echo")).isZero(); // the client's DELETE ended the session and its process
        awaitNoChildren();
    }

    @Test void withoutTheSharedTokenNothingStarts() throws Exception {
        assertThatThrownBy(() -> client.listTools(base + "echo", "wrong-token-0123456789abcdef0123456789"))
                .hasMessageContaining("Zugriff abgelehnt");
        assertThatThrownBy(() -> client.listTools(base + "echo", "")).hasMessageContaining("Zugriff abgelehnt");
        try (var http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            var r = http.send(HttpRequest.newBuilder(URI.create(base + "echo")).POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(401);
            var unknown = http.send(HttpRequest.newBuilder(URI.create(base + "nope")).header("Authorization", "Bearer " + TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(unknown.statusCode()).isEqualTo(404);
        }
        assertThat(bridge.openSessions("echo")).isZero();
        assertThat(children()).isZero();
    }

    @Test void childEnvironmentHoldsOnlyDeclaredValuesAndNoKepluraSecrets() throws Exception {
        String text = client.callTool(base + "echo", TOKEN, "env", M.readTree("{}"));
        String[] parts = text.split(";cwd=");
        assertThat(Set.of(parts[0].split(","))).isSubsetOf(Set.of("PATH", "LANG", "HOME", "DECLARED_SETTING"))
                .contains("DECLARED_SETTING");
        assertThat(text).doesNotContain(TOKEN).doesNotContain("KEPLURA").doesNotContain("JAVA_TOOL_OPTIONS");
        assertThat(Path.of(parts[1]).toRealPath()).isEqualTo(work.toRealPath());
    }

    @Test void aHangingCallEndsAfterTheTimeoutAndItsProcessIsKilled() throws Exception {
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.callTool(base + "hang", TOKEN, "echo", M.readTree("{}"))).hasMessageContaining("HTTP 504");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertThat(bridge.openSessions("hang")).isZero();
        awaitNoChildren();
    }

    @Test void oversizedOutputIsCutOffAndACrashOnlyEndsItsSession() throws Exception {
        assertThatThrownBy(() -> client.callTool(base + "big", TOKEN, "echo", M.readTree("{}"))).hasMessageContaining("HTTP 502");
        assertThatThrownBy(() -> client.callTool(base + "crash", TOKEN, "echo", M.readTree("{}"))).hasMessageContaining("HTTP 502");
        // the next session starts a fresh process
        assertThat(client.listTools(base + "crash", TOKEN)).isNotEmpty();
        awaitNoChildren();
    }

    @Test void configAcceptsOnlyArgumentListsWithAbsoluteProgramsAndNoSecretsInTheEnvironment() {
        String ok = "{\"servers\":[{\"name\":\"files\",\"command\":[\"/usr/bin/node\",\"/opt/mcp/files.js\"],\"workdir\":\"/work/files\"}]}";
        assertThat(BridgeConfig.parse(ok.getBytes(StandardCharsets.UTF_8))).singleElement()
                .satisfies(s -> assertThat(s.command()).containsExactly("/usr/bin/node", "/opt/mcp/files.js"));
        for (String bad : List.of(
                ok.replace("\"/usr/bin/node\"", "\"/bin/sh\",\"-c\""),
                ok.replace("\"/usr/bin/node\"", "\"node\""),
                ok.replace("\"/usr/bin/node\"", "\"/usr/bin/env\""),
                ok.replace("\"command\":[\"/usr/bin/node\",\"/opt/mcp/files.js\"]", "\"command\":\"/usr/bin/node /opt/mcp/files.js\""),
                ok.replace("\"workdir\"", "\"env\":{\"KEPLURA_SECRETS_MASTER_KEY\":\"x\"},\"workdir\""),
                ok.replace("\"workdir\"", "\"env\":{\"LD_PRELOAD\":\"/x.so\"},\"workdir\""),
                ok.replace("\"workdir\":\"/work/files\"", "\"workdir\":\"work\""),
                ok.replace("\"workdir\"", "\"shell\":true,\"workdir\""),
                ok.replace("}]}", "},{\"name\":\"files\",\"command\":[\"/usr/bin/node\"],\"workdir\":\"/w\"}]}"),
                ok.replace("\"workdir\"", "\"timeoutSeconds\":999,\"workdir\""),
                "{\"servers\":[]}", "not json")) {
            assertThatThrownBy(() -> BridgeConfig.parse(bad.getBytes(StandardCharsets.UTF_8))).as(bad)
                    .isInstanceOf(BridgeConfig.InvalidConfig.class);
        }
        assertThatThrownBy(() -> new McpStdioBridge(List.of(), "short")).isInstanceOf(BridgeConfig.InvalidConfig.class);
    }

    @Test void theBridgeRefusesToRunAsRootAndNeedsAToken() throws Exception {
        var err = new ByteArrayOutputStream();
        assertThat(McpStdioBridge.run(new String[]{McpStdioBridge.FLAG}, Map.of("KEPLURA_BRIDGE_TOKEN", TOKEN), () -> 0,
                new PrintStream(err, true, StandardCharsets.UTF_8))).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("refusing to run as root");
        Path config = work.resolve("servers.json");
        Files.writeString(config, "{\"servers\":[{\"name\":\"files\",\"command\":[\"/usr/bin/true\"],\"workdir\":\"/tmp\"}]}");
        var err2 = new ByteArrayOutputStream();
        assertThat(McpStdioBridge.run(new String[]{McpStdioBridge.FLAG, "--config=" + config, "--bind=127.0.0.1:0"}, Map.of(), () -> 1000,
                new PrintStream(err2, true, StandardCharsets.UTF_8))).isEqualTo(3);
        assertThat(err2.toString(StandardCharsets.UTF_8)).contains("KEPLURA_BRIDGE_TOKEN");
    }

    /** The Keplura server process never starts stdio servers: only the bridge (own container), OCR and the local reader spawn processes. */
    @Test void onlyTheBridgeOcrAndTheNativeReaderStartProcessesAndTheBridgeIsNoSpringBean() throws Exception {
        Path main = Path.of("src/main/java");
        Set<String> spawning;
        try (Stream<Path> files = Files.walk(main)) {
            spawning = new java.util.TreeSet<>(files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String s = Files.readString(p);
                    return s.contains("ProcessBuilder") || s.contains("Runtime.getRuntime().exec") || s.contains(".exec(");
                } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            }).map(p -> main.relativize(p).toString().replace('\\', '/')).toList());
        }
        assertThat(spawning).containsExactly("com/keplura/server/bridge/StdioProcess.java",
                "com/keplura/server/datasource/NativeLocalReader.java", "com/keplura/server/search/ocr/PdfOcr.java");
        for (Class<?> c : List.of(McpStdioBridge.class, StdioProcess.class, BridgeConfig.class)) {
            for (var a : c.getAnnotations()) assertThat(a.annotationType().getName()).doesNotStartWith("org.springframework");
        }
        assertThat(McpStdioBridge.requested(new String[]{"--spring.profiles.active=prod"})).isFalse();
        assertThat(McpStdioBridge.requested(new String[]{McpStdioBridge.FLAG})).isTrue();
    }
}
