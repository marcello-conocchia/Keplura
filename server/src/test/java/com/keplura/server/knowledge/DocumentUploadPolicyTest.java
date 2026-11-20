package com.keplura.server.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3c: the whitelist/sanitiser rules the upload endpoint leans on. The
 * sanitiser is a SECURITY control (display-name hygiene) even though storage
 * never uses its output as a path component — defence in depth demands these
 * cases are pinned: fake paths from multipart clients, traversal attempts,
 * exotic unicode and empty residue must either come out as ONE safe component
 * or be rejected outright.
 */
class DocumentUploadPolicyTest {

    private final DocumentUploadPolicy policy = new DocumentUploadPolicy(20L * 1024 * 1024);

    // ---- type whitelist ----

    @ParameterizedTest
    @ValueSource(strings = {"bericht.PDF", "notes.txt", "readme.md", "plan.docx", "daten.CSV", "budget.XLSX", "folien.pptx"})
    void whitelistedExtensionsMapToServerSideTypes(String name) {
        assertThat(policy.contentTypeOf(name)).isNotBlank();
    }

    @Test
    void txtExtensionServesUtf8PlainText() {
        assertThat(policy.contentTypeOf("a.txt")).isEqualTo("text/plain; charset=utf-8");
        assertThat(policy.contentTypeOf("a.csv")).isEqualTo("text/csv; charset=utf-8");
        assertThat(policy.contentTypeOf("a.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alt.xls", "alt.ppt", "alt.doc", "makro.xlsm", "makro.pptm", "evil.exe", "script.sh", "page.html", "noext", "zip.goldberg", // .zip not whitelisted
            "a.p df", "report.", "report", "x.", "a.pdf.txt.exe"})
    void everythingElseIsRejected(String name) {
        assertThatThrownBy(() -> policy.contentTypeOf(name))
                .as(name)
                .isInstanceOf(DocumentUploadPolicy.RejectedTypeException.class);
    }

    @Test
    void doubleExtensionUsesOnlyTheLastOne() {
        // "report.pdf.exe" -> extension "exe" -> rejected; the LAST dot rules
        assertThatThrownBy(() -> policy.contentTypeOf("report.pdf.exe"))
                .isInstanceOf(DocumentUploadPolicy.RejectedTypeException.class);
        // and the inverse order lands in the whitelist (the .txt is what we serve)
        assertThat(policy.contentTypeOf("malware.exe.txt"))
                .isEqualTo("text/plain; charset=utf-8");
    }

    // ---- filename sanitisation ----

    @Test
    void fakeWindowsAndUnixPathsCollapseToTheLeafName() {
        assertThat(DocumentUploadPolicy.sanitizeFilename("..\\..\\windows\\system32\\x.txt"))
                .isEqualTo("x.txt");
        assertThat(DocumentUploadPolicy.sanitizeFilename("/etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(DocumentUploadPolicy.sanitizeFilename("C:\\Users\\me\\Desktop\\a b.pdf"))
                .isEqualTo("a b.pdf");
    }

    @Test
    void traversalAndControlAndUnicodeNoiseIsStrippedNotInterpreted() {
        // traversal attempt: dots survive but as ONE flat component — and the
        // ".." alone is rejected (all-dots residue)
        assertThat(DocumentUploadPolicy.sanitizeFilename("a/../../b.txt")).isEqualTo("b.txt");
        assertThat(DocumentUploadPolicy.sanitizeFilename("..%2F..%2Fetc%2Fpasswd")).isEqualTo("..2F..2Fetc2Fpasswd");
        // NUL + control chars are dropped; the name stays safe
        assertThat(DocumentUploadPolicy.sanitizeFilename("pe\u0000pe.pdf")).isEqualTo("pepe.pdf");
        // letters of every script survive, emoji/symbols are stripped
        assertThat(DocumentUploadPolicy.sanitizeFilename("ünïcødé😀 file.txt")).isEqualTo("ünïcødé file.txt");
    }

    @Test
    void germanFilenamesKeepUmlauteAndCommonSeparators() {
        assertThat(DocumentUploadPolicy.sanitizeFilename("Angebot Müller.md")).isEqualTo("Angebot Müller.md");
        assertThat(DocumentUploadPolicy.sanitizeFilename("Größenübersicht Straße.xlsx"))
                .isEqualTo("Größenübersicht Straße.xlsx");
        assertThat(DocumentUploadPolicy.sanitizeFilename("Müller & Söhne (2), final+.pdf"))
                .isEqualTo("Müller & Söhne (2), final+.pdf");
        // macOS NFD ("u" + combining diaeresis) becomes one precomposed "ü"
        assertThat(DocumentUploadPolicy.sanitizeFilename("Müller.pdf")).isEqualTo("Müller.pdf");
    }

    @Test
    void headerAndDisplayAttacksStayImpossible() {
        // quotes, semicolons, CR/LF never survive (Content-Disposition injection)
        assertThat(DocumentUploadPolicy.sanitizeFilename("a\"; filename=evil.exe\r\nX: y.pdf"))
                .isEqualTo("a filenameevil.exeX y.pdf");
        // bidi override and zero-width characters (spoofed extensions) are dropped
        assertThat(DocumentUploadPolicy.sanitizeFilename("Rechnung‮fdp.exe")).isEqualTo("Rechnungfdp.exe");
        assertThat(DocumentUploadPolicy.sanitizeFilename("x​﻿y.pdf")).isEqualTo("xy.pdf");
    }

    @Test
    void downloadHeaderKeepsAsciiFormAndEncodesUmlaute() {
        assertThat(DocumentController.attachmentHeader("bericht.txt")).isEqualTo("attachment; filename=\"bericht.txt\"");
        assertThat(DocumentController.attachmentHeader("Angebot Müller.md"))
                .isEqualTo("attachment; filename=\"Angebot M_ller.md\"; filename*=UTF-8''Angebot%20M%C3%BCller.md");
        assertThat(DocumentController.attachmentHeader("Müller & Söhne (2).pdf"))
                .doesNotContain("\"Müller").contains("filename*=UTF-8''M%C3%BCller%20&%20S%C3%B6hne%20%282%29.pdf");
    }

    @Test
    void unusableResidueIsRejected() {
        assertThatThrownBy(() -> DocumentUploadPolicy.sanitizeFilename(null))
                .isInstanceOf(DocumentUploadPolicy.InvalidDocumentException.class);
        assertThatThrownBy(() -> DocumentUploadPolicy.sanitizeFilename("   "))
                .isInstanceOf(DocumentUploadPolicy.InvalidDocumentException.class);
        assertThatThrownBy(() -> DocumentUploadPolicy.sanitizeFilename("../../...."))
                .isInstanceOf(DocumentUploadPolicy.InvalidDocumentException.class);
        assertThatThrownBy(() -> DocumentUploadPolicy.sanitizeFilename("x".repeat(Document.MAX_FILENAME_LENGTH + 1) + ".pdf"))
                .isInstanceOf(DocumentUploadPolicy.InvalidDocumentException.class);
    }

    @Test
    void unicodeOnlyNamesReduceToTheirAsciiResidue() {
        // emoji/CJK contribute no safe characters; what remains must still be
        // a usable component (".pdf" keeps an extension), never a path
        assertThat(DocumentUploadPolicy.sanitizeFilename("😀😀.pdf")).isEqualTo(".pdf");
    }

    @Test
    void sanitizedNamesNeverContainSeparatorsOrDotDotComponents() {
        String nasty = "..\\..\\..%2f..%2fetc/passwd\u0000\u0001.pdf";
        String clean = DocumentUploadPolicy.sanitizeFilename(nasty);
        assertThat(clean).doesNotContain("/", "\\", "..");
        // the whitelist: only letters/digits of any script and [._ -(),&+] may ever survive
        assertThat(clean).matches("[\\p{L}\\p{N}._ \\-(),&+]+");
    }

    // ---- size limit configuration ----

    @Test
    void limitIsReadableFromConfiguration() {
        assertThat(new DocumentUploadPolicy(1024).maxBytes()).isEqualTo(1024);
    }
}
