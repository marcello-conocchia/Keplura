package com.keplura.server.org;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link Membership}: one row per (organization, user).
 * The database-level uniqueness of that pair IS the membership guarantee —
 * there is no second source of truth (docs/adr/0004).
 */
public class MembershipId implements Serializable {

    private Long orgId;
    private Long userId;

    public MembershipId() {
        // JPA
    }

    public MembershipId(Long orgId, Long userId) {
        this.orgId = orgId;
        this.userId = userId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MembershipId other)) {
            return false;
        }
        return Objects.equals(orgId, other.orgId) && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orgId, userId);
    }
}
