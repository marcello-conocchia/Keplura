package com.keplura.server.knowledge;

import com.keplura.server.org.MembershipRepository;
import com.keplura.server.space.SpaceRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/**
 * Document upload / list / download use-cases (M3c). The kb API chain one
 * level deeper: every operation proves, in order — org membership → space
 * pinned to that org → kb pinned to that space — BEFORE any file or metadata
 * access, and every denial collapses into {@link DocumentNotFoundException}
 * (uniform 404 across all three levels; the same indistinguishability rule
 * as {@link com.keplura.server.space.SpaceService}).
 *
 * <p>Upload order is deliberate: authorization first, then the
 * type/size/filename policy (400/413 can therefore only be reached by someone
 * already entitled to probe the container), and only then the bytes are
 * streamed to disk. The DB row is written LAST, from the values storage
 * computed — so no metadata can ever reference bytes that failed to land.
 */
@Service
public class DocumentService {

    /** 404 — any level of "absent or not yours", indistinguishable. */
    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException() {
            super("document not found for this caller");
        }
    }

    /** 403 — caller sees the document but is neither its uploader nor an org OWNER. */
    public static class DocumentForbiddenException extends RuntimeException {
        public DocumentForbiddenException() {
            super("only the uploader or an org owner may change this document");
        }
    }

    /** The resolved document a download serves (row metadata + verified file). */
    public record Downloadable(String filename, String contentType, long sizeBytes,
                               java.nio.file.Path file,
                               com.keplura.server.search.DocumentIndexingService.IndexStatus indexStatus, java.time.Instant sourceUpdatedAt) {
    }

    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final SpaceRepository spaceRepository;
    private final MembershipRepository membershipRepository;
    private final DocumentUploadPolicy policy;
    private final DocumentStorage storage;
    private final com.keplura.server.search.DocumentIndexingService indexingService;
    private org.springframework.beans.factory.ObjectProvider<com.keplura.server.search.SearchStore> searchStore;
    private com.keplura.server.search.ChunkRepository chunkRepository;
    private DocumentIndexingQueue indexingQueue;
    private com.keplura.server.access.DocumentAclRepository aclRepository;

    @org.springframework.beans.factory.annotation.Autowired
    void setAclRepository(com.keplura.server.access.DocumentAclRepository aclRepository) {
        this.aclRepository = aclRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setIndexingQueue(DocumentIndexingQueue indexingQueue) {
        this.indexingQueue = indexingQueue;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setDeleteCollaborators(
            org.springframework.beans.factory.ObjectProvider<com.keplura.server.search.SearchStore> searchStore,
            com.keplura.server.search.ChunkRepository chunkRepository) {
        this.searchStore = searchStore;
        this.chunkRepository = chunkRepository;
    }

    public DocumentService(DocumentRepository documentRepository,
                           KnowledgeBaseRepository kbRepository,
                           SpaceRepository spaceRepository,
                           MembershipRepository membershipRepository,
                           DocumentUploadPolicy policy,
                           DocumentStorage storage,
                           com.keplura.server.search.DocumentIndexingService indexingService) {
        this.documentRepository = documentRepository;
        this.kbRepository = kbRepository;
        this.spaceRepository = spaceRepository;
        this.membershipRepository = membershipRepository;
        this.policy = policy;
        this.storage = storage;
        this.indexingService = indexingService;
    }

    /**
     * Persists one uploaded document into a kb addressed through its full
     * tenant path. {@code rawSizeBytes} is the declared part size (pre-read
     * guard); actual bytes are re-measured during streaming and the DB row
     * records the REAL stored size. Returns the stored row.
     */
    @Transactional
    public Document upload(Long userId, Long orgId, Long spaceId, Long kbId,
                           String rawFilename, long declaredSizeBytes, InputStream content) {
        requireKb(userId, orgId, spaceId, kbId);

        // policy on the filename/extension BEFORE touching bytes
        String contentType = policy.contentTypeOf(rawFilename);
        String display = DocumentUploadPolicy.sanitizeFilename(rawFilename);
        if (declaredSizeBytes <= 0) {
            throw new DocumentUploadPolicy.InvalidDocumentException("empty documents are not accepted");
        }
        if (declaredSizeBytes > policy.maxBytes()) {
            throw new DocumentUploadPolicy.TooLargeException("document exceeds the upload size limit");
        }

        DocumentStorage.Stored stored = storage.store(orgId, spaceId, kbId, content,
                policy.maxBytes());
        // the stream may lie about its size (chunked); the measured value is final
        if (stored.sizeBytes() == 0) {
            throw new DocumentUploadPolicy.InvalidDocumentException("empty documents are not accepted");
        }
        if (stored.sizeBytes() > policy.maxBytes()) {
            throw new DocumentUploadPolicy.TooLargeException("document exceeds the upload size limit");
        }
        Document row = Document.of(kbId, display, contentType,
                stored.sizeBytes(), stored.sha256(), stored.storedPath(), userId);
        if (indexingQueue != null && indexingQueue.isAsync() && indexingService.isIndexable(display)) {
            // async mode: commit row + file now, the background indexer
            // parses/embeds after commit (status PENDING until then)
            row.markIndexPending();
            Document pending = documentRepository.saveAndFlush(row);
            indexingQueue.enqueueAfterCommit(pending.getId());
            return pending;
        }
        Document saved = documentRepository.saveAndFlush(row);
        // M3d: parseable types are chunked + embedded INSIDE this transaction —
        // a document row and its chunks commit together or not at all, so a
        // committed searchable document can never reference missing chunks.
        indexingService.index(saved, storage.resolveForRead(saved.getStoredPath()));
        return saved;
    }

    /**
     * ADR-0012: übernimmt eine Datei aus einer Datenquelle in eine Wissensbasis.
     * Die Berechtigung ist durch die Konfiguration der Quelle (nur Admins)
     * belegt; Typ- und Größenregeln gelten wie beim Upload. Gibt das Dokument
     * zurück (Indexierung synchron oder per Warteschlange wie beim Upload).
     */
    @Transactional
    public Document ingestFromSource(Long orgId, Long spaceId, Long kbId, String rawFilename, long declaredSize,
                                     InputStream content, Long uploadedBy) {
        String contentType = policy.contentTypeOf(rawFilename);
        String display = DocumentUploadPolicy.sanitizeFilename(rawFilename);
        if (declaredSize <= 0) {
            throw new DocumentUploadPolicy.InvalidDocumentException("empty documents are not accepted");
        }
        if (declaredSize > policy.maxBytes()) {
            throw new DocumentUploadPolicy.TooLargeException("document exceeds the upload size limit");
        }
        DocumentStorage.Stored stored = storage.store(orgId, spaceId, kbId, content, policy.maxBytes());
        if (stored.sizeBytes() == 0 || stored.sizeBytes() > policy.maxBytes()) {
            throw new DocumentUploadPolicy.InvalidDocumentException("document size not accepted");
        }
        Document row = Document.of(kbId, display, contentType, stored.sizeBytes(), stored.sha256(),
                stored.storedPath(), uploadedBy);
        if (indexingQueue != null && indexingQueue.isAsync() && indexingService.isIndexable(display)) {
            row.markIndexPending();
            Document pending = documentRepository.saveAndFlush(row);
            indexingQueue.enqueueAfterCommit(pending.getId());
            return pending;
        }
        Document saved = documentRepository.saveAndFlush(row);
        indexingService.index(saved, storage.resolveForRead(saved.getStoredPath()));
        return saved;
    }

    /** Internal source-sync comparison; never used for an untrusted API read. */
    @Transactional(readOnly = true)
    public boolean sourceContentMatches(Long documentId, String sha256) {
        return documentRepository.findById(documentId).map(d -> d.getSha256().equals(sha256)).orElse(false);
    }

    /** ADR-0012: entfernt ein Dokument, dessen Quelldatei verschwunden oder geändert ist. */
    @Transactional
    public void removeFromSource(Long documentId) {
        documentRepository.findById(documentId).ifPresent(this::deleteRowAndScheduleFile);
    }

    /** Documents of one kb (upload order) — visible only through the full chain. */
    @Transactional(readOnly = true)
    public List<Document> list(Long userId, Long orgId, Long spaceId, Long kbId) {
        requireKb(userId, orgId, spaceId, kbId);
        return documentRepository.findAllInKbForMember(kbId, spaceId, orgId, userId);
    }

    /**
     * One document for download. The row must pass the kb+space+membership
     * chain (query-level pin), and its storedPath must resolve to a regular
     * file inside the upload dir ({@link DocumentStorage#resolveForRead} —
     * containment re-checked on read). An unreadable file answers the same
     * 404 as an invisible row: the wire never distinguishes "not yours" from
     * "gone", and never streams outside the base dir.
     */
    @Transactional(readOnly = true)
    public Downloadable download(Long userId, Long orgId, Long spaceId, Long kbId, Long documentId) {
        requireKb(userId, orgId, spaceId, kbId);
        Document doc = documentRepository
                .findByIdInKbForMember(documentId, kbId, spaceId, orgId, userId)
                .orElseThrow(DocumentNotFoundException::new);
        try {
            return new Downloadable(doc.getFilename(), doc.getContentType(),
                    doc.getSizeBytes(), storage.resolveForRead(doc.getStoredPath()),
                    indexingService.statusFor(doc), doc.getSourceUpdatedAt());
        } catch (DocumentStorage.UnreadableDocumentException e) {
            throw new DocumentNotFoundException();
        }
    }

    /**
     * Deletes one document: native vectors, chunks and the row in ONE
     * transaction; the stored file is removed only after commit (a rollback
     * never leaves a row pointing at deleted bytes). Allowed for the uploader
     * and for org OWNERs; other members get 403, non-members the uniform 404.
     */
    @Transactional
    public void delete(Long userId, Long orgId, Long spaceId, Long kbId, Long documentId) {
        deleteRowAndScheduleFile(requireChangeable(userId, orgId, spaceId, kbId, documentId));
    }

    /**
     * Re-indexes one document (uploader or org OWNER), e.g. after an
     * embedding-model switch. Async mode: queued (PENDING); sync mode:
     * runs in this request and surfaces parse errors like an upload.
     */
    @Transactional
    public Document reindex(Long userId, Long orgId, Long spaceId, Long kbId, Long documentId) {
        Document doc = requireChangeable(userId, orgId, spaceId, kbId, documentId);
        if (indexingQueue != null && indexingQueue.isAsync()) {
            doc.markIndexPending();
            Document saved = documentRepository.saveAndFlush(doc);
            indexingQueue.enqueueAfterCommit(saved.getId());
            return saved;
        }
        indexingService.reindex(doc, storage.resolveForRead(doc.getStoredPath()));
        return documentRepository.saveAndFlush(doc);
    }

    private Document requireChangeable(Long userId, Long orgId, Long spaceId, Long kbId, Long documentId) {
        requireKb(userId, orgId, spaceId, kbId);
        Document doc = documentRepository
                .findByIdInKbForMember(documentId, kbId, spaceId, orgId, userId)
                .orElseThrow(DocumentNotFoundException::new);
        boolean owner = membershipRepository.findByOrgIdAndUserId(orgId, userId)
                .map(m -> m.getRole() == com.keplura.server.org.MembershipRole.OWNER)
                .orElse(false);
        if (!owner && !userId.equals(doc.getUploadedBy())) {
            throw new DocumentForbiddenException();
        }
        return doc;
    }

    /**
     * Removes a document whose authorization the caller already proved
     * (kb/space delete cascade). Same transaction rules as {@link #delete}.
     */
    @Transactional
    public void deleteAuthorized(Document doc) {
        deleteRowAndScheduleFile(doc);
    }

    private void deleteRowAndScheduleFile(Document doc) {
        searchStore.getObject().afterDocumentDeleted(doc.getId());
        chunkRepository.deleteByDocumentId(doc.getId());
        if (aclRepository != null) {
            aclRepository.deleteByDocumentId(doc.getId()); // ADR-0012: rights die with the document
        }
        documentRepository.delete(doc);
        documentRepository.flush();
        String storedPath = doc.getStoredPath();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            storage.deleteQuietly(storedPath);
                        }
                    });
        } else {
            storage.deleteQuietly(storedPath);
        }
    }

    /** Honest searchability state of a listed document (API projection helper). */
    public com.keplura.server.search.DocumentIndexingService.IndexStatus indexStatusOf(Document doc) {
        return indexingService.statusFor(doc);
    }

    /** Full chain guard: membership (org) → space pinned to org → kb pinned to space. */
    private void requireKb(Long userId, Long orgId, Long spaceId, Long kbId) {
        membershipRepository.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(DocumentNotFoundException::new);
        spaceRepository.findByIdInOrgForMember(spaceId, orgId, userId)
                .orElseThrow(DocumentNotFoundException::new);
        kbRepository.findByIdInSpaceForMember(kbId, spaceId, orgId, userId)
                .orElseThrow(DocumentNotFoundException::new);
    }
}
