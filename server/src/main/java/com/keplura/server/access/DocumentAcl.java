package com.keplura.server.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ADR-0012 §3: one effective read entry (allow or deny) of a data-source document. */
@Entity
@Table(name = "kb_document_acl")
public class DocumentAcl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(nullable = false, length = 300)
    private String principal;

    @Column(name = "allow_read", nullable = false)
    private boolean allowRead;

    protected DocumentAcl() {
    }

    public DocumentAcl(Long documentId, String principal, boolean allowRead) {
        this.documentId = documentId;
        this.principal = principal;
        this.allowRead = allowRead;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getPrincipal() {
        return principal;
    }

    public boolean isAllowRead() {
        return allowRead;
    }
}
