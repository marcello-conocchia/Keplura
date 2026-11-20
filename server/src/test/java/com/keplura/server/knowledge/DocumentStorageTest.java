package com.keplura.server.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3c: {@link DocumentStorage} — the UUID path convention and the READ-side
 * containment checks (the traversal regression suite). The write side is
 * traversal-free by construction (only longs + UUIDs enter the path); the
 * resolve side must additionally survive a hand-corrupted DB row: absolute
 * paths, encoded and plain traversal, unicode tricks, symlink escapes and
 * unreadable residue all collapse into {@link
 * DocumentStorage.UnreadableDocumentException} (uniform 404 upstream — never
 * a 500 stack trace, never bytes from outside the base dir).
 */
class DocumentStorageTest {

    @TempDir
    Path base;

    private DocumentStorage storage() {
        return new DocumentStorage(base.toString());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void storeWritesUnderTenantPrefixWithUuidLeafAndHashesExactlyWhatLanded() throws IOException {
        byte[] payload = "Inhalt mit Umlauten äöü ß".getBytes(StandardCharsets.UTF_8);
        DocumentStorage.Stored stored = storage()
                .store(4L, 9L, 12L, new ByteArrayInputStream(payload), Long.MAX_VALUE);

        assertThat(stored.storedPath())
                .startsWith("orgs/4/spaces/9/kbs/12/")
                .doesNotContain("..");
        String leaf = stored.storedPath().substring("orgs/4/spaces/9/kbs/12/".length());
        assertThat(UUID.fromString(leaf).toString()).isEqualTo(leaf); // pure v4 uuid, no extension

        Path onDisk = base.resolve(stored.storedPath());
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(payload);
        assertThat(stored.sizeBytes()).isEqualTo(payload.length);
        assertThat(stored.sha256()).isEqualTo(sha256Hex(payload));
    }

    @Test
    void theCopyLoopStopsAtTheHardCapAndNeverWritesBeyondIt() throws IOException {
        // M3d hardening (a): a lying declared size used to let the FULL stream
        // land on disk before the service rejected it. Now the loop aborts at
        // cap+1 bytes read — the stored file is capped, the returned size is
        // over the limit (so the caller still 413s), nothing beyond the cap
        // was ever written.
        byte[] payload = new byte[5000]; // attacker "measures" 100 and sends 5000
        java.io.InputStream lying = new ByteArrayInputStream(payload) {
            @Override
            public int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 37)); // awkward chunk sizes
            }
        };
        DocumentStorage.Stored stored = storage().store(1L, 1L, 1L, lying, 1000);

        assertThat(stored.sizeBytes()).isEqualTo(1001); // exactly cap+1: "over the limit", provable
        Path onDisk = base.resolve(stored.storedPath());
        assertThat(Files.size(onDisk)).isEqualTo(1001); // NOT 5000
    }

    @Test
    void twoUploadsOfTheSameFilenameNeverCollide() throws IOException {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        String p1 = storage().store(1L, 1L, 1L, new ByteArrayInputStream(payload), Long.MAX_VALUE).storedPath();
        String p2 = storage().store(1L, 1L, 1L, new ByteArrayInputStream(payload), Long.MAX_VALUE).storedPath();
        assertThat(p1).isNotEqualTo(p2);
    }

    @Test
    void resolveForReadAcceptsAProperlyStoredFile() throws IOException {
        byte[] payload = "roundtrip".getBytes(StandardCharsets.UTF_8);
        DocumentStorage storage = storage();
        DocumentStorage.Stored stored = storage.store(2L, 3L, 4L, new ByteArrayInputStream(payload), Long.MAX_VALUE);
        Path resolved = storage.resolveForRead(stored.storedPath());
        assertThat(Files.readAllBytes(resolved)).isEqualTo(payload);
    }

    // ---- traversal / containment regression suite (AC3) ----

    @Test
    void absolutePathsAreRefused() {
        assertThatThrownBy(() -> storage().resolveForRead("/etc/passwd"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
    }

    @Test
    void plainAndEncodedTraversalAreRefused() throws IOException {
        // a real in-base file first, so "escape must fail" cannot pass by
        // accident of "file does not exist": ../../etc-hosts resolves OUTSIDE
        // the base and must be refused even when the target exists.
        Path outside = Files.createTempFile("keplura-escape-", ".txt");
        Files.writeString(outside, "outside bytes");
        DocumentStorage storage = storage();
        // relativise gives "..keplura-escape-*" — a real file OUTSIDE the
        // base dir reachable via "..": containment must refuse it although it
        // exists (cannot accidentally pass as "missing file").
        String rel = base.relativize(outside).toString();
        assertThatThrownBy(() -> storage.resolveForRead(rel))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
        assertThatThrownBy(() -> storage.resolveForRead("orgs/1/../../..%2F..%2Fetc/passwd"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
        assertThatThrownBy(() -> storage.resolveForRead("orgs/1/..\\..\\..\\Windows\\win.ini"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
        assertThatThrownBy(() -> storage.resolveForRead("./orgs/../../../../etc/hostname"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
    }

    @Test
    void unicodeNormalisationTricksAreRefused() {
        // fullwidth/dotted variants of ".." that some normalisers fold: the
        // strings are NOT the literal ".." components, so they miss the file
        // and must surface as unreadable — never as resolved bytes.
        assertThatThrownBy(() -> storage().resolveForRead("orgs/\uFF0E\uFF0E/etc/passwd"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
        assertThatThrownBy(() -> storage().resolveForRead("orgs/\u202E/../..") )
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
    }

    @Test
    void nulByteAndUnparseablePathsAreRefused() {
        assertThatThrownBy(() -> storage().resolveForRead("orgs/1/leak\u0000.txt"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
        assertThatThrownBy(() -> storage().resolveForRead("   "))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
    }

    @Test
    void symlinkPointingOutsideTheBaseDirIsRefused() throws IOException {
        Path secret = Files.createTempFile("keplura-secret-", ".txt");
        Files.writeString(secret, "top secret");
        Path link = base.resolve("orgs/1/spaces/1/kbs/1/link");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, secret);

        assertThatThrownBy(() -> storage().resolveForRead("orgs/1/spaces/1/kbs/1/link"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
    }

    @Test
    void missingAndDirectoryTargetsAreRefused() {
        assertThatThrownBy(() -> storage().resolveForRead("orgs/1/spaces/1/kbs/1/never-stored"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class);
        assertThatThrownBy(() -> storage().resolveForRead("orgs"))
                .isInstanceOf(DocumentStorage.UnreadableDocumentException.class); // a directory, not a file
    }

    @Test
    void theMultipartFilenameOfATraversalAttemptNeverReachesTheStoredPath() throws IOException {
        // the client sends "../../evil.txt" as the part filename; storage's
        // path is server-composed UUID only, so the traversal string cannot
        // appear anywhere in storedPath (sanitiser handles display separately)
        MockMultipartFile nasty = new MockMultipartFile("file",
                "../../../etc/evil.txt", "application/pdf", "data".getBytes(StandardCharsets.UTF_8));
        DocumentStorage.Stored stored = storage()
                .store(1L, 1L, 1L, new ByteArrayInputStream(nasty.getBytes()), Long.MAX_VALUE);
        assertThat(stored.storedPath()).doesNotContain("..", "evil", "etc");
    }
}
