package com.keplura.server.datasource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** ADR-0012 §4: Abgleichstand einer Datei (Delta-Erkennung und Löschungen). */
@Entity
@Table(name = "data_source_file")
public class DataSourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    @Column(name = "relative_path", nullable = false, length = 2000) private String relativePath;
    @Column(name = "document_id") private Long documentId;
    @Column(name = "size_bytes", nullable = false) private long sizeBytes;
    @Column(name = "modified_at", nullable = false) private Instant modifiedAt;
    @Column(name = "acl_fingerprint", nullable = false, length = 64) private String aclFingerprint;
    @Column(name = "last_error", length = 500) private String lastError;

    protected DataSourceFile() {
    }

    public DataSourceFile(Long dataSourceId, String relativePath) {
        this.dataSourceId = dataSourceId;
        this.relativePath = relativePath;
        this.aclFingerprint = "";
        this.modifiedAt = Instant.EPOCH;
    }

    public Long getId() { return id; }
    public Long getDataSourceId() { return dataSourceId; }
    public String getRelativePath() { return relativePath; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getModifiedAt() { return modifiedAt; }
    public void setState(long sizeBytes, Instant modifiedAt) { this.sizeBytes = sizeBytes; this.modifiedAt = modifiedAt; }
    public String getAclFingerprint() { return aclFingerprint; }
    public void setAclFingerprint(String aclFingerprint) { this.aclFingerprint = aclFingerprint; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) {
        this.lastError = lastError == null ? null : lastError.substring(0, Math.min(lastError.length(), 500));
    }
}
