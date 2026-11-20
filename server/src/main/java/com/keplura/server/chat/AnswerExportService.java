package com.keplura.server.chat;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Antwort exportieren (Word/PDF) — mit POI und PDFBox, die über Tika bereits im
 * Projekt sind (keine neue Abhängigkeit). Markdown wird bewusst schlicht in
 * Blöcke übersetzt: Überschriften, Listen, Tabellen, Code, Absätze, **fett**.
 * Keine HTML-/Bild-Einbettung (kein externer Inhalt im Dokument).
 */
@Service
public class AnswerExportService {

    public record ExportInput(String question, String answer, List<Citation> citations, Instant createdAt,
                              String model) {
    }

    sealed interface Block permits Heading, Para, Bullet, Table, Code {
    }

    record Heading(int level, String text) implements Block {
    }

    record Para(String text) implements Block {
    }

    record Bullet(String text, boolean ordered, int number) implements Block {
    }

    record Table(List<List<String>> rows) implements Block {
    }

    record Code(String text) implements Block {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
            .withZone(ZoneId.of("Europe/Berlin"));
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    // ------------------------------------------------------------------ markdown → blocks

    static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        String[] lines = (md == null ? "" : md).replace("\r", "").split("\n", -1);
        StringBuilder para = new StringBuilder();
        List<List<String>> table = null;
        StringBuilder code = null;
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (code != null) {
                if (line.strip().startsWith("```")) {
                    out.add(new Code(code.toString()));
                    code = null;
                } else {
                    code.append(code.isEmpty() ? "" : "\n").append(raw);
                }
                continue;
            }
            if (line.strip().startsWith("```")) {
                flush(out, para);
                code = new StringBuilder();
                continue;
            }
            if (line.strip().startsWith("|") && line.strip().endsWith("|")) {
                flush(out, para);
                String inner = line.strip().substring(1, line.strip().length() - 1);
                if (inner.replace("|", "").replace("-", "").replace(":", "").isBlank()) continue; // separator row
                if (table == null) table = new ArrayList<>();
                List<String> cells = new ArrayList<>();
                for (String c : inner.split("\\|", -1)) cells.add(c.strip());
                table.add(cells);
                continue;
            } else if (table != null) {
                out.add(new Table(table));
                table = null;
            }
            String t = line.strip();
            if (t.isEmpty()) {
                flush(out, para);
                continue;
            }
            Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(t);
            Matcher ul = Pattern.compile("^[-*+]\\s+(.*)$").matcher(t);
            Matcher ol = Pattern.compile("^(\\d+)[.)]\\s+(.*)$").matcher(t);
            if (h.matches()) {
                flush(out, para);
                out.add(new Heading(h.group(1).length(), h.group(2)));
            } else if (ul.matches()) {
                flush(out, para);
                out.add(new Bullet(ul.group(1), false, 0));
            } else if (ol.matches()) {
                flush(out, para);
                out.add(new Bullet(ol.group(2), true, Integer.parseInt(ol.group(1))));
            } else {
                para.append(para.isEmpty() ? "" : " ").append(t);
            }
        }
        if (code != null) out.add(new Code(code.toString()));
        if (table != null) out.add(new Table(table));
        flush(out, para);
        return out;
    }

    private static void flush(List<Block> out, StringBuilder para) {
        if (!para.isEmpty()) {
            out.add(new Para(para.toString()));
            para.setLength(0);
        }
    }

    /** Removes inline markdown that has no meaning in plain text (links keep their label). */
    static String plain(String s) {
        return s.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1").replace("`", "").replace("**", "").replace("__", "");
    }

    static String sourceLine(int i, Citation c) {
        StringBuilder sb = new StringBuilder().append('[').append(i + 1).append("] ").append(c.filename());
        if (c.pageNumber() != null) sb.append(c.filename().toLowerCase(java.util.Locale.ROOT).endsWith(".pptx")
                ? ", Folie " : ", Seite ").append(c.pageNumber());
        if (c.sectionTitle() != null && !c.sectionTitle().isBlank()) sb.append(", Abschnitt „").append(c.sectionTitle()).append('“');
        else sb.append(", Abschnitt ").append(c.chunkIdx() + 1);
        return sb.toString();
    }

    // ------------------------------------------------------------------ Word

    public byte[] docx(ExportInput in) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph title = doc.createParagraph();
            XWPFRun tr = title.createRun();
            tr.setBold(true);
            tr.setFontSize(16);
            tr.setText(in.question() == null || in.question().isBlank() ? "Antwort aus Keplura" : in.question());
            XWPFParagraph meta = doc.createParagraph();
            XWPFRun mr = meta.createRun();
            mr.setFontSize(9);
            mr.setColor("777777");
            mr.setText("Keplura · " + WHEN.format(in.createdAt() == null ? Instant.now() : in.createdAt()));

            for (Block b : parse(in.answer())) {
                switch (b) {
                    case Heading h -> {
                        XWPFParagraph p = doc.createParagraph();
                        XWPFRun r = p.createRun();
                        r.setBold(true);
                        r.setFontSize(h.level() <= 1 ? 14 : h.level() == 2 ? 13 : 12);
                        r.setText(plain(h.text()));
                    }
                    case Para p -> richRuns(doc.createParagraph(), p.text());
                    case Bullet bl -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setIndentationLeft(360);
                        richRuns(p, (bl.ordered() ? bl.number() + ". " : "• ") + bl.text());
                    }
                    case Code c -> {
                        XWPFParagraph p = doc.createParagraph();
                        XWPFRun r = p.createRun();
                        r.setFontFamily("Consolas");
                        r.setFontSize(9);
                        String[] codeLines = c.text().split("\n", -1);
                        for (int i = 0; i < codeLines.length; i++) {
                            if (i > 0) r.addBreak();
                            r.setText(codeLines[i], i);
                        }
                    }
                    case Table t -> {
                        int cols = t.rows().stream().mapToInt(List::size).max().orElse(1);
                        XWPFTable table = doc.createTable(t.rows().size(), cols);
                        for (int r = 0; r < t.rows().size(); r++) {
                            for (int c = 0; c < cols; c++) {
                                String v = c < t.rows().get(r).size() ? plain(t.rows().get(r).get(c)) : "";
                                var cell = table.getRow(r).getCell(c);
                                cell.removeParagraph(0);
                                XWPFRun run = cell.addParagraph().createRun();
                                run.setBold(r == 0);
                                run.setText(v);
                            }
                        }
                    }
                }
            }
            if (in.citations() != null && !in.citations().isEmpty()) {
                XWPFRun h = doc.createParagraph().createRun();
                h.setBold(true);
                h.setText("Quellen");
                for (int i = 0; i < in.citations().size(); i++) {
                    XWPFRun r = doc.createParagraph().createRun();
                    r.setFontSize(10);
                    r.setText(sourceLine(i, in.citations().get(i)));
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void richRuns(XWPFParagraph p, String text) {
        String t = text.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1").replace("`", "");
        Matcher m = BOLD.matcher(t);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) p.createRun().setText(t.substring(last, m.start()));
            XWPFRun b = p.createRun();
            b.setBold(true);
            b.setText(m.group(1));
            last = m.end();
        }
        if (last < t.length()) p.createRun().setText(t.substring(last));
    }

    // ------------------------------------------------------------------ PDF

    public byte[] pdf(ExportInput in) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter w = new PdfWriter(doc);
            w.text(in.question() == null || in.question().isBlank() ? "Antwort aus Keplura" : in.question(), true, 15, 0);
            w.text("Keplura · " + WHEN.format(in.createdAt() == null ? Instant.now() : in.createdAt()), false, 8, 0);
            w.gap(6);
            for (Block b : parse(in.answer())) {
                switch (b) {
                    case Heading h -> {
                        w.gap(4);
                        w.text(plain(h.text()), true, h.level() <= 1 ? 13 : 12, 0);
                    }
                    case Para p -> w.text(plain(p.text()), false, 10.5f, 0);
                    case Bullet bl -> w.text((bl.ordered() ? bl.number() + ". " : "• ") + plain(bl.text()), false, 10.5f, 14);
                    case Code c -> {
                        for (String line : c.text().split("\n", -1)) w.text(line.isEmpty() ? " " : line, false, 9, 10);
                    }
                    case Table t -> {
                        for (int r = 0; r < t.rows().size(); r++) {
                            w.text(String.join("  |  ", t.rows().get(r).stream().map(AnswerExportService::plain).toList()),
                                    r == 0, 10, 6);
                        }
                    }
                }
                w.gap(3);
            }
            if (in.citations() != null && !in.citations().isEmpty()) {
                w.gap(6);
                w.text("Quellen", true, 11, 0);
                for (int i = 0; i < in.citations().size(); i++) w.text(sourceLine(i, in.citations().get(i)), false, 9.5f, 0);
            }
            w.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Minimal flowing-text writer with word wrap and page breaks (Helvetica, WinAnsi). */
    private static final class PdfWriter {
        private static final float MARGIN = 56;
        private final PDDocument doc;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream cs;
        private float y;
        private final float width = PDRectangle.A4.getWidth() - 2 * MARGIN;

        PdfWriter(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        void gap(float g) {
            y -= g;
        }

        void text(String raw, boolean isBold, float size, float indent) throws IOException {
            PDType1Font font = isBold ? bold : regular;
            String text = winAnsi(raw);
            float lineHeight = size * 1.35f;
            List<String> lines = wrap(text, font, size, width - indent);
            for (String line : lines) {
                if (y - lineHeight < MARGIN) newPage();
                y -= lineHeight;
                cs.beginText();
                cs.setFont(font, size);
                cs.newLineAtOffset(MARGIN + indent, y);
                cs.showText(line);
                cs.endText();
            }
        }

        private List<String> wrap(String text, PDType1Font font, float size, float max) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000 * size > max && !line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                // Long URLs, identifiers and table cells have no word boundary.
                // Split those within the printable area instead of clipping them.
                for (char ch : word.toCharArray()) {
                    if (!line.isEmpty() && font.getStringWidth(line.toString() + ch) / 1000 * size > max) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(ch);
                }
            }
            lines.add(line.toString());
            return lines;
        }

        /** Characters outside WinAnsi (Helvetica) become '?' instead of failing the export. */
        private String winAnsi(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (char ch : s.replace('\t', ' ').toCharArray()) {
                try {
                    regular.encode(String.valueOf(ch));
                    sb.append(ch);
                } catch (IllegalArgumentException | IOException e) {
                    sb.append(ch == '•' ? '-' : '?');
                }
            }
            return sb.toString();
        }

        void close() throws IOException {
            cs.close();
        }
    }
}
