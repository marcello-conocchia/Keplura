package com.keplura.server.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3d: chunking boundary behaviour — exact multiples, overlap math, surrogate
 * safety, blank input, and the config invariant (overlap &lt; size). The
 * chunker must be deterministic: the API tests rely on identical text
 * producing identical chunk splits.
 */
class DocumentChunkerTest {

    @Test
    void headingsAndPdfPagesRemainSeparateWithExactSourceOffsets() {
        String text = "# Urlaub\nUrlaub wird im Team geplant.\n\f# Leitwarte\nDie Schwelle beträgt 42 Prozent.";
        var chunks = new DocumentChunker(800, 100).chunk(text, true);
        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(ChunkText::pageNumber).containsExactly(1, 2);
        assertThat(chunks).extracting(ChunkText::sectionTitle).containsExactly("Urlaub", "Leitwarte");
        assertThat(chunks).allSatisfy(c ->
                assertThat(text.substring(c.startOffset(), c.endOffset())).isEqualTo(c.text()));
        assertThat(new DocumentChunker(800, 100).chunk(text)).allSatisfy(c -> assertThat(c.pageNumber()).isNull());
    }

    @Test
    void longProseUsesParagraphBoundaryInsteadOfCuttingNextParagraph() {
        String text = "a".repeat(50) + "\n\n" + "b".repeat(70);
        var chunks = new DocumentChunker(80, 10).chunk(text);
        assertThat(chunks.getFirst().text()).isEqualTo("a".repeat(50) + "\n\n");
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.text().length()).isLessThanOrEqualTo(80);
            assertThat(text.substring(c.startOffset(), c.endOffset())).isEqualTo(c.text());
        });
    }

    @Test
    void textShorterThanWindowIsOneChunk() {
        DocumentChunker chunker = new DocumentChunker(800, 100);
        List<ChunkText> chunks = chunker.chunk("kurzer text");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("kurzer text");
        assertThat(chunks.get(0).chunkIdx()).isZero();
    }

    @Test
    void exactWindowLengthIsOneChunkNoOverlapTail() {
        DocumentChunker chunker = new DocumentChunker(10, 3);
        String text = "0123456789"; // exactly size
        List<ChunkText> chunks = chunker.chunk(text);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(text);
    }

    @Test
    void windowsStepBySizeMinusOverlapAndCarryTheOverlapForward() {
        DocumentChunker chunker = new DocumentChunker(10, 3); // step 7
        String text = "0123456789ABCDEFGHI"; // 19 chars
        List<ChunkText> chunks = chunker.chunk(text);

        assertThat(chunks).extracting(ChunkText::chunkIdx).containsExactly(0, 1, 2);
        assertThat(chunks.get(0).text()).isEqualTo("0123456789"); // pos 0..10
        assertThat(chunks.get(1).text()).isEqualTo("789ABCDEFG");  // pos 7..17
        assertThat(chunks.get(2).text()).isEqualTo("EFGHI");       // pos 14..19 (tail)
        // consecutive chunks share exactly the overlap (3 chars): "789" / "EFG"
        assertThat(chunks.get(0).text().endsWith(chunks.get(1).text().substring(0, 3))).isTrue();
        assertThat(chunks.get(1).text().endsWith(chunks.get(2).text().substring(0, 3))).isTrue();
    }

    @Test
    void longTextNeverExceedsWindowAndCoversEveryCharacterAtLeastOnce() {
        DocumentChunker chunker = new DocumentChunker(800, 100);
        String text = "x".repeat(5000);
        List<ChunkText> chunks = chunker.chunk(text);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(800));
        // full coverage: first chunk starts at position 0, the LAST chunk ends
        // at the text tail (it is a real suffix), and indices count up
        assertThat(chunks.get(0).text().length()).isEqualTo(800);
        ChunkText last = chunks.get(chunks.size() - 1);
        assertThat(text.endsWith(last.text())).isTrue();
        assertThat(chunks).extracting(ChunkText::chunkIdx)
                .containsExactly(java.util.stream.IntStream.range(0, chunks.size()).boxed().toArray(Integer[]::new));
        long covered = chunks.stream().mapToLong(c -> c.text().length()).sum();
        assertThat(covered).isGreaterThanOrEqualTo(text.length()); // every char seen
    }

    @Test
    void surrogatePairsAreNeverSplitAtTheWindowEdge() {
        // emoji = 2 code units; a 3-unit window would cut "😀" after its HIGH
        // surrogate — the edge check must back off, and the NEXT window (which
        // starts on the orphan LOW surrogate) must skip it instead of emitting
        // invalid UTF-16 at its head.
        DocumentChunker chunker = new DocumentChunker(3, 0);
        String text = "ab😀cd"; // a b 😀(2 units) c d = 6 code units
        List<ChunkText> chunks = chunker.chunk(text);
        assertThat(chunks).extracting(ChunkText::text).containsExactly("ab", "cd");
        assertThat(chunks).allSatisfy(c -> {
            String t = c.text();
            assertThat(Character.isLowSurrogate(t.charAt(0))).as("head of %s", t).isFalse();
            assertThat(Character.isHighSurrogate(t.charAt(t.length() - 1))).as("tail of %s", t).isFalse();
        });
    }

    @Test
    void blankAndNullInputProduceNoChunks() {
        DocumentChunker chunker = new DocumentChunker(800, 100);
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n\t ")).isEmpty();
    }

    @Test
    void chunkingIsDeterministic() {
        DocumentChunker a = new DocumentChunker(50, 10);
        DocumentChunker b = new DocumentChunker(50, 10);
        String text = "Keplura".repeat(100);
        assertThat(a.chunk(text)).isEqualTo(b.chunk(text));
    }

    @Test
    void invalidConfigsFailFastAtConstruction() {
        assertThatThrownBy(() -> new DocumentChunker(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentChunker(100, 100)) // overlap must be < size
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentChunker(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void windowLargerThanTheChunkTextColumnFailsTheBoot() {
        // V4 stores chunk_text varchar(4000); a wider window would boot fine
        // and then 500 on EVERY txt upload at the DB wall — so it must fail
        // at construction like the other config guards (review round 2).
        assertThatThrownBy(() -> new DocumentChunker(Chunk.MAX_TEXT_LENGTH + 1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Chunk.MAX_TEXT_LENGTH));
        // exactly at the column bound is still valid
        assertThat(new DocumentChunker(Chunk.MAX_TEXT_LENGTH, 0).sizeChars())
                .isEqualTo(Chunk.MAX_TEXT_LENGTH);
    }
}
