package com.keplura.server.search;

/**
 * One embedded chunk of a document (M3d): the unit the RAG search path ranks
 * and cites. {@code chunkIdx} is the 0-based position within the document —
 * it travels through the API response so a caller can always trace a hit back
 * to its exact source location (documentId + chunkIdx + filename).
 */
public record ChunkText(int chunkIdx, String text, Integer pageNumber, String sectionTitle,
                        Integer startOffset, Integer endOffset) {
    public ChunkText(int chunkIdx, String text) { this(chunkIdx, text, null, null, null, null); }
}
