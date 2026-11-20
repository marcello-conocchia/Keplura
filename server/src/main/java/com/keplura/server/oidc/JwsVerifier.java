package com.keplura.server.oidc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Map;
import java.util.Set;

/**
 * Minimal, deliberately-narrow JWS RS/PS verifier for OIDC ID tokens
 * (docs/adr/0009 §4) — no new dependency (the task GRENZEN and the ADR name
 * the reasoning: JDK crypto + the Jackson already on the classpath).
 *
 * <p>Hard rules, in order, each killing a named attack:
 * <ol>
 *   <li><b>Alg whitelist BEFORE any parsing.</b> Only RS256/384/512 and
 *       PS256/384/512. {@code none}, HS256/384/512, ES*, and anything else
 *       refuse — there is NO HMAC code path in this class at all, so the
 *       classic alg-confusion attack (forge an HS256 token "signed" with the
 *   public RS key as the HMAC secret) is unrepresentable, not merely
 *       filtered. The token's alg must additionally equal the algorithm the
 *       caller expected ({@link #verify} takes it from the JWKS key entry's
 *       own {@code alg} when present, else the whitelist covers it).</li>
 *   <li><b>JWK shape is strict:</b> kty must be RSA, n/e must be
 *       base64url-decodable positive integers; exponents are capped
 *       (≤ 2^32-1) to refuse a crafted 10MB-e blowup before
 *       KeyFactory sees them.</li>
 *   <li><b>Signature over the EXACT ASCII bytes</b> of
 *       {@code b64url(header) + "." + b64url(payload)} via
 *       {@link java.security.Signature} (constant-time inside the provider);
 *       JSON parsing happens only AFTER the signature verified.</li>
 *   <li><b>kid must resolve</b> against the caller's key set (the
 *       discovery-fetched JWKS — never a key embedded in the token itself).</li>
 * </ol>
 *
 * <p>Every refusal is {@link OidcException#refused()} — a fixed generic
 * message; the specific reason never leaves the JVM (log code names only).
 */
public final class JwsVerifier {

    /** The ONLY accepted signature algorithms (ADR-0009 §4 whitelist). */
    static final Set<String> ALLOWED_ALGS = Set.of(
            "RS256", "RS384", "RS512", "PS256", "PS384", "PS512");

    /** JWS compact = three dot-separated segments; header/payload are JSON. */
    private static final int MAX_TOKEN_CHARS = 16384;
    private static final int MAX_SEGMENT_CHARS = 12288;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwsVerifier() {
    }

    /** One parsed, signature-verified JWS: header + payload as JSON trees. */
    public record Verified(String alg, JsonNode header, JsonNode payload) {
    }

    /**
     * Verify compact JWS {@code token} against {@code keys} (kid → key).
     * {@code expectedAlg} non-null additionally pins the alg (used by tests
     * proving downgrade refusal; production passes null — the whitelist +
     * per-key alg rule already bind the algorithm).
     */
    public static Verified verify(String token, Map<String, PublicKey> keys, String expectedAlg) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            throw OidcException.refused();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw OidcException.refused();
        }
        for (String p : parts) {
            if (p.length() > MAX_SEGMENT_CHARS) {
                throw OidcException.refused();
            }
        }
        byte[] headerBytes;
        byte[] payloadBytes;
        byte[] signature;
        JsonNode header;
        try {
            headerBytes = OidcCodec.base64UrlDecode(parts[0]);
            payloadBytes = OidcCodec.base64UrlDecode(parts[1]);
            signature = OidcCodec.base64UrlDecode(parts[2]);
            header = MAPPER.readTree(headerBytes);
        } catch (IllegalArgumentException e) {
            throw OidcException.refused(); // malformed base64url
        } catch (Exception e) {
            throw OidcException.refused(); // malformed header JSON
        }
        String alg = header.path("alg").asString(null);
        if (alg == null || !ALLOWED_ALGS.contains(alg)) {
            // kills none / HS* / ES* / unknown in one branch, BEFORE parsing
            throw OidcException.refused();
        }
        if (expectedAlg != null && !expectedAlg.equals(alg)) {
            throw OidcException.refused();
        }
        String kid = header.path("kid").asString(null);
        if (kid == null || kid.length() > 200) {
            throw OidcException.refused();
        }
        PublicKey key = keys.get(kid);
        if (key == null) {
            throw OidcException.refused();
        }
        // critical header parameters we do not understand must fail closed:
        // RFC 7515 §4.1.11 — ignore-none handling; any crit member kills us.
        JsonNode crit = header.path("crit");
        if (crit.isArray() && crit.size() > 0) {
            throw OidcException.refused();
        }
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        try {
            Signature verifier = Signature.getInstance(jcaAlgorithm(alg));
            if (alg.startsWith("PS")) {
                String hash = psHash(alg);
                verifier.setParameter(new java.security.spec.PSSParameterSpec(
                        hash,
                        "MGF1",
                        new java.security.spec.MGF1ParameterSpec(hash),
                        psSaltBytes(alg), // JOSE: salt length == hash length (RFC 7518 §3.5)
                        1));
            }
            verifier.initVerify(key);
            verifier.update(signingInput);
            if (!verifier.verify(signature)) {
                throw OidcException.refused();
            }
        } catch (OidcException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw OidcException.refused();
        }
        JsonNode payload;
        try {
            payload = MAPPER.readTree(payloadBytes);
        } catch (Exception e) {
            throw OidcException.refused();
        }
        if (!payload.isObject()) {
            throw OidcException.refused();
        }
        return new Verified(alg, header, payload);
    }

    /**
     * Build an RSA public key from JWK components. Static so the same
     * shape rules guard both the live JWKS path and tests.
     */
    public static PublicKey rsaPublicFromJwk(JsonNode jwk) {
        if (jwk == null || !"RSA".equals(jwk.path("kty").asString(null))) {
            throw OidcException.refused();
        }
        String n = jwk.path("n").asString(null);
        String e = jwk.path("e").asString(null);
        if (n == null || e == null || n.length() > 1600 || e.length() > 16) {
            throw OidcException.refused();
        }
        try {
            BigInteger modulus = new BigInteger(1, OidcCodec.base64UrlDecode(n));
            BigInteger exponent = new BigInteger(1, OidcCodec.base64UrlDecode(e));
            if (modulus.bitLength() < 2047) {
                // OIDC floor: refuse <2048-bit RSA (and any sign-games via
                // the positive-constructor above)
                throw OidcException.refused();
            }
            if (exponent.bitLength() > 32) {
                throw OidcException.refused();
            }
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (OidcException ex) {
            throw ex;
        } catch (Exception ex) {
            throw OidcException.refused();
        }
    }

    private static String jcaAlgorithm(String alg) {
        return switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            default -> "RSASSA-PSS"; // PS* — params set by caller
        };
    }

    private static String psHash(String alg) {
        return switch (alg) {
            case "PS384" -> "SHA-384";
            case "PS512" -> "SHA-512";
            default -> "SHA-256";
        };
    }

    private static int psSaltBytes(String alg) {
        return switch (alg) {
            case "PS384" -> 48;
            case "PS512" -> 64;
            default -> 32;
        };
    }

    /**
     * at_hash check (OIDC Core §3.1.3.6): leftmost half of the SHA-256/384/512
     * of the access token, base64url, compared to the claim. Constant-time.
     */
    static void verifyAtHash(JsonNode payload, String alg, String accessToken) {
        String atHash = payload.path("at_hash").asString(null);
        if (atHash == null) {
            return; // no access token bound: nothing to check (nonce/aud carry the proof)
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw OidcException.refused();
        }
        byte[] hash = switch (alg) {
            case "RS384", "PS384" -> sha("SHA-384", accessToken);
            case "RS512", "PS512" -> sha("SHA-512", accessToken);
            default -> sha("SHA-256", accessToken);
        };
        byte[] left = new byte[hash.length / 2];
        System.arraycopy(hash, 0, left, 0, left.length);
        if (!OidcCodec.equalsConstantTime(atHash, OidcCodec.base64UrlEncode(left))) {
            throw OidcException.refused();
        }
    }

    private static byte[] sha(String name, String value) {
        try {
            return MessageDigest.getInstance(name).digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw OidcException.refused();
        }
    }

    /** Iterate all JWKS keys (helper for the discovery/JWKS loader). */
    static java.util.Iterator<JsonNode> keys(JsonNode jwks) {
        JsonNode arr = jwks == null ? null : jwks.path("keys");
        return arr != null && arr.isArray()
                ? arr.valueStream().iterator() : java.util.Collections.emptyIterator();
    }
}
