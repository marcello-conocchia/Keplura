package com.keplura.server.search;

import com.keplura.server.knowledge.Document;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MVP-5: the extraction contract on REAL self-generated binaries (no sample
 * files in the repo — see {@link TestDocuments}). Covers: happy pdf/docx
 * text, embedded-resource rejection, scanned-PDF blank path, corrupt/fake
 * bytes, and every resource limit: char cap (write limit DURING parse),
 * page caps (structural pre-count), pool saturation and the wall-clock
 * timeout (deterministic via {@code runBounded} + a hung callable — never a
 * race against real parsing speed).
 */
class TikaDocumentTextExtractorTest {

    private static final Path TMP = Path.of("target/test-extractions");

    private static Document doc(String filename) {
        Document d = Document.of(1L, filename, "application/octet-stream", 1L,
                "0".repeat(64), "orgs/1/spaces/1/x", 1L);
        ReflectionTestUtils.setField(d, "id", 1L);
        return d;
    }

    private static Path write(String name, byte[] bytes) throws Exception {
        Files.createDirectories(TMP);
        Path p = TMP.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private static TikaDocumentTextExtractor standard() {
        return new TikaDocumentTextExtractor(2_000_000, 500, 5000, 200, 60, 2);
    }

    // ---- happy paths ----

    @Test
    void pdfTextIsExtractedWithPageCount() throws Exception {
        Path file = write("sample.pdf", TestDocuments.samplePdf());
        TikaDocumentTextExtractor.ExtractedText out = standard().extract(doc("sample.pdf"), file);
        assertThat(out.text()).contains("Betriebstemperatur der Turbine Delta");
        assertThat(out.pages()).isEqualTo(1);
    }

    @Test
    void pdfRetainsRealPageBoundariesIncludingAnEmptyFirstPage() throws Exception {
        Files.createDirectories(TMP);
        Path file = TMP.resolve("two-pages.pdf");
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Die Schwelle auf Seite zwei ist 42 Prozent.");
                content.endText();
            }
            pdf.save(file.toFile());
        }
        var extracted = standard().extract(doc("two-pages.pdf"), file);
        assertThat(extracted.text()).contains("\f");
        var chunks = new DocumentChunker(800, 100).chunk(extracted.text(), true);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().pageNumber()).isEqualTo(2);
        assertThat(chunks.getFirst().text()).contains("42 Prozent");
    }

    @Test
    void docxTextIsExtracted() throws Exception {
        Path file = write("sample.docx", TestDocuments.sampleDocx());
        TikaDocumentTextExtractor.ExtractedText out = standard().extract(doc("sample.docx"), file);
        assertThat(out.text()).contains("Kostenplanung Werksniederlassung Nord");
    }

    @Test
    void docxWithZeroTextIsHonestBlank() throws Exception {
        Path file = write("blank.docx", TestDocuments.docx("   ", null));
        assertThatThrownBy(() -> standard().extract(doc("blank.docx"), file))
                .isInstanceOf(TikaDocumentTextExtractor.BlankExtractedTextException.class);
    }

    @Test
    void embeddedResourcesAreNeverFollowed() throws Exception {
        byte[] withEmbedded = TestDocuments.docx("Haupttext des Dokuments",
                ("VERSTECKTER_EMBEDDED_TEXT darf nie indexiert werden")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path file = write("embedded.docx", withEmbedded);
        TikaDocumentTextExtractor.ExtractedText out = standard().extract(doc("embedded.docx"), file);
        assertThat(out.text()).contains("Haupttext");
        assertThat(out.text()).doesNotContain("VERSTECKTER_EMBEDDED_TEXT");
    }

    // ---- honest failure taxonomy ----

    @Test
    void pdfWithoutTextLayerIsBlankNotSilentlyEmpty() throws Exception {
        Path file = write("scan.pdf", TestDocuments.pdfWithoutTextLayer());
        assertThatThrownBy(() -> standard().extract(doc("scan.pdf"), file))
                .isInstanceOf(TikaDocumentTextExtractor.BlankExtractedTextException.class);
    }

    @Test
    void fakePdfBytesAreUnextractable() throws Exception {
        Path file = write("fake.pdf", "%PDF-1.4 fake content bomb".getBytes());
        assertThatThrownBy(() -> standard().extract(doc("fake.pdf"), file))
                .isInstanceOf(TikaDocumentTextExtractor.UnextractableDocumentException.class);
    }

    @Test
    void fakeDocxBytesAreUnextractable() throws Exception {
        Path file = write("fake.docx", "PK not really a zip at all".getBytes());
        assertThatThrownBy(() -> standard().extract(doc("fake.docx"), file))
                .isInstanceOf(TikaDocumentTextExtractor.UnextractableDocumentException.class);
    }

    // ---- resource limits ----

    /**
     * F-1 regression (reviewer round 2, fresh-JVM lens): Tika 3.3.2's
     * OOXMLParser {@code <clinit>} writes ZipSecureFile(-1.0, 10000) into the
     * gate statics at FIRST class-init — an extractor that applied its gates
     * before parser construction silently ran the first docx of every process
     * with the zip-bomb guard effectively off. A shared surefire JVM cannot
     * prove the first-parse case (an earlier test usually pre-initialises the
     * class), so this spawns a CHILD JVM whose class-init is guaranteed fresh
     * and asserts, over the real {@code extract()} path, that an over-cap
     * docx is REJECTED there and the statics end at the configured values.
     */
    @Test
    void entryGateHoldsOnFirstOoxmlParseOfAFreshJvm() throws Exception {
        Path dir = Files.createTempDirectory("keplura-zipbomb-fork");
        Path bomb = dir.resolve("bomb.docx");
        Files.write(bomb, TestDocuments.docxWithEntryCapProbe(
                "Diese Datei ueberschreitet die Entry-Kappe", 300));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        ZipBombFork.class.getName(), bomb.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("fork finished within 120s").isTrue();
        assertThat(p.exitValue())
                .as("child JVM must exit 0 = over-cap docx rejected on FIRST parse; output:\n"
                        + output)
                .isZero();
    }

    /** Entry point for the child JVM above (kept as a static nested class so it
     * shares the test classpath and the exact production code path). */
    public static final class ZipBombFork {
        public static void main(String[] args) {
            // cap of 5 entries, everything else at production defaults
            TikaDocumentTextExtractor e =
                    new TikaDocumentTextExtractor(2_000_000, 500, 5, 200, 60, 2);
            try {
                Document d = Document.of(1L, "bomb.docx", "application/octet-stream", 1L,
                        "0".repeat(64), "orgs/1/spaces/1/x", 1L);
                org.springframework.test.util.ReflectionTestUtils.setField(d, "id", 1L);
                try {
                    TikaDocumentTextExtractor.ExtractedText out =
                            e.extract(d, Path.of(args[0]));
                    System.out.println("FORK ACCEPTED len=" + out.text().length());
                    System.exit(1); // gate did NOT hold on first parse
                } catch (TikaDocumentTextExtractor.TruncatedExtractionException rejected) {
                    System.out.println("FORK REJECTED: " + rejected.getMessage());
                    long cap = org.apache.poi.openxml4j.util.ZipSecureFile.getMaxFileCount();
                    double ratio = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
                    System.out.println("FORK statics: maxFileCount=" + cap
                            + " minInflateRatio=" + ratio);
                    if (cap != 5 || ratio != 0.01) {
                        System.exit(2); // rejected for the wrong reason
                    }
                    System.exit(0);
                }
            } catch (Exception fail) {
                fail.printStackTrace();
                System.exit(3);
            } finally {
                e.shutdownPool();
            }
        }
    }

    /** Same gate, in-process lens: after ANY docx extract() the statics must
     * read back the configured values (Tika's clobber happened BEFORE them). */
    @Test
    void gateStaticsHoldConfiguredValuesAfterRealDocxParse() throws Exception {
        long original = org.apache.poi.openxml4j.util.ZipSecureFile.getMaxFileCount();
        double originalRatio = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        try {
            Path file = write("smallgate.docx", TestDocuments.sampleDocx());
            TikaDocumentTextExtractor capped =
                    new TikaDocumentTextExtractor(2_000_000, 500, 42, 200, 60, 2);
            capped.extract(doc("smallgate.docx"), file); // real parse, real path
            assertThat(org.apache.poi.openxml4j.util.ZipSecureFile.getMaxFileCount())
                    .as("our gate values must be the LAST write after a parse")
                    .isEqualTo(42);
            assertThat(org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio())
                    .isEqualTo(0.01);
        } finally {
            org.apache.poi.openxml4j.util.ZipSecureFile.setMaxFileCount(original);
            org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(originalRatio);
        }
    }

    @Test
    void charCapAbortsDuringParseNotAfterwards() throws Exception {
        String text = "Die zulassige Betriebstemperatur der Turbine Delta liegt bei 72 Grad. ".repeat(60);
        Path file = write("long.pdf", TestDocuments.pdf(1, text));
        TikaDocumentTextExtractor tiny = new TikaDocumentTextExtractor(500, 500, 5000, 200, 60, 2);
        assertThatThrownBy(() -> tiny.extract(doc("long.pdf"), file))
                .isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class)
                .hasMessageContaining("character limit");
    }

    @Test
    void pageBombIsRejectedStructurallyBeforeAnyExtraction() throws Exception {
        Path file = write("pages.pdf", TestDocuments.pdf(8, "Seitentext"));
        assertThat(TestDocuments.pdfPageCount(Files.readAllBytes(file))).isEqualTo(8);
        TikaDocumentTextExtractor capped = new TikaDocumentTextExtractor(2_000_000, 5, 5000, 200, 60, 2);
        assertThatThrownBy(() -> capped.extract(doc("pages.pdf"), file))
                .isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class)
                .hasMessageContaining("page limit");
    }

    // ---- pool behaviour (deterministic: runBounded + hung callable) ----

    @Test
    void poolSaturationFailsFastAsBusyNotAsDocumentFailure() {
        TikaDocumentTextExtractor one = new TikaDocumentTextExtractor(2_000_000, 500, 5000, 200, 60, 1);
        try {
            one.runBounded(1L, () -> {
                Thread.sleep(10); // finish quickly
                return new TikaDocumentTextExtractor.Parsed("x", 1);
            });
            // occupy the single thread, THEN submit — SynchronousQueue +
            // AbortPolicy must reject instead of queueing
            CountDownLatch hold = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try {
                    one.runBounded(2L, () -> {
                        hold.await();
                        return new TikaDocumentTextExtractor.Parsed("never", 0);
                    });
                } catch (Exception ignored) {
                }
            });
            holder.start();
            // wait until the holder's task is ACTUALLY running on the pool
            // before the second submit (SynchronousQueue + AbortPolicy must
            // reject instead of queueing)
            waitUntil(() -> activeCount(one) == 1);
            assertThatThrownBy(() -> one.runBounded(3L,
                    () -> new TikaDocumentTextExtractor.Parsed("x", 1)))
                    .isInstanceOf(TikaDocumentTextExtractor.ExtractionBusyException.class);
            hold.countDown();
            holder.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            one.shutdownPool();
        }
    }

    @Test
    void timeoutAbandonsTheWorkerAndRejectsTheUpload() {
        TikaDocumentTextExtractor one = new TikaDocumentTextExtractor(2_000_000, 500, 5000, 200, 1, 1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            assertThatThrownBy(() -> one.runBounded(1L, () -> {
                try {
                    Thread.sleep(60_000); // far beyond the 1s budget
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
                return new TikaDocumentTextExtractor.Parsed("never", 0);
            })).isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class)
                    .hasMessageContaining("timed out");
            // cancel(true) DID interrupt the abandoned worker; whether the
            // eventual parse thread respects it is exactly the documented
            // caveat — sleep() does, so here the interruption is observable.
            waitUntil(interrupted::get);
        } finally {
            one.shutdownPool();
        }
    }

    private static int activeCount(TikaDocumentTextExtractor extractor) {
        Object pool = ReflectionTestUtils.getField(extractor, "parserPool");
        return ((java.util.concurrent.ThreadPoolExecutor) pool).getActiveCount();
    }

    /** Poll until {@code condition} holds (fails the test after 5s). */
    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    // ---- construction + helpers ----

    @Test
    void limitsAreValidatedAtConstruction() {
        assertThatThrownBy(() -> new TikaDocumentTextExtractor(0, 500, 5000, 200, 60, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TikaDocumentTextExtractor(100, 500, 5000, 200, 60, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zipBombGatesApplyConfiguredValues() {
        long original = org.apache.poi.openxml4j.util.ZipSecureFile.getMaxFileCount();
        double originalRatio = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        try {
            TikaDocumentTextExtractor e = new TikaDocumentTextExtractor(2_000_000, 500, 42, 200, 60, 2);
            e.applyZipBombGates();
            assertThat(org.apache.poi.openxml4j.util.ZipSecureFile.getMaxFileCount()).isEqualTo(42);
            assertThat(org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio())
                    .isEqualTo(0.01);
        } finally {
            org.apache.poi.openxml4j.util.ZipSecureFile.setMaxFileCount(original);
            org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(originalRatio);
        }
    }

    @Test
    void vanishedStoredFileFailsHonestUnextractable() throws Exception {
        Path file = write("gone.pdf", TestDocuments.samplePdf());
        Files.delete(file);
        assertThatThrownBy(() -> standard().extract(doc("gone.pdf"), file))
                .isInstanceOf(TikaDocumentTextExtractor.UnextractableDocumentException.class);
    }

    @Test
    void normalizeCollapsesExtractionWhitespaceButKeepsContent() {
        assertThat(DocumentIndexingService.normalizeExtracted("a\r\nb\fc\r\n\n\n\n\nd"))
                .isEqualTo("a\nb\nc\n\nd");
    }
}
