package com.keplura.server.search;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One embedded chunk of a document (M3d) — the unit the RAG search ranks and
 * cites. The tenant model is unchanged from M3c: this row carries NO new ACL
 * surface; every search read reaches it ONLY through the kb pin (denormalised
 * for the tenant-gate-first query shape) plus the full
 * membership→space→kb chain the search service proves first.
 *
 * <p>{@code embedding} is the canonical JSON-style text encoding (see
 * {@link EmbeddingCodec}) — portable truth. On PostgreSQL with pgvector the
 * native twin table {@code kb_chunk_embedding} mirrors it for real KNN
 * (docs/adr/0007); Hibernate never maps that table (its {@code vector} type
 * is not portable), the {@link PgVectorEmbeddingStore} reads/writes it via
 * plain JDBC.
 */
@Entity
@Table(name = "kb_chunk",
        // mirrors the V4 unique key so dev/test schemas (Hibernate-owned)
        // enforce the idempotent re-index anchor exactly like prod
        uniqueConstraints = @UniqueConstraint(name = "kb_chunk_document_id_chunk_idx_key",
                columnNames = {"document_id", "chunk_idx"}),
        indexes = @Index(name = "kb_chunk_knowledge_base_id_idx",
                columnList = "knowledge_base_id"))
public class Chunk {

    /** DB hard bound of V4; the chunker's configured window must stay below it. */
    public static final int MAX_TEXT_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    /** Denormalised tenant pin (kb) — set from the document row at index time. */
    @Column(name = "knowledge_base_id", nullable = false, updatable = false)
    private Long kbId;

    @Column(name = "chunk_idx", nullable = false, updatable = false)
    private int chunkIdx;

    @Column(name = "chunk_text", nullable = false, length = MAX_TEXT_LENGTH, updatable = false)
    private String text;

    @Column(nullable = false, length = 65536, updatable = false)
    private String embedding;

    @Column(name = "page_number", updatable = false)
    private Integer pageNumber;
    @Column(name = "section_title", length = 512, updatable = false)
    private String sectionTitle;
    @Column(name = "start_offset", updatable = false)
    private Integer startOffset;
    @Column(name = "end_offset", updatable = false)
    private Integer endOffset;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Chunk() {
        // JPA proxy constructor
    }

    public static Chunk of(Long documentId, Long kbId, int chunkIdx, String text, String embedding) {
        Chunk c = new Chunk();
        c.documentId = documentId;
        c.kbId = kbId;
        c.chunkIdx = chunkIdx;
        c.text = text;
        c.embedding = embedding;
        return c;
    }

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Chunk sourceLocation(ChunkText source) {
        pageNumber = source.pageNumber(); sectionTitle = source.sectionTitle();
        startOffset = source.startOffset(); endOffset = source.endOffset();
        return this;
    }
    public Integer getPageNumber() { return pageNumber; }
    public String getSectionTitle() { return sectionTitle; }
    public Integer getStartOffset() { return startOffset; }
    public Integer getEndOffset() { return endOffset; }

    public Long getDocumentId() {
        return documentId;
    }

    public Long getKbId() {
        return kbId;
    }

    public int getChunkIdx() {
        return chunkIdx;
    }

    public String getText() {
        return text;
    }

    public String getEmbedding() {
        return embedding;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
