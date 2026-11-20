package com.keplura.server.search;

import com.keplura.server.knowledge.DocumentUploadPolicy;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;
import static com.keplura.server.search.TikaDocumentTextExtractor.*;

/** Real POI-generated files exercise preflight + Tika + structural source locations. */
class OfficeFormatsTest {
    @TempDir Path dir;
    private final TikaDocumentTextExtractor extractor = new TikaDocumentTextExtractor(2_000_000, 500, 5000, 200, 60, 2);
    @AfterEach void close() {
        extractor.shutdownPool();
        extractor.applyZipBombGates(); // POI settings are JVM-global, including fixture creation
    }

    private Path xlsx(int sheets, int cells) throws Exception {
        Path file = dir.resolve("budget.xlsx");
        try (var book = new XSSFWorkbook()) {
            for (int i = 0; i < sheets; i++) book.createSheet("Budget " + (i + 1));
            if (sheets > 0) {
                var sheet = book.getSheetAt(0);
                for (int i = 0; i < cells; i++) {
                    int row = i / 100;
                    var r = sheet.getRow(row);
                    if (r == null) r = sheet.createRow(row);
                    r.createCell(i % 100).setCellValue(i);
                }
            }
            try (var out = Files.newOutputStream(file)) { book.write(out); }
        }
        return file;
    }

    private Path pptx(int slides) throws Exception {
        Path file = dir.resolve("vortrag.pptx");
        try (var deck = new XMLSlideShow()) {
            for (int i = 1; i <= slides; i++) {
                var slide = deck.createSlide();
                if (i > 1) slide.createTextBox().setText("Inhalt der Folie " + i);
                if (i == 3) deck.getNotesSlide(slide).createTextBox().setText("Sprechernotiz Grenzwert 42 Prozent");
            }
            // Relationship order stays 1,2,3; presentation order becomes 1,3,2.
            if (slides == 3) deck.setSlideOrder(deck.getSlides().get(2), 1);
            try (var out = Files.newOutputStream(file)) { deck.write(out); }
        }
        return file;
    }

    @Test void workbookHasSheetHeadingsRowsAndCachedFormulaValues() throws Exception {
        Path file = xlsx(2, 3);
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(Files.readAllBytes(file)))) {
            var row = book.getSheetAt(0).getRow(0);
            row.getCell(0).setCellValue("Umsatz");
            row.getCell(1).setCellValue(21);
            row.getCell(2).setCellFormula("B1*2");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.getSheetAt(0).createRow(1).createCell(0).setCellValue("Folgezeile");
            try (var out = Files.newOutputStream(file)) { book.write(out); }
        }
        var out = extractor.extract("budget.xlsx", file);
        assertThat(out.text()).contains("# Budget 1", "# Budget 2", "Umsatz\t21\t42", "\n\tFolgezeile");
        assertThat(out.text()).doesNotContain("B1*2");
        var chunks = new DocumentChunker(800, 100).chunk(out.text());
        assertThat(chunks).anyMatch(c -> "Budget 1".equals(c.sectionTitle()));
        assertThat(chunks).allMatch(c -> c.pageNumber() == null);
        assertThat(DocumentUploadPolicy.isAcceptedFilename("budget.XLSX")).isTrue();
    }

    @Test void slidesKeepPresentationOrderBlankFirstSlideAndNotesInTheirCitation() throws Exception {
        var out = extractor.extract("vortrag.pptx", pptx(3));
        assertThat(out.pages()).isEqualTo(3);
        String[] slides = out.text().split("\f", -1);
        assertThat(slides).hasSize(3);
        assertThat(slides[0]).contains("# Folie 1").doesNotContain("Inhalt der Folie");
        assertThat(slides[1]).contains("# Folie 2", "Inhalt der Folie 3", "Sprechernotiz Grenzwert 42 Prozent");
        assertThat(slides[2]).contains("# Folie 3", "Inhalt der Folie 2").doesNotContain("Sprechernotiz");
        var chunks = new DocumentChunker(80, 10).chunk(out.text(), true);
        assertThat(chunks).filteredOn(c -> c.text().contains("Grenzwert"))
                .isNotEmpty().allSatisfy(c -> { assertThat(c.pageNumber()).isEqualTo(2); assertThat(c.sectionTitle()).isEqualTo("Folie 2"); });
        assertThat(DocumentUploadPolicy.isAcceptedFilename("vortrag.PPTX")).isTrue();
    }

    @Test void rejects51Sheets() throws Exception {
        Path file = xlsx(51, 1);
        assertThatThrownBy(() -> extractor.extract("budget.xlsx", file))
                .isInstanceOf(TruncatedExtractionException.class).hasMessageContaining("sheet limit");
    }
    @Test void accepts50Sheets() throws Exception {
        assertThat(extractor.extract("budget.xlsx", xlsx(50, 1)).text()).contains("Budget 50");
    }
    @Test void rejects201Slides() throws Exception {
        Path file = pptx(201);
        assertThatThrownBy(() -> extractor.extract("vortrag.pptx", file))
                .isInstanceOf(TruncatedExtractionException.class).hasMessageContaining("slide limit");
    }
    @Test void accepts200Slides() throws Exception {
        assertThat(extractor.extract("vortrag.pptx", pptx(200)).pages()).isEqualTo(200);
    }
    @Test void rejects200001StoredCells() throws Exception {
        Path file = xlsx(1, 200_001);
        assertThatThrownBy(() -> extractor.extract("budget.xlsx", file))
                .isInstanceOf(TruncatedExtractionException.class).hasMessageContaining("cell limit");
    }
    @Test void accepts200000StoredCellsInStructuralPreflight() throws Exception {
        Path file = xlsx(1, 200_000);
        // The independent character cap can still reject such a workbook during Tika extraction.
        new org.apache.tika.parser.microsoft.ooxml.OOXMLParser();
        extractor.applyZipBombGates();
        assertThat(OfficePackagePolicy.check("xlsx", file)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"xlsx", "pptx"})
    void characterLimitAndEntryBombGateApplyToBothFormats(String ext) throws Exception {
        Path file = ext.equals("xlsx") ? xlsx(1, 3) : pptx(3);
        var tiny = new TikaDocumentTextExtractor(5, 500, 5000, 200, 60, 1);
        var entries = new TikaDocumentTextExtractor(2_000_000, 500, 5, 200, 60, 1);
        try {
            assertThatThrownBy(() -> tiny.extract("file." + ext, file)).isInstanceOf(TruncatedExtractionException.class);
            assertThatThrownBy(() -> entries.extract("file." + ext, file)).isInstanceOf(TruncatedExtractionException.class)
                    .hasMessageContaining("zip-bomb");
        } finally { tiny.shutdownPool(); entries.shutdownPool(); }
    }

    @ParameterizedTest @ValueSource(strings = {"xlsx", "pptx"})
    void rejectsInflateBombs(String ext) throws Exception {
        Path file = ext.equals("xlsx") ? xlsx(1, 1) : pptx(1);
        Path bomb = dir.resolve("bomb." + ext);
        try (var in = new ZipInputStream(Files.newInputStream(file));
             var out = new ZipOutputStream(Files.newOutputStream(bomb))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals(ext.equals("xlsx") ? "xl/workbook.xml" : "ppt/presentation.xml")) {
                    String xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    xml = xml.replaceFirst(">", "><!--" + "A".repeat(200_000) + "-->");
                    out.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else in.transferTo(out);
                out.closeEntry();
            }
        }
        assertThatThrownBy(() -> extractor.extract("bomb." + ext, bomb))
                .isInstanceOf(TruncatedExtractionException.class).hasMessageContaining("zip-bomb");
    }

    @ParameterizedTest @ValueSource(strings = {"xlsx", "pptx"})
    void rejectsExternalRelationshipsAndMacrosButIgnoresEmbeddedDocuments(String ext) throws Exception {
        Path file = ext.equals("xlsx") ? xlsx(1, 1) : pptx(2);
        try (var pkg = OPCPackage.open(file.toFile(), PackageAccess.READ_WRITE)) {
            var part = pkg.createPart(PackagingURIHelper.createPartName("/embedded/payload.txt"), "text/plain");
            try (var out = part.getOutputStream()) { out.write("EMBEDDED_SECRET_NEVER_INDEX".getBytes()); }
            var root = pkg.getPartsByContentType(ext.equals("xlsx")
                    ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
                    : "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml").getFirst();
            root.addRelationship(part.getPartName(), TargetMode.INTERNAL,
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/package");
        }
        assertThat(extractor.extract("file." + ext, file).text()).doesNotContain("EMBEDDED_SECRET_NEVER_INDEX");
        try (var pkg = OPCPackage.open(file.toFile(), PackageAccess.READ_WRITE)) {
            pkg.addExternalRelationship("http://127.0.0.1:9/private", "external-test");
        }
        assertThatThrownBy(() -> extractor.extract("file." + ext, file))
                .isInstanceOf(UnextractableDocumentException.class).hasMessageContaining("external");
        // A renamed macro-containing package is rejected regardless of filename.
        Path macro = ext.equals("xlsx") ? xlsx(1, 1) : pptx(2);
        try (var pkg = OPCPackage.open(macro.toFile(), PackageAccess.READ_WRITE)) {
            pkg.createPart(PackagingURIHelper.createPartName("/vbaProject.bin"), "application/vnd.ms-office.vbaProject");
        }
        assertThatThrownBy(() -> extractor.extract("file." + ext, macro))
                .isInstanceOf(UnextractableDocumentException.class).hasMessageContaining("macros");
    }

    /** Review-Befund PR #70: normale Hyperlinks dürfen ein Dokument nicht unlesbar machen (werden nie abgerufen). */
    @ParameterizedTest @ValueSource(strings = {"xlsx", "pptx"})
    void acceptsHyperlinksButStillRefusesOtherExternalTargets(String ext) throws Exception {
        Path file = ext.equals("xlsx") ? xlsx(1, 1) : pptx(2);
        String mainType = ext.equals("xlsx")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
                : "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
        try (var pkg = OPCPackage.open(file.toFile(), PackageAccess.READ_WRITE)) {
            pkg.getPartsByContentType(mainType).getFirst()
                    .addExternalRelationship("https://intranet.example.test/handbuch", OfficePackagePolicy.HYPERLINK);
        }
        assertThat(extractor.extract("mit-link." + ext, file).text()).isNotBlank();
        try (var pkg = OPCPackage.open(file.toFile(), PackageAccess.READ_WRITE)) {
            pkg.getPartsByContentType(mainType).getFirst().addExternalRelationship("file:///srv/vorlage.potx",
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate");
        }
        assertThatThrownBy(() -> extractor.extract("mit-vorlage." + ext, file))
                .isInstanceOf(UnextractableDocumentException.class).hasMessageContaining("external");
    }

    @Test void refusesWrongPackageTypeAndLegacyBinaryExtensions() throws Exception {
        Path book = xlsx(1, 1);
        assertThatThrownBy(() -> extractor.extract("renamed.pptx", book)).isInstanceOf(UnextractableDocumentException.class);
        for (String ext : new String[]{"xls", "ppt", "doc"}) {
            assertThat(DocumentUploadPolicy.isAcceptedFilename("old." + ext)).isFalse();
            assertThatThrownBy(() -> extractor.extract("old." + ext, book)).isInstanceOf(UnextractableDocumentException.class);
        }
    }
}
