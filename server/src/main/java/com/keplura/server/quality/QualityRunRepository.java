package com.keplura.server.quality;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QualityRunRepository extends JpaRepository<QualityRun, Long> {
    List<QualityRun> findTop10ByKnowledgeBaseIdOrderByIdDesc(Long kbId);
}
