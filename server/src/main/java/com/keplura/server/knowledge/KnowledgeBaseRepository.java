package com.keplura.server.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Knowledge-base reads (M3c). <b>Tenant isolation is enforced here, at query
 * level</b>, exactly like {@link com.keplura.server.space.SpaceRepository}:
 * every read takes the calling user id and requires a matching
 * {@code membership} row for the PATH's organization in the same statement.
 * Because the finder additionally pins {@code k.spaceId = :spaceId}, a valid
 * kb id reached through the WRONG space path is equally invisible (cross-
 * tenant enumeration defence, ADR-0004 pattern).
 *
 * <p>No bare {@code findById}-style access for API read code. The inherited
 * {@code findBySpaceIdAndName} is reserved for the create transaction, which
 * has already proven the caller's membership in the owning org (same rule as
 * {@code SpaceRepository.findByOrgIdAndName}).
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /** All kbs of one space — ONLY for cascades whose chain was proven upstream. */
    List<KnowledgeBase> findBySpaceId(Long spaceId);

    /**
     * Exact-name probe inside one space (the (space_id, name) unique key) —
     * the pre-check of the create transaction ONLY, which already holds the
     * membership proof for the owning org. Never wire an API read path here.
     */
    Optional<KnowledgeBase> findBySpaceIdAndName(Long spaceId, String name);

    /** Every kb of the space, ONLY if the caller is a member of the owning org. */
    @Query("""
            select k from KnowledgeBase k
            where k.spaceId = :spaceId
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            order by k.id
            """)
    List<KnowledgeBase> findAllInSpaceForMember(@Param("spaceId") Long spaceId,
                                                @Param("orgId") Long orgId,
                                                @Param("userId") Long userId);

    /**
     * One kb by (kbId, spaceId), visible ONLY to an org member AND only
     * through its own space's path — foreign kb, foreign space/org and
     * unknown ids all come back empty (uniform 404 upstream).
     */
    @Query("""
            select k from KnowledgeBase k
            where k.id = :kbId
              and k.spaceId = :spaceId
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            """)
    Optional<KnowledgeBase> findByIdInSpaceForMember(@Param("kbId") Long kbId,
                                                     @Param("spaceId") Long spaceId,
                                                     @Param("orgId") Long orgId,
                                                     @Param("userId") Long userId);
}
