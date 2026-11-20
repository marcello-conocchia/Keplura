package com.keplura.server.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Organization reads (M2c). <b>Tenant isolation is enforced here, at query
 * level</b>: the only finder takes the calling user id and requires a matching
 * {@code membership} row in the same statement, so a non-member can never
 * receive an organization — regardless of whether the requested id exists
 * (docs/adr/0004, IDOR defence).
 *
 * <p>Do not add bare {@code findById}-style access paths for API read code;
 * {@code findById} inherited from {@code JpaRepository} stays reserved for the
 * creation transaction, which already holds membership proof (it made the row).
 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** Fetches the organization ONLY if the user is a member; empty otherwise. */
    @Query("""
            select o from Organization o
            where o.id = :orgId
              and exists (select 1 from Membership m
                          where m.orgId = :orgId and m.userId = :userId)
            """)
    Optional<Organization> findByIdForMember(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
