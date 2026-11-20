package com.keplura.server.search;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * SELF-GENERATED binary document fixtures (MVP-5) — built at test runtime
 * with the parsers' own writer stacks (PDFBox / POI). No foreign sample
 * files enter the repo, and every fixture is reproducible from source.
 */
public final class TestDocuments {

    public static byte[] sampleXlsx() throws IOException {
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = new ByteArrayOutputStream()) {
            var row = book.createSheet("Kostenplanung").createRow(0);
            row.createCell(0).setCellValue("Prueffrist");
            row.createCell(1).setCellValue(53);
            book.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] samplePptx() throws IOException {
        try (var deck = new org.apache.poi.xslf.usermodel.XMLSlideShow();
             var out = new ByteArrayOutputStream()) {
            deck.createSlide();
            deck.createSlide();
            var slide = deck.createSlide();
            slide.createTextBox().setText("Bauplaene Prueffrist 53 Tage");
            deck.getNotesSlide(slide).createTextBox().setText("Sprechernotiz zur Freigabe");
            deck.write(out);
            return out.toByteArray();
        }
    }

    private TestDocuments() {
    }

    public static final String PDF_PHRASE =
            "Betriebstemperatur der Turbine Delta liegt bei 72 Grad Celsius";

    public static final String DOCX_PHRASE =
            "Kostenplanung Werksniederlassung Nord insgesamt 480000 Euro";

    /** One-page PDF drawing {@link #PDF_PHRASE} in Helvetica. */
    public static byte[] samplePdf() throws IOException {
        return pdf(1, PDF_PHRASE);
    }

    /** One-page PDF drawing {@code phrase}. */
    public static byte[] pdf(String phrase) throws IOException {
        return pdf(1, phrase);
    }

    /** {@code pages}-page PDF; page i carries text {@code phrase} + page number. */
    public static byte[] pdf(int pages, String phrase) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 10);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(phrase + " Seite " + i);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** PDF with real pages but NO text at all (draws shapes only) = a scan. */
    public static byte[] pdfWithoutTextLayer() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.addRect(10, 10, 200, 300);
                cs.fill();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Real DOCX (OPC package) with one paragraph carrying {@link #DOCX_PHRASE}. */
    public static byte[] sampleDocx() throws Exception {
        return docx(DOCX_PHRASE, null);
    }

    /**
     * DOCX with an optional embedded text resource (OOXML embedded package)
     * — used to prove the reject-all extractor never follows embedded parts.
     */
    public static byte[] docx(String text, byte[] embeddedTxt) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            if (embeddedTxt != null) {
                org.apache.poi.openxml4j.opc.OPCPackage pkg = doc.getPackage();
                var part = pkg.createPart(
                        org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName("/embedded/notes.txt"),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.embeddedText");
                try (java.io.OutputStream os = part.getOutputStream()) {
                    os.write(embeddedTxt);
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    /**
     * DOCX with {@code extraParts} dummy OPC parts on top of the normal
     * package — the zip entry-count probe for the POI ZipSecureFile gate
     * (a real "many files in one archive" bomb shape, still a valid docx).
     */
    public static byte[] docxWithEntryCapProbe(String text, int extraParts) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            for (int i = 0; i < extraParts; i++) {
                var part = doc.getPackage().createPart(
                        org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName(
                                "/probe/part" + i + ".bin"),
                        "application/octet-stream");
                try (java.io.OutputStream os = part.getOutputStream()) {
                    os.write(new byte[]{ 'x' });
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** Sanity check for the generators themselves: page count via PDFBox. */
    public static int pdfPageCount(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return doc.getNumberOfPages();
        }
    }
}
