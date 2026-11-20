package com.keplura.server.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A one-time, expiring membership grant (SEC-2). The plaintext token NEVER
 * exists on this row: {@code tokenHash} stores only the SHA-256 hex digest,
 * so a database leak cannot mint memberships and the API can honestly show
 * the token exactly once (in the create response, from the generator's own
 * hand). Redemption stamps {@code usedAt}/{@code usedBy} — the row stays for
 * audit; revocation deletes it (a revoked invite has no future reader).
 *
 * <p>{@code role} is persisted because the acceptance path grants whatever
 * the invite promised — but the CHECK constraint (V8) clamps it to MEMBER,
 * and the service hard-codes MEMBER, so an invite can structurally never
 * mint an OWNER (no escalation path, docs/adr/0004).
 */
@Entity
@Table(name = "org_invite")
public class OrgInvite {

    /** Column width of the hex digest (SHA-256 = 32 bytes = 64 hex chars). */
    public static final int TOKEN_HASH_CHARS = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    @Column(name = "inviter_id", nullable = false, updatable = false)
    private Long inviterId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = TOKEN_HASH_CHARS)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private MembershipRole role;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_by")
    private Long usedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrgInvite() {
        // JPA proxy constructor
    }

    private OrgInvite(Long orgId, Long inviterId, String tokenHash,
                      MembershipRole role, Instant expiresAt) {
        this.orgId = orgId;
        this.inviterId = inviterId;
        this.tokenHash = tokenHash;
        this.role = role;
        this.expiresAt = expiresAt;
    }

    /**
     * A fresh pending invite. Callers must pass the HASH (see
     * {@link InviteService#hashToken}) — never plaintext — and the role the
     * redemption will grant.
     */
    public static OrgInvite create(Long orgId, Long inviterId, String tokenHash,
                                   MembershipRole role, Instant expiresAt) {
        if (orgId == null || inviterId == null) {
            throw new IllegalArgumentException("orgId and inviterId must not be null");
        }
        if (tokenHash == null || tokenHash.length() != TOKEN_HASH_CHARS) {
            throw new IllegalArgumentException("tokenHash must be " + TOKEN_HASH_CHARS + " hex chars");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        return new OrgInvite(orgId, inviterId, tokenHash, role, expiresAt);
    }

    /** Pending = unspent AND unexpired, evaluated against {@code now}. */
    public boolean isPending(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getInviterId() {
        return inviterId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public MembershipRole getRole() {
        return role;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Long getUsedBy() {
        return usedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
