package com.keplura.server.datasource;

import com.keplura.server.search.TikaDocumentTextExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExposureContentInspectorTest {

    private final TikaDocumentTextExtractor extractor =
            new TikaDocumentTextExtractor(2_000_000, 500, 5000, 200, 60, 2);
    private final ExposureContentInspector inspector =
            new ExposureContentInspector(extractor, true, 1024 * 1024, 50, 10, 100);

    @AfterEach
    void stop() {
        inspector.stop();
    }

    /** Share whose files are byte arrays; {@code null} = read error. */
    record Share(Map<String, byte[]> files) implements SourceConnector {
        @Override public List<Entry> list(int limit) {
            return List.of();
        }
        @Override public InputStream open(String relativePath) throws IOException {
            byte[] b = files.get(relativePath);
            if (b == null) throw new IOException("access denied");
            return new ByteArrayInputStream(b);
        }
        @Override public FileAcl acl(String relativePath) {
            return new FileAcl(java.util.Set.of("everyone"), java.util.Set.of());
        }
        @Override public void close() {
        }
    }

    private ExposureContentInspector.Result inspect(String path, byte[] content) {
        var share = new Share(content == null ? Map.of() : Map.of(path, content));
        long size = content == null ? 10 : content.length;
        return inspector.inspect(share, new SourceConnector.Entry(path, size, Instant.EPOCH));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void textFileIsScanned() {
        var r = inspect("hr/liste.csv", utf8("Name;IBAN\nA;DE89370400440532013000\nB;GB82WEST12345698765432\n"));
        assertThat(r.state()).isEqualTo(ExposureContentInspector.CHECKED);
        assertThat(r.hits()).containsExactly(new ContentPatterns.Hit(ContentPatterns.IBAN, "IBAN", 2));
    }

    @Test
    void cleanTextFileIsCheckedWithoutHits() {
        var r = inspect("notes.md", utf8("# Protokoll\nDie Anlage läuft."));
        assertThat(r.state()).isEqualTo(ExposureContentInspector.CHECKED);
        assertThat(r.hits()).isEmpty();
    }

    @Test
    void tooLargeIsNotCheckableWithoutReading() {
        var share = new Share(Map.of());
        var r = inspector.inspect(share, new SourceConnector.Entry("big.txt", 2L * 1024 * 1024, Instant.EPOCH));
        assertThat(r.state()).isEqualTo(ExposureContentInspector.NOT_CHECKABLE);
        assertThat(r.reason()).contains("zu groß");
    }

    @Test
    void sizeLyingListingIsCutAtTheByteLimit() {
        byte[] big = new byte[1024 * 1024 + 10];
        java.util.Arrays.fill(big, (byte) 'a');
        var share = new Share(Map.of("big.txt", big));
        var r = inspector.inspect(share, new SourceConnector.Entry("big.txt", 100, Instant.EPOCH));
        assertThat(r.state()).isEqualTo(ExposureContentInspector.NOT_CHECKABLE);
        assertThat(r.reason()).contains("zu groß");
    }

    @Test
    void readErrorFailsClosed() {
        var r = inspect("gf/geheim.txt", null);
        assertThat(r.state()).isEqualTo(ExposureContentInspector.NOT_CHECKABLE);
        assertThat(r.reason()).isEqualTo("Datei konnte nicht gelesen werden");
    }

    @Test
    void emptyTextIsNotCheckable() {
        var r = inspect("leer.txt", utf8("   \n"));
        assertThat(r.state()).isEqualTo(ExposureContentInspector.NOT_CHECKABLE);
        assertThat(r.reason()).contains("kein Text");
    }

    @Test
    void brokenPdfIsNotCheckable() {
        var r = inspect("scan.pdf", utf8("not a pdf at all"));
        assertThat(r.state()).isEqualTo(ExposureContentInspector.NOT_CHECKABLE);
    }

    @Test
    void wordDocumentIsExtracted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Gehaltsabrechnung März: Bruttogehalt 4.200 €, "
                    + "Nettogehalt 2.810 €, Steuerklasse I, Steuer-ID 86095742719");
            doc.write(out);
        }
        var r = inspect("personal/abrechnung.docx", out.toByteArray());
        assertThat(r.state()).isEqualTo(ExposureContentInspector.CHECKED);
        assertThat(r.hits()).extracting(ContentPatterns.Hit::category)
                .containsExactly(ContentPatterns.TAX_ID, ContentPatterns.HR_TERMS);
    }

    @Test
    void unknownTypeIsNotCheckable() {
        var r = inspect("bild.png", new byte[]{1, 2, 3});
        assertThat(r.state()).isEqualTo(ExposureContentInspector.NOT_CHECKABLE);
    }
}
