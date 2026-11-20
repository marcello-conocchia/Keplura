package com.keplura.server.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical text encoding of a float vector (M3d): {@code [f1,f2,...]} using
 * {@link Float#toString} per component — no spaces, exactly the pgvector text
 * literal format, so the SAME string serves as (a) the portable
 * {@code kb_chunk.embedding} value on every database and (b) the {@code
 * ::vector} cast input on PostgreSQL. {@code Float.toString} round-trips
 * bit-exactly through {@link Float#parseFloat}, so the in-JVM fallback
 * re-derives the identical vector it stored.
 *
 * <p>Rejects non-finite components: pgvector refuses NaN/Inf vectors, so the
 * codec enforces the same rule on every backend (fail at write time, never
 * store a vector the native side would reject later).
 */
public final class EmbeddingCodec {

    private EmbeddingCodec() {
    }

    public static String encode(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            float v = vector[i];
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("embedding components must be finite");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.append(']').toString();
    }

    public static float[] decode(String encoded) {
        String s = encoded.trim();
        if (s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']') {
            throw new IllegalArgumentException("embedding is not in canonical [..] form");
        }
        String body = s.substring(1, s.length() - 1);
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        List<Float> vals = new ArrayList<>(parts.length);
        for (String p : parts) {
            vals.add(Float.parseFloat(p.trim()));
        }
        float[] out = new float[vals.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = vals.get(i);
        }
        return out;
    }
}
