package com.keplura.server.datasource;

import com.keplura.server.access.AccessFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RISK-01 — Oversharing-Check: prüft VOR der Übernahme für jede Datei einer
 * Quelle, ob sie für (praktisch) alle lesbar ist und ob Pfad/Name auf sensible
 * Inhalte hindeutet. Liest Dateiliste und Rechte und schreibt nichts — weder
 * in Keplura noch in die Ablage.
 *
 * <p>RISK-02: Ist der {@link ExposureContentInspector} aktiv, wird zusätzlich der Inhalt
 * breit lesbarer Dateien auf sensible Muster geprüft. Dabei werden diese Dateien
 * gelesen (nur sie); im Bericht stehen nur Kategorie und Anzahl, nie Werte.
 *
 * <p>Ergebnisse liegen nur im Speicher (je Quelle das letzte); nach einem
 * Neustart wird neu geprüft. Pfade sind Admin-Daten und werden nicht geloggt.
 */
@Service
public class ExposureScanService {

    private static final Logger log = LoggerFactory.getLogger(ExposureScanService.class);
    static final int MAX_FINDINGS = 500;
    static final int MAX_FOLDERS = 100;

    public record Group(String principal, String label, int files) {
    }

    public record Folder(String folder, int broadFiles, int sensitiveFiles) {
    }

    /**
     * {@code contentState}: {@code CHECKED}, {@code NOT_CHECKABLE} oder {@code null} (Inhalt nicht geprüft).
     * {@code contentHits}: nur Kategorie + Anzahl.
     */
    public record Finding(String relativePath, List<String> categories, List<String> broadGroups,
                          String contentState, String contentReason, List<ContentPatterns.Hit> contentHits) {
        Finding(String relativePath, List<String> categories, List<String> broadGroups) {
            this(relativePath, categories, broadGroups, null, null, List.of());
        }
    }

    /** RISK-02: Inhaltsprüfung der breit lesbaren Dateien. {@code skipped} = über der Dateigrenze. */
    public record ContentSummary(boolean enabled, int checked, int sensitive, int notCheckable, int skipped) {
        static final ContentSummary OFF = new ContentSummary(false, 0, 0, 0, 0);
    }

    public record Report(String state, Instant startedAt, Instant finishedAt, int filesTotal, int filesChecked,
                         int broadFiles, int sensitiveBroadFiles, int sensitiveFiles, int unreadable,
                         boolean mirrorPermissions, boolean truncated, List<Group> broadGroups,
                         List<Folder> folders, List<Finding> findings, String message, ContentSummary content,
                         int findingsTotal) {

        static Report running(Instant started, int total, int checked, boolean mirror) {
            return running(started, total, checked, mirror, null);
        }

        static Report running(Instant started, int total, int checked, boolean mirror, ContentSummary content) {
            return new Report("RUNNING", started, null, total, checked, 0, 0, 0, 0, mirror, false,
                    List.of(), List.of(), List.of(), null, content, 0);
        }
    }

    private final DataSourceSyncService sync;
    private final DataSourceRepository sources;
    private final Map<Long, Report> reports = new ConcurrentHashMap<>();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "datasource-exposure");
        t.setDaemon(true);
        return t;
    });
    private com.keplura.server.directory.DirectoryService directoryService;
    private ExposureContentInspector contentInspector;

    public ExposureScanService(DataSourceSyncService sync, DataSourceRepository sources) {
        this.sync = sync;
        this.sources = sources;
    }

    @Autowired(required = false)
    void setDirectoryService(com.keplura.server.directory.DirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    @Autowired(required = false)
    void setContentInspector(ExposureContentInspector contentInspector) {
        this.contentInspector = contentInspector;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** false = für diese Quelle läuft schon eine Prüfung. */
    public boolean start(Long dataSourceId) {
        if (!running.add(dataSourceId)) return false;
        reports.put(dataSourceId, Report.running(Instant.now(), 0, 0, true));
        try {
            executor.submit(() -> {
                try {
                    sources.findById(dataSourceId).ifPresentOrElse(
                            ds -> reports.put(dataSourceId, scan(ds)),
                            () -> reports.remove(dataSourceId));
                } finally {
                    running.remove(dataSourceId);
                }
            });
        } catch (RuntimeException e) {
            running.remove(dataSourceId);
            throw e;
        }
        return true;
    }

    public Report report(Long dataSourceId) {
        return reports.get(dataSourceId);
    }

    /** Nach einem Ausschluss: betroffene Treffer aus dem gespeicherten Bericht entfernen. */
    public void forget(Long dataSourceId, Set<String> relativePaths) {
        reports.computeIfPresent(dataSourceId, (id, r) -> new Report(r.state(), r.startedAt(), r.finishedAt(),
                r.filesTotal(), r.filesChecked(), r.broadFiles(), r.sensitiveBroadFiles(), r.sensitiveFiles(),
                r.unreadable(), r.mirrorPermissions(), r.truncated(), r.broadGroups(), r.folders(),
                r.findings().stream().filter(f -> !relativePaths.contains(f.relativePath())).toList(),
                r.message(), r.content(), r.findingsTotal()));
    }

    public void discard(Long dataSourceId) {
        reports.remove(dataSourceId);
    }

    // ------------------------------------------------------------------ scan

    Report scan(DataSource ds) {
        Instant started = Instant.now();
        boolean mirror = ds.isMirrorPermissions();
        try (SourceConnector c = sync.connect(ds)) {
            List<SourceConnector.Entry> all = c.list(DataSourceSyncService.MAX_FILES);
            DataSourceSyncService.Filter filter = sync.new Filter(ds);
            List<SourceConnector.Entry> accepted = all.stream()
                    .filter(e -> filter.check(e) == DataSourceSyncService.Verdict.OK).toList();
            boolean inspectEnabled = contentInspector != null && contentInspector.enabled();
            ContentSummary runningContent = inspectEnabled ? new ContentSummary(true, 0, 0, 0, 0) : ContentSummary.OFF;
            reports.put(ds.getId(), Report.running(started, accepted.size(), 0, mirror, runningContent));

            Map<String, Integer> groupCounts = new HashMap<>();
            Map<String, int[]> folderCounts = new HashMap<>();
            List<Finding> findings = new ArrayList<>();
            int broad = 0, sensitiveBroad = 0, sensitive = 0, unreadable = 0, candidates = 0;
            boolean inspect = inspectEnabled;
            long contentDeadline = inspect ? System.currentTimeMillis() + contentInspector.maxRunMillis() : 0;
            int cChecked = 0, cSensitive = 0, cNotCheckable = 0, cSkipped = 0;
            AtomicInteger checked = new AtomicInteger();
            for (SourceConnector.Entry e : accepted) {
                if (Thread.currentThread().isInterrupted()) break;
                SourceConnector.FileAcl acl;
                if (!mirror) {
                    acl = new SourceConnector.FileAcl(Set.of(AccessFilter.EVERYONE), Set.of());
                } else {
                    try {
                        acl = c.acl(e.relativePath());
                    } catch (IOException ex) {
                        acl = null;
                    }
                }
                int n = checked.incrementAndGet();
                if (n % (inspect ? 10 : 200) == 0) reports.put(ds.getId(), Report.running(started, accepted.size(), n, mirror,
                        runningContent));
                Set<String> categories = ExposureRules.sensitiveCategories(e.relativePath());
                if (!categories.isEmpty()) sensitive++;
                if (acl == null) { // fail-closed beim Abgleich: für niemanden sichtbar
                    unreadable++;
                    continue;
                }
                List<String> broadHere = broadPrincipals(acl);
                if (broadHere.isEmpty()) continue;
                broad++;
                for (String p : broadHere) groupCounts.merge(p, 1, Integer::sum);
                int[] fc = folderCounts.computeIfAbsent(ExposureRules.folderOf(e.relativePath()), k -> new int[2]);
                fc[0]++;
                if (!categories.isEmpty()) sensitiveBroad++;
                ExposureContentInspector.Result content = null;
                if (inspect) {
                    if (cChecked + cNotCheckable < contentInspector.maxFiles()
                            && System.currentTimeMillis() < contentDeadline) {
                        content = contentInspector.inspect(c, e);
                        if (ExposureContentInspector.CHECKED.equals(content.state())) {
                            cChecked++;
                            if (!content.hits().isEmpty()) cSensitive++;
                        } else {
                            cNotCheckable++;
                        }
                    } else {
                        cSkipped++;
                    }
                }
                boolean contentHit = content != null && !content.hits().isEmpty();
                boolean notCheckable = content != null && ExposureContentInspector.NOT_CHECKABLE.equals(content.state());
                if (!categories.isEmpty() || contentHit) fc[1]++;
                if (!categories.isEmpty() || contentHit || notCheckable) {
                    candidates++;
                    if (findings.size() < MAX_FINDINGS) {
                        findings.add(new Finding(e.relativePath(), List.copyOf(categories),
                                broadHere.stream().map(ExposureRules::broadLabel).distinct().toList(),
                                content == null ? null : content.state(),
                                content == null ? null : content.reason(),
                                content == null ? List.of() : List.copyOf(content.hits())));
                    }
                }
            }
            List<Group> groups = groupCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(en -> new Group(en.getKey(), groupLabel(ds, en.getKey()), en.getValue())).toList();
            List<Folder> folders = folderCounts.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, int[]> en) -> en.getValue()[0]).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(MAX_FOLDERS)
                    .map(en -> new Folder(en.getKey(), en.getValue()[0], en.getValue()[1])).toList();
            findings.sort(Comparator.comparing(Finding::relativePath));
            boolean truncated = all.size() >= DataSourceSyncService.MAX_FILES || candidates > findings.size();
            ContentSummary contentSummary = inspect
                    ? new ContentSummary(true, cChecked, cSensitive, cNotCheckable, cSkipped) : ContentSummary.OFF;
            String message = !mirror
                    ? "Die Rechte-Übernahme ist ausgeschaltet: Alle Mitglieder der Wissensbasis sehen alle Dateien."
                    : all.size() >= DataSourceSyncService.MAX_FILES
                    ? "Mehr als " + DataSourceSyncService.MAX_FILES + " Dateien – nur die ersten wurden geprüft."
                    : null;
            return new Report("DONE", started, Instant.now(), accepted.size(), checked.get(), broad, sensitiveBroad,
                    sensitive, unreadable, mirror, truncated, groups, folders, List.copyOf(findings), message,
                    contentSummary, candidates);
        } catch (IOException | RuntimeException e) {
            log.info("datasource {} exposure scan failed ({})", ds.getId(), e.getClass().getSimpleName());
            return new Report("FAILED", started, Instant.now(), 0, 0, 0, 0, 0, 0, mirror, false,
                    List.of(), List.of(), List.of(), DataSourceSyncService.message(e), null, 0);
        }
    }

    /** Breite Prinzipale, die tatsächlich lesen dürfen (Verweigern hat Vorrang, wie im AccessFilter). */
    static List<String> broadPrincipals(SourceConnector.FileAcl acl) {
        if (acl.deny().contains(AccessFilter.EVERYONE)) return List.of();
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String p : acl.allow()) {
            if (ExposureRules.broadLabel(p) != null && !acl.deny().contains(p) && seen.add(p)) out.add(p);
        }
        out.sort(null);
        return out;
    }

    private String groupLabel(DataSource ds, String principal) {
        String label = ExposureRules.broadLabel(principal);
        if (directoryService == null || !principal.startsWith("sid:S-1-5-21-")) return label;
        try {
            String resolved = directoryService.resolveNames(ds.getOrgId(), List.of(principal)).get(principal);
            return resolved == null ? label : resolved + " (" + label + ")";
        } catch (RuntimeException e) {
            return label;
        }
    }
}
