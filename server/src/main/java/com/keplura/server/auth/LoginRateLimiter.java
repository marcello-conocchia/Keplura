package com.keplura.server.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal fixed-window login rate limiter (M2b, hardened in M2c): at most
 * {@value #MAX_ATTEMPTS} failed login attempts per (client IP, normalised
 * email) within {@value #WINDOW_MINUTES} minutes; further attempts get 429
 * until the window passes. A success resets the counter.
 *
 * <p>M2c additions:
 * <ul>
 *   <li><b>Per-email window</b> ({@value #MAX_ATTEMPTS_PER_EMAIL}): the
 *       (ip, email) key alone is useless against an email-spray — one
 *       password guess per IP never blocks any single key. A second counter
 *       keyed by email only closes that gap for a single target account.</li>
 *   <li><b>Retry-After support</b>: {@link #retryAfterSeconds} reports the
 *       remaining window so the controller can honour RFC 9110 on 429.</li>
 * </ul>
 *
 * <p><b>Known limitation, accepted for the MVP:</b> the state is in-memory and
 * per application instance — it does NOT coordinate across a multi-instance
 * deployment and is lost on restart. It exists to slow down single-target
 * brute force, not to stop a distributed attacker. The M2b directive explicitly
 * excludes Redis/external stores; a shared or edge-level limiter belongs to a
 * later milestone (see docs/adr/0003).
 *
 * <p>The caller must pass the IP from {@code request.getRemoteAddr()} only —
 * the {@code X-Forwarded-For} header is deliberately NOT consulted because it
 * is client-spoofable and would let an attacker trivially rotate limiter keys.
 * A deployment behind a reverse proxy should enable Tomcat's RemoteIpValve so
 * getRemoteAddr() already reflects the true client.
 *
 * <p>The key map is capped ({@value #MAX_KEYS}) so an attacker spraying random
 * emails cannot grow memory without bound; over the cap, expired entries are
 * swept first and, failing that, the whole table is reset (fail-open — rate
 * limiting is a brake, not an authorization check).
 */
@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 10;
    static final int MAX_ATTEMPTS_PER_EMAIL = 30;
    static final long WINDOW_MINUTES = 15;
    static final int MAX_KEYS = 100_000;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);

    private record WindowState(int failures, Instant windowStart) {
        boolean expiredAt(Instant now) {
            return now.isAfter(windowStart.plus(WINDOW));
        }
    }

    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public LoginRateLimiter() {
        this(Clock.systemUTC());
    }

    /** Test seam: fixed/offset clocks make window-expiry testable without sleeping. */
    LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** True when a login attempt for this (ip, email) is currently throttled. */
    public boolean isBlocked(String clientIp, String normalizedEmail) {
        return blockedCounted(clientKey(clientIp, normalizedEmail), MAX_ATTEMPTS)
                || blockedCounted(emailKey(normalizedEmail), MAX_ATTEMPTS_PER_EMAIL);
    }

    /**
     * Whole seconds until the binding window expires (≥1), for the 429
     * Retry-After header. Call only when {@link #isBlocked} returned true;
     * if a race already cleared both windows, returns a conservative 1.
     */
    public long retryAfterSeconds(String clientIp, String normalizedEmail) {
        Instant now = clock.instant();
        Duration remaining = Duration.ZERO;
        WindowState ipState = windows.get(clientKey(clientIp, normalizedEmail));
        if (ipState != null && ipState.failures() >= MAX_ATTEMPTS) {
            remaining = windowRemaining(ipState, now);
        }
        WindowState emailState = windows.get(emailKey(normalizedEmail));
        if (emailState != null && emailState.failures() >= MAX_ATTEMPTS_PER_EMAIL) {
            Duration emailRemaining = windowRemaining(emailState, now);
            remaining = emailRemaining.compareTo(remaining) > 0 ? emailRemaining : remaining;
        }
        long seconds = remaining.plusSeconds(59).toSeconds(); // round up, floor at 1
        return Math.max(1, Math.min(seconds, WINDOW.toSeconds()));
    }

    /** Records one failed attempt; call after every authentication failure. */
    public void recordFailure(String clientIp, String normalizedEmail) {
        if (windows.size() >= MAX_KEYS) {
            sweepExpired();
        }
        bump(clientKey(clientIp, normalizedEmail));
        bump(emailKey(normalizedEmail));
        if (windows.size() > MAX_KEYS) {
            windows.clear(); // still unbounded growth: reset, fail-open
        }
    }

    /** Clears the failure windows after a successful login. */
    public void recordSuccess(String clientIp, String normalizedEmail) {
        windows.remove(clientKey(clientIp, normalizedEmail));
        windows.remove(emailKey(normalizedEmail));
    }

    private boolean blockedCounted(String key, int limit) {
        WindowState state = live(key);
        return state != null && state.failures() >= limit;
    }

    /** Fixed-window bump; each key keeps its own counter and window start. */
    private void bump(String key) {
        windows.compute(key, (k, prev) -> {
            Instant now = clock.instant();
            if (prev == null || prev.expiredAt(now)) {
                return new WindowState(1, now);
            }
            return new WindowState(prev.failures() + 1, prev.windowStart());
        });
    }

    private static Duration windowRemaining(WindowState state, Instant now) {
        Duration left = Duration.between(now, state.windowStart().plus(WINDOW));
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Returns the state only if it is present and not yet expired. */
    private WindowState live(String key) {
        WindowState state = windows.get(key);
        if (state == null) {
            return null;
        }
        if (state.expiredAt(clock.instant())) {
            windows.remove(key, state);
            return null;
        }
        return state;
    }

    private void sweepExpired() {
        Instant now = clock.instant();
        windows.entrySet().removeIf(e -> e.getValue().expiredAt(now));
    }

    private static String clientKey(String clientIp, String normalizedEmail) {
        return clientIp + '|' + normalizedEmail;
    }

    private static String emailKey(String normalizedEmail) {
        return "email:" + normalizedEmail;
    }
}
