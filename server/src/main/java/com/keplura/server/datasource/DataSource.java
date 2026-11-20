package com.keplura.server.datasource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** ADR-0012 §1: eine nur lesende Datenquelle, die genau eine Wissensbasis füllt. */
@Entity
@Table(name = "data_source")
public class DataSource {

    public static final String KIND_LOCAL = "LOCAL";
    public static final String KIND_SMB = "SMB";
    public static final String KIND_SHAREPOINT = "SHAREPOINT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "org_id", nullable = false) private Long orgId;
    @Column(name = "space_id", nullable = false) private Long spaceId;
    @Column(name = "knowledge_base_id", nullable = false) private Long knowledgeBaseId;
    @Column(nullable = false, length = 20) private String kind;
    @Column(name = "display_name", nullable = false, length = 200) private String displayName;
    @Column(nullable = false, length = 1000) private String location;
    @Column(length = 300) private String username;
    @Column(name = "tenant_id", length = 100) private String tenantId;
    @Column(name = "client_id", length = 100) private String clientId;
    @Column(name = "secret_cipher", length = 2000) private String secretCipher;
    @Column(name = "include_patterns", nullable = false, length = 2000) private String includePatterns;
    @Column(name = "exclude_patterns", nullable = false, length = 20000) private String excludePatterns;
    @Column(name = "max_file_bytes", nullable = false) private long maxFileBytes;
    @Column(name = "mirror_permissions", nullable = false) private boolean mirrorPermissions;
    @Column(name = "interval_minutes", nullable = false) private int intervalMinutes;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "last_run_started_at") private Instant lastRunStartedAt;
    @Column(name = "last_run_finished_at") private Instant lastRunFinishedAt;
    @Column(name = "last_run_status", length = 20) private String lastRunStatus;
    @Column(name = "last_run_message", length = 1000) private String lastRunMessage;
    @Column(name = "files_seen", nullable = false) private int filesSeen;
    @Column(name = "files_indexed", nullable = false) private int filesIndexed;
    @Column(name = "files_failed", nullable = false) private int filesFailed;
    @Column(name = "created_by", nullable = false) private Long createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected DataSource() {
    }

    public DataSource(Long orgId, Long spaceId, Long knowledgeBaseId, Long createdBy) {
        this.orgId = orgId;
        this.spaceId = spaceId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void runStarted() {
        lastRunStartedAt = Instant.now();
        lastRunStatus = "RUNNING";
        lastRunMessage = null;
    }

    public void runFinished(String status, String message, int seen, int indexed, int failed) {
        lastRunFinishedAt = Instant.now();
        lastRunStatus = status;
        lastRunMessage = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
        filesSeen = seen;
        filesIndexed = indexed;
        filesFailed = failed;
    }

    public boolean isDue(Instant now) {
        if (!enabled) return false;
        if ("RUNNING".equals(lastRunStatus)) return false;
        return lastRunStartedAt == null || lastRunStartedAt.plusSeconds(60L * intervalMinutes).isBefore(now);
    }

    public Long getId() { return id; }
    public Long getOrgId() { return orgId; }
    public Long getSpaceId() { return spaceId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getSecretCipher() { return secretCipher; }
    public void setSecretCipher(String secretCipher) { this.secretCipher = secretCipher; }
    public boolean hasSecret() { return secretCipher != null && !secretCipher.isBlank(); }
    public String getIncludePatterns() { return includePatterns; }
    public void setIncludePatterns(String includePatterns) { this.includePatterns = includePatterns; }
    public String getExcludePatterns() { return excludePatterns; }
    public void setExcludePatterns(String excludePatterns) { this.excludePatterns = excludePatterns; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public boolean isMirrorPermissions() { return mirrorPermissions; }
    public void setMirrorPermissions(boolean mirrorPermissions) { this.mirrorPermissions = mirrorPermissions; }
    public int getIntervalMinutes() { return intervalMinutes; }
    public void setIntervalMinutes(int intervalMinutes) { this.intervalMinutes = intervalMinutes; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getLastRunStartedAt() { return lastRunStartedAt; }
    public Instant getLastRunFinishedAt() { return lastRunFinishedAt; }
    public String getLastRunStatus() { return lastRunStatus; }
    public String getLastRunMessage() { return lastRunMessage; }
    public int getFilesSeen() { return filesSeen; }
    public int getFilesIndexed() { return filesIndexed; }
    public int getFilesFailed() { return filesFailed; }
    public Long getCreatedBy() { return createdBy; }
}
