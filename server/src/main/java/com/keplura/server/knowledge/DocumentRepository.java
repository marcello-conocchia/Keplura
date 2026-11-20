package com.keplura.server.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Document reads (M3c). The full isolation chain lives in ONE statement —
 * document → kb pin → space pin → org membership EXISTS — so a row is only
 * ever visible to a member of the owning org, reached through the document's
 * own org/space/kb path. Any other combination (foreign org, wrong space
 * path, wrong kb path, unknown id) comes back empty and collapses into the
 * uniform 404 upstream; the API can never confirm that a document id exists.
 *
 * <p>Service code must never call the inherited {@code findById} for an API
 * read; the two finders below are the only read access for wire paths.
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /** All documents of one kb — ONLY for cascades whose chain was proven upstream. */
    List<Document> findByKbId(Long kbId);

    /** Async indexer recovery: rows still waiting after a restart. */
    List<Document> findByIndexStatus(String indexStatus);

    /**
     * Every document of one kb (upload order), visible ONLY through the
     * owning kb's own space path and only to a member of the owning org.
     */
    @Query("""
            select d from Document d
            where d.kbId = :kbId
              and exists (select 1 from KnowledgeBase k
                          where k.id = :kbId and k.spaceId = :spaceId)
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
              and """ + com.keplura.server.access.AccessFilter.JPQL_DOCUMENT + """
            order by d.id
            """)
    List<Document> findAllInKbForMember(@Param("kbId") Long kbId,
                                        @Param("spaceId") Long spaceId,
                                        @Param("orgId") Long orgId,
                                        @Param("userId") Long userId);

    /** Admin-Rechte-Nachweis: Anzahl, die genau diese Person über Suche/Liste sehen würde (gleicher Filter). */
    @Query("""
            select count(d) from Document d
            where d.kbId = :kbId
              and exists (select 1 from KnowledgeBase k
                          where k.id = :kbId and k.spaceId = :spaceId)
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
              and """ + com.keplura.server.access.AccessFilter.JPQL_DOCUMENT + """
            """)
    long countAllInKbForMember(@Param("kbId") Long kbId,
                               @Param("spaceId") Long spaceId,
                               @Param("orgId") Long orgId,
                               @Param("userId") Long userId);

    long countByKbId(Long kbId);

    /** Admin-Suche nach Dateiname (nur für den Rechte-Nachweis, Admin-geschützt). */
    List<Document> findTop20ByFilenameContainingIgnoreCaseOrderByIdDesc(String filename);

    /**
     * One document pinned to its kb AND space AND (via membership) org in the
     * same chain — the M3c IDOR core: a victim document id probed under an
     * attacker's own org/space/kb path is indistinguishable from absent.
     */
    @Query("""
            select d from Document d
            where d.id = :documentId
              and d.kbId = :kbId
              and exists (select 1 from KnowledgeBase k
                          where k.id = :kbId and k.spaceId = :spaceId)
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
              and """ + com.keplura.server.access.AccessFilter.JPQL_DOCUMENT + """
            """)
    Optional<Document> findByIdInKbForMember(@Param("documentId") Long documentId,
                                             @Param("kbId") Long kbId,
                                             @Param("spaceId") Long spaceId,
                                             @Param("orgId") Long orgId,
                                             @Param("userId") Long userId);
}
