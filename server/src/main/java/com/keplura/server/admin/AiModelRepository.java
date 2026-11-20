package com.keplura.server.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for {@link AiModel} (ADMIN-KI SCOPE C). */
public interface AiModelRepository extends JpaRepository<AiModel, Long> {

    /**
     * The DEFAULT lookup used by the runtime resolver: one enabled default per
     * type. The predicate re-proves enabled-ness (a race against a concurrent
     * disable must not hand the resolver a dead row). Returns at most one row
     * while the {@code default_slot} guard holds; {@code ORDER BY id} keeps
     * the choice deterministic even on a historically corrupt table.
     */
    Optional<AiModel> findFirstByModelTypeAndIsDefaultTrueAndEnabledTrueOrderByIdAsc(String modelType);

    List<AiModel> findAllByOrderByProviderIdAscIdAsc();

    List<AiModel> findByProviderIdOrderByIdAsc(Long providerId);

    List<AiModel> findByModelTypeAndEnabledTrueOrderByIdAsc(String modelType);

    /** The sibling that currently holds the default slot of a type (for the swap). */
    @Query("select m from AiModel m where m.modelType = :type and m.defaultSlot is not null and m.id <> :excludeId")
    List<AiModel> findOtherDefaults(@Param("type") String modelType, @Param("excludeId") long excludeId);

    long countByProviderId(Long providerId);

    boolean existsByModelTypeAndIsDefaultTrueAndEnabledTrue(String modelType);
}
