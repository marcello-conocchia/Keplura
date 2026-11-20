package com.keplura.server.oidc;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting for the OIDC endpoints (docs/adr/0009 §8/§11): the begin
 * endpoint is an unauthenticated dial-trigger (every call makes the server
 * fetch the IdP's discovery document), and the exchange endpoint converts a
 * leaked browser fragment into a session — both need a ceiling that does not
 * depend on the session.
 *
 * <p>Same fixed-window discipline as {@link com.keplura.server.auth
 * .LoginRateLimiter} (M2c hardening), per (endpoint, client IP): a bounded
 * number of windows in memory, expired buckets dropped on write, no
 * eviction-order surprises. X-Forwarded-For is NOT trusted (the limiter
 * docstring there spells out why); behind a proxy the deployment must
 * terminate TLS so {@code getRemoteAddr()} is the real peer.
 */
@Component
public class OidcRateLimiter {

    /** Bucket per (action, client IP). */
    private record Key(String action, String clientIp) {
    }

    /**
     * Test seam (package-private, no production caller): integration
     * suites share one Spring context, so their cumulative begin/exchange
     * legs would starve on the fixed window. Never wired into config.
     */
    void resetForTests() {
        windows.clear();
    }

    private record Window(Instant start, int hits) {
    }

    /** Begin is more sensitive (server-side dial); exchange carries the mint. */
    static final int BEGIN_LIMIT = 20;
    static final int EXCHANGE_LIMIT = 30;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final Map<Key, Window> windows = new ConcurrentHashMap<>();

    /** True when the caller may proceed; increments the window when not. */
    public boolean allowBegin(String clientIp) {
        return allow(new Key("begin", clientIp), BEGIN_LIMIT);
    }

    public boolean allowExchange(String clientIp) {
        return allow(new Key("exchange", clientIp), EXCHANGE_LIMIT);
    }

    /** Seconds until the current window rolls over (for Retry-After). */
    public Duration retryAfterBegin(String clientIp) {
        return retryAfter(clientIp, "begin");
    }

    public Duration retryAfterExchange(String clientIp) {
        return retryAfter(clientIp, "exchange");
    }

    private Duration retryAfter(String clientIp, String action) {
        Window w = windows.get(new Key(action, clientIp));
        if (w == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(Instant.now(), w.start().plus(WINDOW));
        return left.isNegative() ? Duration.ZERO : left;
    }

    private boolean allow(Key key, int limit) {
        Instant now = Instant.now();
        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.start().plus(WINDOW).isBefore(now)) {
                return new Window(now, 1);
            }
            return new Window(existing.start(), existing.hits() + 1);
        });
        if (current.hits() > limit) {
            return false;
        }
        // bound memory: drop fully-expired windows on the write path
        if (windows.size() > 4096) {
            windows.entrySet().removeIf(e -> e.getValue().start().plus(WINDOW).isBefore(now));
        }
        return true;
    }
}
