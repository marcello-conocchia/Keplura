package com.keplura.server.search;

import java.util.List;

/**
 * Embedding backend SPI (M3d). One text in, one fixed-dimension unit-ish
 * vector out; {@link #embedAll} exists so a gateway-backed implementation can
 * batch a document's chunks into ONE request. {@link #dimension()} must match
 * {@code keplura.search.embedding-dim} and the pgvector column — the store
 * probe refuses a mismatch at startup instead of writing garbage.
 *
 * <p>Two implementations: {@link LocalHashEmbeddingService} (deterministic
 * hash features — dev/CI only, NOT semantic) and
 * {@link GatewayEmbeddingService} (OpenAI-compatible /v1/embeddings client for
 * a real model). The active one is chosen by {@code
 * keplura.search.embedding-backend}; a production deployment must set the
 * gateway backend via ENV.
 */
public interface EmbeddingService {

    float[] embed(String text);

    List<float[]> embedAll(List<String> texts);

    /** Identifier written to logs/config (e.g. {@code local-hash-64}). */
    String modelTag();

    int dimension();
}
