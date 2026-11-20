package com.keplura.server.datasource;

import com.keplura.server.access.AccessFilter;
import com.keplura.server.access.DocumentAcl;
import com.keplura.server.access.DocumentAclRepository;
import com.keplura.server.admin.SecretCipher;
import com.keplura.server.knowledge.Document;
import com.keplura.server.knowledge.DocumentService;
import com.keplura.server.knowledge.DocumentUploadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ADR-0012 §4 — Abgleich der Datenquellen (Delta, Rechte, Löschungen) und Probelauf.
 * Läuft in einem eigenen Hintergrund-Thread; eine Quelle nie doppelt gleichzeitig.
 */
@Service
public class DataSourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSyncService.class);
    static final int MAX_FILES = 50_000;
    static final int PREVIEW_SAMPLE = 500;

    public record PrincipalCount(String principal, int files, String label) {
    }

    private com.keplura.server.directory.DirectoryService directoryService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDirectoryService(com.keplura.server.directory.DirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    public record Preview(int filesFound, int filesAccepted, long bytesAccepted, int skippedType, int skippedSize,
                          int skippedPattern, List<String> sample, List<PrincipalCount> principals,
                          int aclSampled, int aclUnreadable, String aclUnreadableReason, String message) {
    }

    private final DataSourceRepository sources;
    private final DataSourceFileRepository files;
    private final DocumentService documentService;
    private final DocumentAclRepository aclRepository;
    private final SecretCipher secretCipher;
    private final TransactionTemplate tx;
    private final List<Path> localRoots;
    private final long uploadMaxBytes;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "datasource-sync");
        t.setDaemon(true);
        return t;
    });

    public DataSourceSyncService(DataSourceRepository sources, DataSourceFileRepository files,
                                 DocumentService documentService, DocumentAclRepository aclRepository,
                                 SecretCipher secretCipher, PlatformTransactionManager txManager,
                                 @Value("${keplura.datasource.local-roots:}") String localRoots,
                                 @Value("${keplura.upload.max-size-bytes:20971520}") long uploadMaxBytes) {
        this.sources = sources;
        this.files = files;
        this.documentService = documentService;
        this.aclRepository = aclRepository;
        this.secretCipher = secretCipher;
        this.tx = new TransactionTemplate(txManager);
        this.uploadMaxBytes = uploadMaxBytes;
        this.localRoots = Arrays.stream(localRoots.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> Path.of(s).toAbsolutePath().normalize()).toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startScheduler() {
        tx.executeWithoutResult(s -> sources.findAll().forEach(ds -> {
            if ("RUNNING".equals(ds.getLastRunStatus())) {
                ds.runFinished("FAILED", "Der Abgleich wurde durch einen Neustart unterbrochen.",
                        ds.getFilesSeen(), ds.getFilesIndexed(), ds.getFilesFailed());
                sources.save(ds);
            }
        }));
        scheduler.scheduleWithFixedDelay(this::tick, 60, 60, TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            Instant now = Instant.now();
            for (DataSource ds : sources.findByEnabledTrue()) {
                if (ds.isDue(now)) runNow(ds.getId());
            }
        } catch (RuntimeException e) {
            log.warn("datasource scheduler tick failed ({})", e.getClass().getSimpleName());
        }
    }

    /** Startet einen Abgleich im Hintergrund (false, wenn er bereits läuft). */
    public boolean requestRun(Long id) {
        if (running.contains(id)) return false;
        scheduler.execute(() -> runNow(id));
        return true;
    }

    // ------------------------------------------------------------------ connectors

    SourceConnector connect(DataSource ds) throws IOException {
        if (DataSource.KIND_LOCAL.equals(ds.getKind())) {
            // compare REAL paths (symlinks resolved) so a link cannot lead out of an allowed root
            Path real;
            try {
                real = Path.of(ds.getLocation()).toRealPath();
            } catch (IOException | RuntimeException e) {
                throw new IOException("Der Ordner existiert nicht oder ist für den Server nicht lesbar.");
            }
            boolean allowed = false;
            for (Path root : localRoots) {
                try {
                    if (real.startsWith(root.toRealPath())) allowed = true;
                } catch (IOException ignored) {
                    // a configured root that does not exist allows nothing
                }
            }
            if (!allowed) {
                throw new IOException("Dieser Ordner ist nicht freigegeben. Erlaubte Wurzeln legt der Betrieb über "
                        + "KEPLURA_DATASOURCE_LOCAL_ROOTS fest.");
            }
            return new LocalSourceConnector(real);
        }
        if (DataSource.KIND_SMB.equals(ds.getKind())) {
            String secret = null;
            if (ds.hasSecret()) {
                if (!secretCipher.available()) throw new IOException("Auf dem Server fehlt der Master-Key für gespeicherte Passwörter.");
                secret = secretCipher.decrypt(ds.getSecretCipher());
            }
            return new SmbSourceConnector(ds.getLocation(), ds.getUsername(), secret);
        }
        if (DataSource.KIND_SHAREPOINT.equals(ds.getKind())) {
            if (!ds.hasSecret()) throw new IOException("Bitte den geheimen Clientschlüssel der App-Registrierung angeben.");
            if (!secretCipher.available()) throw new IOException("Auf dem Server fehlt der Master-Key für gespeicherte Passwörter.");
            return new SharePointSourceConnector(ds.getLocation(), ds.getTenantId(), ds.getClientId(),
                    secretCipher.decrypt(ds.getSecretCipher()));
        }
        throw new IOException("Unbekannter Quellentyp.");
    }

    public List<Path> localRoots() {
        return localRoots;
    }

    // ------------------------------------------------------------------ preview

    public Preview preview(DataSource ds) {
        try (SourceConnector c = connect(ds)) {
            List<SourceConnector.Entry> all = c.list(MAX_FILES);
            Filter f = new Filter(ds);
            List<SourceConnector.Entry> accepted = new ArrayList<>();
            int skippedType = 0, skippedSize = 0, skippedPattern = 0;
            long bytes = 0;
            for (SourceConnector.Entry e : all) {
                switch (f.check(e)) {
                    case OK -> { accepted.add(e); bytes += e.sizeBytes(); }
                    case TYPE -> skippedType++;
                    case SIZE -> skippedSize++;
                    case PATTERN -> skippedPattern++;
                }
            }
            Map<String, Integer> counts = new HashMap<>();
            Map<String, Integer> reasons = new HashMap<>();
            int sampled = 0, unreadable = 0;
            if (ds.isMirrorPermissions()) {
                for (SourceConnector.Entry e : accepted.subList(0, Math.min(PREVIEW_SAMPLE, accepted.size()))) {
                    sampled++;
                    SourceConnector.FileAcl acl;
                    try {
                        acl = c.acl(e.relativePath());
                    } catch (IOException ex) {
                        unreadable++;
                        reasons.merge(message(ex), 1, Integer::sum); // nur eigene deutsche Texte, keine Pfade
                        continue;
                    }
                    for (String p : acl.allow()) {
                        if (!acl.deny().contains(p)) counts.merge(p, 1, Integer::sum);
                    }
                }
            }
            List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(20).toList();
            Map<String, String> names = directoryService == null ? Map.of()
                    : directoryService.resolveNames(ds.getOrgId(), sorted.stream().map(Map.Entry::getKey).toList());
            List<PrincipalCount> top = sorted.stream()
                    .map(en -> new PrincipalCount(en.getKey(), en.getValue(), names.get(en.getKey()))).toList();
            String msg = all.size() >= MAX_FILES
                    ? "Mehr als " + MAX_FILES + " Dateien – bitte die Auswahl eingrenzen."
                    : "Verbunden.";
            return new Preview(all.size(), accepted.size(), bytes, skippedType, skippedSize, skippedPattern,
                    accepted.stream().limit(10).map(SourceConnector.Entry::relativePath).toList(),
                    top, sampled, unreadable, mostFrequent(reasons), msg);
        } catch (IOException | RuntimeException e) {
            return new Preview(0, 0, 0, 0, 0, 0, List.of(), List.of(), 0, 0, null, message(e));
        }
    }

    /** Häufigster Grund für unlesbare Rechte im Probelauf (für den Hinweis im Assistenten). */
    static String mostFrequent(Map<String, Integer> reasons) {
        return reasons.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
    }

    // ------------------------------------------------------------------ sync

    void runNow(Long id) {
        if (!running.add(id)) return;
        try {
            DataSource ds = tx.execute(s -> sources.findById(id).map(d -> {
                d.runStarted();
                return sources.save(d);
            }).orElse(null));
            if (ds == null) return;
            int seen = 0, indexed = 0, failed = 0;
            String status;
            String message = null;
            try (SourceConnector c = connect(ds)) {
                List<SourceConnector.Entry> entries = c.list(MAX_FILES);
                Filter f = new Filter(ds);
                Map<String, DataSourceFile> known = new HashMap<>();
                for (DataSourceFile row : files.findByDataSourceId(ds.getId())) known.put(row.getRelativePath(), row);
                Set<String> present = new java.util.HashSet<>();
                for (SourceConnector.Entry e : entries) {
                    if (f.check(e) != Verdict.OK) continue;
                    seen++;
                    present.add(e.relativePath());
                    try {
                        if (syncFile(ds, c, e, known.get(e.relativePath()))) indexed++;
                    } catch (RuntimeException | IOException ex) {
                        failed++;
                        recordError(ds.getId(), e.relativePath(), message(ex));
                    }
                }
                for (DataSourceFile row : known.values()) {
                    if (!present.contains(row.getRelativePath())) removeFile(row);
                }
                status = failed == 0 ? "OK" : "PARTIAL";
                if (entries.size() >= MAX_FILES) message = "Mehr als " + MAX_FILES + " Dateien – nur die ersten wurden abgeglichen.";
                else if (failed > 0) message = failed + " Datei(en) konnten nicht übernommen werden.";
            } catch (IOException | RuntimeException e) {
                status = "FAILED";
                message = message(e);
                log.warn("datasource {} sync failed ({})", id, e.getClass().getSimpleName());
            }
            final String st = status, msg = message;
            final int s1 = seen, s2 = indexed, s3 = failed;
            tx.executeWithoutResult(t -> sources.findById(id).ifPresent(d -> {
                d.runFinished(st, msg, s1, s2, s3);
                sources.save(d);
            }));
        } finally {
            running.remove(id);
        }
    }

    /** true = (re)indexed; false = unchanged or only rights updated. */
    boolean syncFile(DataSource ds, SourceConnector c, SourceConnector.Entry e, DataSourceFile known)
            throws IOException {
        if (ds.isMirrorPermissions() && c instanceof LocalSourceConnector local) {
            return syncLocalSnapshot(ds, local, e, known);
        }
        SourceConnector.FileAcl acl;
        if (ds.isMirrorPermissions()) {
            try {
                acl = c.acl(e.relativePath());
            } catch (IOException ex) {
                acl = SourceConnector.FileAcl.unknown(); // fail-closed
            }
        } else {
            acl = new SourceConnector.FileAcl(Set.of(AccessFilter.EVERYONE), Set.of());
        }
        String fingerprint = fingerprint(acl);
        boolean contentChanged = known == null || known.getDocumentId() == null
                || known.getSizeBytes() != e.sizeBytes() || !known.getModifiedAt().equals(e.modifiedAt());
        if (!contentChanged) {
            if (!fingerprint.equals(known.getAclFingerprint())) {
                final SourceConnector.FileAcl a = acl;
                tx.executeWithoutResult(t -> {
                    writeAcl(known.getDocumentId(), a);
                    DataSourceFile row = files.findById(known.getId()).orElseThrow();
                    row.setAclFingerprint(fingerprint);
                    row.setLastError(null);
                    files.save(row);
                });
            }
            return false;
        }
        final SourceConnector.FileAcl a = acl;
        try (InputStream in = c.open(e.relativePath())) {
            tx.executeWithoutResult(t -> {
                DataSourceFile row = known == null
                        ? new DataSourceFile(ds.getId(), e.relativePath())
                        : files.findById(known.getId()).orElse(new DataSourceFile(ds.getId(), e.relativePath()));
                if (row.getDocumentId() != null) documentService.removeFromSource(row.getDocumentId());
                String name = e.relativePath().substring(e.relativePath().lastIndexOf('/') + 1);
                Document doc = documentService.ingestFromSource(ds.getOrgId(), ds.getSpaceId(),
                        ds.getKnowledgeBaseId(), name, e.sizeBytes(), in, ds.getCreatedBy());
                writeAcl(doc.getId(), a); // same transaction: never visible without its rights
                row.setDocumentId(doc.getId());
                row.setState(e.sizeBytes(), e.modifiedAt());
                row.setAclFingerprint(fingerprint);
                row.setLastError(null);
                files.save(row);
            });
        }
        return true;
    }

    private boolean syncLocalSnapshot(DataSource ds, LocalSourceConnector local, SourceConnector.Entry entry,
                                      DataSourceFile known) throws IOException {
        long maximum = Math.min(ds.getMaxFileBytes() <= 0 ? uploadMaxBytes : ds.getMaxFileBytes(), uploadMaxBytes);
        try (var snapshot = local.snapshot(entry.relativePath(), maximum)) {
            boolean[] changed = {false};
            try (InputStream content = snapshot.open()) {
                tx.executeWithoutResult(t -> {
                    DataSourceFile row = known == null ? new DataSourceFile(ds.getId(), entry.relativePath())
                            : files.findById(known.getId()).orElse(new DataSourceFile(ds.getId(), entry.relativePath()));
                    // Size/mtime from a prior listing can name a different inode. A local
                    // mirror always snapshots and compares content before changing its ACL.
                    if (row.getDocumentId() == null || !documentService.sourceContentMatches(row.getDocumentId(), snapshot.sha256())) {
                        if (row.getDocumentId() != null) documentService.removeFromSource(row.getDocumentId());
                        String name = entry.relativePath().substring(entry.relativePath().lastIndexOf('/') + 1);
                        Document doc = documentService.ingestFromSource(ds.getOrgId(), ds.getSpaceId(),
                                ds.getKnowledgeBaseId(), name, snapshot.size(), content, ds.getCreatedBy());
                        row.setDocumentId(doc.getId());
                        changed[0] = true;
                    }
                    writeAcl(row.getDocumentId(), snapshot.acl());
                    row.setState(snapshot.size(), snapshot.modifiedAt());
                    row.setAclFingerprint(fingerprint(snapshot.acl()));
                    row.setLastError(null);
                    files.save(row);
                });
            }
            return changed[0];
        } catch (IOException | RuntimeException failure) {
            // A failed/missing native reader must also revoke an older broad ACL.
            if (known != null && known.getDocumentId() != null) {
                tx.executeWithoutResult(t -> writeAcl(known.getDocumentId(), SourceConnector.FileAcl.unknown()));
            }
            throw failure;
        }
    }

    void writeAcl(Long documentId, SourceConnector.FileAcl acl) {
        aclRepository.deleteByDocumentId(documentId);
        aclRepository.flush();
        if (acl.allow().isEmpty() && acl.deny().isEmpty()) {
            // no readable entry at all → nobody (fail-closed)
            aclRepository.save(new DocumentAcl(documentId, AccessFilter.EVERYONE, false));
            return;
        }
        for (String p : acl.allow()) aclRepository.save(new DocumentAcl(documentId, p, true));
        for (String p : acl.deny()) aclRepository.save(new DocumentAcl(documentId, p, false));
        if (acl.allow().isEmpty() && !acl.deny().contains(AccessFilter.EVERYONE)) {
            aclRepository.save(new DocumentAcl(documentId, AccessFilter.EVERYONE, false));
        }
    }

    private void removeFile(DataSourceFile row) {
        tx.executeWithoutResult(t -> {
            if (row.getDocumentId() != null) documentService.removeFromSource(row.getDocumentId());
            files.deleteById(row.getId());
        });
    }

    /** Entfernt beim Löschen einer Quelle alle von ihr übernommenen Dokumente. */
    public void purge(Long dataSourceId) {
        for (DataSourceFile row : files.findByDataSourceId(dataSourceId)) removeFile(row);
    }

    private void recordError(Long dsId, String path, String error) {
        try {
            tx.executeWithoutResult(t -> {
                DataSourceFile row = files.findByDataSourceId(dsId).stream()
                        .filter(r -> r.getRelativePath().equals(path)).findFirst()
                        .orElse(new DataSourceFile(dsId, path));
                row.setLastError(error);
                files.save(row);
            });
        } catch (RuntimeException ignored) {
            // error bookkeeping must never abort the run
        }
    }

    static String fingerprint(SourceConnector.FileAcl acl) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("A" + new TreeSet<>(acl.allow()) + "D" + new TreeSet<>(acl.deny())).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String message(Exception e) {
        String m = e.getMessage();
        if (e instanceof DocumentUploadPolicy.TooLargeException) return "Datei ist zu groß.";
        if (e instanceof DocumentUploadPolicy.RejectedTypeException) return "Dateityp wird nicht unterstützt.";
        if (e instanceof DocumentUploadPolicy.InvalidDocumentException) return "Datei ist leer oder ungültig.";
        if (m != null && !m.isBlank() && m.length() < 300 && Character.isUpperCase(m.charAt(0)) && m.contains(" ")) {
            return m; // our own German connector messages
        }
        return "Unerwarteter Fehler (" + e.getClass().getSimpleName() + ").";
    }

    // ------------------------------------------------------------------ filter

    enum Verdict { OK, TYPE, SIZE, PATTERN }

    final class Filter {
        private final List<PathMatcher> include = new ArrayList<>();
        private final List<PathMatcher> exclude = new ArrayList<>();
        private final long maxBytes;

        Filter(DataSource ds) {
            for (String p : split(ds.getIncludePatterns())) include.add(glob(p));
            for (String p : split(ds.getExcludePatterns())) exclude.add(glob(p));
            this.maxBytes = Math.min(ds.getMaxFileBytes() <= 0 ? uploadMaxBytes : ds.getMaxFileBytes(), uploadMaxBytes);
        }

        Verdict check(SourceConnector.Entry e) {
            String name = e.relativePath().substring(e.relativePath().lastIndexOf('/') + 1);
            if (!DocumentUploadPolicy.isAcceptedFilename(name)) return Verdict.TYPE;
            if (e.sizeBytes() <= 0 || e.sizeBytes() > maxBytes) return Verdict.SIZE;
            Path rel = Path.of(e.relativePath().toLowerCase(Locale.ROOT));
            if (!include.isEmpty() && include.stream().noneMatch(m -> m.matches(rel))) return Verdict.PATTERN;
            if (exclude.stream().anyMatch(m -> m.matches(rel))) return Verdict.PATTERN;
            return Verdict.OK;
        }
    }

    static List<String> split(String patterns) {
        if (patterns == null) return List.of();
        return Arrays.stream(patterns.split("[,\\n]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static PathMatcher glob(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern.toLowerCase(Locale.ROOT));
    }

    /** Für die UI: lesbare Bezeichnung bekannter Prinzipale. */
    static Map<String, String> wellKnown() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(AccessFilter.EVERYONE, "Alle angemeldeten Personen");
        return m;
    }
}
