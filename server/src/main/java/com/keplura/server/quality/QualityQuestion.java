package com.keplura.server.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** DS-5: eine Testfrage mit erwarteter Quelle (Dateiname bzw. Teil davon) und optionalem Stichwort. */
@Entity
@Table(name = "quality_question")
public class QualityQuestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "org_id", nullable = false) private Long orgId;
    @Column(name = "space_id", nullable = false) private Long spaceId;
    @Column(name = "knowledge_base_id", nullable = false) private Long knowledgeBaseId;
    @Column(nullable = false, length = 2000) private String question;
    @Column(name = "expected_document", nullable = false, length = 500) private String expectedDocument;
    @Column(name = "expected_phrase", length = 500) private String expectedPhrase;
    @Column(name = "created_by", nullable = false) private Long createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected QualityQuestion() {
    }

    public QualityQuestion(Long orgId, Long spaceId, Long kbId, String question, String expectedDocument,
                           String expectedPhrase, Long createdBy) {
        this.orgId = orgId;
        this.spaceId = spaceId;
        this.knowledgeBaseId = kbId;
        this.question = question;
        this.expectedDocument = expectedDocument;
        this.expectedPhrase = expectedPhrase;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrgId() { return orgId; }
    public Long getSpaceId() { return spaceId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public String getQuestion() { return question; }
    public String getExpectedDocument() { return expectedDocument; }
    public String getExpectedPhrase() { return expectedPhrase; }
}
