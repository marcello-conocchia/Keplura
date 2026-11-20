package com.keplura.server.search.ocr;

import com.keplura.server.knowledge.Document;
import com.keplura.server.search.TestDocuments;
import com.keplura.server.search.TikaDocumentTextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfOcrTest {

    @TempDir Path temp;

    /** Simulierte Texterkennung: merkt sich die angefragten Seiten. */
    static class FakeOcr extends PdfOcr {
        final List<List<Integer>> calls = new ArrayList<>();
        final boolean on;
        int skipped;

        FakeOcr(boolean on) {
            super(true, "tesseract", "deu+eng", 300, 100, 60, 900);
            this.on = on;
        }

        @Override public boolean available() {
            return on;
        }

        int lastBudget;

        @Override public Result recognize(Path pdf, List<Integer> pages, int limit, int budgetSeconds) {
            lastBudget = budgetSeconds;
            calls.add(List.copyOf(pages));
            Map<Integer, String> out = new LinkedHashMap<>();
            for (int p : pages.subList(0, Math.min(limit, pages.size()))) out.put(p, "Erkannter Text der Seite " + (p + 1));
            return new Result(out, Math.max(0, pages.size() - limit), 0);
        }
    }

    private static Document doc(String name) {
        Document d = Document.of(1L, name, "application/pdf", 1L, "0".repeat(64), "orgs/1/spaces/1/x", 1L);
        ReflectionTestUtils.setField(d, "id", 1L);
        return d;
    }

    private static TikaDocumentTextExtractor extractor(PdfOcr ocr) {
        var t = new TikaDocumentTextExtractor(2_000_000, 500, 5000, 200, 60, 2);
        t.setOcr(ocr);
        return t;
    }

    @Test void pageOutputLimitRejectsInsteadOfReturningASilentSubstring() throws Exception {
        String exact = "Ä".repeat(PdfOcr.MAX_CHARS_PER_PAGE);
        assertThat(PdfOcr.readCapped(new java.io.ByteArrayInputStream(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEqualTo(exact);
        for (String text : new String[]{exact + "x", "x".repeat(PdfOcr.MAX_CHARS_PER_PAGE * 4 + 1)}) {
            assertThatThrownBy(() -> PdfOcr.readCapped(new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class);
        }
    }

    public static class OutputFork {
        public static void main(String[] args) { System.out.print("x".repeat(500_000)); }
    }

    @Test void realProcessOutputCapPropagatesAsAnExtractionLimit() {
        var ocr = new PdfOcr(true, "unused", "eng", 72, 1, 10, 20);
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            assertThatThrownBy(() -> ocr.exec(List.of(java, "-cp", System.getProperty("java.class.path"), OutputFork.class.getName()), 10))
                    .isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class);
        } finally { ReflectionTestUtils.invokeMethod(ocr, "stop"); }
    }

    /**
     * PDFBox rendering runs in-process and ignores interrupts: a page whose caller gave up
     * can keep the single OCR worker busy. Later pages and documents must then fail fast
     * instead of each waiting the full page timeout behind it (indexer throughput).
     */
    @Test void stuckPageDoesNotMakeLaterPagesWaitBehindIt() throws Exception {
        Path pdf = temp.resolve("stuck.pdf");
        try (var doc = new PDDocument()) {
            for (int i = 0; i < 4; i++) doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        var release = new java.util.concurrent.CountDownLatch(1);
        var hung = new java.util.concurrent.atomic.AtomicBoolean();
        var ocr = new PdfOcr(true, "unused", "eng", 72, 100, 1, 60) {
            @Override Run exec(List<String> args, int timeout) {
                if (hung.compareAndSet(false, true)) {
                    // simulates a render step that does not react to Thread.interrupt()
                    while (true) {
                        try {
                            if (release.await(30, java.util.concurrent.TimeUnit.SECONDS)) break;
                        } catch (InterruptedException ignored) {
                            // keep running, like a non-interruptible PDFBox render
                        }
                    }
                }
                return new Run(0, "Seite erkannt");
            }
        };
        try {
            var first = ocr.recognize(pdf, List.of(0, 1, 2, 3), 100);
            assertThat(first.pages()).isEmpty();
            assertThat(first.failedPages()).isEqualTo(4);

            long started = System.nanoTime();
            var second = ocr.recognize(pdf, List.of(0, 1, 2, 3), 100);
            long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(second.failedPages()).isEqualTo(4);
            assertThat(millis).as("later document must not wait page timeouts behind a stuck page").isLessThan(900);

            release.countDown();
            var recovered = ocr.recognize(pdf, List.of(0, 1), 100);
            for (int i = 0; i < 50 && recovered.pages().size() < 2; i++) {
                Thread.sleep(50); // the released task finishes asynchronously
                recovered = ocr.recognize(pdf, List.of(0, 1), 100);
            }
            assertThat(recovered.pages()).containsEntry(0, "Seite erkannt").containsEntry(1, "Seite erkannt");
        } finally {
            release.countDown();
            ReflectionTestUtils.invokeMethod(ocr, "stop");
        }
    }

    /**
     * R-1 — the common case: Tesseract simply exceeds the page timeout but reacts to the
     * interrupt and needs a few milliseconds to clean up. Only that one page may fail;
     * the worker must not be taken for stuck and later pages must still be recognized.
     */
    @Test void slowButInterruptiblePageFailsAloneAndLaterPagesStillRun() throws Exception {
        Path pdf = temp.resolve("slow.pdf");
        try (var doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var ocr = new PdfOcr(true, "unused", "eng", 72, 100, 1, 60) {
            @Override Run exec(List<String> args, int timeout) throws java.io.IOException {
                if (first.getAndSet(false)) {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        try {
                            Thread.sleep(20); // cleanup after the interrupt (destroy process, delete PNG)
                        } catch (InterruptedException ignored) {
                            // keep cleaning up
                        }
                        throw new java.io.IOException("ocr interrupted");
                    }
                }
                return new Run(0, "ok");
            }
        };
        try {
            var r = ocr.recognize(pdf, List.of(0, 1, 2), 100);
            assertThat(r.failedPages()).isEqualTo(1);
            assertThat(r.pages()).containsKeys(1, 2);
        } finally {
            ReflectionTestUtils.invokeMethod(ocr, "stop");
        }
    }

    @Test void pageCharacterCapRejectsTheWholeDocumentRatherThanAReportedFailedPage() throws Exception {
        Path pdf = Files.write(temp.resolve("page-cap.pdf"), TestDocuments.pdfWithoutTextLayer());
        var ocr = new PdfOcr(true, "unused", "eng", 72, 1, 10, 20) {
            @Override public boolean available() { return true; }
            @Override Run exec(List<String> args, int timeout) {
                throw new TikaDocumentTextExtractor.TruncatedExtractionException("OCR page limit");
            }
        };
        var t = extractor(ocr);
        try {
            assertThatThrownBy(() -> t.extract("page-cap.pdf", pdf))
                    .isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class);
            assertThatThrownBy(() -> t.extract(doc("page-cap.pdf"), pdf))
                    .isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class);
        } finally {
            ReflectionTestUtils.invokeMethod(t, "shutdownPool");
            ReflectionTestUtils.invokeMethod(ocr, "stop");
        }
    }

    @Test void combinedOcrTextCannotBypassTheDocumentCharacterCap() throws Exception {
        var pdf = temp.resolve("ocr-limit.pdf");
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage()); doc.addPage(new PDPage()); doc.save(pdf.toFile());
        }
        var ocr = new FakeOcr(true) {
            @Override public Result recognize(Path p, List<Integer> pages, int limit, int budget) {
                return new Result(Map.of(0, "A".repeat(700), 1, "B".repeat(700)), 0, 0);
            }
        };
        var t = new TikaDocumentTextExtractor(1000, 500, 5000, 200, 60, 2);
        t.setOcr(ocr);
        try {
            assertThatThrownBy(() -> t.extract("ocr-limit.pdf", pdf))
                    .isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class)
                    .hasMessageContaining("character limit");
            // Document upload and attachment share the exact merge policy.
            assertThatThrownBy(() -> t.extract(doc("ocr-limit.pdf"), pdf))
                    .isInstanceOf(TikaDocumentTextExtractor.TruncatedExtractionException.class);
        } finally { ReflectionTestUtils.invokeMethod(t, "shutdownPool"); }
        var exact = new TikaDocumentTextExtractor(1401, 500, 5000, 200, 60, 2);
        exact.setOcr(ocr);
        try { assertThat(exact.extract("ocr-limit.pdf", pdf).text()).hasSize(1401); }
        finally { ReflectionTestUtils.invokeMethod(exact, "shutdownPool"); }
    }

    /** Seite 1 mit echter Textschicht, Seite 2 nur Grafik (Scan). */
    static byte[] mixedPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage p1 = new PDPage();
            doc.addPage(p1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Wartungsanleitung Pumpe P7 mit Textschicht");
                cs.endText();
            }
            PDPage p2 = new PDPage();
            doc.addPage(p2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                cs.addRect(10, 10, 200, 300);
                cs.fill();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void scannedPdfIsReadThroughOcrInsteadOfNoTextLayer() throws Exception {
        Path f = Files.write(temp.resolve("scan.pdf"), TestDocuments.pdfWithoutTextLayer());
        FakeOcr ocr = new FakeOcr(true);
        var out = extractor(ocr).extract(doc("scan.pdf"), f);
        assertThat(out.text()).contains("Erkannter Text der Seite 1");
        assertThat(out.ocrPages()).isEqualTo(1);
        assertThat(ocr.calls).containsExactly(List.of(0));
    }

    @Test
    void mixedPdfKeepsTextPagesAndOnlyRecognisesTheScannedPage() throws Exception {
        Path f = Files.write(temp.resolve("mixed.pdf"), mixedPdf());
        FakeOcr ocr = new FakeOcr(true);
        var out = extractor(ocr).extract(doc("mixed.pdf"), f);
        String[] pages = out.text().split("\f", -1);
        assertThat(pages).hasSize(2);
        assertThat(pages[0]).contains("Wartungsanleitung Pumpe P7");
        assertThat(pages[1]).contains("Erkannter Text der Seite 2");
        assertThat(out.pages()).isEqualTo(2);
        assertThat(out.ocrPages()).isEqualTo(1);
        assertThat(ocr.calls).containsExactly(List.of(1));
    }

    @Test
    void textPdfNeverCallsOcr() throws Exception {
        Path f = Files.write(temp.resolve("text.pdf"), TestDocuments.samplePdf());
        FakeOcr ocr = new FakeOcr(true);
        var out = extractor(ocr).extract(doc("text.pdf"), f);
        assertThat(out.ocrPages()).isZero();
        assertThat(ocr.calls).isEmpty();
    }

    @Test
    void withoutOcrAScanStaysNoTextLayer() throws Exception {
        Path f = Files.write(temp.resolve("scan.pdf"), TestDocuments.pdfWithoutTextLayer());
        assertThatThrownBy(() -> extractor(new FakeOcr(false)).extract(doc("scan.pdf"), f))
                .isInstanceOf(TikaDocumentTextExtractor.BlankExtractedTextException.class);
        assertThatThrownBy(() -> extractor(null).extract(doc("scan.pdf"), f))
                .isInstanceOf(TikaDocumentTextExtractor.BlankExtractedTextException.class);
    }

    @Test
    void chatAttachmentsAreCappedAtTenOcrPages() throws Exception {
        try (PDDocument d = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 0; i < 12; i++) d.addPage(new PDPage());
            d.save(out);
            Path f = Files.write(temp.resolve("lang.pdf"), out.toByteArray());
            FakeOcr ocr = new FakeOcr(true);
            var r = extractor(ocr).extract("lang.pdf", f);
            assertThat(r.ocrPages()).isEqualTo(10);
            assertThat(ocr.lastBudget).isEqualTo(90); // Antwort kommt vor dem 120-s-Limit der Oberfläche
            assertThat(r.text().split("\f", -1)).hasSize(12);
        }
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new PdfOcr(true, "tesseract", "deu; rm -rf /", 300, 100, 60, 900))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PdfOcr(true, "tesseract", "deu", 5000, 100, 60, 900))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PdfOcr(false, "tesseract", "deu", 300, 100, 60, 900).available()).isFalse();
        assertThat(new PdfOcr(true, "kein-tesseract-vorhanden", "deu", 300, 100, 60, 900).available()).isFalse();
    }

    // ---- echte Texterkennung (nur wo Tesseract mit deu installiert ist, z. B. im Server-Image) ----

    static boolean realTesseract() {
        return new PdfOcr(true, "tesseract", "deu+eng", 300, 100, 60, 900).available();
    }

    /** Ein Bild mit Text, als Bild in eine PDF gelegt: keine Textschicht, wie ein echter Scan. */
    static byte[] imageOnlyPdf(String... lines) throws Exception {
        BufferedImage img = new BufferedImage(1700, 2200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 56));
        int y = 300;
        for (String l : lines) {
            g.drawString(l, 150, y);
            y += 110;
        }
        g.dispose();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            var xo = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(xo, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @EnabledIf("realTesseract")
    void realTesseractReadsAGermanScan() throws Exception {
        Path f = Files.write(temp.resolve("echt.pdf"),
                imageOnlyPdf("Prüfbericht für Anlage Nord", "Druckbehälter geprüft am 12.03.2025", "Ergebnis: ohne Mängel"));
        var ocr = new PdfOcr(true, "tesseract", "deu+eng", 300, 100, 60, 900);
        try {
            var out = extractor(ocr).extract(doc("echt.pdf"), f);
            assertThat(out.ocrPages()).isEqualTo(1);
            assertThat(out.text()).contains("Prüfbericht").contains("Druckbehälter").contains("Mängel");
        } finally {
            ReflectionTestUtils.invokeMethod(ocr, "stop");
        }
    }
}
