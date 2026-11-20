package com.keplura.server.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Section-aware chunker with overlap — deterministic (same input = same chunks, byte for byte; a
 * re-index can therefore never produce drift against stored embeddings).
 *
 * <p>Algorithm: fixed window of {@code sizeChars} characters, advanced by
 * {@code sizeChars - overlapChars} each step, so consecutive chunks share the
 * trailing/leading {@code overlapChars} — an answer spanning a cut stays
 * retrievable from at least one side. Headings and real PDF page separators
 * establish independent sections; windows prefer paragraph boundaries.
 * Windows split on UTF-16 code-unit
 * boundaries only where safe: a surrogate pair is never torn in half (the
 * window end steps back by one). Source offsets refer to the canonical
 * extracted text; a page is assigned only for PDF extraction with real page boundaries.
 *
 * <p>Config invariants (validated at construction, so a misconfiguration
 * fails the boot instead of looping forever or smuggling oversized rows):
 * overlap &lt; size, and size &le; {@link Chunk#MAX_TEXT_LENGTH} — the
 * portable {@code chunk_text varchar(4000)} wall (V4) — so a config drift can
 * never boot fine and then 500 on every text upload at the column bound.
 */
@Component
public class DocumentChunker {

    private final int sizeChars;
    private final int overlapChars;

    public DocumentChunker(
            @org.springframework.beans.factory.annotation.Value("${keplura.search.chunk-size-chars:800}") int sizeChars,
            @org.springframework.beans.factory.annotation.Value("${keplura.search.chunk-overlap-chars:100}") int overlapChars) {
        if (sizeChars <= 0) {
            throw new IllegalArgumentException("chunk size must be > 0");
        }
        if (sizeChars > Chunk.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("chunk size must be <= " + Chunk.MAX_TEXT_LENGTH
                    + " (kb_chunk.chunk_text column bound — a wider window would overflow the schema)");
        }
        if (overlapChars < 0 || overlapChars >= sizeChars) {
            throw new IllegalArgumentException("chunk overlap must be in [0, size)");
        }
        this.sizeChars = sizeChars;
        this.overlapChars = overlapChars;
    }

    public int sizeChars() {
        return sizeChars;
    }

    public int overlapChars() {
        return overlapChars;
    }

    /**
     * Split {@code text} into overlapping chunks. Empty/blank input yields an
     * empty list (nothing to index); a text of at most one window yields a
     * single chunk. Chunks are NEVER longer than {@code sizeChars}.
     */
    public List<ChunkText> chunk(String text) {
        return chunk(text, false);
    }

    /** Sections and PDF pages never share a chunk; long prose ends at a paragraph when possible. */
    public List<ChunkText> chunk(String text, boolean pdfPages) {
        List<ChunkText> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        var boundaries = java.util.regex.Pattern.compile("(?m)(?:^|(?<=\\f))#{1,6}\\h+[^\\r\\n\\f]+|\\f").matcher(text);
        int start = 0;
        int page = 1;
        String section = null;
        while (boundaries.find()) {
            if (boundaries.start() > start) {
                appendSection(out, text, start, boundaries.start(), pdfPages ? page : null, section);
            }
            if (boundaries.group().equals("\f")) {
                page++;
                start = boundaries.end();
            } else {
                section = boundaries.group().replaceFirst("^#{1,6}\\h+", "").strip();
                if (section.length() > 512) section = section.substring(0, 512);
                start = boundaries.start();
            }
        }
        appendSection(out, text, start, text.length(), pdfPages ? page : null, section);
        return out;
    }

    private void appendSection(List<ChunkText> out, String text, int start, int len,
                               Integer page, String section) {
        int step = sizeChars - overlapChars; // > 0 by the constructor invariant
        int pos = start;
        while (pos < len) {
            // if the previous window backed off a pair boundary, the next
            // window must not START with the low surrogate: skip it (losing
            // the already-indexed pair tail is strictly better than emitting
            // a chunk with invalid UTF-16 at its head)
            if (pos > start && Character.isLowSurrogate(text.charAt(pos))) {
                pos++;
                if (pos >= len) {
                    break;
                }
            }
            int end = Math.min(pos + sizeChars, len);
            if (end < len) {
                int paragraphEnd = text.lastIndexOf("\n\n", end - 2);
                if (paragraphEnd >= pos + sizeChars / 2) end = paragraphEnd + 2;
            }
            // never split a surrogate pair at the window edge — but only when
            // backing off actually shrinks the window (end > pos + 1), else a
            // pair would never fit any window and the loop would stall
            if (end < len && end > pos + 1 && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            String snippet = text.substring(pos, end);
            if (!snippet.isBlank()) out.add(new ChunkText(out.size(), snippet, page, section, pos, end));
            if (end >= len) {
                break; // this chunk covered the tail; overlap adds nothing
            }
            // Use the actual structural cut; preserve the legacy surrogate
            // fallback for an unbroken paragraph and very small windows.
            pos = end < Math.min(pos + sizeChars, len) && !Character.isHighSurrogate(text.charAt(end))
                    ? Math.max(pos + 1, end - overlapChars) : pos + step;
        }
    }
}
