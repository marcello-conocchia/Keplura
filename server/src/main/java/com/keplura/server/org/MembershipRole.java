package com.keplura.server.org;

/**
 * Roles inside an {@link Organization} (M2c). Deliberately minimal per the
 * milestone scope: OWNER is the creator, MEMBER a later invitation target.
 * No admin role until a real permission difference exists (docs/adr/0004).
 */
public enum MembershipRole {
    OWNER,
    MEMBER
}
