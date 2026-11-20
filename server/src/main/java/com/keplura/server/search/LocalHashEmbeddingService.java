package com.keplura.server.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>TEST / DEV IMPLEMENTATION — NOT A LANGUAGE MODEL.</b>
 *
 * <p>Deterministic feature-hashing embedding (M3d): tokenises on
 * non-alphanumerics, lowercases, hashes each token (SHA-256 for stability
 * across JVMs — String.hashCode is explicitly NOT cross-process stable) into
 * {@code dim} signed buckets, applies IDF-free L2 normalisation. The result is
 * a bag-of-words projection: lexical overlap → high cosine similarity. It is
 * NOT semantic (paraphrases score low), it has NO pretrained knowledge, and
 * it must never be presented as a model — production retrieval requires the
 * gateway backend ({@code keplura.search.embedding-backend=gateway}, see
 * {@link GatewayEmbeddingService}).
 *
 * <p>Why it exists: CI and the H2 test path need an embedding that (1) never
 * calls the network, (2) is bit-reproducible so stored vectors can be
 * asserted exactly (EmbeddingDeterminism tests), and (3) still makes the RAG
 * round-trip (index → search) exercise REAL ranking logic end to end. The
 * interface boundary it sits behind is the same one the prod model uses, so
 * swapping is config-only.
 *
 * <p>Active when {@code keplura.search.embedding-backend} is {@code local}
 * (the default).
 */
@Service
@ConditionalOnProperty(name = "keplura.search.embedding-backend",
        havingValue = "local", matchIfMissing = true)
public class LocalHashEmbeddingService implements EmbeddingService {

    private static final int DEFAULT_DIM = 64;

    private final int dimension;

    public LocalHashEmbeddingService(
            @org.springframework.beans.factory.annotation.Value("${keplura.search.embedding-dim:64}") int dimension) {
        if (dimension < 8) {
            throw new IllegalArgumentException("embedding dimension must be >= 8");
        }
        this.dimension = dimension;
    }

    @Override
    public String modelTag() {
        // keeps the tag stable even if the dim property differs from 64
        return "local-hash-" + dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dimension == 0 ? DEFAULT_DIM : dimension];
        if (text != null) {
            for (String token : tokenize(text)) {
                int bucket = bucketOf(token);
                // sign from a second bit slice: collisions cancel, not accumulate
                float sign = ((bucket & 1) == 0) ? 1f : -1f;
                v[Math.floorMod(bucket >>> 1, v.length)] += sign;
            }
        }
        double norm = 0;
        for (float f : v) {
            norm += (double) f * f;
        }
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / Math.sqrt(norm));
            }
        }
        return v;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }

    /** Lowercase alphanumeric word tokens (unicode letters/digits). */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                cur.append(Character.toLowerCase(c));
            } else if (cur.length() > 0) {
                tokens.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }
        return tokens;
    }

    /** Stable cross-JVM bucket: first 8 bytes of SHA-256(token) as a positive int. */
    private int bucketOf(String token) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return (int) (((h[0] & 0x7fL) << 24) | ((h[1] & 0xffL) << 16)
                    | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }
}
