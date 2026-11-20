package com.keplura.server.oidc;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HexFormat;

/**
 * The begin→callback binding (docs/adr/0009 §1): state, nonce and the PKCE
 * verifier live in the caller's HttpSession — the existing container
 * session machinery (ADR-0003), so there is no new store, expiry rides on
 * the session, and the CSRF property is exactly the double-submit shape:
 * an attacker page can forge a callback URL with a state value but can
 * NEVER place that value into the victim's server session, and cannot read
 * what is in there.
 *
 * <p>Attributes are written via a typed wrapper so the session never holds
 * bare strings an unrelated code path could collide with; the binding is
 * consumed (removed) on EVERY callback outcome — success, refusal, replay —
 * which makes a second callback with the same state an automatic refusal.
 *
 * <p>A fresh begin OVERWRITES an unconsumed binding (the same browser can
 * only have one OIDC login in flight anyway; a stale binding is strictly
 * worse than a replaced one).
 */
@Service
public class OidcBindingStore {

    static final String SESSION_ATTR = "keplura.oidc.binding";

    /** What begin generated and callback must enforce, all server-side. */
    public record Binding(String state, String nonce, String pkceVerifier,
                          long providerId, Instant issuedAt) implements java.io.Serializable {
    }

    public Binding create(HttpSession session, long providerId) {
        Binding binding = new Binding(
                OidcCodec.randomHex(32),
                OidcCodec.randomHex(16),
                OidcCodec.pkceVerifier(),
                providerId,
                Instant.now());
        session.setAttribute(SESSION_ATTR, binding);
        return binding;
    }

    /**
     * Atomically take and remove the binding for this session; returns null
     * when absent (no begin, or already consumed → replay). Caller enforces
     * providerId match and TTL.
     */
    public Binding consume(HttpSession session) {
        Object raw = session.getAttribute(SESSION_ATTR);
        session.removeAttribute(SESSION_ATTR);
        return (raw instanceof Binding b) ? b : null;
    }

    public boolean fresh(Binding binding, long ttlSeconds) {
        return binding != null
                && binding.issuedAt().plusSeconds(ttlSeconds).isAfter(Instant.now());
    }
}
