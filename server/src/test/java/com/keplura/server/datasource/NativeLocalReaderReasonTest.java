package com.keplura.server.datasource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verständliche Ablehnungsgründe des lokalen Rechte-Lesers (Exit 3 = NFS u. Ä., Exit 4 = erweiterte ACL). */
class NativeLocalReaderReasonTest {

    @TempDir Path temp;

    @Test
    void exitCodesMapToAdminMessagesAndUnknownCodesStayGeneric() {
        assertThat(NativeLocalReader.reasonFor(3)).contains("NFS").contains("gesperrt");
        assertThat(NativeLocalReader.reasonFor(4)).contains("erweiterte Linux-Rechte").contains("gesperrt");
        assertThat(NativeLocalReader.reasonFor(1)).isNull();
        assertThat(NativeLocalReader.reasonFor(0)).isNull();
        // Die Abgleich-Fehlerliste zeigt nur „eigene“ Meldungen unverändert an
        assertThat(DataSourceSyncService.message(new IOException(NativeLocalReader.UNSUPPORTED_FILESYSTEM)))
                .isEqualTo(NativeLocalReader.UNSUPPORTED_FILESYSTEM);
        assertThat(DataSourceSyncService.message(new IOException(NativeLocalReader.EXTENDED_ACL)))
                .isEqualTo(NativeLocalReader.EXTENDED_ACL);
    }

    @Test
    void previewNamesTheMostFrequentReason() {
        assertThat(DataSourceSyncService.mostFrequent(Map.of())).isNull();
        assertThat(DataSourceSyncService.mostFrequent(Map.of(
                NativeLocalReader.EXTENDED_ACL, 2, NativeLocalReader.UNSUPPORTED_FILESYSTEM, 9)))
                .isEqualTo(NativeLocalReader.UNSUPPORTED_FILESYSTEM);
    }

    private Path helper(int exitCode) throws IOException {
        Path script = temp.resolve("reader-" + exitCode + ".sh");
        Files.writeString(script, "#!/bin/sh\necho 'local snapshot refused' >&2\nexit " + exitCode + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void refusedSnapshotsCarryTheReasonOfTheHelper() throws Exception {
        Path root = Files.createDirectory(temp.resolve("ablage"));
        assertThatThrownBy(() -> NativeLocalReader.read(helper(3).toString(), root, "a.txt", 0, false))
                .isInstanceOf(IOException.class).hasMessage(NativeLocalReader.UNSUPPORTED_FILESYSTEM);
        assertThatThrownBy(() -> NativeLocalReader.read(helper(4).toString(), root, "a.txt", 0, true))
                .isInstanceOf(IOException.class).hasMessage(NativeLocalReader.EXTENDED_ACL);
        assertThatThrownBy(() -> NativeLocalReader.read(helper(1).toString(), root, "a.txt", 0, false))
                .isInstanceOf(IOException.class)
                .hasMessage("Die lokale Datei konnte nicht mit sicheren Rechten übernommen werden.");
    }
}
