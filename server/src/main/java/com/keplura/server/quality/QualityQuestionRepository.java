package com.keplura.server.quality;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QualityQuestionRepository extends JpaRepository<QualityQuestion, Long> {
    List<QualityQuestion> findByKnowledgeBaseIdOrderByIdAsc(Long kbId);
}
