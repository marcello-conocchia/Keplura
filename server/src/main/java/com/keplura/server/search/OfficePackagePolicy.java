package com.keplura.server.search;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.util.XMLHelper;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.nio.file.Path;
import java.util.Locale;

import static com.keplura.server.search.TikaDocumentTextExtractor.*;

/** Structural streaming preflight, inside the same parser pool/time budget and POI zip gates. */
final class OfficePackagePolicy {
    static final int MAX_SLIDES = 200;
    static final int MAX_SHEETS = 50;
    static final int MAX_CELLS = 200_000;

    private OfficePackagePolicy() { }

    static int check(String extension, Path file) throws Exception {
        String mainType = switch (extension) {
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
            default -> throw new IllegalArgumentException("not an Office type");
        };
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            rejectExternal(pkg.getRelationships());
            var main = pkg.getPartsByContentType(mainType);
            var roots = pkg.getRelationshipsByType(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument");
            if (main.size() != 1 || roots.size() != 1
                    || !pkg.getPart(roots.getRelationship(0)).equals(main.getFirst())) {
                throw new UnextractableDocumentException("Office package does not match its extension");
            }
            int[] counts = new int[2]; // sheets/slides, stored cells (including blank/formatted cells)
            for (PackagePart part : pkg.getParts()) {
                String type = part.getContentType().toLowerCase(Locale.ROOT);
                if (type.contains("macroenabled") || type.contains("vba")
                        || type.contains("macrosheet")) {
                    throw new UnextractableDocumentException("Office macros are not supported");
                }
                if (!part.isRelationshipPart()) rejectExternal(part.getRelationships());
                boolean primary = part.equals(main.getFirst());
                boolean worksheet = type.equals(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml");
                if (!primary && !worksheet) continue;
                var reader = XMLHelper.newXMLReader(); // hardened: no DTDs/entities/external fetches
                reader.setContentHandler(new DefaultHandler() {
                    @Override
                    public void startElement(String uri, String local, String qName, Attributes atts) {
                        if (primary && ((extension.equals("xlsx") && local.equals("sheet"))
                                || (extension.equals("pptx") && local.equals("sldId")))) {
                            if (++counts[0] > (extension.equals("xlsx") ? MAX_SHEETS : MAX_SLIDES)) {
                                throw new TruncatedExtractionException(extension.equals("xlsx")
                                        ? "xlsx exceeds the sheet limit (50)" : "pptx exceeds the slide limit (200)");
                            }
                        }
                        if (worksheet && local.equals("c") && ++counts[1] > MAX_CELLS) {
                            throw new TruncatedExtractionException("xlsx exceeds the cell limit (200000)");
                        }
                    }
                });
                try (var in = part.getInputStream()) {
                    reader.parse(new InputSource(in));
                }
            }
            return counts[0];
        }
    }

    /**
     * Hyperlinks are plain link targets: neither POI nor Tika ever fetch them, and almost every
     * real presentation/workbook contains some. Every OTHER external relationship (linked OLE
     * objects, external data connections, remote images, attached templates) stays refused.
     */
    static final String HYPERLINK = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";

    private static void rejectExternal(PackageRelationshipCollection relationships) {
        for (var rel : relationships) {
            if (rel.getTargetMode() == TargetMode.EXTERNAL && !HYPERLINK.equals(rel.getRelationshipType())) {
                throw new UnextractableDocumentException("external Office references are not supported");
            }
        }
    }
}
