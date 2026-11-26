package com.keplura.server.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * TU-06c, PO-Entscheidung 26.11.2026 „Bridge im Container“: a separate mode of the server jar
 * ({@code --mcp-stdio-bridge}) that runs in its OWN container of the deployment. It never starts Spring, has no
 * database and no Keplura secrets. It exposes each operator-declared stdio MCP server as Streamable HTTP at
 * {@code /mcp/<name>}; Keplura reaches it through the existing MCP HTTP adapter like any admin MCP server.
 *
 * <ul>
 *   <li>Only declared commands, as argument lists, without a shell ({@link BridgeConfig}).</li>
 *   <li>Every request needs the shared random token (constant-time compare); without it nothing starts.</li>
 *   <li>Each MCP session gets a fresh process (no state shared between calls or people); a crash only ends that
 *       session and the next session starts anew. Per call timeout, bounded output, idle and age limits.</li>
 *   <li>Refuses to run as root. CPU, memory, process count, read-only file system and egress come from the
 *       container definition (deploy/compose.prod.yml).</li>
 * </ul>
 */
public final class McpStdioBridge {

    public static final String FLAG = "--mcp-stdio-bridge";
    static final int MAX_REQUEST_BYTES = 1_000_000;
    static final Duration MAX_SESSION_AGE = Duration.ofMinutes(10);
    static final int MIN_TOKEN_LENGTH = 32;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Map<String, BridgeConfig.ServerSpec> servers;
    private final byte[] token;
    private final Map<String, StdioProcess> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionServer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-bridge-reaper");
        t.setDaemon(true);
        return t;
    });
    private ExecutorService workers;
    private HttpServer http;

    public McpStdioBridge(List<BridgeConfig.ServerSpec> specs, String token) {
        if (token == null || token.length() < MIN_TOKEN_LENGTH) {
            throw new BridgeConfig.InvalidConfig("KEPLURA_BRIDGE_TOKEN must be set to a random value of at least 32 characters");
        }
        Map<String, BridgeConfig.ServerSpec> byName = new java.util.LinkedHashMap<>();
        specs.forEach(s -> byName.put(s.name(), s));
        this.servers = Map.copyOf(byName);
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** True only for the explicit bridge flag; the normal server never enters this code path. */
    public static boolean requested(String[] args) {
        return args != null && java.util.Arrays.asList(args).contains(FLAG);
    }

    public InetSocketAddress start(InetSocketAddress bind) throws IOException {
        http = HttpServer.create(bind, 64);
        workers = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "mcp-bridge-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(workers);
        http.createContext("/", this::handle);
        http.start();
        reaper.scheduleAtFixedRate(this::reap, 5, 5, TimeUnit.SECONDS);
        return http.getAddress();
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (workers != null) workers.shutdownNow();
        reaper.shutdownNow();
        sessions.values().forEach(StdioProcess::close);
        sessions.clear();
        sessionServer.clear();
    }

    /** Open sessions (= running processes) per server; for tests and health. */
    int openSessions(String server) {
        return (int) sessionServer.values().stream().filter(server::equals).count();
    }

    void reap() {
        for (var e : sessions.entrySet()) {
            StdioProcess p = e.getValue();
            BridgeConfig.ServerSpec spec = servers.get(sessionServer.get(e.getKey()));
            if (!p.alive() || spec == null || p.idleLongerThan(spec.idleTimeout()) || p.olderThan(MAX_SESSION_AGE)) end(e.getKey());
        }
    }

    private void end(String sessionId) {
        StdioProcess p = sessions.remove(sessionId);
        sessionServer.remove(sessionId);
        if (p != null) p.close();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getRawPath();
            // Liveness only (no server names, no counts): usable by the container health check without the token.
            if (path.equals("/health") && "GET".equals(ex.getRequestMethod())) { reply(ex, 200, MAPPER.createObjectNode().put("status", "UP")); return; }
            if (!authorized(ex.getRequestHeaders().getFirst("Authorization"))) { reply(ex, 401, null); return; }
            if (!path.startsWith("/mcp/") || ex.getRequestURI().getRawQuery() != null) { reply(ex, 404, null); return; }
            BridgeConfig.ServerSpec spec = servers.get(path.substring(5));
            if (spec == null) { reply(ex, 404, null); return; }
            String sessionId = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            switch (ex.getRequestMethod()) {
                case "POST" -> post(ex, spec, sessionId);
                case "DELETE" -> {
                    if (sessionId != null && spec.name().equals(sessionServer.get(sessionId))) end(sessionId);
                    reply(ex, 204, null);
                }
                default -> reply(ex, 405, null);
            }
        } catch (RuntimeException e) {
            try { reply(ex, 500, null); } catch (IOException | RuntimeException ignored) { }
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] given = header.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token);
    }

    private void post(HttpExchange ex, BridgeConfig.ServerSpec spec, String sessionId) throws IOException {
        byte[] body = readBounded(ex.getRequestBody());
        if (body == null) { reply(ex, 413, null); return; }
        JsonNode msg;
        try {
            msg = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            reply(ex, 400, null);
            return;
        }
        if (msg == null || !msg.isObject() || !"2.0".equals(msg.path("jsonrpc").asString("")) || !msg.path("method").isString()) {
            reply(ex, 400, null);
            return;
        }
        boolean isRequest = msg.has("id");
        String method = msg.path("method").asString();
        StdioProcess process;
        String sid = sessionId;
        if ("initialize".equals(method)) {
            if (sessionId != null || !isRequest) { reply(ex, 400, null); return; }
            synchronized (this) {
                if (openSessions(spec.name()) >= spec.maxSessions()) { reply(ex, 503, null); return; }
                process = StdioProcess.start(spec);
                sid = UUID.randomUUID().toString();
                sessions.put(sid, process);
                sessionServer.put(sid, spec.name());
            }
        } else {
            process = sessionId == null || !spec.name().equals(sessionServer.get(sessionId)) ? null : sessions.get(sessionId);
            if (process == null) { reply(ex, 404, null); return; }
        }
        try {
            if (!isRequest) {
                process.notify(msg);
                ex.getResponseHeaders().set("Mcp-Session-Id", sid);
                reply(ex, 202, null);
                return;
            }
            JsonNode response = process.request(msg);
            ex.getResponseHeaders().set("Mcp-Session-Id", sid);
            reply(ex, 200, response);
        } catch (StdioProcess.Failure f) {
            end(sid);
            reply(ex, f.httpStatus, null);
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_REQUEST_BYTES + 1);
        return data.length > MAX_REQUEST_BYTES ? null : data;
    }

    private static void reply(HttpExchange ex, int status, JsonNode body) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    // ---- process entry ----

    /** Real user id on Linux (/proc/self/status); -1 when unknown. */
    public static int currentUid() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("Uid:")) return Integer.parseInt(line.substring(4).strip().split("\\s+")[0]);
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux or not readable
        }
        return -1;
    }

    /**
     * Starts the bridge from the deployment environment. Returns a non-zero exit code on any refusal; blocks while
     * running otherwise. Fixed messages only (no token, no command lines).
     */
    public static int run(String[] args, Map<String, String> env, IntSupplier uid, java.io.PrintStream err) {
        if (uid.getAsInt() == 0) {
            err.println("keplura mcp bridge: refusing to run as root; set a non-root user for the container");
            return 2;
        }
        String config = option(args, "--config=", env.getOrDefault("KEPLURA_BRIDGE_CONFIG", "/etc/keplura-bridge/servers.json"));
        String bind = option(args, "--bind=", env.getOrDefault("KEPLURA_BRIDGE_BIND", "0.0.0.0:8090"));
        String token = env.get("KEPLURA_BRIDGE_TOKEN");
        try {
            String tokenFile = env.get("KEPLURA_BRIDGE_TOKEN_FILE");
            if ((token == null || token.isBlank()) && tokenFile != null && !tokenFile.isBlank()) {
                token = Files.readString(Path.of(tokenFile)).strip();
            }
            List<BridgeConfig.ServerSpec> specs = BridgeConfig.parse(Files.readAllBytes(Path.of(config)));
            int colon = bind.lastIndexOf(':');
            var bridge = new McpStdioBridge(specs, token);
            var address = bridge.start(new InetSocketAddress(bind.substring(0, colon), Integer.parseInt(bind.substring(colon + 1))));
            err.println("keplura mcp bridge: " + specs.size() + " server(s) on port " + address.getPort());
            Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop));
            Thread.currentThread().join();
            return 0;
        } catch (BridgeConfig.InvalidConfig e) {
            err.println("keplura mcp bridge: " + e.getMessage());
            return 3;
        } catch (IOException | RuntimeException e) {
            err.println("keplura mcp bridge: cannot start (" + e.getClass().getSimpleName() + ")");
            return 4;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static String option(String[] args, String prefix, String fallback) {
        for (String a : args) if (a.startsWith(prefix)) return a.substring(prefix.length());
        return fallback;
    }
}
