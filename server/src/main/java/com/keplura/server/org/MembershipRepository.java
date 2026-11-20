package com.keplura.server.org;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Membership rows are the authorization source of truth (M2c, docs/adr/0004):
 * every org read filters on them, every org-scoped write checks them.
 */
public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    /** Every membership of a user — the backing set for "list MY organizations". */
    List<Membership> findByUserId(Long userId);

    /** The single (org, user) grant, or empty = not a member. */
    Optional<Membership> findByOrgIdAndUserId(Long orgId, Long userId);

    /** The full roster of an org, oldest grant first (SEC-2 member list). */
    List<Membership> findByOrgIdOrderByCreatedAtAsc(Long orgId);

    long countByOrgIdAndRole(Long orgId, MembershipRole role);
}
