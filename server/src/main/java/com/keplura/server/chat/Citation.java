package com.keplura.server.chat;

/**
 * One citation attached to an assistant answer (M4b): a KB chunk that
 * grounded the turn, carried with FULL source attribution — the exact
 * fields the M3d search API already exposes per hit (documentId, filename,
 * chunkIdx, cosine score). The chunk text is deliberately NOT part of the
 * citation: it lived in the prompt, the user's answer refers to it, and the
 * transcript stays slim. Wire shape mirrors {@code SearchHit} minus text.
 */
public record Citation(Long documentId, String filename, int chunkIdx, double score,
                       Integer pageNumber, String sectionTitle, Integer startOffset, Integer endOffset, java.time.Instant sourceUpdatedAt) {
    public Citation(Long documentId, String filename, int chunkIdx, double score,
            Integer pageNumber, String sectionTitle, Integer startOffset, Integer endOffset) {
        this(documentId, filename, chunkIdx, score, pageNumber, sectionTitle, startOffset, endOffset, null);
    }
    public Citation(Long documentId, String filename, int chunkIdx, double score) {
        this(documentId, filename, chunkIdx, score, null, null, null, null);
    }
}
