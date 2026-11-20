package com.keplura.server.search;

import java.util.List;

/**
 * Vector store SPI for the search read path (M3d, docs/adr/0007). Two
 * implementations are selected at startup by {@code keplura.search.store}
 * plus a live capability probe:
 *
 * <ul>
 *   <li>{@link MemoryEmbeddingStore} — loads the caller-visible chunks
 *       (tenant gate in the JPA query) and scores cosine similarity in the
 *       JVM from the portable {@code kb_chunk.embedding} text column. The
 *       H2/dev/CI path.</li>
 *   <li>{@link PgVectorEmbeddingStore} — native {@code embedding
 *       <=> ?::vector} KNN on the PostgreSQL twin table with the SAME
 *       tenant pins inside the statement. The prod path when the extension
 *       and table are present at the configured dimension.</li>
 * </ul>
 *
 * <p>Chunk ROWS are always persisted portably by
 * {@link DocumentIndexingService} via JPA; {@link #afterChunkPersisted} is
 * the hook a native store uses to mirror the vector into its own structure.
 * Every search call receives the full tenant chain and MUST enforce it in
 * its own statement — a hit list is never post-filtered in Java.
 */
public interface SearchStore {

    /**
     * Called once per persisted chunk, inside the indexing transaction,
     * AFTER all portable rows flushed. Implementations that mirror into a
     * non-transactional structure MUST register a
     * TransactionSynchronization so a rolled-back upload leaves no native
     * rows behind.
     */
    void afterChunkPersisted(Chunk chunk, float[] embedding);

    /**
     * Top-{@code topK} chunks of the kb addressed by the chain, cosine
     * similarity descending, with source attribution per hit.
     */
    List<SearchHit> search(float[] queryEmbedding, Long kbId, Long spaceId,
                           Long orgId, Long userId, int topK);

    /** Query-aware retrieval; PostgreSQL combines German full text and vectors. */
    default List<SearchHit> search(String query, float[] queryEmbedding, Long kbId, Long spaceId,
                                   Long orgId, Long userId, int topK) {
        return search(queryEmbedding, kbId, spaceId, orgId, userId, topK);
    }

    /** {@code pgvector} or {@code memory} — logged at boot, asserted in tests. */
    String backendName();

    /** Removes native mirror rows of a deleted document (portable rows are deleted by the caller). */
    default void afterDocumentDeleted(Long documentId) {
    }
}
