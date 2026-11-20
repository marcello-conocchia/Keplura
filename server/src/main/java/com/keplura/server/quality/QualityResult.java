package com.keplura.server.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** DS-5: Ergebnis einer Testfrage in einem Prüflauf. */
@Entity
@Table(name = "quality_result")
public class QualityResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "run_id", nullable = false) private Long runId;
    @Column(name = "question_id", nullable = false) private Long questionId;
    @Column(nullable = false) private boolean hit;
    @Column(name = "hit_rank") private Integer hitRank;
    @Column(name = "top_document", length = 500) private String topDocument;

    protected QualityResult() {
    }

    public QualityResult(Long runId, Long questionId, boolean hit, Integer hitRank, String topDocument) {
        this.runId = runId;
        this.questionId = questionId;
        this.hit = hit;
        this.hitRank = hitRank;
        this.topDocument = topDocument == null ? null : topDocument.substring(0, Math.min(500, topDocument.length()));
    }

    public Long getQuestionId() { return questionId; }
    public boolean isHit() { return hit; }
    public Integer getHitRank() { return hitRank; }
    public String getTopDocument() { return topDocument; }
}
