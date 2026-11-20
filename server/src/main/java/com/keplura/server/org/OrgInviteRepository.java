package com.keplura.server.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Invite lookup (SEC-2). The digest is the accept-path key — an INDEXED
 * unique-column lookup, never a scan, so a forged token costs one btree
 * probe. The pending list is the OWNER surface: org-scoped, newest first.
 */
public interface OrgInviteRepository extends JpaRepository<OrgInvite, Long> {

    Optional<OrgInvite> findByTokenHash(String tokenHash);

    /**
     * Atomic one-time redemption (FINDING-1, SEC-2): conditional UPDATE that
     * stamps used_at/used_by ONLY while the row is still unspent AND
     * unexpired. Under concurrent accepts of the same token the database
     * serialises the two UPDATEs on the row: the first matches (1 row), the
     * second re-reads the committed new version, fails the {@code used_at IS
     * NULL} guard and matches 0 rows — a lost update is structurally
     * impossible (unlike a findByTokenHash + JPA dirty-check write). The
     * caller grants the membership only on a 1-row result; portability is
     * plain conditional-update SQL (H2 PostgreSQL mode + PostgreSQL, no
     * LIMIT, no vendor syntax — matching the V8 rules).
     */
    @Modifying
    @Query("""
            update OrgInvite i set i.usedAt = :now, i.usedBy = :userId
            where i.tokenHash = :tokenHash
              and i.usedAt is null
              and i.expiresAt > :now
            """)
    int redeemAtomically(@Param("tokenHash") String tokenHash,
                         @Param("userId") Long userId,
                         @Param("now") Instant now);

    /** Every invite of an org (service filters pending vs. redeemed for display). */
    List<OrgInvite> findByOrgIdOrderByCreatedAtDesc(Long orgId);

    /** Revoke = delete the row, but ONLY inside the tenant (no cross-org deletes). */
    long deleteByOrgIdAndId(Long orgId, Long id);
}
