package com.keplura.server.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** DS-5: ein Prüflauf über alle Testfragen einer Wissensbasis. */
@Entity
@Table(name = "quality_run")
public class QualityRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "knowledge_base_id", nullable = false) private Long knowledgeBaseId;
    @Column(name = "run_by", nullable = false) private Long runBy;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(nullable = false) private int total;
    @Column(nullable = false) private int hits;

    protected QualityRun() {
    }

    public QualityRun(Long kbId, Long runBy) {
        this.knowledgeBaseId = kbId;
        this.runBy = runBy;
        this.startedAt = Instant.now();
    }

    public void finish(int total, int hits) {
        this.total = total;
        this.hits = hits;
        this.finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getTotal() { return total; }
    public int getHits() { return hits; }
}
