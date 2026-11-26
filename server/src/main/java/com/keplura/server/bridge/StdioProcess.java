package com.keplura.server.bridge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One stdio MCP process of the bridge (TU-06c). Started with the declared argument list (no shell), a cleared
 * environment plus the declared variables, and the declared working directory. Messages are newline-delimited
 * JSON-RPC (MCP stdio transport). Every request waits at most the declared timeout; a hang, an oversized line or
 * too much total output kills the process tree. Never reused after a failure.
 */
final class StdioProcess implements AutoCloseable {

    /** Environment of every child, before the declared variables. Nothing from the bridge's own environment. */
    static final java.util.Map<String, String> BASE_ENV = java.util.Map.of(
            "PATH", "/usr/local/bin:/usr/bin:/bin", "LANG", "C.UTF-8");

    static class Failure extends RuntimeException {
        final int httpStatus;
        Failure(String message, int httpStatus) {
            super(message, null, false, false);
            this.httpStatus = httpStatus;
        }
    }

    private static final Object EOF = new Object();
    private static final Object OVERFLOW = new Object();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Process process;
    private final OutputStream stdin;
    private final BlockingQueue<Object> messages = new LinkedBlockingQueue<>(256);
    private final BridgeConfig.ServerSpec spec;
    private final AtomicLong lastUsed = new AtomicLong(System.nanoTime());
    private final long started = System.nanoTime();
    private volatile boolean closed;

    private StdioProcess(BridgeConfig.ServerSpec spec, Process process) {
        this.spec = spec;
        this.process = process;
        this.stdin = process.getOutputStream();
        Thread reader = new Thread(() -> readStdout(process.getInputStream()), "mcp-bridge-" + spec.name() + "-out");
        reader.setDaemon(true);
        reader.start();
        Thread drain = new Thread(() -> drain(process.getErrorStream()), "mcp-bridge-" + spec.name() + "-err");
        drain.setDaemon(true);
        drain.start();
    }

    static StdioProcess start(BridgeConfig.ServerSpec spec) {
        ProcessBuilder pb = new ProcessBuilder(spec.command());
        var env = pb.environment();
        env.clear(); // never inherit the bridge's environment (token, JVM options, anything else)
        env.putAll(BASE_ENV);
        env.put("HOME", spec.workdir().toString());
        env.putAll(spec.env());
        pb.directory(spec.workdir().toFile());
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        try {
            return new StdioProcess(spec, pb.start());
        } catch (IOException e) {
            throw new Failure("server process could not be started", 502);
        }
    }

    long pid() { return process.pid(); }

    boolean alive() { return !closed && process.isAlive(); }

    boolean idleLongerThan(Duration idle) { return System.nanoTime() - lastUsed.get() > idle.toNanos(); }

    boolean olderThan(Duration age) { return System.nanoTime() - started > age.toNanos(); }

    /** Sends a request and waits for the response with the same id. Server-to-client messages are dropped. */
    synchronized JsonNode request(JsonNode message) {
        send(message);
        JsonNode id = message.path("id");
        long deadline = System.nanoTime() + spec.timeout().toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            Object next;
            try {
                next = left <= 0 ? null : messages.poll(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                throw new Failure("interrupted", 504);
            }
            if (next == null) {
                close();
                throw new Failure("server did not answer in time", 504);
            }
            if (next == EOF) {
                close();
                throw new Failure("server process ended", 502);
            }
            if (next == OVERFLOW) {
                close();
                throw new Failure("server output too large", 502);
            }
            JsonNode msg = (JsonNode) next;
            if (msg.isObject() && msg.path("id").equals(id) && (msg.has("result") || msg.has("error"))) {
                lastUsed.set(System.nanoTime());
                return msg;
            }
        }
    }

    /** A notification (no response expected). */
    synchronized void notify(JsonNode message) {
        send(message);
    }

    private void send(JsonNode message) {
        if (!alive()) throw new Failure("server process ended", 502);
        lastUsed.set(System.nanoTime());
        try {
            stdin.write(MAPPER.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            close();
            throw new Failure("server process ended", 502);
        }
    }

    private void readStdout(InputStream in) {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long total = 0;
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > (long) spec.maxOutputBytes() * 4) { overflow(); return; }
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        offer(line.toByteArray());
                        line.reset();
                    } else {
                        line.write(b);
                        if (line.size() > spec.maxOutputBytes()) { overflow(); return; }
                    }
                }
            }
        } catch (IOException ignored) {
            // process gone
        }
        messages.offer(EOF);
    }

    private void offer(byte[] raw) {
        if (raw.length == 0) return;
        try {
            JsonNode msg = MAPPER.readTree(new String(raw, StandardCharsets.UTF_8));
            if (!messages.offer(msg)) overflow();
        } catch (RuntimeException e) {
            // not JSON (e.g. a stray log line on stdout): ignored, never forwarded
        }
    }

    private void overflow() {
        messages.clear();
        messages.offer(OVERFLOW);
        close();
    }

    private static void drain(InputStream err) {
        byte[] buf = new byte[4096];
        try (err) {
            while (err.read(buf) > 0) { /* discarded: stderr is never forwarded or logged */ }
        } catch (IOException ignored) {
            // process gone
        }
    }

    @Override
    public void close() {
        closed = true;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { stdin.close(); } catch (IOException ignored) { }
    }
}
