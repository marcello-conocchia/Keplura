package com.keplura.server.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * The tenant boundary row (M2c, docs/adr/0004): which user may see which
 * organization, and in which role. The composite primary key (orgId, userId)
 * makes duplicate memberships impossible at the DB level; {@code role} is
 * stored as a string so enum reordering can never silently remap privileges.
 *
 * <p>{@link Persistable} with an explicit new-flag: with an {@code @IdClass}
 * the id is never null, so Spring Data's default "is the id set?" heuristic
 * would turn {@code save()} on an existing (org,user) pair into a merge that
 * silently UPDATES the role instead of hitting the unique key. For an
 * authorization table that is exactly the wrong failure mode — so a freshly
 * granted Membership always INSERTs (and a duplicate grant surfaces as a
 * data-integrity violation), and entities loaded from the DB are not-new.
 */
@Entity
@Table(name = "membership")
@IdClass(MembershipId.class)
public class Membership implements Persistable<MembershipId> {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean newGrant = true;

    protected Membership() {
        // JPA proxy constructor
    }

    private Membership(Long orgId, Long userId, MembershipRole role) {
        if (orgId == null || userId == null) {
            throw new IllegalArgumentException("orgId and userId must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        this.orgId = orgId;
        this.userId = userId;
        this.role = role;
    }

    /** Grants {@code userId} membership of {@code orgId} with the given role. */
    public static Membership grant(Long orgId, Long userId, MembershipRole role) {
        return new Membership(orgId, userId, role);
    }

    @Override
    public MembershipId getId() {
        return new MembershipId(orgId, userId);
    }

    @Override
    public boolean isNew() {
        return newGrant;
    }

    @PostLoad
    @PostPersist
    void markExisting() {
        newGrant = false;
    }

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getUserId() {
        return userId;
    }

    public MembershipRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
