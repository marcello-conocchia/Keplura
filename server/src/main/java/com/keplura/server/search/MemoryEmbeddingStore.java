package com.keplura.server.search;


import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-JVM vector store (M3d) — the H2/dev/CI path and the fallback when the
 * DataSource has no usable pgvector (see {@link SearchStoreConfiguration}
 * for the probe). Rationale (docs/adr/0007): pgvector is PostgreSQL-only,
 * the CI matrices must stay hermetic without a database service, so the
 * portable {@code kb_chunk.embedding} text column plus this scorer give the
 * SAME search semantics (identical cosine scores) on any database.
 *
 * <p>The tenant gate is NOT here: {@link ChunkRepository#findCandidateChunks}
 * enforces kb + space + org + membership (four pins) in SQL BEFORE a single
 * vector reaches Java — exactly mirrored, one-for-one, by the pgvector
 * statement in {@link PgVectorEmbeddingStore#search}; this class only ranks
 * what the query already whitelisted. Complexity is a
 * full scan over the caller-visible chunks — fine at MVP scale, documented
 * non-goal (no ANN indexes on the fallback path).
 */
@Component
public class MemoryEmbeddingStore implements SearchStore {

    private final ChunkRepository chunkRepository;

    public MemoryEmbeddingStore(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    public void afterChunkPersisted(Chunk chunk, float[] embedding) {
        // portable path: kb_chunk.embedding (written by the indexing
        // transaction via JPA) IS the store — nothing native to mirror
    }

    @Override
    public List<SearchHit> search(float[] queryEmbedding, Long kbId, Long spaceId,
                                  Long orgId, Long userId, int topK) {
        List<ChunkRepository.Candidate> candidates = chunkRepository.findCandidateChunks(kbId, spaceId, orgId, userId);
        record Scored(ChunkRepository.Candidate candidate, double score) {
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (ChunkRepository.Candidate candidate : candidates) {
            Chunk c = candidate.getChunk();
            scored.add(new Scored(candidate, Similarity.cosine(queryEmbedding,
                    EmbeddingCodec.decode(c.getEmbedding()))));
        }
        // score desc, then stable (documentId, chunkIdx) — deterministic ties
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(s -> s.candidate().getChunk().getDocumentId())
                .thenComparing(s -> s.candidate().getChunk().getChunkIdx()));
        List<Scored> top = scored.subList(0, Math.min(topK, scored.size()));

        return top.stream()
                .map(s -> new SearchHit(s.candidate().getChunk().getDocumentId(),
                        s.candidate().getFilename(),
                        s.candidate().getChunk().getChunkIdx(), s.candidate().getChunk().getText(), s.score(),
                        s.candidate().getChunk().getPageNumber(), s.candidate().getChunk().getSectionTitle(),
                        s.candidate().getChunk().getStartOffset(), s.candidate().getChunk().getEndOffset(), s.candidate().getSourceUpdatedAt()))
                .toList();
    }

    @Override
    public String backendName() {
        return "memory";
    }
}
