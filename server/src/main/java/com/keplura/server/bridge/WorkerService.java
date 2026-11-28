package com.keplura.server.bridge;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class WorkerService implements AutoCloseable {
    private static final byte[] EMPTY = new byte[0];

    private final WorkerConfig config;
    private final byte[] key;
    private final PodmanRuntime runtime;
    private final Map<String, String> slots = new HashMap<>();
    private final Semaphore channels;
    private final Semaphore exchanges = new Semaphore(3);
    private final Set<SocketChannel> connections = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newScheduledThreadPool(2);
    private volatile boolean ready;

    WorkerService(WorkerConfig config, byte[] key, PodmanRuntime runtime) {
        this.config = config;
        this.key = key.clone();
        this.runtime = runtime;
        this.channels = new Semaphore(config.maxWorkers() + 8);
    }

    void prepare() throws IOException {
        runtime.verify(config);
        runtime.reap(config.instance());
        ready = true;
    }

    synchronized boolean reserve(String name, String session) {
        if (!ready || !config.servers().containsKey(name) || slots.containsKey(session) || slots.size() >= config.maxWorkers()
                || slots.values().stream().filter(name::equals).count() >= config.maxPerServer()) return false;
        slots.put(session, name);
        return true;
    }

    synchronized void release(String session) { slots.remove(session); }

    record Start(String server, String session) { }

    Start authenticate(byte[] bytes) throws IOException {
        var node = BridgeWire.JSON.readTree(bytes);
        WorkerConfig.fields(node, Set.of("server", "session", "key"));
        String name = node.path("server").asString("");
        String session = node.path("session").asString("");
        if (!node.path("key").isString() || !node.path("server").isString() || !node.path("session").isString()
                || !BridgeWire.matches(key, node.path("key").asString("")) || !BridgeWire.name(name) || !BridgeWire.session(session)
                || !config.servers().containsKey(name)) throw new IOException("start refused");
        return new Start(name, session);
    }

    void accept(SocketChannel socket) throws IOException {
        if (!channels.tryAcquire()) {
            socket.close();
            return;
        }
        connections.add(socket);
        Thread.ofVirtual().start(() -> {
            try (socket) {
                runSession(socket);
            } catch (IOException | RuntimeException ignored) {
                // No payload, exception or credential details reach logs.
            } finally {
                connections.remove(socket);
                channels.release();
            }
        });
    }

    private void runSession(SocketChannel socket) throws IOException {
        Start start = null;
        boolean reserved = false;
        AtomicReference<Process> worker = new AtomicReference<>();
        var lifetime = deadlines.schedule(() -> abort(socket, worker), WorkerConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var in = new DataInputStream(Channels.newInputStream(socket)); var out = new DataOutputStream(Channels.newOutputStream(socket))) {
            var helloDeadline = deadlines.schedule(() -> abort(socket, worker), 4, TimeUnit.SECONDS);
            try {
                var frame = BridgeWire.read(in, 2048);
                if (frame.type() != BridgeWire.START) return;
                start = authenticate(frame.bytes());
            } finally {
                helloDeadline.cancel(false);
            }
            reserved = reserve(start.server(), start.session());
            if (!reserved) {
                BridgeWire.write(out, BridgeWire.BUSY, EMPTY);
                return;
            }
            Process process = runtime.start(config.instance(), start.session(), config.servers().get(start.server()));
            worker.set(process);
            var stdout = new BufferedInputStream(process.getInputStream());
            BridgeWire.write(out, BridgeWire.OK, EMPTY);
            for (;;) {
                var frame = BridgeWire.read(in, BridgeWire.MAX_REQUEST);
                if (frame.type() != BridgeWire.REQUEST && frame.type() != BridgeWire.NOTIFY) return;
                if (!exchanges.tryAcquire()) {
                    BridgeWire.write(out, BridgeWire.BUSY, EMPTY);
                    return;
                }
                var deadline = deadlines.schedule(() -> abort(socket, worker), 12, TimeUnit.SECONDS);
                try {
                    byte[] message = BridgeWire.JSON.writeValueAsBytes(BridgeWire.JSON.readTree(frame.bytes()));
                    var stdin = process.getOutputStream();
                    stdin.write(message);
                    stdin.write('\n');
                    stdin.flush();
                    byte[] answer = frame.type() == BridgeWire.REQUEST ? response(stdout) : EMPTY;
                    BridgeWire.write(out, BridgeWire.OK, answer);
                } finally {
                    deadline.cancel(false);
                    exchanges.release();
                }
            }
        } finally {
            lifetime.cancel(false);
            abort(socket, worker);
            if (reserved) {
                try {
                    runtime.stop(config.instance(), start.session());
                } catch (IOException e) {
                    ready = false;
                    System.err.println("stdio worker cleanup failed; new starts suspended");
                } finally {
                    release(start.session());
                }
            }
        }
    }

    private static void abort(SocketChannel socket, AtomicReference<Process> process) {
        try { socket.close(); } catch (IOException ignored) { }
        Process child = process.get();
        if (child != null) child.destroyForcibly();
    }

    static byte[] response(InputStream input) throws IOException {
        // Allow a bounded number of JSON-RPC notifications before the response.
        int budget = BridgeWire.MAX_REPLY;
        for (int line = 0; line < 8 && budget > 0; line++) {
            var out = new ByteArrayOutputStream();
            for (int b; (b = input.read()) != '\n'; ) {
                if (b == -1 || --budget < 0) throw new IOException("invalid worker response");
                out.write(b);
            }
            byte[] bytes = out.toByteArray();
            var json = BridgeWire.JSON.readTree(bytes);
            if (json == null || !json.isObject() || !"2.0".equals(json.path("jsonrpc").asString(""))) {
                throw new IOException("invalid worker response");
            }
            if (json.has("id") && (json.has("result") ^ json.has("error")) && !json.has("method")) return bytes;
            if (json.has("id") || !json.path("method").isString()) break;
        }
        throw new IOException("invalid worker response");
    }

    @Override public void close() {
        ready = false;
        for (var connection : connections) {
            try { connection.close(); } catch (IOException ignored) { }
        }
        deadlines.shutdown();
    }

    static void run(Map<String, String> env) throws IOException {
        if (!BridgeWire.nonRoot()) throw new IOException("non-root Linux required");
        Path socket = Path.of(env.getOrDefault("KEPLURA_STDIO_SOCKET", "/run/keplura-stdio/worker.sock"));
        Path keyFile = Path.of(env.getOrDefault("KEPLURA_STDIO_RUNNER_KEY_FILE", "/etc/keplura-stdio/runner.key"));
        Path configFile = Path.of(env.getOrDefault("KEPLURA_STDIO_WORKERS_FILE", "/etc/keplura-stdio/workers.json"));
        Path lockFile = socket.resolveSibling("worker.lock");
        Path baseline = Path.of(env.getOrDefault("KEPLURA_STDIO_SECCOMP_BASELINE", "/usr/share/containers/seccomp.json"));
        try (var lock = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var singleton = lock.tryLock()) {
            if (singleton == null) throw new IOException("worker service already running");
            Path profile = PodmanRuntime.writeWorkerProfile(baseline, socket.getParent());
            try {
                serve(socket, WorkerConfig.read(configFile), BridgeWire.secret(keyFile), new PodmanRuntime(profile));
            } finally {
                Files.deleteIfExists(profile);
            }
        }
    }

    private static void serve(Path socket, WorkerConfig config, byte[] key, PodmanRuntime runtime) throws IOException {
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
             var service = new WorkerService(config, key, runtime)) {
            service.prepare(); // No listener or starts until effective limits and orphan cleanup are verified.
            if (Files.exists(socket, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                int mode = (int) Files.getAttribute(socket, "unix:mode", java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if ((mode & 0170000) != 0140000) throw new IOException("invalid socket path");
                Files.delete(socket);
            }
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw----"));
            while (true) service.accept(server.accept());
        }
    }
}
