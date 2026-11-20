package com.keplura.server.search;

import com.keplura.server.knowledge.Document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MVP-5 acceptance (c): pre-MVP-5 pdf/docx rows were stored with the
 * PARSER_PENDING marker and have NO chunk rows. The new API vocabulary has
 * no PENDING state — so the status projection must keep telling the truth
 * about historical rows: a pdf that claims INDEXED by type policy but has no
 * chunks answers NOT_INDEXED, never a fake "searchable". The chunk-existence
 * cross-check in {@link DocumentIndexingService#statusFor} is exactly what
 * this test pins (the wire tests cannot produce a chunk-less pdf without
 * deleting rows behind the service's back).
 */
@ExtendWith(MockitoExtension.class)
class DocumentIndexingStatusTest {

    @Mock
    private ChunkRepository chunkRepository;

    private DocumentIndexingService service() {
        // statusFor only touches the chunk cross-check — the remaining
        // collaborators are never invoked on this path.
        return new DocumentIndexingService(null, null, chunkRepository, null, null, 20_971_520L);
    }

    private static Document doc(String filename, long id) {
        Document d = Document.of(1L, filename, "application/pdf", 100L,
                "ab".repeat(32), "orgs/1/spaces/1/kbs/1/x.pdf", 7L);
        org.springframework.test.util.ReflectionTestUtils.setField(d, "id", id);
        return d;
    }

    @Test
    void legacyPdfRowWithoutChunksIsNotIndexed() {
        // pre-MVP-5 upload: no persisted marker, zero chunk rows
        when(chunkRepository.existsByDocumentId(42L)).thenReturn(false);
        assertThat(service().statusFor(doc("altgut.pdf", 42L)))
                .isEqualTo(DocumentIndexingService.IndexStatus.NOT_INDEXED);
    }

    @Test
    void currentPdfRowWithChunksIsIndexed() {
        // post-MVP-5 upload: same derivation, but chunks really exist now
        when(chunkRepository.existsByDocumentId(42L)).thenReturn(true);
        assertThat(service().statusFor(doc("neu.pdf", 42L)))
                .isEqualTo(DocumentIndexingService.IndexStatus.INDEXED);
    }

    @Test
    void noTextLayerMarkerWinsOverChunkAbsence() {
        // scanned pdf stored since MVP-5: persisted marker, no chunks — the
        // specific honest state, not the generic NOT_INDEXED.
        Document d = doc("scan.pdf", 42L);
        d.recordNoTextLayer();
        assertThat(service().statusFor(d))
                .isEqualTo(DocumentIndexingService.IndexStatus.NO_TEXT_LAYER);
    }

    @Test
    void blankTextDocumentReportsNotIndexed() {
        // txt with zero chunks (blank content): same cross-check applies to
        // text types, so the old any() stub shape is deliberately broad here.
        when(chunkRepository.existsByDocumentId(any())).thenReturn(false);
        Document d = Document.of(1L, "leer.txt", "text/plain", 0L,
                "ab".repeat(32), "orgs/1/spaces/1/kbs/1/x.txt", 7L);
        org.springframework.test.util.ReflectionTestUtils.setField(d, "id", 43L);
        assertThat(service().statusFor(d))
                .isEqualTo(DocumentIndexingService.IndexStatus.NOT_INDEXED);
    }
}
