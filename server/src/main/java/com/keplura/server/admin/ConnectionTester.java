package com.keplura.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * The ADMIN-KI "Verbindung testen" probe (Scope B/E, docs/adr/0008 §7).
 * Probes an OPENAI-COMPATIBLE provider's root endpoint cheaply: GET on the
 * base URL with a short timeout. A 2xx/3xx/4xx HTTP answer — any HTTP answer
 * — proves the endpoint is ALIVE and reachable (a 401 from a keyless probe
 * still means "the server spoke to us"); only IO failure, timeout, or an
 * SSRF refusal mean "not reachable".
 *
 * <p>Hard rules carried from the ADR:
 * <ul>
 *   <li>SSRF route policy runs FIRST ({@link SsrfGuard#validateForDial}) —
 *       refused targets are never dialed.</li>
 *   <li>Redirects are NEVER followed (credential-smuggling + SSRF pivot,
 *       same posture as gatewayHttpClient).</li>
 *   <li>Request timeout = the provider's configured timeout, capped at
 *       {@link #MAX_TIMEOUT_SECONDS}; the client connect timeout matches.</li>
 *   <li>Response body is read only to drain-and-discard under a small cap
 *       — the CONTENT never appears anywhere (it could echo internal state).</li>
 *   <li>The secret travels as a normal bearer ONLY for presets that use one
 *       (ollama/vllm have no auth surface — the key would leak into a LAN
 *       host's access log, which is exactly what AuthKind.NONE prevents in
 *       the GatewayClient; same rule here).</li>
 *   <li>The RESULT is a closed vocabulary: reachable + optional coarse HTTP
 *       class. Never host, IP, port, or exception message.</li>
 * </ul>
 */
@Service
public class ConnectionTester {

    /** Hard cap on the probe timeout regardless of the stored provider value. */
    static final long MAX_TIMEOUT_SECONDS = 15;

    /** Bytes of an error/upstream body we tolerate draining before giving up. */
    private static final int BODY_DRAIN_CAP = 64 * 1024;

    public record TestOutcome(boolean reachable, String code) {
    }

    private static final Logger log = LoggerFactory.getLogger(ConnectionTester.class);

    private final SsrfGuard ssrfGuard;
    private final SecretCipher secretCipher;

    public ConnectionTester(SsrfGuard ssrfGuard, SecretCipher secretCipher) {
        this.ssrfGuard = ssrfGuard;
        this.secretCipher = secretCipher;
    }

    /**
     * Probe one stored provider row. {@code testUrl} is the row's OWN base
     * URL (never a request-body URL — there is deliberately no "test an
     * arbitrary URL" endpoint that would bypass the stored-row review).
     */
    public TestOutcome test(AiProvider provider) {
        ssrfGuard.validateForDial(provider.getBaseUrl());
        URI target = probeTarget(provider.getBaseUrl());
        long timeout = Math.min(Math.max(1, provider.getTimeoutSeconds()), MAX_TIMEOUT_SECONDS);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(timeout))
                .GET();
        if (sendsAuth(provider.getProviderType()) && provider.hasSecret() && secretCipher.available()) {
            builder.header("Authorization", "Bearer " + secretCipher.decrypt(provider.getSecretCipher()));
        }
        try {
            HttpResponse<InputStream> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            drainCapped(response.body());
            int status = response.statusCode();
            log.info("connection test: provider={} http {} (target facts never logged)",
                    provider.getId(), status);
            // any HTTP-level answer = reachable; 5xx means "alive but upstream
            // is unhappy" — still reachable for the PO question (Scope E
            // vocabulary distinguishes error separately).
            if (status >= 500) {
                return new TestOutcome(true, "reachable_upstream_error");
            }
            return new TestOutcome(true, "reachable");
        } catch (IOException e) {
            // class name only: JDK IO messages embed host/port ("Connect
            // timed out /127.0.0.1:11434") — echoing them would turn the
            // probe into a recon oracle.
            log.warn("connection test: provider={} IO failure ({})",
                    provider.getId(), e.getClass().getSimpleName());
            return new TestOutcome(false, "unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestOutcome(false, "unreachable");
        }
    }

    /**
     * The OpenAI-compat presets all answer cheaply on the base root
     * (Ollama: "Ollama is running"; vLLM/managed: 401/404) — GET on the base
     * URL path as configured, no fabricated endpoint guessing.
     */
    private static URI probeTarget(String baseUrl) {
        URI parsed = SsrfGuard.parseShape(baseUrl);
        // URI.create again via components so the query/fragment of the stored
        // value never rides into the probe
        try {
            String path = parsed.getPath() == null || parsed.getPath().isEmpty() ? "/" : parsed.getPath();
            return new URI(parsed.getScheme(), null, parsed.getHost(), parsed.getPort(), path, null, null);
        } catch (Exception e) {
            throw AdminException.invalid("base URL could not be normalised");
        }
    }

    /** Mirrors ProviderPreset.AuthKind semantics without importing gateway wiring. */
    private static boolean sendsAuth(String providerType) {
        return "api".equals(providerType == null ? "" : providerType.toLowerCase(Locale.ROOT));
    }

    private static void drainCapped(InputStream in) {
        if (in == null) {
            return;
        }
        try (InputStream stream = in) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = stream.read(buf)) > 0) {
                total += n;
                if (total > BODY_DRAIN_CAP) {
                    return; // enough — the body is DISCARDED either way
                }
            }
        } catch (IOException ignored) {
            // the probe already told us what matters (the status line)
        }
    }
}
