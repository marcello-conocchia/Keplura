package com.keplura.server.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * File persistence for uploaded documents (M3c). The base directory comes from
 * {@code keplura.upload.dir} (ENV {@code KEPLURA_UPLOAD_DIR}, default
 * {@code ./data/uploads} — resolved against the server's working directory and
 * created on demand).
 *
 * <h2>The storedPath convention — why a UUID and never the user filename</h2>
 * <ul>
 *   <li><b>Structure:</b> {@code orgs/{orgId}/spaces/{spaceId}/kbs/{kbId}/{uuid}}
 *       — tenant coordinates come from the AUTHORISED path chain (service has
 *       already proven membership + pins), the leaf name is a random v4 UUID.
 *       No extension: the type is whitelisted metadata (server-mapped), the
 *       path must carry zero information derived from client input.</li>
 *   <li><b>Injection can't escape:</b> every dynamic segment is a server-side
 *       {@code long} or {@code UUID.toString()} — characters that cannot form
 *       "..", a separator, or a NUL. The storedPath written to the DB is
 *       therefore traversal-free by CONSTRUCTION, not by filtering.</li>
 *   <li><b>Read side is still hard-verified:</b> {@link #resolveForRead}
 *       normalises the resolved path and requires it to stay under the base
 *       dir (canonical containment), so even a corrupted/hand-edited DB row
 *       cannot make the download endpoint read outside the upload tree.</li>
 *   <li><b>Collisions:</b> UUID-named leaves mean two documents called
 *       "report.pdf" never overwrite each other; sha256 (computed here over
 *       the bytes AS STORED) gives M3d a dedup/integrity anchor instead of a
 *       filename heuristic.</li>
 *   <li><b>Tenant prefix</b> is convenience for operators (per-org trees,
 *       simple quota/backup policy later) — NOT an access-control mechanism;
 *       the repository query chain is the only authorization that matters.</li>
 * </ul>
 */
@Component
public class DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorage.class);

    /** 8 KiB streaming buffer; hashing happens while copying, never from a re-read. */
    private static final int COPY_BUFFER = 8192;

    public record Stored(String storedPath, long sizeBytes, String sha256) {
    }

    /** Storage failure (disk full, IO) — surfaces as 500, never as partial metadata. */
    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Row references bytes we cannot serve (missing/corrupted/unreadable) → 404, never 500-with-stack. */
    public static class UnreadableDocumentException extends RuntimeException {
        public UnreadableDocumentException(String message) {
            super(message);
        }
    }

    private final Path baseDir;

    public DocumentStorage(@Value("${keplura.upload.dir:./data/uploads}") String uploadDir) {
        this.baseDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    Path baseDir() {
        return baseDir;
    }

    /**
     * Streams {@code content} into a fresh UUID-named file under the tenant
     * prefix and returns the relative storedPath + size + sha256 of exactly
     * what landed on disk. The row is only ever persisted after this returns,
     * so metadata and bytes cannot diverge on a failed upload; an orphan file
     * from a failed transaction stays invisible (no listing can point at it)
     * and is the acceptable MVP trade for not deleting files inside a
     * possibly-rolling-back transaction.
     *
     * <p><b>Hard cap during the copy (M3d hardening (a)):</b> {@code maxBytes}
     * is enforced INSIDE the loop — at most {@code maxBytes} are ever written
     * (or hashed), and reading stops the moment one byte more than the limit
     * is seen. A lying declared size or a stream that keeps sending past the
     * cap therefore cannot make the server write an oversized file that is
     * only rejected after the fact; the caller still maps the returned
     * oversized {@link Stored#sizeBytes()} to 413, but the disk side effect
     * is bounded by the cap, not by the attacker's body length.
     */
    public Stored store(Long orgId, Long spaceId, Long kbId, InputStream content, long maxBytes) {
        // the sentinel Long.MAX_VALUE ("no cap", tests) would overflow the
        // cap+1 arithmetic below — clamp one byte short, still unreachably
        // large for any real body.
        if (maxBytes == Long.MAX_VALUE) {
            maxBytes = Long.MAX_VALUE - 1;
        }
        String relative = "orgs/" + orgId + "/spaces/" + spaceId + "/kbs/" + kbId + "/"
                + UUID.randomUUID();
        Path target = baseDir.resolve(relative).normalize();
        // belt & braces: the construction above cannot escape, but never trust
        // a composed path without checking containment before writing.
        if (!target.startsWith(baseDir)) {
            throw new StorageException("derived storage path escaped the base dir", null);
        }
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream in = content; OutputStream out = Files.newOutputStream(target)) {
                byte[] buf = new byte[COPY_BUFFER];
                int n;
                while (size <= maxBytes && (n = in.read(buf)) > 0) {
                    // write at most up to the cap; the loop condition then
                    // stops reading, so bytes beyond maxBytes+1 never arrive
                    int allowed = (int) Math.min(n, maxBytes + 1 - size);
                    digest.update(buf, 0, allowed);
                    out.write(buf, 0, allowed);
                    size += allowed;
                }
            }
            return new Stored(relative, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException e) {
            // best effort: never leave a half-written file behind the failure
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                log.warn("upload cleanup failed (path stays unreferenced)");
            }
            throw new StorageException("could not persist uploaded document", e);
        }
    }

    /**
     * Resolves a stored DB path for READING with containment enforced at every
     * step: no absolute paths, no traversal segments surviving normalisation,
     * the real file must sit inside the base dir (symlinked out counts as
     * escaping) and must be a regular file. Anything else is an
     * {@link UnreadableDocumentException} — wire-indistinguishable from a
     * missing row (uniform 404).
     */
    /**
     * Best-effort removal of a stored file after its row is gone. Same
     * containment rules as reads; a missing/foreign path is ignored (the row
     * is already deleted, an orphan file is an ops concern, not a leak).
     */
    public void deleteQuietly(String storedPath) {
        try {
            Files.deleteIfExists(resolveForRead(storedPath));
        } catch (UnreadableDocumentException | IOException | StorageException e) {
            // nothing to delete or not deletable — row deletion stands
        }
    }

    public Path resolveForRead(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new UnreadableDocumentException("document has no stored path");
        }
        Path relative;
        try {
            relative = Path.of(storedPath);
        } catch (java.nio.file.InvalidPathException e) {
            // NUL bytes / invalid encoding — e.g. a corrupted DB row
            throw new UnreadableDocumentException("stored path is not parseable");
        }
        if (relative.isAbsolute()) {
            throw new UnreadableDocumentException("stored path must be relative");
        }
        Path resolved = baseDir.resolve(relative).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new UnreadableDocumentException("stored path escapes the upload dir");
        }
        Path real;
        try {
            if (!Files.isRegularFile(resolved)) {
                throw new UnreadableDocumentException("stored file is missing");
            }
            real = resolved.toRealPath();
        } catch (IOException e) {
            throw new UnreadableDocumentException("stored file is not readable");
        }
        Path realBase;
        try {
            realBase = baseDir.toRealPath();
        } catch (IOException e) {
            throw new StorageException("upload base dir is not accessible", e);
        }
        if (!real.startsWith(realBase)) {
            throw new UnreadableDocumentException("stored file escapes the upload dir");
        }
        return real;
    }
}
