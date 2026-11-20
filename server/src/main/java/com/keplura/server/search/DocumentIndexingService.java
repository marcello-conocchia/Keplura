package com.keplura.server.search;

import com.keplura.server.knowledge.Document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Upload → text → chunks → embeddings (M3d, parsers since MVP-5). Runs
 * INSIDE the upload transaction (called by
 * {@link com.keplura.server.knowledge.DocumentService#upload}
 * after the document row is flushed): a committed document row therefore
 * always has its chunks committed with it, and a failed indexing rolls the
 * WHOLE upload back — no half-indexed corpus, no rows referencing missing
 * bytes, no orphan chunk rows.
 *
 * <h2>Type policy since MVP-5 — the honest decision on pdf/docx</h2>
 * <ul>
 *   <li>{@code txt / md / csv} — parsed as UTF-8 text and indexed
 *       (unchanged; strict decoding, see below).</li>
 *   <li>{@code pdf / docx} — extracted with Apache Tika via
 *       {@link TikaDocumentTextExtractor} (resource-limited, embedded
 *       resources rejected) and fed through the SAME chunker + embedding
 *       pipeline: real search hits with citations. The M3d
 *       {@code PARSER_PENDING} marker is gone — there is no longer a type
 *       that is "stored but knowingly not parsed". Only two outcomes
 *       remain, and both are honest: extraction failure (corrupt,
 *       encrypted, over-limit) rejects the upload with a 400 and rolls
 *       everything back; a document with no extractable text layer (scanned
 *       PDF without OCR — OCR is an explicit Non-Goal) is stored with
 *       {@code NO_TEXT_LAYER} persisted on the row, never a silent success.</li>
 *   <li>Pre-MVP-5 pdf/docx rows (uploaded while PARSER_PENDING was in
 *       force) have no chunk rows; the chunk cross-check below therefore
 *       reports them {@code NOT_INDEXED} — the API keeps telling the truth
 *       about historical data it cannot claim to have parsed.</li>
 * </ul>
 *
 * <p>Text decoding is STRICT UTF-8 (replace-action off): a text document that
 * is not valid UTF-8 fails the upload with a clean 400 rather than storing
 * mojibake chunks — silent corruption in a retrieval corpus is the worst
 * outcome here. CSV/MD get no structural parsing: they are text for MVP
 * retrieval (documented).
 */
@Service
public class DocumentIndexingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexingService.class);

    /** Plain-text extensions decoded + indexed directly. */
    static final java.util.Set<String> INDEXED_EXTENSIONS = java.util.Set.of("txt", "md", "csv");

    /** Document-level index state, exposed on the API (never guessed client-side). */
    public enum IndexStatus {
        INDEXED,           // chunks committed, searchable now
        NO_TEXT_LAYER,     // parsed cleanly but the document has no text (scanned PDF, empty DOCX)
        NOT_INDEXED,       // indexable type with no chunks (historical row, or blank text)
        PENDING,           // async mode: queued for the background indexer
        FAILED             // async mode: indexing failed, re-index possible
    }

    private final DocumentChunker chunker;
    private final EmbeddingService embeddings;
    private final ChunkRepository chunkRepository;
    private final SearchStore store;
    private final TikaDocumentTextExtractor extractor;
    private final long maxDocBytes;

    public DocumentIndexingService(DocumentChunker chunker,
                                   EmbeddingService embeddings,
                                   ChunkRepository chunkRepository,
                                   SearchStore store,
                                   TikaDocumentTextExtractor extractor,
                                   @Value("${keplura.search.max-doc-bytes:20971520}") long maxDocBytes) {
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.chunkRepository = chunkRepository;
        this.store = store;
        this.extractor = extractor;
        this.maxDocBytes = maxDocBytes;
    }

    /**
     * Indexes one freshly stored document: text types are decoded, pdf/docx
     * are Tika-extracted — both then chunked + embedded identically.
     * Returns the number of chunks written (0 for blank-text documents).
     * Throws {@link UnparseableDocumentException} on undecodable text and
     * the extractor's failure taxonomy for binary types — the upload
     * transaction rolls back and the API answers 400 (or 503 when the
     * parser pool is saturated).
     */
    @Transactional
    public int index(Document document, Path storedFile) {
        String ext = extensionOf(document.getFilename());
        boolean binary = TikaDocumentTextExtractor.PARSED_EXTENSIONS.contains(ext);
        if (!binary && !INDEXED_EXTENSIONS.contains(ext)) {
            log.debug("document not indexed (extension .{}) — status {}", ext, statusForExtension(ext));
            return 0;
        }
        if (document.getSizeBytes() > maxDocBytes) {
            // defense in depth: the upload cap already guarantees this
            throw new DocumentUploadPolicyGap("document exceeds the indexing cap");
        }
        String text;
        if (binary) {
            TikaDocumentTextExtractor.ExtractedText extracted;
            try {
                extracted = extractor.extract(document, storedFile);
            } catch (TikaDocumentTextExtractor.BlankExtractedTextException e) {
                // honest middle state: bytes are a valid pdf/docx, they just
                // carry no text layer (a scan). Stored + marked, upload 201s,
                // NOTHING is searchable and the API says so per row.
                document.recordNoTextLayer();
                return 0;
            }
            text = ("pdf".equals(ext) || "pptx".equals(ext))
                    ? java.util.Arrays.stream(extracted.text().split("\f", -1))
                        .map(DocumentIndexingService::normalizeExtracted)
                        .collect(java.util.stream.Collectors.joining("\f"))
                    : normalizeExtracted(extracted.text());
        } else {
            text = decodeUtf8Strict(storedFile);
        }
        List<ChunkText> chunks = chunker.chunk(text, "pdf".equals(ext) || "pptx".equals(ext));
        if (chunks.isEmpty()) {
            return 0; // blank document: stored as metadata, nothing to rank
        }
        List<float[]> vectors = embeddings.embedAll(chunks.stream().map(ChunkText::text).toList());
        List<Chunk> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkText c = chunks.get(i);
            float[] v = vectors.get(i);
            if (v.length != embeddings.dimension()) {
                throw new IllegalStateException("embedding dimension drift: got " + v.length
                        + ", expected " + embeddings.dimension());
            }
            rows.add(Chunk.of(document.getId(), document.getKbId(), c.chunkIdx(),
                    c.text(), EmbeddingCodec.encode(v)).sourceLocation(c));
        }
        // saveAll + explicit flush: chunk ids must exist before native stores mirror them
        List<Chunk> saved = chunkRepository.saveAllAndFlush(rows);
        for (int i = 0; i < saved.size(); i++) {
            store.afterChunkPersisted(saved.get(i), vectors.get(i));
        }
        log.debug("indexed document {} into {} chunks (model {})", document.getId(),
                saved.size(), embeddings.modelTag());
        return saved.size();
    }

    /**
     * Re-index: drops existing chunks + native vectors of the document, then
     * indexes again (same transaction). Used by the async worker and the
     * re-index endpoint (e.g. after an embedding-model switch).
     */
    @Transactional
    public int reindex(Document document, Path storedFile) {
        store.afterDocumentDeleted(document.getId());
        chunkRepository.deleteByDocumentId(document.getId());
        chunkRepository.flush();
        document.clearIndexStatus();
        return index(document, storedFile);
    }

    /**
     * TRUE index state of one stored document: the persisted NO_TEXT_LAYER
     * marker wins (only the parse knows it), otherwise what the type policy
     * says, cross-checked against whether chunks actually exist. Pre-MVP5
     * uploads of indexable types — and pre-MVP-5 pdf/docx rows without
     * chunks — therefore report NOT_INDEXED; the API never claims
     * searchability it cannot back with rows.
     */
    public IndexStatus statusFor(Document document) {
        if (Document.INDEX_STATUS_NO_TEXT_LAYER.equals(document.getIndexStatus())) {
            return IndexStatus.NO_TEXT_LAYER;
        }
        if (Document.INDEX_STATUS_PENDING.equals(document.getIndexStatus())) {
            return IndexStatus.PENDING;
        }
        if (Document.INDEX_STATUS_FAILED.equals(document.getIndexStatus())) {
            return IndexStatus.FAILED;
        }
        IndexStatus derived = statusForFilename(document.getFilename());
        if (derived == IndexStatus.INDEXED && !chunkRepository.existsByDocumentId(document.getId())) {
            return IndexStatus.NOT_INDEXED;
        }
        return derived;
    }

    /** True when the type policy would parse + embed this filename. */
    public boolean isIndexable(String filename) {
        String ext = extensionOf(filename);
        return INDEXED_EXTENSIONS.contains(ext) || TikaDocumentTextExtractor.PARSED_EXTENSIONS.contains(ext);
    }

    /** Status a document/list/search response should report for a filename. */
    public static IndexStatus statusForFilename(String filename) {
        return statusForExtension(extensionOf(filename));
    }

    private static IndexStatus statusForExtension(String ext) {
        return INDEXED_EXTENSIONS.contains(ext) || TikaDocumentTextExtractor.PARSED_EXTENSIONS.contains(ext)
                ? IndexStatus.INDEXED
                : IndexStatus.NOT_INDEXED;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Extraction output is normalized, never restructured: CR removed,
     * form-feed (PDFBox page separator) → newline, and runs of blank lines
     * collapsed to one. Whitespace-only runs inside a line stay untouched —
     * the chunker window is character-exact and the citation must quote what
     * the document says, not what a formatter wished it said.
     */
    static String normalizeExtracted(String text) {
        String s = text.replace("\r", "").replace('\f', '\n');
        // collapse 3+ consecutive newlines (blank-line runs) to one blank line
        StringBuilder out = new StringBuilder(s.length());
        int nl = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                nl++;
                if (nl <= 2) {
                    out.append(c);
                }
            } else {
                nl = 0;
                out.append(c);
            }
        }
        return out.toString().strip();
    }

    /** 400-class: bytes stored but NOT decodable/parseable as their claimed type. */
    public static class UnparseableDocumentException extends RuntimeException {
        public UnparseableDocumentException(String message) {
            super(message);
        }
    }

    /** Should be unreachable via the API (upload cap already gates); defensive. */
    public static class DocumentUploadPolicyGap extends RuntimeException {
        public DocumentUploadPolicyGap(String message) {
            super(message);
        }
    }

    private static String decodeUtf8Strict(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UnparseableDocumentException("stored file vanished during indexing");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new UnparseableDocumentException("text document is not valid UTF-8");
        }
    }
}
