package com.keplura.server.identity;

/**
 * Global deployment role (ADMIN-KI SCOPE A, docs/adr/0008 §1). Complementary
 * to the org-scoped {@link com.keplura.server.org.MembershipRole}: the
 * membership table stays the tenant ACL, this column only answers "may this
 * account enter the admin surface".
 *
 * <p>Like every other authorization decision in this codebase (ADR-0004), the
 * role is read FRESH from the database per request — it is deliberately NOT
 * copied into the session principal, so revoking ADMIN takes effect on the
 * very next request, not at the next login.
 *
 * <p>Stored as the plain name (V9 migration CHECK) — mapped as a String on
 * the entity and parsed here so a corrupt value fails loudly at the service
 * boundary instead of silently becoming a USER.
 */
public enum UserRole {
    USER,
    ADMIN;

    /** Strict parse of the stored column value; unknown input is a bug, not a default. */
    public static UserRole fromStorage(String raw) {
        if (raw == null || raw.isBlank()) {
            return USER; // only reachable on a pre-V9 dev database mid-migration
        }
        return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
