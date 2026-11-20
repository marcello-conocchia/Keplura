package com.keplura.server.knowledge;

import com.keplura.server.search.Chunk;
import com.keplura.server.search.ChunkText;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentPreviewServiceTest {
    private List<Chunk> overlapping() {
        return List.of(
            Chunk.of(1L,1L,0,"abcdefgh","[]").sourceLocation(new ChunkText(0,"abcdefgh",2,"Handbuch",0,8)),
            Chunk.of(1L,1L,1,"fghijkl","[]").sourceLocation(new ChunkText(1,"fghijkl",2,"Handbuch",5,12)));
    }
    @Test
    void readerRemovesOverlapBeforeTheSelectedPassageWithoutLosingSourceText() {
        var blocks=DocumentPreviewService.blocksFor(overlapping(),1);
        assertThat(blocks).extracting(DocumentPreviewService.Block::text).containsExactly("abcde","fghijkl");
        assertThat(blocks.getLast().pageNumber()).isEqualTo(2);
    }
    @Test
    void readerRemovesOverlapAfterTheSelectedPassageAndLeavesItWhole() {
        var blocks=DocumentPreviewService.blocksFor(overlapping(),0);
        assertThat(blocks).extracting(DocumentPreviewService.Block::text).containsExactly("abcdefgh","ijkl");
    }
}
