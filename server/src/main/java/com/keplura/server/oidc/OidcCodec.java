package com.keplura.server.oidc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Encoding + crypto plumbing for the OIDC login path (docs/adr/0009). Pure
 * statics, JDK-only — the deliberate no-new-dependency surface: Base64url
 * (strict, no padding, RFC 7515), SHA-256, and fresh SecureRandom values for
 * state / nonce / PKCE verifier / handoff codes.
 */
public final class OidcCodec {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();

    private OidcCodec() {
    }

    /** RFC 7515 base64url without padding; strict decode (throws on junk). */
    public static byte[] base64UrlDecode(String value) {
        return B64URL.decode(value);
    }

    /** Canonical RFC 7515 base64url without padding. */
    public static String base64UrlEncode(byte[] bytes) {
        return B64URL_ENC.encodeToString(bytes);
    }

    /** Lower-case hex of {@code n} fresh SecureRandom bytes. */
    public static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /**
     * PKCE code_verifier (RFC 7636 §4.1): 64 random bytes as base64url —
     * 86 chars, inside the 43..128 band, charset is unreserved by
     * construction (A-Za-z0-9-_ needs no escaping).
     */
    public static String pkceVerifier() {
        byte[] buf = new byte[64];
        RANDOM.nextBytes(buf);
        return base64UrlEncode(buf);
    }

    /** S256 challenge for a verifier (RFC 7636 §4.2). */
    public static String pkceChallenge(String verifier) {
        return base64UrlEncode(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    public static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from JVM", e);
        }
    }

    /** SHA-256 rendered base64url — used for the at_hash left-half check. */
    public static String sha256B64Url(byte[] in) {
        return base64UrlEncode(sha256(in));
    }

    /** Constant-time string equality (claims comparisons). */
    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
