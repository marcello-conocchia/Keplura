package com.keplura.server.datasource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import static org.assertj.core.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
class LocalSourceConnectorTest {
    @TempDir Path temp;

    @Test void extendedFileAclMaskCannotBecomeAGroupGrant() throws Exception {
        Path root = Files.createDirectory(temp.resolve("acl-file"));
        Path file = Files.writeString(root.resolve("secret.txt"), "synthetic");
        setAcl(file, "u::rw-,u:2001:r--,g::---,m::r--,o::---");
        assertThat(((Integer) Files.getAttribute(file, "unix:mode")) & 0040).isNotZero();
        try (var c = new LocalSourceConnector(root)) {
            assertThatThrownBy(() -> c.acl("secret.txt")).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void extendedAncestorAclAlsoFailsClosed() throws Exception {
        Path root = Files.createDirectory(temp.resolve("acl-parent"));
        Path child = Files.createDirectory(root.resolve("inside"));
        Files.writeString(child.resolve("secret.txt"), "synthetic");
        setAcl(root, "u::rwx,u:2001:r-x,g::---,m::r-x,o::---");
        try (var c = new LocalSourceConnector(root)) {
            assertThatThrownBy(() -> c.acl("inside/secret.txt")).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void replacedFileUsesAclAndBytesFromTheSameNativeSnapshot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("snapshot"));
        Path file = Files.writeString(root.resolve("doc.txt"), "PUBLIC");
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        try (var c = new LocalSourceConnector(root)) {
            assertThat(c.acl("doc.txt").allow()).contains("everyone");
            Path next = Files.writeString(root.resolve("next"), "PRIVATE_SYNTHETIC_CANARY");
            Files.setPosixFilePermissions(next, PosixFilePermissions.fromString("rw-------"));
            Files.move(next, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            try (var snapshot = c.snapshot("doc.txt", 1000); var input = snapshot.open()) {
                assertThat(snapshot.acl().allow()).doesNotContain("everyone");
                assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("PRIVATE_SYNTHETIC_CANARY");
                assertThat(snapshot.sha256()).hasSize(64);
            }
            assertThatThrownBy(() -> c.snapshot("doc.txt", 2)).isInstanceOf(java.io.IOException.class);
        }
    }

    private static void setAcl(Path file, String acl) throws Exception {
        var process = new ProcessBuilder("setfacl", "-m", acl, "--", file.toString()).start();
        assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("setfacl test fixture").isZero();
    }

    @Test void sourceRootAlsoRestrictsOtherwisePublicFiles() throws Exception {
        Path root = Files.createDirectory(temp.resolve("private"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(root.resolve("public.txt"), "Vertraulich");
        Files.setPosixFilePermissions(root.resolve("public.txt"), PosixFilePermissions.fromString("rw-r--r--"));
        try (var c = new LocalSourceConnector(root)) {
            assertThat(c.acl("public.txt").allow()).doesNotContain("everyone");
        }
    }

    @Test void symlinkInsertedAfterListingCannotEscapeThroughParent() throws Exception {
        Path root = Files.createDirectory(temp.resolve("source"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "Nicht freigegeben");
        Files.createSymbolicLink(root.resolve("link"), outside);
        try (var c = new LocalSourceConnector(root)) {
            assertThat(c.list(100)).isEmpty();
            assertThatThrownBy(() -> c.open("link/secret.txt")).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(() -> c.acl("link/secret.txt")).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(() -> c.open("../outside/secret.txt")).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void unreadableSourceAccountCannotPublishAnOthersGrant() throws Exception {
        Path root = Files.createDirectory(temp.resolve("source"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path file = Files.writeString(root.resolve("others.txt"), "Nur andere Benutzer");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("------r--"));
        try (var c = new LocalSourceConnector(root)) {
            assertThatThrownBy(() -> c.acl("others.txt")).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void groupDenialStillOverridesOthersReadInASuccessfulSnapshot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("group"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path file = Files.writeString(root.resolve("others.txt"), "synthetic");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw----r--"));
        try (var c = new LocalSourceConnector(root)) {
            var acl = c.acl("others.txt");
            assertThat(acl.allow()).contains("everyone");
            assertThat(acl.deny()).contains("posix-gid:" + Files.getAttribute(file, "unix:gid"));
        }
    }
}
