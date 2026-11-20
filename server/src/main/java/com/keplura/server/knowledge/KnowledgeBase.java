package com.keplura.server.knowledge;

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
 * A knowledge base — the document container below a space (M3c, ROADMAP M3).
 * Like {@link com.keplura.server.space.Space} this entity has no owner
 * column: authorization is the ORG MEMBERSHIP three levels up, enforced at
 * query level ({@link KnowledgeBaseRepository} chains the same EXISTS on
 * membership the space API uses), so guessing a kb id yields nothing (IDOR
 * defence, docs/adr/0004 pattern).
 *
 * <p>The {@code (space_id, name)} uniqueness mirrors the space-in-org naming
 * rule: a name is unique WITHIN its space; other spaces (and orgs) may reuse
 * it. {@code spaceId} is the tenant coordinate every read/write is filtered
 * by — never the name.
 */
@Entity
@Table(name = "knowledge_base",
        // Mirrors the Flyway V3 unique key so the dev profile (ddl-auto=update,
        // Hibernate-owned schema) keeps the same DB-level name-uniqueness the
        // prod/PostgreSQL schema has; without it the 409 race guard in
        // KnowledgeBaseService would only exist on the migrated path.
        // NOT NULL/length stay on the columns; ddl-auto=validate ignores the
        // constraint itself (same convention as Space).
        uniqueConstraints = @UniqueConstraint(
                name = "knowledge_base_space_id_name_key",
                columnNames = {"space_id", "name"}))
public class KnowledgeBase {

    /** Mirrors the column width; enforced here so the API can answer 400, not 500. */
    public static final int MAX_NAME_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning space. Immutable: knowledge bases are never moved between tenants. */
    @Column(name = "space_id", nullable = false, updatable = false)
    private Long spaceId;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KnowledgeBase() {
        // JPA proxy constructor
    }

    private KnowledgeBase(Long spaceId, String name) {
        this.spaceId = spaceId;
        this.name = name;
    }

    /**
     * Creates a fully-formed knowledge base; no setters by design (same
     * convention as {@link com.keplura.server.space.Space}).
     */
    public static KnowledgeBase create(Long spaceId, String name) {
        if (spaceId == null) {
            throw new IllegalArgumentException("spaceId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        return new KnowledgeBase(spaceId, trimmed);
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

    public Long getSpaceId() {
        return spaceId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
