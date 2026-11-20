package com.keplura.server.space;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Space reads (M3b). <b>Tenant isolation is enforced here, at query level</b>,
 * exactly like {@link com.keplura.server.org.OrganizationRepository}: every
 * read takes the calling user id and requires a matching {@code membership}
 * row for the PATH's organization in the same statement. A non-member can
 * never receive a space; and because the finder additionally pins
 * {@code s.orgId = :orgId}, a valid space id reached through the WRONG org
 * path is equally invisible (cross-tenant enumeration defence, ADR-0004).
 *
 * <p>No bare {@code findById}-style access for API read code. The inherited
 * {@code findByOrgIdAndName} is reserved for the create transaction, which
 * has already proven the caller's membership in that org (same rule as
 * {@code OrganizationRepository.findById}).
 */
public interface SpaceRepository extends JpaRepository<Space, Long> {

    /** All spaces of one org — ONLY for the org-delete cascade (chain proven upstream). */
    List<Space> findByOrgId(Long orgId);

    /**
     * Exact-name probe inside one org (the (org_id, name) unique key) — the
     * pre-check of the create transaction ONLY, which already holds membership
     * proof for {@code orgId}. Never wire an API read path through this.
     */
    Optional<Space> findByOrgIdAndName(Long orgId, String name);

    /** Every space of the org, ONLY if the caller is a member of it; empty otherwise. */
    @Query("""
            select s from Space s
            where s.orgId = :orgId
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            order by s.id
            """)
    List<Space> findAllInOrgForMember(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * One space by (spaceId, orgId), visible ONLY to an org member AND only
     * through its own organization's path — foreign space, foreign org and
     * unknown ids all come back empty (uniform 404 upstream).
     */
    @Query("""
            select s from Space s
            where s.id = :spaceId
              and s.orgId = :orgId
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            """)
    Optional<Space> findByIdInOrgForMember(@Param("spaceId") Long spaceId,
                                           @Param("orgId") Long orgId,
                                           @Param("userId") Long userId);
}
