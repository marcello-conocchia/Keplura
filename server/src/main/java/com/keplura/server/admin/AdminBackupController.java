package com.keplura.server.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * PILOT 4e — Datensicherung in der Administration sichtbar machen.
 *
 * <p>Read-only: {@code scripts/backup.sh} schreibt nach einer VERIFIZIERTEN
 * Sicherung eine kleine Statusdatei (ohne Secrets) neben die Dokumentablage;
 * dieser Endpunkt liest sie. Sicherung und Wiederherstellung selbst bleiben
 * Server-Befehle — eine Wiederherstellung ersetzt den laufenden Datenbestand
 * und gehört nicht hinter einen Klick im Browser. Liegt unter
 * {@code /api/v1/admin/**}: Session + CSRF + globale ADMIN-Rolle (ADR-0008).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBackupController {

    /** Älter als das gilt eine Sicherung als überfällig (Hinweis in der UI). */
    static final Duration STALE_AFTER = Duration.ofHours(26);
    private static final long MAX_STATUS_BYTES = 4096;

    public record LastBackup(Instant finishedAt, String backupName, long databaseBytes, long documentsBytes,
                             boolean verified, boolean online, long ageHours, boolean stale) {
    }

    public record BackupStatus(boolean statusFileFound, LastBackup lastBackup) {
    }

    private final Path statusFile;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AdminBackupController(@Value("${keplura.backup.status-file:/var/lib/keplura/backup-status.json}") String statusFile) {
        this.statusFile = Path.of(statusFile);
    }

    @GetMapping("/backup")
    public BackupStatus backup() {
        try {
            if (!Files.isRegularFile(statusFile) || Files.size(statusFile) > MAX_STATUS_BYTES) {
                return new BackupStatus(false, null);
            }
            JsonNode n = mapper.readTree(Files.readAllBytes(statusFile));
            Instant finished = Instant.parse(n.path("finishedAt").asString(""));
            long age = Math.max(0, Duration.between(finished, Instant.now()).toHours());
            String name = n.path("backupName").asString("");
            if (!name.matches("[A-Za-z0-9._-]{0,100}")) {
                name = "";
            }
            return new BackupStatus(true, new LastBackup(finished, name,
                    Math.max(0, n.path("databaseBytes").asLong(0)),
                    Math.max(0, n.path("documentsBytes").asLong(0)),
                    n.path("verified").asBoolean(false),
                    n.path("online").asBoolean(false),
                    age,
                    Duration.between(finished, Instant.now()).compareTo(STALE_AFTER) > 0));
        } catch (Exception e) {
            // unreadable/corrupt status file = "no usable status", never a 500
            return new BackupStatus(false, null);
        }
    }
}
