package com.keplura.server.datasource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * ADR-0012 §1 — nur lesender Zugriff auf eine Ablage. Eine Instanz gilt für
 * einen Abgleichlauf und wird danach geschlossen.
 */
public interface SourceConnector extends AutoCloseable {

    /** One file below the source root; relativePath always uses '/'. */
    record Entry(String relativePath, long sizeBytes, Instant modifiedAt) {
    }

    /** Effective read rights: allow and deny principals (canonical keys). */
    record FileAcl(Set<String> allow, Set<String> deny) {
        public static FileAcl unknown() {
            // fail-closed: nobody sees a file whose rights could not be read
            return new FileAcl(Set.of(), Set.of(com.keplura.server.access.AccessFilter.EVERYONE));
        }
    }

    /** All files (no directories, no links), at most {@code limit}. */
    List<Entry> list(int limit) throws IOException;

    InputStream open(String relativePath) throws IOException;

    FileAcl acl(String relativePath) throws IOException;

    @Override
    void close();
}
