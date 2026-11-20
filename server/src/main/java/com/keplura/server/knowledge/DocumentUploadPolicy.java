package com.keplura.server.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Upload input rules (M3c): extension whitelist, configured size limit and
 * display-filename sanitisation. Deliberately an INJECTABLE component with no
 * servlet coupling so every rule is unit-testable, and so the type decision
 * is made ONCE, from the extension the server controls — never from the
 * client-supplied multipart Content-Type header (trivially forgeable, e.g.
 * an .exe renamed to text/plain).
 *
 * <p><b>MVP whitelist (pdf/txt/md/docx/csv).</b> MIME sniffing, magic-byte
 * checks and virus scanning are conscious gaps for this milestone (task
 * Non-Goals; the download endpoint serves bytes back verbatim — tenant-
 * authenticated only). MVP-5 narrowed the practical risk: stored pdf/docx
 * are now genuinely PARSED at upload (Tika, resource-limited) and rejected
 * 400 if the bytes are not a real document of the claimed type — fake
 * magic-byte headers no longer survive indexing. Residual gap: a
 * well-formed but malicious file of an accepted type still passes (no
 * AV scanning yet); tracked with the security gate.
 */
@Component
public class DocumentUploadPolicy {

    /**
     * Extension → served MIME type. The extension must be one of these keys
     * (case-insensitive); the value is what the download endpoint reports —
     * the client part header is ignored entirely. docx/pdf/csv have no
     * charset; text types pin utf-8 so browsers never guess latin-1.
     */
    static final Map<String, String> ALLOWED_TYPES = Map.of(
            "pdf", "application/pdf",
            "txt", "text/plain; charset=utf-8",
            "md", "text/markdown; charset=utf-8",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "csv", "text/csv; charset=utf-8");

    /** Fallback when the whitelist is widened without extending this map. */
    static final String GENERIC_CONTENT_TYPE = "application/octet-stream";

    /** Rejected before anything is read from the stream. */
    public static class RejectedTypeException extends RuntimeException {
        public RejectedTypeException(String message) {
            super(message);
        }
    }

    /** 413 — the byte count (or multipart limit) exceeded keplura.upload.max-size-bytes. */
    public static class TooLargeException extends RuntimeException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /** 400 — no usable display name left after sanitising (or the size is zero). */
    public static class InvalidDocumentException extends RuntimeException {
        public InvalidDocumentException(String message) {
            super(message);
        }
    }

    private final long maxBytes;

    public DocumentUploadPolicy(@Value("${keplura.upload.max-size-bytes:20971520}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Returns the MIME type to serve for {@code rawFilename}, or throws
     * {@link RejectedTypeException}. A filename must contain at least one dot
     * with a non-empty extension made of ascii alphanumerics — anything else
     * ("report", "file.exe", "a.p df", names with NUL/control chars) is
     * rejected, so no exotic filename can slip into the whitelist.
     */
    /** ADR-0012: true when the extension is one Keplura accepts (data-source pre-filter). */
    public static boolean isAcceptedFilename(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot > 0 && ALLOWED_TYPES.containsKey(filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    public String contentTypeOf(String rawFilename) {
        String ext = extensionOf(rawFilename);
        String type = ALLOWED_TYPES.get(ext);
        if (type == null) {
            throw new RejectedTypeException("file type ." + ext + " is not accepted");
        }
        return type;
    }

    /** Lowercase ascii-alnum extension WITHOUT the dot, or a rejection. */
    static String extensionOf(String rawFilename) {
        String name = sanitizeFilename(rawFilename); // same parse rules, reused
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new RejectedTypeException("filename has no usable extension");
        }
        // case-insensitive whitelist match ("bericht.PDF" is a PDF)
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Reduces a client filename to a single safe DISPLAY component. Multipart
     * parts have historically carried whole fake paths ("..\..\windows\x",
     * "/etc/passwd", backslash and slash mixed), so: take only the segment
     * after the LAST slash/backslash, strip every character that is not
     * [A-Za-z0-9._ -], collapse the rest — then reject if what remains is
     * empty, all-dots, or longer than {@link Document#MAX_FILENAME_LENGTH}.
     * The result can therefore never contain a path separator, a ".."
     * traversal component, a control byte, or a NUL.
     *
     * <p>This value is metadata for humans only. It is NEVER used to build a
     * storage path — {@link DocumentStorage} derives the on-disk name from a
     * server-side UUID (defence in depth: even a sanitiser bug cannot turn
     * into a traversal, because the sanitised name never reaches the
     * filesystem as a path component).
     */
    /**
     * Letters and digits of every script (Umlaute, ß, é …) plus a few harmless separators.
     * Never allowed: path separators, control/format characters (e.g. bidi overrides),
     * quotes, semicolons and other header/shell metacharacters, emoji and symbols.
     */
    static boolean allowedInFilename(int cp) {
        if (Character.isLetterOrDigit(cp)) return true;
        return cp == '.' || cp == '_' || cp == '-' || cp == ' ' || cp == '(' || cp == ')'
                || cp == ',' || cp == '&' || cp == '+';
    }

    public static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidDocumentException("filename is required");
        }
        // NFC first: macOS sends "ü" as "u" + combining diaeresis (NFD).
        String base = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC);
        int cut = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (cut >= 0) {
            base = base.substring(cut + 1);
        }
        StringBuilder sb = new StringBuilder(base.length());
        base.codePoints().filter(DocumentUploadPolicy::allowedInFilename).forEach(sb::appendCodePoint);
        String clean = sb.toString().strip();
        if (clean.isEmpty() || clean.replace(".", "").isEmpty()) {
            throw new InvalidDocumentException("filename is not usable");
        }
        if (clean.length() > Document.MAX_FILENAME_LENGTH) {
            throw new InvalidDocumentException("filename is too long");
        }
        return clean;
    }
}
