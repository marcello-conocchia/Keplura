package com.keplura.server.oidc;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one-time handoff between the OIDC browser legs (begin/callback, run in
 * the WEBVIEW navigation cookie jar) and the desktop API session
 * (POST /auth/oidc/exchange, run through the native transport, ADR-0009 §6).
 * The two jars differ on packaged Tauri (ADR-0005), so a session cookie
 * minted at the callback would be invisible to the app; the handoff code is
 * the bridge.
 *
 * <p>Properties (all by construction):
 * <ul>
 *   <li>256-bit random code, carried fragment-only (never query, never in a
 *       server access log line, fragments are not sent to servers);</li>
 *   <li>server-side TTL (default 60 s) — replaying a sniffed fragment needs
 *       both the fragment and the window;</li>
 *   <li>single-use: ANY exchange attempt removes the entry first, so there
 *       is no oracle to iterate against and no double-mint from one login;</li>
 *   <li>in-JVM only: this product is a single-server deployment, and a
 *       restart merely expires logins still inside their 60-second window
 *       (documented trade-off — a 60-second artifact needs no durable
 *       store).</li>
 * </ul>
 *
 * <p>The entry carries the userId ONLY — the exchange re-reads the user
 * row, so a deleted/renamed user can never log in from a stale handoff.
 */
@org.springframework.stereotype.Component
public class OidcHandoffStore {

    private record Entry(long userId, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Mint a handoff for a fully-validated login result. */
    public void issue(String code, long userId, long ttlSeconds) {
        sweep();
        entries.put(code, new Entry(userId, Instant.now().plusSeconds(ttlSeconds)));
    }

    /**
     * Consume a code exactly once: the entry is REMOVED before any check,
     * so an expired-code attempt still burns it (no timing oracle over
     * repeated attempts) and a correct code cannot be exchanged twice.
     */
    public Long consume(String code) {
        if (code == null || code.length() > 64) {
            return null;
        }
        Entry entry = entries.remove(code);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        return entry.userId();
    }

    /** Drop expired entries; called opportunistically on issue (bound: 60s). */
    void sweep() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }
}
