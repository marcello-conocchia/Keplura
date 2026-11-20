package com.keplura.server.oidc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent OIDC account link (docs/adr/0009 §5): one subject at one
 * provider ↔ exactly one local user. Rows are created ONLY by the two
 * governed paths — the verified-email claim at callback time (service
 * re-verifies email_verified==true and an unlinked matching user) and an
 * explicit admin pairing. No auto-provisioning: an unknown subject without a
 * link never gets a session.
 *
 * <p>Scoped by provider_id on purpose: retiring an IdP and wiring its
 * successor must NEVER inherit the old links — a cross-provider {@code sub}
 * collision (both issuers minting "1234") could otherwise silently grant
 * access. The DB-level UNIQUE(provider_id, subject) makes one corporate
 * identity owning two accounts impossible even under a race.
 */
@Entity
@Table(name = "oidc_account_link")
public class OidcAccountLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    /** The OIDC {@code sub} claim, stored verbatim (never normalised). */
    @Column(nullable = false, length = 200)
    private String subject;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OidcAccountLink() {
        // JPA proxy constructor
    }

    public static OidcAccountLink create(Long providerId, String subject, Long userId) {
        if (providerId == null || userId == null) {
            throw new IllegalArgumentException("provider and user are required");
        }
        if (subject == null || subject.isBlank() || subject.length() > 200) {
            throw new IllegalArgumentException("subject must be 1..200 chars");
        }
        OidcAccountLink link = new OidcAccountLink();
        link.providerId = providerId;
        link.subject = subject;
        link.userId = userId;
        return link;
    }

    @PrePersist
    void stamp() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getProviderId() {
        return providerId;
    }

    public String getSubject() {
        return subject;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
