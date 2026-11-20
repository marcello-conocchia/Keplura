package com.keplura.server.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One chat conversation (M4b) — a personal thread inside a tenant path
 * (org + space) with an optional knowledge-base RAG pin. Like every M3
 * entity this row carries NO ACL surface of its own: {@code created_by} is
 * the user filter and org/space are the tenant pins, and
 * {@link ChatRepository} re-proves membership + space visibility in EVERY
 * query (ADR-0004). There are no setters by design (Space precedent).
 *
 * <p>The model name is NOT pinned here: it lives on each assistant
 * {@link ChatMessage} (a conversation may span gateway model changes —
 * the audit fact is "which model produced THIS answer").
 */
@Entity
@Table(name = "chat_conversation",
        indexes = @Index(name = "chat_conversation_org_id_created_by_space_id_idx",
                columnList = "org_id,created_by,space_id"))
public class Conversation {

    /** Mirrors the V6 column width; enforced at the API edge (400, not 500). */
    public static final int MAX_TITLE_LENGTH = 200;

    /** The untitled placeholder — also the auto-title guard predicate. */
    public static final String DEFAULT_TITLE = "Neuer Chat";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning organization. Immutable: conversations never move between tenants. */
    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    /** Owning space (the chat scope); immutable, same rationale as orgId. */
    @Column(name = "space_id", nullable = false, updatable = false)
    private Long spaceId;

    /** The personal owner — conversations are not shared within the org. */
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    /** Optional RAG context kb; null = plain chat without citations. */
    @Column(name = "knowledge_base_id", updatable = false)
    private Long knowledgeBaseId;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Bumped on every message add — the list read sorts by this. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
        // JPA proxy constructor
    }

    private Conversation(Long orgId, Long spaceId, Long createdBy,
                         Long knowledgeBaseId, String title) {
        this.orgId = orgId;
        this.spaceId = spaceId;
        this.createdBy = createdBy;
        this.knowledgeBaseId = knowledgeBaseId;
        this.title = title;
    }

    /**
     * Creates a fully-formed conversation. Blanks and over-length strings are
     * rejected here so the service can answer 400 without touching the DB;
     * trimming happens once, at construction.
     */
    public static Conversation create(Long orgId, Long spaceId, Long createdBy,
                                      Long knowledgeBaseId, String title) {
        if (orgId == null || spaceId == null || createdBy == null) {
            throw new IllegalArgumentException("orgId/spaceId/createdBy must not be null");
        }
        String trimmed = (title == null) ? "" : title.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title must be 1-" + MAX_TITLE_LENGTH + " characters");
        }
        return new Conversation(orgId, spaceId, createdBy, knowledgeBaseId, trimmed);
    }

    @PrePersist
    void stampTimestamps() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void stampUpdatedAt() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
