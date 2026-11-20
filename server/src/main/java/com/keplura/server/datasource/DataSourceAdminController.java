package com.keplura.server.datasource;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.knowledge.KnowledgeBaseRepository;
import com.keplura.server.space.SpaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * ADR-0012 §5 — Datenquellen in der Administration (Assistent + Statusseite).
 * Unter {@code /api/v1/admin/**}: Session + CSRF + globale ADMIN-Rolle.
 * Zugangsdaten der Freigabe sind write-only.
 */
@RestController
@RequestMapping("/api/v1/admin/datasources")
public class DataSourceAdminController {

    public static class InputException extends RuntimeException {
        public InputException(String message) {
            super(message);
        }
    }

    public record ErrorResponse(String error) {
    }

    public record SourceInput(Long orgId, Long spaceId, Long knowledgeBaseId, String kind, String displayName,
                              String location, String username, String password, String includePatterns,
                              String excludePatterns, Long maxFileBytes, Boolean mirrorPermissions,
                              Integer intervalMinutes, Boolean enabled, String tenantId, String clientId) {
    }

    public record SourceView(Long id, Long orgId, Long spaceId, Long knowledgeBaseId, String kind, String displayName,
                             String location, String username, String tenantId, String clientId,
                             boolean secretSet, String includePatterns,
                             String excludePatterns, long maxFileBytes, boolean mirrorPermissions,
                             int intervalMinutes, boolean enabled, Instant lastRunStartedAt,
                             Instant lastRunFinishedAt, String lastRunStatus, String lastRunMessage,
                             int filesSeen, int filesIndexed, int filesFailed) {
    }

    public record FileProblem(String relativePath, String error) {
    }

    public record Capabilities(List<String> localRoots, List<String> kinds) {
    }

    /** RISK-01: Dateien (relative Pfade aus dem Bericht), die nicht übernommen werden sollen. */
    public record ExcludeInput(List<String> paths) {
    }

    static final int MAX_EXCLUDE_PATHS = 200;
    static final int MAX_EXCLUDE_TOTAL = 20_000;

    private final DataSourceRepository sources;
    private final DataSourceFileRepository files;
    private final DataSourceSyncService sync;
    private final SecretCipher secretCipher;
    private final KnowledgeBaseRepository kbRepository;
    private final SpaceRepository spaceRepository;
    private final ExposureScanService exposure;

    public DataSourceAdminController(DataSourceRepository sources, DataSourceFileRepository files,
                                     DataSourceSyncService sync, SecretCipher secretCipher,
                                     KnowledgeBaseRepository kbRepository, SpaceRepository spaceRepository,
                                     ExposureScanService exposure) {
        this.sources = sources;
        this.files = files;
        this.sync = sync;
        this.secretCipher = secretCipher;
        this.kbRepository = kbRepository;
        this.spaceRepository = spaceRepository;
        this.exposure = exposure;
    }

    /** Was diese Installation kann (z. B. freigegebene lokale Wurzeln für Linux-Ordner). */
    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(sync.localRoots().stream().map(Object::toString).toList(),
                List.of(DataSource.KIND_SMB, DataSource.KIND_LOCAL, DataSource.KIND_SHAREPOINT));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<SourceView> list() {
        return sources.findAllByOrderByIdAsc().stream().map(DataSourceAdminController::view).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<SourceView> create(@RequestBody SourceInput in, HttpServletRequest request) {
        if (in == null || in.orgId() == null || in.spaceId() == null || in.knowledgeBaseId() == null) {
            throw new InputException("Bitte Organisation, Arbeitsbereich und Wissensbasis wählen.");
        }
        var space = spaceRepository.findById(in.spaceId()).orElse(null);
        var kb = kbRepository.findById(in.knowledgeBaseId()).orElse(null);
        if (space == null || kb == null || !space.getOrgId().equals(in.orgId()) || !kb.getSpaceId().equals(in.spaceId())) {
            throw new InputException("Wissensbasis, Arbeitsbereich und Organisation passen nicht zusammen.");
        }
        DataSource ds = new DataSource(in.orgId(), in.spaceId(), in.knowledgeBaseId(), currentUser(request));
        ds.setIncludePatterns("");
        ds.setExcludePatterns("**/~$*, **/.*, **/archiv/**");
        ds.setMaxFileBytes(0);
        ds.setMirrorPermissions(true);
        ds.setIntervalMinutes(15);
        ds.setEnabled(false); // aktiv erst nach Probelauf (Assistent)
        apply(ds, in, true);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(sources.saveAndFlush(ds)));
    }

    @PatchMapping("/{id}")
    @Transactional
    public SourceView update(@PathVariable("id") Long id, @RequestBody SourceInput in) {
        DataSource ds = sources.findById(id).orElseThrow(() -> new InputException("not-found"));
        apply(ds, in, false);
        return view(sources.saveAndFlush(ds));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        DataSource ds = sources.findById(id).orElseThrow(() -> new InputException("not-found"));
        ds.setEnabled(false);
        sources.saveAndFlush(ds);
        sync.purge(id); // übernommene Dokumente entfernen (Originale bleiben unberührt)
        sources.deleteById(id);
        exposure.discard(id);
        return ResponseEntity.noContent().build();
    }

    /** Probelauf: verbindet, zählt, prüft Rechte an einer Stichprobe — schreibt nichts. */
    @PostMapping("/{id}/preview")
    public DataSourceSyncService.Preview preview(@PathVariable("id") Long id) {
        DataSource ds = sources.findById(id).orElseThrow(() -> new InputException("not-found"));
        return sync.preview(ds);
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<ErrorResponse> run(@PathVariable("id") Long id) {
        sources.findById(id).orElseThrow(() -> new InputException("not-found"));
        return sync.requestRun(id)
                ? ResponseEntity.accepted().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("Der Abgleich läuft bereits."));
    }

    /** RISK-01: Oversharing-Check starten (liest nur Dateiliste + Rechte, schreibt nichts). */
    @PostMapping("/{id}/exposure")
    public ResponseEntity<ErrorResponse> startExposure(@PathVariable("id") Long id) {
        sources.findById(id).orElseThrow(() -> new InputException("not-found"));
        return exposure.start(id)
                ? ResponseEntity.accepted().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("Die Prüfung läuft bereits."));
    }

    @GetMapping("/{id}/exposure")
    public ExposureScanService.Report exposure(@PathVariable("id") Long id) {
        sources.findById(id).orElseThrow(() -> new InputException("not-found"));
        ExposureScanService.Report r = exposure.report(id);
        if (r == null) throw new InputException("not-found");
        return r;
    }

    /**
     * RISK-01: einzelne Dateien von der Übernahme ausschließen. Hängt exakte Muster an die
     * Ausschlussliste an; der nächste Abgleich übernimmt sie nicht bzw. entfernt sie wieder.
     * Die Ablage selbst bleibt unverändert.
     */
    @PostMapping("/{id}/exclude")
    @Transactional
    public SourceView exclude(@PathVariable("id") Long id, @RequestBody ExcludeInput in) {
        DataSource ds = sources.findById(id).orElseThrow(() -> new InputException("not-found"));
        if (in == null || in.paths() == null || in.paths().isEmpty()) {
            throw new InputException("Bitte mindestens eine Datei wählen.");
        }
        if (in.paths().size() > MAX_EXCLUDE_PATHS) {
            throw new InputException("Bitte höchstens " + MAX_EXCLUDE_PATHS + " Dateien auf einmal ausschließen.");
        }
        java.util.LinkedHashSet<String> patterns = new java.util.LinkedHashSet<>(
                DataSourceSyncService.split(ds.getExcludePatterns()));
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (String raw : in.paths()) {
            if (raw == null || raw.isBlank() || raw.length() > 2000 || raw.startsWith("/")
                    || java.util.Arrays.asList(raw.split("/")).contains("..")) {
                throw new InputException("Ungültiger Dateipfad.");
            }
            paths.add(raw);
            patterns.add(ExposureRules.excludePatternFor(raw));
        }
        String joined = String.join(", ", patterns);
        if (joined.length() > MAX_EXCLUDE_TOTAL) {
            throw new InputException("Die Ausschlussliste wird zu lang. Schließe besser ganze Ordner aus "
                    + "(z. B. personal/**) oder bereinige die Rechte in der Ablage.");
        }
        ds.setExcludePatterns(joined);
        DataSource saved = sources.saveAndFlush(ds);
        exposure.forget(id, paths);
        return view(saved);
    }

    @GetMapping("/{id}/problems")
    @Transactional(readOnly = true)
    public List<FileProblem> problems(@PathVariable("id") Long id) {
        return files.findByDataSourceId(id).stream().filter(f -> f.getLastError() != null)
                .limit(200).map(f -> new FileProblem(f.getRelativePath(), f.getLastError())).toList();
    }

    @ExceptionHandler(InputException.class)
    public ResponseEntity<ErrorResponse> invalid(InputException e) {
        if ("not-found".equals(e.getMessage())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not found"));
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    // ------------------------------------------------------------------ helpers

    private void apply(DataSource ds, SourceInput in, boolean creating) {
        if (in == null) throw new InputException("Angaben fehlen.");
        if (in.kind() != null || creating) {
            String kind = in.kind() == null ? "" : in.kind().trim().toUpperCase(Locale.ROOT);
            if (!DataSource.KIND_SMB.equals(kind) && !DataSource.KIND_LOCAL.equals(kind)
                    && !DataSource.KIND_SHAREPOINT.equals(kind)) {
                throw new InputException("Typ muss Windows-Freigabe (SMB), Linux-Ordner (LOCAL) oder SharePoint sein.");
            }
            ds.setKind(kind);
        }
        ds.setDisplayName(text(in.displayName(), ds.getDisplayName(), "Name", 200, creating));
        ds.setLocation(text(in.location(), ds.getLocation(), "Pfad", 1000, creating));
        if (in.username() != null) ds.setUsername(in.username().isBlank() ? null : in.username().trim());
        if (in.tenantId() != null) ds.setTenantId(in.tenantId().isBlank() ? null : limit(in.tenantId(), 100));
        if (in.clientId() != null) ds.setClientId(in.clientId().isBlank() ? null : limit(in.clientId(), 100));
        if (in.password() != null && !in.password().isEmpty()) {
            if (!secretCipher.available()) {
                throw new InputException("Auf dem Server fehlt der Master-Key (KEPLURA_SECRETS_MASTER_KEY); "
                        + "das Passwort kann nicht sicher gespeichert werden.");
            }
            ds.setSecretCipher(secretCipher.encrypt(in.password()));
        }
        if (in.includePatterns() != null) ds.setIncludePatterns(limit(in.includePatterns(), 2000));
        if (in.excludePatterns() != null) ds.setExcludePatterns(limit(in.excludePatterns(), MAX_EXCLUDE_TOTAL));
        if (in.maxFileBytes() != null) ds.setMaxFileBytes(Math.max(0, in.maxFileBytes()));
        if (in.mirrorPermissions() != null) ds.setMirrorPermissions(in.mirrorPermissions());
        if (in.intervalMinutes() != null) {
            if (in.intervalMinutes() < 5 || in.intervalMinutes() > 10080) {
                throw new InputException("Der Abgleich-Abstand muss zwischen 5 Minuten und 7 Tagen liegen.");
            }
            ds.setIntervalMinutes(in.intervalMinutes());
        }
        if (in.enabled() != null) ds.setEnabled(in.enabled());
    }

    private static String text(String value, String current, String label, int max, boolean creating) {
        if (value == null || value.isBlank()) {
            if (!creating && current != null) return current;
            throw new InputException(label + " fehlt.");
        }
        String v = value.trim();
        if (v.length() > max) throw new InputException(label + " ist zu lang.");
        return v;
    }

    private static String limit(String v, int max) {
        String t = v.trim();
        if (t.length() > max) throw new InputException("Die Muster sind zu lang.");
        return t;
    }

    private static Long currentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object p = session == null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (p instanceof AuthPrincipal ap) return ap.userId();
        throw new IllegalStateException("admin request without principal");
    }

    static SourceView view(DataSource d) {
        return new SourceView(d.getId(), d.getOrgId(), d.getSpaceId(), d.getKnowledgeBaseId(), d.getKind(),
                d.getDisplayName(), d.getLocation(), d.getUsername(), d.getTenantId(), d.getClientId(),
                d.hasSecret(), d.getIncludePatterns(),
                d.getExcludePatterns(), d.getMaxFileBytes(), d.isMirrorPermissions(), d.getIntervalMinutes(),
                d.isEnabled(), d.getLastRunStartedAt(), d.getLastRunFinishedAt(), d.getLastRunStatus(),
                d.getLastRunMessage(), d.getFilesSeen(), d.getFilesIndexed(), d.getFilesFailed());
    }
}
