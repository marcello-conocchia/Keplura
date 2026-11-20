package com.keplura.server.space;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A space — the tenant level below an organization (M3b, ROADMAP M3). Like
 * {@link com.keplura.server.org.Organization} this entity has no owner
 * column: authorization for spaces is org membership, checked at query level
 * ({@link SpaceRepository}), so guessing a space id yields nothing (IDOR
 * defence carried over from docs/adr/0004).
 *
 * <p>The {@code (org_id, name)} uniqueness mirrors the org-level naming rule:
 * a name is unique WITHIN its organization; different orgs may reuse it.
 * The name is a plain column here — never a trust anchor; the orgId is the
 * tenant coordinate every read/write is filtered by.
 */
@Entity
@Table(name = "space",
        // Mirrors the Flyway V2 unique key so the dev profile (ddl-auto=update,
        // Hibernate-owned schema) keeps the same DB-level name-uniqueness the
        // prod/PostgreSQL schema has; without it the 409 race guard in
        // SpaceService would only exist on the migrated path. NOT NULL/length
        // stay on the columns; ddl-auto=validate ignores the constraint itself.
        uniqueConstraints = @UniqueConstraint(
                name = "space_org_id_name_key",
                columnNames = {"org_id", "name"}))
public class Space {

    /** Mirrors the column width; enforced here so the API can answer 400, not 500. */
    public static final int MAX_NAME_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning organization. Immutable: spaces are never moved between tenants. */
    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Space() {
        // JPA proxy constructor
    }

    private Space(Long orgId, String name) {
        this.orgId = orgId;
        this.name = name;
    }

    /**
     * Creates a fully-formed space; no setters by design (same convention as
     * {@link com.keplura.server.org.Organization}).
     */
    public static Space create(Long orgId, String name) {
        if (orgId == null) {
            throw new IllegalArgumentException("orgId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        return new Space(orgId, trimmed);
    }

    /** Rename with the same rules as {@link #create} (trim, non-blank, max length). */
    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String trimmed = newName.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        this.name = trimmed;
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

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
