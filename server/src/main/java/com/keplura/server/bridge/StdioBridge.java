package com.keplura.server.bridge;

import com.keplura.server.http.BoundedHttpBody;
import com.keplura.server.mcp.McpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import tools.jackson.databind.JsonNode;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

final class StdioBridge implements AutoCloseable {
    private static final byte[] EMPTY = new byte[0];
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private final HttpServer http;
    private final Path runner;
    private final byte[] token;
    private final String runnerKey;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(32);
    private final Semaphore exchanges = new Semaphore(3);
    private final java.util.concurrent.ExecutorService requests = Executors.newFixedThreadPool(32);
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newScheduledThreadPool(2);

    StdioBridge(HttpServer http, Path runner, byte[] token, byte[] runnerKey) {
        if (MessageDigest.isEqual(token, runnerKey)) throw new IllegalArgumentException("independent credentials required");
        this.http = http;
        this.runner = runner;
        this.token = token.clone();
        this.runnerKey = new String(runnerKey, StandardCharsets.US_ASCII);
        http.setExecutor(requests);
        http.createContext("/", this::handle);
    }

    void start() { http.start(); }

    private void handle(HttpExchange request) {
        try {
            route(request);
        } catch (IOException | RuntimeException e) {
            try { reply(request, 502, EMPTY); } catch (IOException ignored) { }
        } finally {
            request.close();
        }
    }

    private void route(HttpExchange request) throws IOException {
        String method = request.getRequestMethod();
        String path = request.getRequestURI().getRawPath();
        if ("GET".equals(method) && "/health".equals(path)) {
            reply(request, 204, EMPTY);
            return;
        }
        String authorization = header(request, "Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || !BridgeWire.matches(token, authorization.substring(7))) {
            reply(request, 401, EMPTY);
            return;
        }
        String name = path.startsWith("/mcp/") ? path.substring(5) : "";
        String proof = header(request, McpClient.BRIDGE_PROOF_HEADER);
        if (!BridgeWire.name(name) || request.getRequestURI().getRawQuery() != null || proof == null || !proof.matches("[a-f0-9]{64}")) {
            reply(request, 404, EMPTY);
            return;
        }
        String id = header(request, "Mcp-Session-Id");
        Session session = id == null ? null : sessions.get(id);
        if (id != null && (session == null || !session.name.equals(name) || !MessageDigest.isEqual(session.proofHash, BridgeWire.hash(proof)))) {
            reply(request, 404, EMPTY);
            return;
        }
        if ("DELETE".equals(method) && session != null) {
            session.close();
            reply(request, 204, EMPTY);
            return;
        }
        if (!"POST".equals(method)) {
            reply(request, 405, EMPTY);
            return;
        }
        if (!exchanges.tryAcquire()) {
            reply(request, 503, EMPTY);
            return;
        }
        try {
            post(request, name, proof, session);
        } finally {
            exchanges.release();
        }
    }

    private void post(HttpExchange request, String name, String proof, Session session) throws IOException {
        JsonNode rpc;
        try (var body = new BoundedHttpBody(request.getRequestBody(), BridgeWire.MAX_REQUEST, TIMEOUT)) {
            rpc = BridgeWire.JSON.readTree(body.readAllBytes());
        }
        if (rpc == null || !rpc.isObject() || !"2.0".equals(rpc.path("jsonrpc").asString("")) || !rpc.path("method").isString()) {
            reply(request, 400, EMPTY);
            return;
        }

        boolean initialize = "initialize".equals(rpc.path("method").asString(""));
        if ((session == null) != initialize || initialize && (!rpc.has("id") || request.getRequestHeaders().containsKey("Mcp-Session-Id"))) {
            reply(request, 400, EMPTY);
            return;
        }
        if (session == null) {
            if (!slots.tryAcquire()) {
                reply(request, 503, EMPTY);
                return;
            }
            Connection wire = null;
            try {
                wire = new Connection(SocketChannel.open(StandardProtocolFamily.UNIX));
                session = new Session(BridgeWire.id(), name, BridgeWire.hash(proof), wire);
            } finally {
                if (session == null) {
                    if (wire != null) wire.close();
                    slots.release();
                }
            }
            try {
                var hello = BridgeWire.JSON.createObjectNode().put("server", name).put("session", session.id).put("key", runnerKey);
                var started = session.wire.start(runner, BridgeWire.JSON.writeValueAsBytes(hello));
                if (started.type() != BridgeWire.OK || started.bytes().length != 0) {
                    session.close();
                    reply(request, 503, EMPTY);
                    return;
                }
                session.register();
            } catch (IOException | RuntimeException e) {
                session.close();
                reply(request, 503, EMPTY);
                return;
            }
        }
        if (!session.lock.tryLock()) {
            reply(request, 409, EMPTY);
            return;
        }
        try {
            boolean hasId = rpc.has("id");
            var result = session.wire.exchange(hasId ? BridgeWire.REQUEST : BridgeWire.NOTIFY, BridgeWire.JSON.writeValueAsBytes(rpc));
            if (result.type() != BridgeWire.OK) throw new IOException("worker unavailable");
            byte[] responseBytes = EMPTY;
            if (hasId) {
                var response = BridgeWire.JSON.readTree(result.bytes());
                if (response == null || !response.isObject() || !"2.0".equals(response.path("jsonrpc").asString(""))
                        || !rpc.path("id").equals(response.path("id")) || !(response.has("result") ^ response.has("error"))
                        || response.has("method")) throw new IOException("invalid worker response");
                String json = response.toString();
                if (json.contains(proof) || json.contains(runnerKey) || json.contains(new String(token, StandardCharsets.US_ASCII))) {
                    throw new IOException("invalid worker response");
                }
                responseBytes = BridgeWire.JSON.writeValueAsBytes(response);
            } else if (result.bytes().length != 0) {
                throw new IOException("invalid notification response");
            }
            request.getResponseHeaders().set("Mcp-Session-Id", session.id);
            reply(request, hasId ? 200 : 202, responseBytes);
        } catch (IOException | RuntimeException e) {
            session.close();
            reply(request, 502, EMPTY);
        } finally {
            session.lock.unlock();
        }
    }

    private static String header(HttpExchange request, String name) {
        var values = request.getRequestHeaders().get(name);
        return values == null || values.size() != 1 ? null : values.getFirst();
    }

    private static void reply(HttpExchange request, int status, byte[] bytes) throws IOException {
        request.getResponseHeaders().set("Cache-Control", "no-store");
        if (bytes.length == 0) {
            request.sendResponseHeaders(status, -1);
            return;
        }
        request.getResponseHeaders().set("Content-Type", "application/json");
        request.sendResponseHeaders(status, bytes.length);
        try (var out = request.getResponseBody()) { out.write(bytes); }
    }

    private final class Session {
        final String id;
        final String name;
        final byte[] proofHash;
        final Connection wire;
        final ReentrantLock lock = new ReentrantLock();
        private java.util.concurrent.ScheduledFuture<?> expiry;
        private boolean closed;

        Session(String id, String name, byte[] proofHash, Connection wire) {
            this.id = id;
            this.name = name;
            this.proofHash = proofHash;
            this.wire = wire;
        }

        synchronized void register() throws IOException {
            if (closed || sessions.putIfAbsent(id, this) != null) throw new IOException("session unavailable");
            expiry = deadlines.schedule(this::close, WorkerConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            sessions.remove(id, this);
            if (expiry != null) expiry.cancel(false);
            wire.close();
            slots.release();
        }
    }

    private final class Connection {
        private final SocketChannel socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        Connection(SocketChannel socket) {
            this.socket = socket;
            this.in = new DataInputStream(Channels.newInputStream(socket));
            this.out = new DataOutputStream(Channels.newOutputStream(socket));
        }

        BridgeWire.Frame start(Path path, byte[] hello) throws IOException {
            var deadline = deadlines.schedule(this::close, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try {
                socket.connect(UnixDomainSocketAddress.of(path));
                BridgeWire.write(out, BridgeWire.START, hello);
                return BridgeWire.read(in, 0);
            } finally { deadline.cancel(false); }
        }

        BridgeWire.Frame exchange(int type, byte[] payload) throws IOException {
            var deadline = deadlines.schedule(this::close, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try {
                BridgeWire.write(out, type, payload);
                return BridgeWire.read(in, BridgeWire.MAX_REPLY);
            } finally { deadline.cancel(false); }
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void close() {
        http.stop(0);
        sessions.values().forEach(Session::close);
        requests.shutdownNow();
        deadlines.shutdownNow();
    }

    static void run(Map<String, String> env) throws Exception {
        if (!BridgeWire.nonRoot()) throw new IOException("non-root Linux required");
        Path token = Path.of(env.getOrDefault("KEPLURA_STDIO_TOKEN_FILE", "/run/keplura-secrets/bridge.key"));
        Path key = Path.of(env.getOrDefault("KEPLURA_STDIO_RUNNER_KEY_FILE", "/run/keplura-secrets/runner.key"));
        Path socket = Path.of(env.getOrDefault("KEPLURA_STDIO_SOCKET", "/run/keplura-stdio/worker.sock"));
        Path tls = Path.of(env.getOrDefault("KEPLURA_STDIO_TLS_FILE", "/run/keplura-secrets/bridge.p12"));
        Path password = Path.of(env.getOrDefault("KEPLURA_STDIO_TLS_PASSWORD_FILE", "/run/keplura-secrets/tls.key"));
        var http = HttpsServer.create(new InetSocketAddress(8090), 32);
        http.setHttpsConfigurator(new HttpsConfigurator(tlsContext(tls, password)));
        try (var bridge = new StdioBridge(http, socket, BridgeWire.secret(token), BridgeWire.secret(key))) {
            bridge.start();
            Thread.currentThread().join();
        }
    }

    private static SSLContext tlsContext(Path file, Path passwordFile) throws Exception {
        char[] password = new String(BridgeWire.secret(passwordFile), StandardCharsets.US_ASCII).toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var data = Files.newInputStream(file)) { store.load(data, password); }
            var manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            manager.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(manager.getKeyManagers(), null, null);
            return context;
        } finally { java.util.Arrays.fill(password, '\0'); }
    }
}
