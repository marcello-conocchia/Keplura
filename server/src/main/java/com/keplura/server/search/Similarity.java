package com.keplura.server.search;

/**
 * Cosine similarity — the one scoring function both search backends share
 * (pgvector's {@code <=>} operator is cosine distance; the score reported by
 * either backend is 1 - distance, so {@code MemoryEmbeddingStore} and
 * {@code PgVectorEmbeddingStore} return the SAME numbers for the same
 * vectors, which is what makes the H2 test path a faithful stand-in).
 */
public final class Similarity {

    private Similarity() {
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vector dimensions differ: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
