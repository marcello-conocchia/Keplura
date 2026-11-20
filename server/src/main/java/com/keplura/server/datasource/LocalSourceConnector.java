package com.keplura.server.datasource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * ADR-0012 §1 Typ 2 — Linux-Verzeichnis (lokal oder per NFS/SMB in den
 * Server-Container gemountet, nur lesend).
 *
 * <p>Rechte (POSIX, ohne erweiterte ACLs): Lesen erlaubt für den Besitzer
 * ({@code posix-uid:}) bei u+r, die Gruppe ({@code posix-gid:}) bei g+r und
 * alle ({@code everyone}) bei o+r. Konservativ für Elternordner: ein Prinzipal
 * bleibt nur, wenn JEDER Ordner zwischen Quellwurzel und Datei ihm direkt (als
 * Besitzer/Gruppe) oder allen das Durchqueren (x) erlaubt. So wird eine
 * lesbare Datei in einem privaten Ordner niemals für alle sichtbar
 * (fail-closed; ein Zugriff nur über Zusatzgruppen geht dabei verloren).
 * Erweiterte POSIX-/NFSv4-ACLs werden nicht ausgewertet und daher verweigert.
 * Symbolische Links werden nie verfolgt.
 */
public class LocalSourceConnector implements SourceConnector {

    private final Path root;

    public LocalSourceConnector(Path root) throws IOException {
        Path real = root.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Der Ordner existiert nicht oder ist kein Verzeichnis.");
        }
        this.root = real;
    }

    @Override
    public List<Entry> list(int limit) throws IOException {
        List<Entry> out = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                    out.add(new Entry(root.relativize(file).toString().replace('\\', '/'), attrs.size(),
                            attrs.lastModifiedTime().toInstant()));
                }
                return out.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // unreadable entries are skipped, never fatal
            }
        });
        return out;
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        Path p = resolve(relativePath);
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file");
        }
        try (var directory = Files.newDirectoryStream(root)) {
            if (!(directory instanceof java.nio.file.SecureDirectoryStream<Path> secure)) {
                throw new IOException("Das Dateisystem unterstützt keinen sicheren Verzeichniszugriff.");
            }
            return openSecure(secure, root.relativize(p));
        }
    }

    private InputStream openSecure(java.nio.file.SecureDirectoryStream<Path> directory, Path path) throws IOException {
        if (path.getNameCount() == 1) {
            return java.nio.channels.Channels.newInputStream(directory.newByteChannel(path,
                    Set.<java.nio.file.OpenOption>of(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)));
        }
        try (var child = directory.newDirectoryStream(path.getName(0), LinkOption.NOFOLLOW_LINKS)) {
            return openSecure(child, path.subpath(1, path.getNameCount()));
        }
    }

    @Override
    public FileAcl acl(String relativePath) throws IOException {
        resolve(relativePath); // reject obvious path/links before invoking the FD-bound reader
        try (var snapshot = NativeLocalReader.read(root, relativePath, 0, false)) {
            return snapshot.acl();
        }
    }

    NativeLocalReader.Snapshot snapshot(String relativePath, long maximum) throws IOException {
        resolve(relativePath);
        return NativeLocalReader.read(root, relativePath, maximum, true);
    }

    private Path resolve(String relativePath) throws IOException {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw new IOException("path escapes the source root");
        }
        Path current = root;
        for (Path part : root.relativize(p)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolische Links werden nicht übernommen.");
        }
        return p;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
