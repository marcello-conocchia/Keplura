package com.keplura.server.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Tenant root (M2c, docs/adr/0004). An organization has no direct owner column:
 * membership is the single source of truth ({@link Membership}), which keeps
 * role changes and future multi-role logic on one table instead of scattered
 * ownership fields.
 *
 * <p>Nothing here may be read through an id alone: every access path goes
 * through a membership-filtered query (see {@link MembershipRepository}),
 * so guessing an id yields nothing (IDOR defence, ADR-0004).
 */
@Entity
@Table(name = "organization")
public class Organization {

    /** Mirrors the column width; enforced here so the API can answer 400, not 500. */
    public static final int MAX_NAME_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Organization() {
        // JPA proxy constructor
    }

    private Organization(String name) {
        this.name = name;
    }

    /**
     * Creates a fully-formed organization; no setters by design (same
     * convention as {@link com.keplura.server.identity.User}).
     */
    public static Organization create(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        return new Organization(trimmed);
    }

    /** Rename with the same rules as {@link #create}. */
    public void rename(String newName) {
        this.name = create(newName).getName();
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

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
