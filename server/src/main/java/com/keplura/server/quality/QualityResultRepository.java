package com.keplura.server.quality;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QualityResultRepository extends JpaRepository<QualityResult, Long> {
    List<QualityResult> findByRunId(Long runId);
}
