package com.keplura.server.admin;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link AiProvider} (ADMIN-KI SCOPE B). */
public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    boolean existsByNameIgnoreCase(String name);

    /** True when at least one ENABLED provider row exists (seed-offer + status projection). */
    boolean existsByEnabledTrue();
}
