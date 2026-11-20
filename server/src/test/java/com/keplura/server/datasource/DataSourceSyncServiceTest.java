package com.keplura.server.datasource;

import com.keplura.server.access.DocumentAcl;
import com.keplura.server.access.DocumentAclRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class DataSourceSyncServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    @Test void localSnapshotReplacesContentEvenWhenOldListingMetadataMatches() throws Exception {
        var files = mock(DataSourceFileRepository.class);
        var docs = mock(com.keplura.server.knowledge.DocumentService.class);
        var acl = mock(DocumentAclRepository.class);
        var local = mock(LocalSourceConnector.class);
        var source = new DataSource(1L, 1L, 1L, 7L);
        source.setMirrorPermissions(true); source.setMaxFileBytes(0);
        var row = new DataSourceFile(1L, "doc.txt");
        org.springframework.test.util.ReflectionTestUtils.setField(row, "id", 1L);
        row.setDocumentId(42L); row.setState(3, java.time.Instant.EPOCH);
        var bytes = java.nio.file.Files.writeString(temp.resolve("snapshot"), "NEW");
        var rights = new SourceConnector.FileAcl(java.util.Set.of("posix-uid:1000"), java.util.Set.of("everyone"));
        var modified = java.time.Instant.ofEpochSecond(10);
        when(local.snapshot("doc.txt", 1024)).thenReturn(new NativeLocalReader.Snapshot(bytes, rights, 3, modified, "new-sha"));
        when(files.findById(1L)).thenReturn(java.util.Optional.of(row));
        when(docs.sourceContentMatches(42L, "new-sha")).thenReturn(false);
        var doc = com.keplura.server.knowledge.Document.of(1L, "doc.txt", "text/plain", 3L,
                "0".repeat(64), "orgs/1/spaces/1/x", 7L);
        org.springframework.test.util.ReflectionTestUtils.setField(doc, "id", 43L);
        when(docs.ingestFromSource(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    java.io.InputStream in = invocation.getArgument(5);
                    assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("NEW");
                    return doc;
                });
        var service = new DataSourceSyncService(null, files, docs, acl, null,
                mock(org.springframework.transaction.PlatformTransactionManager.class), "", 1024);
        try {
            assertThat(service.syncFile(source, local, new SourceConnector.Entry("doc.txt", 3, java.time.Instant.EPOCH), row)).isTrue();
            verify(docs).removeFromSource(42L);
            verify(acl).save(argThat(a -> a.getDocumentId().equals(43L) && a.getPrincipal().equals("everyone") && !a.isAllowRead()));
            assertThat(row.getDocumentId()).isEqualTo(43L);
            assertThat(row.getModifiedAt()).isEqualTo(modified);
            assertThat(java.nio.file.Files.exists(bytes)).isFalse();
        } finally { service.stop(); }
    }

    @Test void failedLocalSnapshotRevokesAnExistingBroadAcl() throws Exception {
        var acl = mock(DocumentAclRepository.class);
        var local = mock(LocalSourceConnector.class);
        when(local.snapshot(anyString(), anyLong())).thenThrow(new java.io.IOException("snapshot refused"));
        var row = new DataSourceFile(1L, "doc.txt"); row.setDocumentId(42L);
        var source = new DataSource(1L, 1L, 1L, 7L); source.setMirrorPermissions(true);
        var service = new DataSourceSyncService(null, null, null, acl, null,
                mock(org.springframework.transaction.PlatformTransactionManager.class), "", 1024);
        try {
            assertThatThrownBy(() -> service.syncFile(source, local,
                    new SourceConnector.Entry("doc.txt", 3, java.time.Instant.EPOCH), row)).isInstanceOf(java.io.IOException.class);
            verify(acl).save(argThat(a -> a.getDocumentId().equals(42L) && a.getPrincipal().equals("everyone") && !a.isAllowRead()));
        } finally { service.stop(); }
    }

    @Test void unreadableRightsWriteOneDenyAllWithoutViolatingUniqueConstraint() {
        var repo = mock(DocumentAclRepository.class);
        var service = new DataSourceSyncService(null, null, null, repo, null,
                mock(org.springframework.transaction.PlatformTransactionManager.class), "", 1024);
        try {
            service.writeAcl(42L, SourceConnector.FileAcl.unknown());
            var rows = ArgumentCaptor.forClass(DocumentAcl.class);
            verify(repo, times(1)).save(rows.capture());
            assertThat(rows.getValue().getPrincipal()).isEqualTo("everyone");
            assertThat(rows.getValue().isAllowRead()).isFalse();
        } finally {
            service.stop();
        }
    }
}
