package com.keplura.server.knowledge;

import com.keplura.server.search.Chunk;
import com.keplura.server.search.ChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;

/** Read already-indexed text only; never render active document bytes in the app. */
@Service
public class DocumentPreviewService {
    public record Block(int chunkIdx, String text, Integer pageNumber, String sectionTitle) { }
    public record Preview(Long documentId, String filename, String contentType, String indexStatus,
                          int selectedChunkIdx, boolean exactLocation, boolean hasPrevious, boolean hasNext,
                          List<Block> blocks, java.time.Instant sourceUpdatedAt) { }
    private final DocumentService documents;
    private final ChunkRepository chunks;
    public DocumentPreviewService(DocumentService documents, ChunkRepository chunks) {
        this.documents=documents; this.chunks=chunks;
    }

    @Transactional(readOnly=true)
    public Preview preview(Long userId, Long orgId, Long spaceId, Long kbId, Long documentId,
                           int selected, Integer expectedOffset, Integer expectedPage) {
        if (selected<0 || expectedOffset!=null && expectedOffset<0 || expectedPage!=null && expectedPage<1) {
            throw new IllegalArgumentException("invalid source position");
        }
        // Proves the complete chain and that the original still exists inside
        // the upload tree. The following query independently re-pins the chain.
        var document=documents.download(userId,orgId,spaceId,kbId,documentId);
        if (!document.indexStatus().name().equals("INDEXED")) {
            return new Preview(documentId,document.filename(),document.contentType(),document.indexStatus().name(),
                    selected,false,false,false,List.of(),document.sourceUpdatedAt());
        }
        int first=Math.max(0,selected-10);
        int last=(int)Math.min(Integer.MAX_VALUE,(long)selected+10);
        var window=chunks.findPreviewWindow(documentId,kbId,spaceId,orgId,userId,first,
                last==Integer.MAX_VALUE ? last : last+1);
        Chunk target=window.stream().filter(c->c.getChunkIdx()==selected).findFirst()
                .orElseThrow(DocumentService.DocumentNotFoundException::new);
        if (expectedOffset!=null && !Objects.equals(expectedOffset,target.getStartOffset())
                || expectedPage!=null && !Objects.equals(expectedPage,target.getPageNumber())) {
            throw new DocumentService.DocumentNotFoundException();
        }
        boolean hasNext=window.stream().anyMatch(c->c.getChunkIdx()>last);
        var blocks=blocksFor(window.stream().filter(c->c.getChunkIdx()<=last).toList(),selected);
        return new Preview(documentId,document.filename(),document.contentType(),document.indexStatus().name(),
                selected,target.getStartOffset()!=null,first>0,hasNext,blocks,document.sourceUpdatedAt());
    }

    /** Remove indexing overlap from the reader while keeping the cited block whole. */
    static List<Block> blocksFor(List<Chunk> rows,int selected) {
        var blocks=new java.util.ArrayList<Block>();
        Chunk previous=null;
        for (Chunk row:rows) {
            String text=row.getText();
            if (previous!=null && previous.getEndOffset()!=null && row.getStartOffset()!=null) {
                int overlap=previous.getEndOffset()-row.getStartOffset();
                if (overlap>0 && overlap<=text.length() && overlap<=previous.getText().length()
                        && previous.getText().endsWith(text.substring(0,overlap))) {
                    if (row.getChunkIdx()==selected) {
                        Block last=blocks.getLast();
                        blocks.set(blocks.size()-1,new Block(last.chunkIdx(),
                                last.text().substring(0,Math.max(0,last.text().length()-overlap)),
                                last.pageNumber(),last.sectionTitle()));
                    } else text=text.substring(overlap);
                }
            }
            blocks.add(new Block(row.getChunkIdx(),text,row.getPageNumber(),row.getSectionTitle()));
            previous=row;
        }
        return blocks.stream().filter(b->!b.text().isBlank() || b.chunkIdx()==selected).toList();
    }
}
