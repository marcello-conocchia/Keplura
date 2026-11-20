package com.keplura.server.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI-compatible chat-completions proxy client (M4a). Owns the ONLY place
 * in the codebase where the API key is used: it attaches the bearer header to
 * the upstream request and NOTHING else may touch it.
 *
 * <p>Leak discipline by construction:
 * <ul>
 *   <li>Every upstream failure maps to a {@link GatewayException} carrying a
 *       fixed generic message — no cause chain is attached, because JDK
 *       IOException messages embed the upstream host ("Connection refused:
 *       /127.0.0.1:3456"). Logs print exception CLASS names only.</li>
 *   <li>Upstream response bodies on error statuses are read only to bound the
 *       connection and then DISCARDED — never relayed.</li>
 *   <li>Success-path bodies ARE relayed verbatim (that is the product), but
 *       the request itself was whitelisted upstream-side by the validator, so
 *       prompt content exists in transit only.</li>
 *   <li>Log lines carry model name, stream flag, status code and duration —
 *       never key, URL, headers or prompt content.</li>
 * </ul>
 *
 * <p>Redirects: refused at client build level (GatewayConfiguration). Timeouts:
 * request timeout bounds the time to response headers; an idle watchdog closes
 * the stream (unblocking the read) when a live stream goes silent, and a hard
 * deadline caps total duration. A killed stream ends the HTTP response early —
 * the consumer sees an incomplete SSE transfer, which is the honest signal.
 *
 * <p>ADMIN-KI (docs/adr/0008 §5.2): the dial target may come from DB rows
 * instead of ENV. The three-arg {@code completeJson}/{@code openStream}
 * overloads run one request against an EFFECTIVE {@link GatewayProperties}
 * resolved per request by {@link GatewayRuntime} — base URL, key, model gate
 * and per-call timeout ride along; the transport caps (stream watchdog, byte
 * limits) always stay the boot/ENV values because GatewayRuntime copies them
 * into the effective instance. The two-arg forms keep the pure ENV behaviour
 * (boot bean) untouched for the ENV/legacy path and existing tests.
 */
public class GatewayClient {

    /** Upstream reply: status already validated as 2xx; body bytes capped. */
    public record BufferedUpstreamResponse(int status, String contentType, byte[] body) {
    }

    /** Open stream: caller MUST close; watchdog timers are armed. */
    public record UpstreamStream(InputStream body, String contentType) implements AutoCloseable {
        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // closing an already-dead stream tells us nothing actionable
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);

    private final HttpClient client;
    private final GatewayProperties props;
    private final URI endpoint;

    public GatewayClient(HttpClient client, GatewayProperties props) {
        this.client = client;
        this.props = props;
        // URI.create may throw at boot on a malformed URL — fail fast there, not per request.
        this.endpoint = URI.create(props.chatCompletionsEndpoint());
    }

    /** The boot/ENV config this client was built with (ADMIN-KI fallback leg). */
    public GatewayProperties properties() {
        return props;
    }

    /** Buffered (stream=false) chat call against the ENV boot config. */
    public BufferedUpstreamResponse completeJson(byte[] canonicalBody, String model) {
        return completeJson(canonicalBody, model, props, endpoint);
    }

    /**
     * ADMIN-KI: buffered call against a request-bound effective config
     * (DB-resolved). The endpoint is re-derived per call — base URLs change
     * live through the admin UI.
     */
    public BufferedUpstreamResponse completeJson(byte[] canonicalBody, String model,
                                                 GatewayProperties effective) {
        return completeJson(canonicalBody, model, effective, URI.create(effective.chatCompletionsEndpoint()));
    }

    private BufferedUpstreamResponse completeJson(byte[] canonicalBody, String model,
                                                  GatewayProperties cfg, URI target) {
        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(
                    request(canonicalBody, "application/json", cfg, target),
                    HttpResponse.BodyHandlers.ofInputStream());
            String retryAfter = sanitizeRetryAfter(response);
            int status = response.statusCode();
            if (status == 429) {
                drainQuietly(response);
                throw new GatewayException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "model gateway rate limited", retryAfter);
            }
            if (status < 200 || status >= 300) {
                // upstream auth problems and everything else: OUR server-side issue, mapped to 502.
                if (status == 400 || status == 422) {
                    byte[] error = readCapped(response.body(), cfg);
                    if (unsupportedTools(canonicalBody, error)) throw new GatewayException.ToolsUnsupported();
                } else {
                    drainQuietly(response);
                }
                log.warn("gateway upstream status {} (model={}, stream=false, {}ms)",
                        status, model, elapsed(start));
                throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "model gateway upstream failure");
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                drainQuietly(response);
                throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "model gateway upstream response invalid");
            }
            byte[] body = readCapped(response.body(), cfg);
            log.info("gateway chat json: model={} status={} bytes={} ms={}",
                    model, status, body.length, elapsed(start));
            return new BufferedUpstreamResponse(status, contentType, body);
        } catch (GatewayException e) {
            throw e;
        } catch (IOException e) {
            // e.getMessage() can name the upstream host — never attach, never print it.
            log.warn("gateway upstream unreachable (model={}, stream=false; {})",
                    model, e.getClass().getSimpleName());
            throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "model gateway upstream unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "model gateway call interrupted");
        }
    }

    private static boolean unsupportedTools(byte[] request, byte[] error) {
        try {
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            if (!json.readTree(request).has("tools")) return false;
            var root = json.readTree(error).path("error");
            String code = root.path("code").asString("");
            String message = (root.isString() ? root.asString() : root.path("message").asString(""))
                    .toLowerCase(java.util.Locale.ROOT);
            return code.equals("unsupported_tools") || code.equals("tools_not_supported")
                    || message.contains("does not support tools") || message.contains("does not support tool calling");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Opens the SSE stream against the ENV boot config. */
    public UpstreamStream openStream(byte[] canonicalBody, String model) {
        return openStream(canonicalBody, model, props, endpoint);
    }

    /**
     * ADMIN-KI: SSE stream against a request-bound effective config.
     *
     * <p>Opens the SSE stream. Non-2xx answers throw mapped {@link GatewayException}
     * BEFORE any bytes flow, so the controller can still emit a clean JSON error.
     * The returned stream is watchdog-guarded (idle timeout + hard deadline).
     */
    public UpstreamStream openStream(byte[] canonicalBody, String model,
                                     GatewayProperties effective) {
        return openStream(canonicalBody, model, effective, URI.create(effective.chatCompletionsEndpoint()));
    }

    private UpstreamStream openStream(byte[] canonicalBody, String model,
                                      GatewayProperties cfg, URI target) {
        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(
                    request(canonicalBody, "text/event-stream", cfg, target),
                    HttpResponse.BodyHandlers.ofInputStream());
            String retryAfter = sanitizeRetryAfter(response);
            int status = response.statusCode();
            if (status == 429) {
                drainQuietly(response);
                throw new GatewayException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "model gateway rate limited", retryAfter);
            }
            if (status < 200 || status >= 300) {
                drainQuietly(response);
                log.warn("gateway upstream status {} (model={}, stream=true, {}ms)",
                        status, model, elapsed(start));
                throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "model gateway upstream failure");
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                drainQuietly(response);
                throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "model gateway upstream response invalid");
            }
            log.info("gateway chat stream open: model={} status={} ms={}", model, status, elapsed(start));
            return new UpstreamStream(watchdog(response.body(), model, cfg), contentType);
        } catch (GatewayException e) {
            throw e;
        } catch (IOException e) {
            log.warn("gateway upstream unreachable (model={}, stream=true; {})",
                    model, e.getClass().getSimpleName());
            throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "model gateway upstream unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "model gateway call interrupted");
        }
    }

    // ---- internals ----

    private HttpRequest request(byte[] body, String accept, GatewayProperties cfg, URI target) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        // M4c: the preset decides whether auth rides at all. Local backends
        // (Ollama/vLLM) have no auth surface — sending a hosted-API bearer to
        // them would leak the key into an untrusted LAN host's access logs.
        if (cfg.sendsAuth() && !cfg.apiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + cfg.apiKey());
        }
        return builder.build();
    }

    /**
     * Retry-After is echoed to the caller ONLY in this sanitised numeric form
     * (the QA-gate pattern from M2d). Dates/http-equiv forms are dropped — a
     * generic 429 without the header is always safe.
     */
    private static String sanitizeRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after")
                .map(String::trim)
                .filter(v -> v.matches("\\d{1,5}"))
                .orElse(null);
    }

    private void drainQuietly(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            if (in != null) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
        } catch (IOException ignored) {
            // the response is terminal anyway
        }
    }

    private byte[] readCapped(InputStream in, GatewayProperties cfg) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = stream.read(buf)) > 0) {
                total += n;
                if (total > cfg.maxResponseBytes()) {
                    throw new GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                            "model gateway upstream response too large");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * Wraps the stream so that (a) a silent gap longer than the idle budget and
     * (b) a total duration beyond the hard deadline CLOSE the stream, which
     * unblocks the pump with an IOException. Close cancels both timers.
     */
    private InputStream watchdog(InputStream raw, String model, GatewayProperties cfg) {
        AtomicLong lastReadMillis = new AtomicLong(System.currentTimeMillis());
        Timer guard = new Timer("gateway-stream-guard", true);
        Runnable kill = () -> {
            try {
                raw.close();
            } catch (IOException ignored) {
                // best-effort: close on an active connection aborts the read
            }
        };
        InputStream guarded = new FilterInputStream(raw) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    int n = super.read(b, off, len);
                    lastReadMillis.set(System.currentTimeMillis());
                    return n;
                } catch (IOException e) {
                    // OUR watchdog kill surfaces here too: only the class name is
                    // logged either way — JDK IO messages can name the upstream host.
                    log.warn("gateway stream read failed (model={}, cause={})",
                            model, e.getClass().getSimpleName());
                    throw e;
                }
            }

            @Override
            public void close() {
                guard.cancel();
                try {
                    super.close();
                } catch (IOException ignored) {
                    // closing an already-dead stream tells us nothing actionable
                }
            }
        };
        guard.scheduleAtFixedRate(new TimerTask() {
            private final long startWallMillis = System.currentTimeMillis();

            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long deadlineMillis = Duration.ofSeconds(cfg.streamDeadlineSeconds()).toMillis();
                long idleMillis = Duration.ofSeconds(cfg.streamIdleSeconds()).toMillis();
                if (now - lastReadMillis.get() > idleMillis) {
                    log.warn("gateway stream idle timeout (model={})", model);
                    kill.run();
                } else if (now - startWallMillis > deadlineMillis) {
                    log.warn("gateway stream deadline exceeded (model={})", model);
                    kill.run();
                }
            }
        }, 1000, 1000);
        return guarded;
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
