package com.keplura.server.search;

/**
 * One RAG-light search result (M3d): the chunk text plus FULL source
 * attribution — which document it came from (id + filename) and where inside
 * it (chunkIdx) — and the cosine similarity score. The wire response is
 * exactly this shape: a caller can always cite what it was shown.
 */
public record SearchHit(Long documentId, String filename, int chunkIdx, String text, double score,
                        Integer pageNumber, String sectionTitle, Integer startOffset, Integer endOffset, java.time.Instant sourceUpdatedAt) {
    public SearchHit(Long documentId, String filename, int chunkIdx, String text, double score,
            Integer pageNumber, String sectionTitle, Integer startOffset, Integer endOffset) {
        this(documentId, filename, chunkIdx, text, score, pageNumber, sectionTitle, startOffset, endOffset, null);
    }
    public SearchHit(Long documentId, String filename, int chunkIdx, String text, double score) {
        this(documentId, filename, chunkIdx, text, score, null, null, null, null);
    }
    public SearchHit withScore(double nextScore) {
        return new SearchHit(documentId, filename, chunkIdx, text, nextScore,
                pageNumber, sectionTitle, startOffset, endOffset, sourceUpdatedAt);
    }
}
