package com.keplura.server.knowledge;

import com.keplura.server.org.Membership;
import com.keplura.server.org.MembershipRepository;
import com.keplura.server.org.MembershipRole;
import com.keplura.server.space.Space;
import com.keplura.server.space.SpaceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M3c: unit-level chain logic of {@link DocumentService} — guards short-
 * circuit in order (membership → space → kb), the policy runs BEFORE any
 * storage write, and the DB row is only saved after storage succeeded. The
 * upload roundtrip over real HTTP lives in DocumentApiIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private KnowledgeBaseRepository kbRepository;

    @Mock
    private SpaceRepository spaceRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private DocumentStorage storage;

    @Mock
    private com.keplura.server.search.DocumentIndexingService indexingService;

    private DocumentUploadPolicy policy;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        policy = new DocumentUploadPolicy(100L); // tiny limit for branch testing
        service = new DocumentService(documentRepository, kbRepository, spaceRepository,
                membershipRepository, policy, storage, indexingService);
    }

    private void givenChainHolds() {
        when(membershipRepository.findByOrgIdAndUserId(3L, 9L))
                .thenReturn(Optional.of(Membership.grant(3L, 9L, MembershipRole.MEMBER)));
        Space space = Space.create(3L, "S");
        ReflectionTestUtils.setField(space, "id", 7L);
        when(spaceRepository.findByIdInOrgForMember(7L, 3L, 9L)).thenReturn(Optional.of(space));
        KnowledgeBase kb = KnowledgeBase.create(7L, "KB");
        ReflectionTestUtils.setField(kb, "id", 12L);
        when(kbRepository.findByIdInSpaceForMember(12L, 7L, 3L, 9L)).thenReturn(Optional.of(kb));
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadStoresThenPersistsRowFromComputedValues() {
        givenChainHolds();
        when(storage.store(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenAnswer(inv ->
                new DocumentStorage.Stored("orgs/3/spaces/7/kbs/12/uuid-1", 42L, "ab".repeat(32)));
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", 99L);
            return d;
        });

        Document doc = service.upload(9L, 3L, 7L, 12L, "bericht.pdf", 42L, bytes("x"));

        assertThat(doc.getId()).isEqualTo(99L);
        assertThat(doc.getKbId()).isEqualTo(12L);
        assertThat(doc.getFilename()).isEqualTo("bericht.pdf");
        assertThat(doc.getContentType()).isEqualTo("application/pdf");
        assertThat(doc.getSizeBytes()).isEqualTo(42L);
        assertThat(doc.getSha256()).isEqualTo("ab".repeat(32));
        assertThat(doc.getStoredPath()).isEqualTo("orgs/3/spaces/7/kbs/12/uuid-1");
        assertThat(doc.getUploadedBy()).isEqualTo(9L);
        // M3d: indexing runs for the flushed row inside the same upload flow
        verify(indexingService).index(any(Document.class), any());
    }

    @Test
    void nonMemberUploadWritesNothingToDiskAndSavesNothing() {
        when(membershipRepository.findByOrgIdAndUserId(3L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(99L, 3L, 7L, 12L, "a.txt", 10L, bytes("hello")))
                .isInstanceOf(DocumentService.DocumentNotFoundException.class);
        verify(storage, never()).store(anyLong(), anyLong(), anyLong(), any(), anyLong());
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void wrongSpaceOrKbPathCollapsesToUniform404BeforeAnyWrite() {
        when(membershipRepository.findByOrgIdAndUserId(3L, 9L))
                .thenReturn(Optional.of(Membership.grant(3L, 9L, MembershipRole.MEMBER)));
        when(spaceRepository.findByIdInOrgForMember(7L, 3L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(9L, 3L, 7L, 12L, "a.txt", 10L, bytes("hello")))
                .isInstanceOf(DocumentService.DocumentNotFoundException.class);
        verify(kbRepository, never()).findByIdInSpaceForMember(anyLong(), anyLong(), anyLong(), anyLong());
        verify(storage, never()).store(anyLong(), anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void rejectedTypeNeverTouchesStorage() {
        givenChainHolds();

        assertThatThrownBy(() -> service.upload(9L, 3L, 7L, 12L, "evil.exe", 10L, bytes("hello")))
                .isInstanceOf(DocumentUploadPolicy.RejectedTypeException.class);
        verify(storage, never()).store(anyLong(), anyLong(), anyLong(), any(), anyLong());
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void declaredAndMeasuredOversizeBothRejectedBeforeRowSaved() {
        givenChainHolds();

        // declared size over limit: rejected without reading a single byte
        assertThatThrownBy(() -> service.upload(9L, 3L, 7L, 12L, "big.txt", 101L, bytes("x")))
                .isInstanceOf(DocumentUploadPolicy.TooLargeException.class);
        verify(storage, never()).store(anyLong(), anyLong(), anyLong(), any(), anyLong());

        // lying stream: declared 10, actually 200 bytes > limit -> storage-side
        // measurement (stubbed) reports the truth, row never saved
        org.mockito.Mockito.reset(storage);
        when(storage.store(anyLong(), anyLong(), anyLong(), any(), anyLong())).thenAnswer(inv ->
                new DocumentStorage.Stored("orgs/3/spaces/7/kbs/12/x", 200L, "ff".repeat(32)));
        assertThatThrownBy(() -> service.upload(9L, 3L, 7L, 12L, "big.txt", 10L, bytes("x")))
                .isInstanceOf(DocumentUploadPolicy.TooLargeException.class);
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void emptyUploadIsRejected() {
        givenChainHolds();
        assertThatThrownBy(() -> service.upload(9L, 3L, 7L, 12L, "a.txt", 0L, bytes("")))
                .isInstanceOf(DocumentUploadPolicy.InvalidDocumentException.class);
        verify(storage, never()).store(anyLong(), anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void downloadMapsUnreadableFileIntoUniformNotFound() {
        givenChainHolds();
        Document doc = Document.of(12L, "gone.txt", "text/plain; charset=utf-8", 5L,
                "ab".repeat(32), "orgs/3/spaces/7/kbs/12/vanished", 9L);
        when(documentRepository.findByIdInKbForMember(50L, 12L, 7L, 3L, 9L))
                .thenReturn(Optional.of(doc));
        when(storage.resolveForRead("orgs/3/spaces/7/kbs/12/vanished"))
                .thenThrow(new DocumentStorage.UnreadableDocumentException("stored file is missing"));

        assertThatThrownBy(() -> service.download(9L, 3L, 7L, 12L, 50L))
                .isInstanceOf(DocumentService.DocumentNotFoundException.class);
    }
}
