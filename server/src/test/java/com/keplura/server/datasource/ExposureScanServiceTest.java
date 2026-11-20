package com.keplura.server.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExposureScanServiceTest {

    private static final String DOMAIN_USERS = "sid:S-1-5-21-1-2-3-513";
    private static final String SALES = "sid:S-1-5-21-1-2-3-1105";

    private final DataSourceSyncService sync = spy(new DataSourceSyncService(null, null, null, null, null,
            mock(PlatformTransactionManager.class), "", 10 * 1024 * 1024));

    @AfterEach
    void stop() {
        sync.stop();
    }

    /** In-memory share: path → ACL (null = rights unreadable). */
    record FakeShare(Map<String, SourceConnector.FileAcl> files) implements SourceConnector {
        @Override public List<Entry> list(int limit) {
            return files.keySet().stream().sorted().map(p -> new Entry(p, 100, Instant.EPOCH)).toList();
        }
        @Override public InputStream open(String relativePath) {
            throw new AssertionError("the exposure scan must never read file contents");
        }
        @Override public FileAcl acl(String relativePath) throws IOException {
            FileAcl a = files.get(relativePath);
            if (a == null) throw new IOException("access denied");
            return a;
        }
        @Override public void close() {
        }
    }

    private DataSource source(boolean mirror, String exclude) {
        DataSource ds = new DataSource(1L, 2L, 3L, 9L);
        ReflectionTestUtils.setField(ds, "id", 7L);
        ds.setKind(DataSource.KIND_SMB);
        ds.setIncludePatterns("");
        ds.setExcludePatterns(exclude);
        ds.setMaxFileBytes(0);
        ds.setMirrorPermissions(mirror);
        return ds;
    }

    private static SourceConnector.FileAcl allow(String... p) {
        return new SourceConnector.FileAcl(Set.of(p), Set.of());
    }

    @Test
    void reportsBroadAndSensitiveFilesWithoutReadingContents() throws Exception {
        var files = new java.util.HashMap<String, SourceConnector.FileAcl>();
        files.put("personal/Gehaltsliste 2025.csv", allow(DOMAIN_USERS));        // breit + sensibel
        files.put("personal/Urlaubsplan.csv", allow(DOMAIN_USERS));             // breit
        files.put("vertrieb/Angebot.pdf", allow(SALES));                         // eng
        files.put("vertrieb/Kündigung Kunde.pdf", allow(SALES));                // sensibel, aber eng
        files.put("it/Passwörter.txt", allow("everyone"));                       // breit + sensibel
        files.put("gf/geheim.pdf", null);                                        // Rechte unlesbar
        files.put("bilder/logo.png", allow("everyone"));                         // Format nicht übernehmbar
        DataSource ds = source(true, "");
        doReturn(new FakeShare(files)).when(sync).connect(ds);

        var r = new ExposureScanService(sync, mock(DataSourceRepository.class)).scan(ds);

        assertThat(r.state()).isEqualTo("DONE");
        assertThat(r.filesTotal()).isEqualTo(6);
        assertThat(r.filesChecked()).isEqualTo(6);
        assertThat(r.broadFiles()).isEqualTo(3);
        assertThat(r.sensitiveBroadFiles()).isEqualTo(2);
        assertThat(r.sensitiveFiles()).isEqualTo(4); // inkl. unlesbarer „geheim.pdf“
        assertThat(r.unreadable()).isEqualTo(1);
        assertThat(r.findings()).extracting(ExposureScanService.Finding::relativePath)
                .containsExactly("it/Passwörter.txt", "personal/Gehaltsliste 2025.csv");
        assertThat(r.findings().get(1).broadGroups()).containsExactly("Domänen-Benutzer");
        assertThat(r.broadGroups()).extracting(ExposureScanService.Group::principal)
                .containsExactly(DOMAIN_USERS, "everyone");
        assertThat(r.folders().get(0)).isEqualTo(new ExposureScanService.Folder("personal", 2, 1));
    }

    @Test
    void excludedFilesAreNotReported() throws Exception {
        var files = Map.of("personal/Gehaltsliste.csv", allow(DOMAIN_USERS));
        DataSource ds = source(true, ExposureRules.excludePatternFor("personal/Gehaltsliste.csv"));
        doReturn(new FakeShare(files)).when(sync).connect(ds);

        var r = new ExposureScanService(sync, mock(DataSourceRepository.class)).scan(ds);

        assertThat(r.filesTotal()).isZero();
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void withoutRightsMirroringEverythingIsBroad() throws Exception {
        var files = Map.of("personal/Gehaltsliste.csv", allow(SALES), "a/b.md", allow(SALES));
        DataSource ds = source(false, "");
        doReturn(new FakeShare(files)).when(sync).connect(ds);

        var r = new ExposureScanService(sync, mock(DataSourceRepository.class)).scan(ds);

        assertThat(r.broadFiles()).isEqualTo(2);
        assertThat(r.sensitiveBroadFiles()).isEqualTo(1);
        assertThat(r.message()).contains("Rechte-Übernahme ist ausgeschaltet");
    }

    @Test
    void connectionErrorsBecomeAFailedReport() throws Exception {
        DataSource ds = source(true, "");
        doThrow(new IOException("Anmeldung am Fileserver fehlgeschlagen.")).when(sync).connect(ds);

        var r = new ExposureScanService(sync, mock(DataSourceRepository.class)).scan(ds);

        assertThat(r.state()).isEqualTo("FAILED");
        assertThat(r.message()).isEqualTo("Anmeldung am Fileserver fehlgeschlagen.");
    }

    /** Echte POSIX-Rechte über den echten Linux-Ordner-Konnektor (nur unter Linux, also in ci-local). */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void realLinuxFolderSeparatesWorldReadableFromPrivateFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)
            throws Exception {
        var perms = (java.util.function.Function<String, java.util.Set<java.nio.file.attribute.PosixFilePermission>>)
                java.nio.file.attribute.PosixFilePermissions::fromString;
        var root = java.nio.file.Files.createDirectory(temp.resolve("ablage"));
        java.nio.file.Files.setPosixFilePermissions(root, perms.apply("rwxr-xr-x"));
        var hr = java.nio.file.Files.createDirectory(root.resolve("personal"));
        java.nio.file.Files.setPosixFilePermissions(hr, perms.apply("rwxr-xr-x"));
        var open = java.nio.file.Files.writeString(hr.resolve("Gehaltsliste 2025.csv"), "Name;Gehalt");
        java.nio.file.Files.setPosixFilePermissions(open, perms.apply("rw-r--r--"));     // o+r → alle
        var closed = java.nio.file.Files.writeString(hr.resolve("Kündigung Meier.md"), "intern");
        java.nio.file.Files.setPosixFilePermissions(closed, perms.apply("rw-r-----"));   // nur Gruppe
        var priv = java.nio.file.Files.createDirectory(root.resolve("gf"));
        java.nio.file.Files.setPosixFilePermissions(priv, perms.apply("rwx------"));
        var inPrivate = java.nio.file.Files.writeString(priv.resolve("Passwörter.txt"), "x");
        java.nio.file.Files.setPosixFilePermissions(inPrivate, perms.apply("rw-r--r--")); // Ordner sperrt

        DataSource ds = source(true, "");
        doReturn(new LocalSourceConnector(root)).when(sync).connect(ds);
        var r = new ExposureScanService(sync, mock(DataSourceRepository.class)).scan(ds);

        assertThat(r.state()).isEqualTo("DONE");
        assertThat(r.filesChecked()).isEqualTo(3);
        assertThat(r.broadFiles()).isEqualTo(1);
        assertThat(r.findings()).extracting(ExposureScanService.Finding::relativePath)
                .containsExactly("personal/Gehaltsliste 2025.csv");
        assertThat(r.findings().get(0).broadGroups()).containsExactly("Alle angemeldeten Personen");
    }

    @Test
    void forgetRemovesExcludedFindings() throws Exception {
        var files = Map.of("hr/Gehaltsliste.csv", allow("everyone"), "hr/Kündigung.pdf", allow("everyone"));
        DataSource ds = source(true, "");
        doReturn(new FakeShare(files)).when(sync).connect(ds);
        DataSourceRepository repo = mock(DataSourceRepository.class);
        when(repo.findById(7L)).thenReturn(java.util.Optional.of(ds));
        var service = new ExposureScanService(sync, repo);
        try {
            assertThat(service.start(7L)).isTrue();
            for (int i = 0; i < 100 && !"DONE".equals(service.report(7L).state()); i++) Thread.sleep(20);
            assertThat(service.report(7L).findings()).hasSize(2);
            service.forget(7L, Set.of("hr/Kündigung.pdf"));
            assertThat(service.report(7L).findings()).extracting(ExposureScanService.Finding::relativePath)
                    .containsExactly("hr/Gehaltsliste.csv");
        } finally {
            service.stop();
        }
    }
}
