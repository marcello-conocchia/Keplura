package com.keplura.server.search;

import com.keplura.server.knowledge.Document;

import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.renderer.PageRangeRequest;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Apache Tika text extraction for binary document types (MVP-5): pdf via
 * PDFBox, docx/xlsx/pptx via POI-OOXML. Replaces the M3d {@code PARSER_PENDING}
 * policy — extracted text feeds the SAME chunker + embedding pipeline as
 * txt/md/csv, so a PDF or DOCX becomes a real search hit with citations.
 *
 * <h2>Resource limits (the anti-DoS contract — every one is a config knob)</h2>
 * <ul>
 *   <li>{@code keplura.parser.extracted-chars} (default 2M) — hard output
 *       cap enforced DURING parsing by Tika's {@link BodyContentHandler}
 *       write limit: a content-stream bomb stops at the cap and the parse
 *       aborts. Hitting the cap is an honest REJECT
 *       ({@link TruncatedExtractionException}) — never a silently truncated
 *       "successful" index.</li>
 *   <li>{@code keplura.parser.pdf-max-pages} (default 500) — PDFs are
 *       pre-counted structurally (PDFBox {@code Loader}, no text extraction)
 *       and rejected above the cap BEFORE the expensive parse; the parse
 *       additionally carries a parser-side {@link PageRangeRequest} window
 *       as defence in depth against a lying count.</li>
 *   <li>{@code keplura.parser.docx-max-pages} (default 200) — DOCX page
 *       count comes from the OPC app-properties when the producer wrote
 *       them; a missing count never vetoes (the char/entry caps still bound
 *       the document).</li>
 *   <li>{@code keplura.parser.max-entry-count} (default 5000) and a 0.01
 *       minimum inflate ratio — the zip-bomb gates for the DOCX OPC package,
 *       applied through POI's static {@code ZipSecureFile} guard immediately
 *       AFTER {@code new OOXMLParser()} on every parse (they are JVM-global
 *       settings, and the parser's static initializer overwrites them with
 *       hostile defaults at class-init — see {@link #applyZipBombGates} for
 *       why the order is load-bearing).</li>
 *   <li>{@code keplura.parser.timeout-seconds} (default 60) on a bounded
 *       pool of {@code keplura.parser.max-concurrency} (default 2) worker
 *       threads — see the honest abandonment caveat in {@link #runBounded}.</li>
 * </ul>
 *
 * <h2>Embedded resources are never followed</h2>
 * A reject-all {@link EmbeddedDocumentExtractor} is injected into every
 * parse: a docx wrapping a zip wrapping a thousand docs (or a pdf with an
 * attached payload) indexes ONLY the container's own text — the recursion
 * surface is exactly one document deep.
 *
 * <h2>No network, no external processes</h2>
 * Both parsers are pure-Java in-process readers. {@code NO_OCR} is set
 * explicitly on the PDF config so PDFBox can never try to shell out to
 * tesseract (OCR is a Non-Goal), inline images are not extracted or
 * rendered, and interactive PDF content (AcroForm, annotations, bookmarks,
 * XFA) is switched OFF — form/action payloads are attack surface with zero
 * retrieval value here.
 *
 * <h2>Encrypted PDFs are refused, not cracked</h2>
 * Tika surfaces protected documents as {@link EncryptedDocumentException};
 * it maps to {@link UnextractableDocumentException} (upload 400). Password
 * entry does not exist on this API — a protected PDF is an unreadable one.
 */
@Component
public class TikaDocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentTextExtractor.class);

    /** Binary extensions this extractor serves (= the parser-integration set). */
    public static final java.util.Set<String> PARSED_EXTENSIONS = java.util.Set.of("pdf", "docx", "xlsx", "pptx");

    private final int maxExtractedChars;
    private final int maxPdfPages;
    private final int maxEntryCount;
    private final int maxDocxPages;
    private final int timeoutSeconds;
    private final ThreadPoolExecutor parserPool;

    public TikaDocumentTextExtractor(
            @Value("${keplura.parser.extracted-chars:2000000}") int maxExtractedChars,
            @Value("${keplura.parser.pdf-max-pages:500}") int maxPdfPages,
            @Value("${keplura.parser.max-entry-count:5000}") int maxEntryCount,
            @Value("${keplura.parser.docx-max-pages:200}") int maxDocxPages,
            @Value("${keplura.parser.timeout-seconds:60}") int timeoutSeconds,
            @Value("${keplura.parser.max-concurrency:2}") int maxConcurrency) {
        if (maxExtractedChars <= 0 || maxPdfPages <= 0 || maxEntryCount <= 0
                || maxDocxPages <= 0 || timeoutSeconds <= 0 || maxConcurrency <= 0) {
            throw new IllegalArgumentException("all keplura.parser.* limits must be > 0");
        }
        this.maxExtractedChars = maxExtractedChars;
        this.maxPdfPages = maxPdfPages;
        this.maxEntryCount = maxEntryCount;
        this.maxDocxPages = maxDocxPages;
        this.timeoutSeconds = timeoutSeconds;
        // Bounded, fail-fast pool: parser threads are CPU-heavy, a queue
        // would only hide latency — at capacity a further parse 400/503s
        // immediately (see ExtractionBusyException) instead of piling up.
        this.parserPool = new ThreadPoolExecutor(maxConcurrency, maxConcurrency,
                0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "tika-parser");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdownPool() {
        parserPool.shutdownNow();
    }

    /**
     * Extract the plain text of one stored PDF or OOXML document. Runs the parse on the
     * bounded pool with a hard wall-clock timeout.
     *
     * @throws UnextractableDocumentException bytes are not a valid document
     *         of the claimed type (corrupt, encrypted, wrong magic) — the
     *         upload 400s and rolls back.
     * @throws TruncatedExtractionException an extraction limit was hit
     *         (chars/pages/timeout) — upload 400s; indexing a truncated cut
     *         of a bomb is NOT an honest option.
     * @throws BlankExtractedTextException the document parses cleanly but
     *         carries no text layer at all (scanned PDF, empty DOCX) — the
     *         indexing service stores it as {@code NO_TEXT_LAYER} instead of
     *         claiming a fake INDEXED.
     */
    public ExtractedText extract(Document document, Path storedFile) {
        String ext = extensionOf(document.getFilename());
        if (!PARSED_EXTENSIONS.contains(ext)) {
            throw new UnextractableDocumentException("unsupported document type");
        }
        Parsed parsed = runBounded(document.getId(), () -> parse(ext, storedFile));
        int ocrPages = 0;
        if ("pdf".equals(ext)) {
            OcrMerge merged = withOcr(parsed, storedFile, Integer.MAX_VALUE, Integer.MAX_VALUE);
            parsed = merged.parsed();
            ocrPages = merged.ocrPages();
        }
        if (parsed.text().isBlank()) {
            throw new BlankExtractedTextException(
                    "document contains no extractable text layer");
        }
        return new ExtractedText(parsed.text(), parsed.pages(), ocrPages);
    }

    /**
     * Chat-Anhang: dieselben Grenzen (Pool, Zeitlimit, Zip-Bomben-Gates) für eine
     * Datei ohne Dokument-Zeile. PDF/DOCX/XLSX/PPTX.
     */
    public ExtractedText extract(String filename, Path file) {
        String ext = extensionOf(filename);
        if (!PARSED_EXTENSIONS.contains(ext)) {
            throw new UnextractableDocumentException("unsupported attachment type");
        }
        Parsed parsed = runBounded(null, () -> parse(ext, file));
        int ocrPages = 0;
        if ("pdf".equals(ext)) {
            // Chat-Anhang wartet synchron auf die Antwort: OCR nur für die ersten Seiten
            OcrMerge merged = withOcr(parsed, file, ATTACHMENT_OCR_PAGES, ATTACHMENT_OCR_SECONDS);
            parsed = merged.parsed();
            ocrPages = merged.ocrPages();
        }
        if (parsed.text().isBlank()) {
            throw new BlankExtractedTextException("document contains no extractable text layer");
        }
        return new ExtractedText(parsed.text(), parsed.pages(), ocrPages);
    }

    /**
     * Result of one successful extraction; {@code pages} may be -1 = unknown;
     * {@code ocrPages} = Seiten, deren Text per Texterkennung (OCR-01) gewonnen wurde.
     */
    public record ExtractedText(String text, int pages, int ocrPages) {
        public ExtractedText(String text, int pages) {
            this(text, pages, 0);
        }
    }

    // ------------------------------------------------------------------
    // OCR-01: Seiten ohne Textschicht per lokaler Texterkennung ergänzen
    // ------------------------------------------------------------------

    static final int ATTACHMENT_OCR_PAGES = 10;
    /** Chat-Anhang: die Oberfläche wartet 120 s; OCR muss deutlich vorher fertig sein. */
    static final int ATTACHMENT_OCR_SECONDS = 90;
    /** Seitentrenner, den {@link #parse} zwischen PDF-Seiten einfügt (Formfeed). */
    static final String PAGE_BREAK = "\f";
    /** Weniger Buchstaben/Ziffern als das gilt als „Seite ohne Text“ (Scan mit Stempel o. Ä.). */
    static final int MIN_TEXT_CHARS = 8;

    private com.keplura.server.search.ocr.PdfOcr ocr;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOcr(com.keplura.server.search.ocr.PdfOcr ocr) {
        this.ocr = ocr;
    }

    record OcrMerge(Parsed parsed, int ocrPages) {
    }

    /**
     * Ersetzt leere Seiten durch erkannten Text. Die Seitentrennung bleibt erhalten
     * (Seitenumbruch-Zeichen je Seite), damit Zitate weiterhin die echte PDF-Seite nennen.
     * Läuft NACH dem begrenzten Tika-Parse auf dem eigenen OCR-Arbeiter mit eigenen
     * Zeitlimits, damit das 60-s-Parserlimit für Textdokumente unverändert gilt.
     */
    OcrMerge withOcr(Parsed parsed, Path pdf, int limit, int budgetSeconds) {
        if (ocr == null || !ocr.available()) return new OcrMerge(parsed, 0);
        String[] segs = parsed.text().split(PAGE_BREAK, -1);
        int n = parsed.pages() > 0 ? parsed.pages() : segs.length;
        if (segs.length != n) {
            if (!parsed.text().isBlank()) return new OcrMerge(parsed, 0); // Seiten nicht sicher zuordenbar
            segs = new String[n];
            java.util.Arrays.fill(segs, "");
        }
        java.util.List<Integer> blank = new java.util.ArrayList<>();
        for (int i = 0; i < segs.length; i++) {
            if (visibleChars(segs[i]) < MIN_TEXT_CHARS) blank.add(i);
        }
        if (blank.isEmpty()) return new OcrMerge(parsed, 0);
        var result = ocr.recognize(pdf, blank, limit, budgetSeconds);
        long chars = java.util.Arrays.stream(segs).mapToLong(String::length).sum() + Math.max(0, segs.length - 1);
        if (chars > maxExtractedChars) {
            throw new TruncatedExtractionException("document exceeds the extraction character limit");
        }
        int used = 0;
        for (var e : result.pages().entrySet()) {
            if (visibleChars(e.getValue()) > 0) {
                chars += (long) e.getValue().length() - segs[e.getKey()].length();
                if (chars > maxExtractedChars) {
                    throw new TruncatedExtractionException("document exceeds the extraction character limit (including OCR)");
                }
                segs[e.getKey()] = e.getValue();
                used++;
            }
        }
        if (result.skippedPages() > 0 || result.failedPages() > 0) {
            log.info("ocr: {} pages recognised, {} over the page limit, {} failed",
                    used, result.skippedPages(), result.failedPages());
        }
        return new OcrMerge(new Parsed(String.join(PAGE_BREAK, segs), n), used);
    }

    static int visibleChars(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) c++;
        }
        return c;
    }

    // ------------------------------------------------------------------
    // the parse itself (always on a tika-parser pool thread)
    // ------------------------------------------------------------------

    record Parsed(String text, int pages) {
    }

    private Parsed parse(String ext, Path storedFile) throws Exception {
        // The parse works on a temp copy, never the stored original:
        // (1) PDFBox memory-maps path-backed input, so an abandoned
        // (timed-out) worker could keep the LIVE upload file open — a copy
        // makes abandonment harmless and cleanup unconditional; (2) the copy
        // size is bounded by the upload cap, so the extra work is bounded.
        Path tmp = Files.createTempFile("keplura-parse-", "." + ext);
        try {
            Files.copy(storedFile, tmp, StandardCopyOption.REPLACE_EXISTING);
            boolean pdf = "pdf".equals(ext);
            boolean slides = "pptx".equals(ext);
            boolean workbook = "xlsx".equals(ext);
            if (pdf) {
                preCheckPdfPages(tmp); // structural reject BEFORE text extraction
            }
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            context.set(EmbeddedDocumentExtractor.class, REJECT_ALL_EMBEDDED);
            if (pdf) {
                // defence in depth: even if the structural count below were
                // bypassed, the parser never extracts beyond the window
                context.set(PageRangeRequest.class, new PageRangeRequest(1, maxPdfPages));
            }
            if (slides || workbook) {
                var office = new org.apache.tika.parser.microsoft.OfficeParserConfig();
                office.setExtractMacros(false);
                office.setIncludeSlideNotes(true);
                office.setIncludeSlideMasterContent(false);
                // The SAX extractor iterates OPC relationships, which may differ from
                // presentation order. POI's bounded slideshow follows p:sldIdLst.
                office.setUseSAXPptxExtractor(false);
                context.set(org.apache.tika.parser.microsoft.OfficeParserConfig.class, office);
            }
            BodyContentHandler handler = new BodyContentHandler(maxExtractedChars);
            // Keep real PDF page boundaries inside the same bounded parser and
            // preserve tagged headings for structure-aware chunking. DOCX page
            // numbers cannot be inferred reliably from reflowable text.
            org.xml.sax.helpers.XMLFilterImpl structured = new org.xml.sax.helpers.XMLFilterImpl() {
                private boolean firstPage = true;
                private int slide = 0;
                @Override
                public void startElement(String uri, String localName, String qName,
                                         org.xml.sax.Attributes attributes) throws SAXException {
                    if (pdf && "div".equals(localName) && "page".equals(attributes.getValue("class"))) {
                        if (!firstPage) super.characters(new char[]{'\f'}, 0, 1);
                        firstPage = false;
                    }
                    if (slides && "div".equals(localName)
                            && "slide-content".equals(attributes.getValue("class"))) {
                        if (++slide > OfficePackagePolicy.MAX_SLIDES) {
                            throw new TruncatedExtractionException("pptx exceeds the slide limit (200)");
                        }
                        if (slide > 1) super.characters(new char[]{'\f'}, 0, 1);
                        char[] title = ("\n# Folie " + slide + "\n").toCharArray();
                        super.characters(title, 0, title.length);
                    }
                    super.startElement(uri, localName, qName, attributes);
                    if (localName.matches("h[1-6]")) {
                        char[] marker = ("\n" + "#".repeat(localName.charAt(1) - '0') + " ").toCharArray();
                        super.characters(marker, 0, marker.length);
                    }
                }
            };
            structured.setContentHandler(handler);
            long start = System.nanoTime();
            // Load-bearing order (F-1): parserFor() constructs the OOXMLParser
            // — forcing its class-init, which overwrites the ZipSecureFile
            // gate statics with hostile values — and re-applies OUR gate
            // values immediately afterwards. By the time parse() runs below,
            // the last write to those statics is ours, on EVERY parse
            // including the first one in this JVM. Never move this call
            // earlier or apply the gates before it.
            Parser parser = parserFor(ext);
            int officeSections = -1;
            try {
                if (slides || workbook) officeSections = OfficePackagePolicy.check(ext, tmp);
                try (InputStream in = TikaInputStream.get(tmp)) {
                    parser.parse(in, structured, metadata, context);
                }
            } catch (Exception e) {
                // Tika signals its own write limit through a wrapped SAXException;
                // POI's zip-bomb gates surface as an IOException/
                // InvalidFormatException wrapped in a TikaException whose text
                // POI itself emits; everything else here is a malformed or
                // hostile document of an unknown shape.
                if (WriteLimitReachedException.isWriteLimitReached(e)) {
                    throw new TruncatedExtractionException(
                            "document exceeds the extraction character limit");
                }
                if (isPoiZipBomb(e)) {
                    throw new TruncatedExtractionException(
                            "document exceeds the zip-bomb entry/ratio limits");
                }
                throw e;
            }
            // Tika can recover from malformed individual sheets/slides. Such a
            // partial result must never be advertised as successfully indexed.
            if ((slides || workbook) && metadata.getValues(
                    org.apache.tika.metadata.TikaCoreProperties.TIKA_META_EXCEPTION_WARNING).length > 0) {
                throw new UnextractableDocumentException("Office document could not be read completely");
            }
            String text = handler.toString();
            int pages = slides ? officeSections : parsePages(metadata.get(PagedText.N_PAGES));
            int pageLimit = pdf ? maxPdfPages : slides ? OfficePackagePolicy.MAX_SLIDES
                    : workbook ? Integer.MAX_VALUE : maxDocxPages;
            if (pages > pageLimit) {
                throw new TruncatedExtractionException("document exceeds the page limit ("
                        + pages + " pages > " + pageLimit + ")");
            }
            log.debug("extracted {} chars / {} pages from a {} in {} ms", text.length(), pages,
                    ext, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return new Parsed(text, pages);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                tmp.toFile().deleteOnExit();
            }
        }
    }

    /** Structural page pre-count: a page bomb is rejected before any text work. */
    private void preCheckPdfPages(Path tmp) throws IOException {
        try (PDDocument doc = Loader.loadPDF(tmp.toFile())) {
            int pages = doc.getNumberOfPages();
            if (pages > maxPdfPages) {
                throw new TruncatedExtractionException("pdf exceeds the page limit ("
                        + pages + " pages > " + maxPdfPages + ")");
            }
        } catch (IOException e) {
            // unreadable structure — let the real parse produce the classified error
            log.debug("pdf pre-count could not open the document; continuing to parse", e);
        }
    }

    /**
     * Build the parser for one extension. IMPORTANT ordering contract: this
     * is the ONLY place that constructs {@link OOXMLParser}, and it
     * re-applies {@link #applyZipBombGates()} immediately AFTER construction —
     * never before, because the parser's static initializer writes hostile
     * defaults into the ZipSecureFile gate statics at first class-init.
     */
    private Parser parserFor(String ext) {
        if ("pdf".equals(ext)) {
            PDFParser pdf = new PDFParser();
            PDFParserConfig cfg = new PDFParserConfig();
            // NO_OCR inside Tika: the parse stays pure-Java and bounded. OCR-01 runs
            // separately afterwards (PdfOcr, own worker + limits) only for pages
            // without a text layer. No inline image extraction here.
            cfg.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            cfg.setExtractInlineImages(false);
            cfg.setExtractUniqueInlineImagesOnly(false);
            // interactive content = attack surface with zero retrieval value
            cfg.setExtractAcroFormContent(false);
            cfg.setExtractAnnotationText(false);
            cfg.setExtractBookmarksText(false);
            cfg.setIfXFAExtractOnlyXFA(false);
            // cap PDFBox main-heap buffering; larger bodies go via temp file
            cfg.setMaxMainMemoryBytes(64L * 1024 * 1024);
            pdf.setPDFParserConfig(cfg);
            return pdf;
        }
        OOXMLParser ooxml = new OOXMLParser();
        // Constructing the parser is what triggers its class-init, which
        // clobbers the ZipSecureFile statics (cap 10000, inflate ratio -1.0).
        // applyZipBombGates() MUST therefore run AFTER this call — the caller
        // in parse() does exactly that, immediately before parser.parse().
        applyZipBombGates();
        return ooxml;
    }

    /**
     * True if the (possibly wrapped) failure is POI's zip-bomb guard doing
     * its job: the entry-count gate ("potentially malicious ... more internal
     * file entries") or the inflate-ratio/size gates ("Zip bomb detected!").
     * Both literals are emitted by POI 5.x's ZipSecureFile /
     * ZipArchiveThresholdInputStream (verified against the project classpath,
     * poi-ooxml 5.5.1, at runtime) and
     * reach us wrapped by Tika as {@code TikaException("Error creating OOXML
     * extractor", InvalidFormatException/IOException)}. Matching on these is
     * deliberately conservative: a non-match keeps the generic classification,
     * so an unknown parser failure can never be mislabelled as a limit hit.
     */
    static boolean isPoiZipBomb(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("more internal file entries")
                    || msg.contains("Zip bomb detected"))) {
                return true;
            }
            if (t.getCause() == t) {
                break; // self-referential chain guard
            }
        }
        return false;
    }

    /**
     * POI's ZipSecureFile defaults (entry-count cap 1000) were tuned before
     * real-world corporate documents — a legitimate 200-image DOCX trips
     * them. Apply the configured values immediately AFTER {@code new
     * OOXMLParser()} in {@link #parserFor}, never before: Tika 3.3.2's
     * OOXMLParser static initializer writes its OWN hostile values
     * (setMaxFileCount(10000), setMinInflateRatio(-1.0) — verified in the
     * 3.3.2 bytecode, {@code <clinit>} offsets 281-290) into these statics
     * at first class-init, silently clobbering any gate applied earlier in
     * the same call. Constructing the parser forces class-init, so applying
     * right after construction is the only call order where our values are
     * last-writer-win on EVERY parse, including the first one in a process.
     * The settings are static JVM globals; re-applying per-parse (not once)
     * keeps every Spring context self-consistent even when the test suite
     * caches multiple contexts in one JVM. Concurrent contexts writing the
     * same statics is an accepted, documented test-suite-only race:
     * production runs exactly one context.
     */
    void applyZipBombGates() {
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxFileCount(maxEntryCount);
        org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0.01);
    }

    /**
     * Runs {@code work} on the parser pool with a wall-clock timeout.
     *
     * <p><b>Honest caveat (review-relevant):</b> on timeout the worker is
     * cancelled with {@code mayInterruptIfRunning=true} but Java cannot
     * force-kill a thread stuck inside PDFBox/POI — the abandoned parse may
     * keep burning CPU on its own temp copy until it finishes or self-limits.
     * What IS guaranteed: the upload gets its 400/503 answer within
     * {@code timeoutSeconds}, and the JVM never runs more than
     * {@code maxConcurrency} parser threads at once (SynchronousQueue +
     * AbortPolicy — extras are rejected, not queued).
     *
     * <p>Package-private so the timeout/abandonment mapping is unit-testable
     * deterministically (a hung callable) instead of racing real parsing
     * speed.
     */
    Parsed runBounded(Long documentId, Callable<Parsed> work) {
        java.util.concurrent.Future<Parsed> future;
        try {
            future = parserPool.submit(work);
        } catch (RejectedExecutionException e) {
            throw new ExtractionBusyException("server is busy extracting documents", e);
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TruncatedExtractionException("extraction timed out after "
                    + timeoutSeconds + "s (document considered unsafe)");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnextractableDocumentException("extraction interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ExtractionFailureException failure) {
                throw failure;
            }
            if (cause instanceof EncryptedDocumentException) {
                throw new UnextractableDocumentException("encrypted documents are not supported");
            }
            if (cause instanceof IOException) {
                throw new UnextractableDocumentException(
                        "stored file is not a valid document of its claimed type");
            }
            log.debug("extraction failed for document {}", documentId, cause);
            throw new UnextractableDocumentException("document could not be parsed");
        }
    }

    /** Page metadata is parser-cooperative — a missing/garbage count never vetoes. */
    private static int parsePages(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Reject-all embedded-document extractor: one document deep, ever. */
    private static final EmbeddedDocumentExtractor REJECT_ALL_EMBEDDED =
            new EmbeddedDocumentExtractor() {
                @Override
                public boolean shouldParseEmbedded(Metadata metadata) {
                    return false;
                }

                @Override
                public void parseEmbedded(InputStream stream, org.xml.sax.ContentHandler handler,
                                          Metadata metadata, boolean outputHtml) {
                    // drop the resource unread: zero bytes consumed, zero text emitted
                    log.debug("rejected embedded document resource");
                }
            };

    // ------------------------------------------------------------------
    // failure taxonomy (mapped to wire statuses by DocumentIndexingService)
    // ------------------------------------------------------------------

    /** Base for every extraction-policy failure. */
    public static class ExtractionFailureException extends RuntimeException {
        public ExtractionFailureException(String message) {
            super(message);
        }

        public ExtractionFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Bytes are not a valid document of the claimed type, or encrypted (400). */
    public static class UnextractableDocumentException extends ExtractionFailureException {
        public UnextractableDocumentException(String message) {
            super(message);
        }
    }

    /** An extraction limit (chars/pages/timeout) was hit — honest rejection (400). */
    public static class TruncatedExtractionException extends ExtractionFailureException {
        public TruncatedExtractionException(String message) {
            super(message);
        }
    }

    /** Parser pool saturated — retry-later class (503). */
    public static class ExtractionBusyException extends ExtractionFailureException {
        public ExtractionBusyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Document has zero text layer (400-then-store-NO_TEXT_LAYER upstream). */
    public static class BlankExtractedTextException extends ExtractionFailureException {
        public BlankExtractedTextException(String message) {
            super(message);
        }
    }
}
