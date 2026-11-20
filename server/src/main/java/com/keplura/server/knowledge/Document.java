package com.keplura.server.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An uploaded document (M3c): the DB row is METADATA ONLY — the bytes live on
 * disk below the configured upload base dir, referenced through
 * {@link #getStoredPath()}. Embeddings/parsing/chunking are deliberately NOT
 * part of this entity (M3d).
 *
 * <p>Authorization follows the whole chain pattern: no ACL columns; the
 * {@link DocumentRepository} reads pin kb + space + org membership in one
 * query, so a document is reachable ONLY through its own
 * org/space/kb path (uniform 404 upstream, ADR-0004 carry-over).
 *
 * <p>Field trust levels: {@code filename} is the DISPLAY name (sanitised at
 * upload, never used to build a path); {@code storedPath} is 100%
 * server-generated (UUID layout via {@link DocumentStorage}).
 */
@Entity
@Table(name = "kb_document",
        // Mirrors the Flyway V3 index so dev/test schemas (Hibernate-owned)
        // back the "documents of one kb" list read the same way prod does.
        // ddl-auto=validate ignores indexes — this is schema-shape fidelity,
        // not a validation requirement.
        indexes = @Index(name = "kb_document_knowledge_base_id_idx",
                columnList = "knowledge_base_id"))
public class Document {

    /** Display-filename column width; sanitiser clamps BEFORE persisting. */
    public static final int MAX_FILENAME_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning knowledge base. Immutable: documents are never moved. */
    @Column(name = "knowledge_base_id", nullable = false, updatable = false)
    private Long kbId;

    /** Client-supplied display name, sanitised to a single safe component. */
    @Column(nullable = false, length = MAX_FILENAME_LENGTH, updatable = false)
    private String filename;

    /** Server-mapped MIME type of the accepted extension — never the client part header. */
    @Column(name = "content_type", nullable = false, length = 200, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    /** Lowercase hex sha256 over the stored bytes — integrity + dedup anchor (M3d). */
    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;

    /** Server-generated relative path under the upload base dir; never user input. */
    @Column(name = "stored_path", nullable = false, length = 500, updatable = false)
    private String storedPath;

    /** Session user id of the uploader — provenance, not ACL (membership is). */
    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    /**
     * Persisted index marker (MVP-5). NULL for every ordinary row — their
     * status is DERIVED from the type policy + chunk presence (so a
     * derivation can never drift from a stored claim). The only value ever
     * stored is {@link #INDEX_STATUS_NO_TEXT_LAYER}: a fact that is ONLY
     * knowable at parse time (a scanned PDF has no chunk rows to look at,
     * and absence would be indistinguishable from a historical row).
     */
    @Column(name = "index_status", length = 20, updatable = true)
    private String indexStatus;

    /** The one persisted marker (see {@link #indexStatus}) — scanned/empty binary docs. */
    public static final String INDEX_STATUS_NO_TEXT_LAYER = "NO_TEXT_LAYER";
    /** Async indexing: stored, waiting for the background indexer. */
    public static final String INDEX_STATUS_PENDING = "PENDING";
    /** Async indexing: the indexer failed; a re-index may be requested. */
    public static final String INDEX_STATUS_FAILED = "FAILED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Document() {
        // JPA proxy constructor
    }

    /**
     * Creates the fully-formed metadata row; {@link DocumentUploadPolicy} +
     * {@link DocumentStorage} have already sanitised/derived every value. No
     * setters by design — the row mirrors immutable uploaded bytes (M3c has no
     * update or delete; see the Non-Goals in the task spec).
     */
    public static Document of(Long kbId, String filename, String contentType, long sizeBytes,
                              String sha256, String storedPath, Long uploadedBy) {
        return new Document(kbId, filename, contentType, sizeBytes, sha256, storedPath, uploadedBy);
    }

    private Document(Long kbId, String filename, String contentType, long sizeBytes,
                     String sha256, String storedPath, Long uploadedBy) {
        this.kbId = kbId;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storedPath = storedPath;
        this.uploadedBy = uploadedBy;
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

    public Long getKbId() {
        return kbId;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    /** Persisted index marker or NULL (see the {@link #indexStatus} field doc). */
    public String getIndexStatus() {
        return indexStatus;
    }

    /**
     * Stamps the NO_TEXT_LAYER marker during indexing. The row was already
     * flushed by the upload (id assigned) and stays in the SAME transaction,
     * so Hibernate dirty-checks this single-column update at commit — the
     * marker can never exist without the row (and vice versa).
     */
    public void recordNoTextLayer() {
        this.indexStatus = INDEX_STATUS_NO_TEXT_LAYER;
    }

    public void markIndexPending() {
        this.indexStatus = INDEX_STATUS_PENDING;
    }

    public void markIndexFailed() {
        this.indexStatus = INDEX_STATUS_FAILED;
    }

    /** Back to the derived state (chunks present ⇒ INDEXED). */
    public void clearIndexStatus() {
        this.indexStatus = null;
    }

    // Read with the existing authorized document query; never persisted or written back.
    @org.hibernate.annotations.Formula("coalesce((select max(sf.modified_at) from data_source_file sf where sf.document_id = id), created_at)")
    private Instant sourceUpdatedAt;

    public Instant getSourceUpdatedAt() { return sourceUpdatedAt; }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
