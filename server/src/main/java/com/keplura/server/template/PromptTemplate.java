package com.keplura.server.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Gespeicherte Frage/Vorlage einer Organisation (spaceId null) oder eines Arbeitsbereichs. */
@Entity
@Table(name = "prompt_template")
public class PromptTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "org_id", nullable = false) private Long orgId;
    @Column(name = "space_id") private Long spaceId;
    @Column(nullable = false, length = 120) private String title;
    @Column(nullable = false, length = 4000) private String body;
    @Column(name = "created_by", nullable = false) private Long createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected PromptTemplate() {
    }

    public PromptTemplate(Long orgId, Long spaceId, String title, String body, Long createdBy) {
        this.orgId = orgId;
        this.spaceId = spaceId;
        this.title = title;
        this.body = body;
        this.createdBy = createdBy;
        this.createdAt = this.updatedAt = Instant.now();
    }

    public void update(String title, String body, Long spaceId) {
        this.title = title;
        this.body = body;
        this.spaceId = spaceId;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrgId() { return orgId; }
    public Long getSpaceId() { return spaceId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Long getCreatedBy() { return createdBy; }
}
