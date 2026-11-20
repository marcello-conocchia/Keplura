package com.keplura.server.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    /** Org-wide templates plus those of the given space (spaceId may be null → org-wide only). */
    @Query("""
            select t from PromptTemplate t
            where t.orgId = :orgId and (t.spaceId is null or t.spaceId = :spaceId)
            order by t.title
            """)
    List<PromptTemplate> findVisible(@Param("orgId") Long orgId, @Param("spaceId") Long spaceId);
}
