package com.keplura.server.chat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Export: Word/PDF entstehen auch mit Umlauten, Tabellen, Listen, Code und Zeichen außerhalb von WinAnsi. */
class AnswerExportServiceTest {

    private static final String MD = """
            # Reisekosten
            Hotels werden **bis 120 €** pro Nacht erstattet [1].

            - Belege binnen 14 Tagen
            1. Antrag stellen

            | Posten | Grenze |
            |---|---|
            | Hotel | 120 € |

            ```
            beispiel = "äöüß"
            ```
            Emoji 🙂 und 中文 dürfen den Export nicht abbrechen.
            """;

    private final AnswerExportService.ExportInput input = new AnswerExportService.ExportInput(
            "Bis zu welchem Betrag werden Hotels erstattet?", MD,
            List.of(new Citation(1L, "Reisekostenrichtlinie.pdf", 2, 0.9, 3, "Übernachtung", 0, 10)),
            Instant.parse("2026-11-14T10:00:00Z"), "qwen2.5:7b");

    private final AnswerExportService service = new AnswerExportService();

    @Test
    void parsesBlocks() {
        var blocks = AnswerExportService.parse(MD);
        assertThat(blocks).anyMatch(b -> b instanceof AnswerExportService.Heading);
        assertThat(blocks).anyMatch(b -> b instanceof AnswerExportService.Table t && t.rows().size() == 2);
        assertThat(blocks).anyMatch(b -> b instanceof AnswerExportService.Code);
        assertThat(blocks).filteredOn(b -> b instanceof AnswerExportService.Bullet).hasSize(2);
    }

    @Test
    void docxIsAZipWithContent() throws Exception {
        byte[] docx = service.docx(input);
        assertThat(docx.length).isGreaterThan(1000);
        assertThat(new String(docx, 0, 2)).isEqualTo("PK");
    }

    @Test
    void pdfIsGeneratedDespiteNonWinAnsiCharacters() throws Exception {
        byte[] pdf = service.pdf(input);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void sourceLineNamesPhysicalSlide() {
        assertThat(AnswerExportService.sourceLine(0,
                new Citation(1L, "Vortrag.PPTX", 4, 0.9, 3, "Folie 3", 100, 200)))
                .contains(", Folie 3").doesNotContain("Seite");
    }

    @Test
    void sourceLineMentionsPageAndSection() {
        assertThat(AnswerExportService.sourceLine(0, input.citations().get(0)))
                .isEqualTo("[1] Reisekostenrichtlinie.pdf, Seite 3, Abschnitt „Übernachtung“");
    }

    @Test
    void longTableCellsWrapAcrossPagesWithoutClippingAndKeepGermanText() throws Exception {
        String markdown = "# Überprüfung\n\n| Größe | Inhalt |\n|---|---|\n"
                + ("| ÄÖÜ äöü ß € | " + "UngetrenntePrüfnummer".repeat(60) + " |\n").repeat(12);
        byte[] bytes = service.pdf(new AnswerExportService.ExportInput("Überprüfung", markdown,
                input.citations(), input.createdAt(), input.model()));
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
            var stripper = new org.apache.pdfbox.text.PDFTextStripper() {
                @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition text) {
                    assertThat(text.getXDirAdj() + text.getWidthDirAdj()).isLessThan(540f);
                    super.processTextPosition(text);
                }
            };
            String text = stripper.getText(pdf);
            assertThat(text).contains("ÄÖÜ äöü ß €", "Quellen", "Reisekostenrichtlinie.pdf");
        }
        java.nio.file.Files.write(java.nio.file.Path.of("target/export-review.pdf"), bytes);
    }
}
