package com.keplura.server.oidc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * OIDC provider row (OIDC-M2-Tail, docs/adr/0009 §2): one corporate IdP —
 * deployment-level config like {@code ai_provider} (no org_id column), with
 * the write-only-secret discipline reused verbatim (AES-GCM ciphertext via
 * {@link com.keplura.server.admin.SecretCipher}, never plaintext, never
 * out of a DTO).
 *
 * <p>At most ONE row is active at a time (the single "Mit Firmenkonto
 * anmelden" button). Enforced the portable ai_model default-slot way:
 * {@code active_slot} is NULL unless this row is the active one and is
 * UNIQUE — a double activation is impossible even under a race (the second
 * writer hits the constraint and the transaction rolls back).
 *
 * <p>No delete surface (same posture as ai_provider): {@code active=false}
 * IS the deactivation verb; retired rows keep their identity for audit.
 *
 * <p>There is deliberately NO use_state toggle: the OIDC {@code state}
 * parameter round-trip is always on (spec default), and the server-side
 * session↔binding check (ADR-0009 §1) is never switchable — an escape
 * hatch that quietly disables CSRF protection is the wrong default.
 */
@Entity
@Table(name = "oidc_provider")
public class OidcProvider {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_URL_LENGTH = 500;
    public static final int MAX_CLIENT_ID_LENGTH = 200;
    public static final int MIN_TIMEOUT_SECONDS = 1;
    public static final int MAX_TIMEOUT_SECONDS = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Operator-facing label ("Firmenkonto"); shown on the login button. */
    @Column(nullable = false, unique = true, length = MAX_NAME_LENGTH)
    private String name;

    /** Must equal the discovery document's issuer EXACTLY (ADR-0009 §4). */
    @Column(nullable = false, length = MAX_URL_LENGTH)
    private String issuer;

    /** Discovery document URL; dialed SSRF-guarded at save and begin. */
    @Column(name = "discovery_url", nullable = false, length = MAX_URL_LENGTH)
    private String discoveryUrl;

    @Column(name = "client_id", nullable = false, length = MAX_CLIENT_ID_LENGTH)
    private String clientId;

    /** AES-GCM ciphertext (Base64) of the client secret; NULL = no secret. */
    @Column(name = "client_secret_cipher", length = 1000)
    private String clientSecretCipher;

    /** Active = THE provider login uses (uniqueness via active_slot). */
    @Column(nullable = false)
    private boolean active = true;

    /** Per-endpoint dial timeout, same bounds as ai_provider. */
    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 15;

    /** NULL unless this is the active row (id there); UNIQUE guard (V11). */
    @Column(name = "active_slot")
    private Long activeSlot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OidcProvider() {
        // JPA proxy constructor
    }

    /**
     * Create fully formed (no general setters — edits go through
     * {@link #applyEdit}). {@code clientSecretCipher} is the ALREADY-
     * ENCRYPTED value or null; the plaintext must never reach here.
     */
    public static OidcProvider create(String name, String issuer, String discoveryUrl,
                                      String clientId, String clientSecretCipher,
                                      int timeoutSeconds) {
        OidcProvider p = new OidcProvider();
        p.name = name;
        p.issuer = issuer;
        p.discoveryUrl = discoveryUrl;
        p.clientId = clientId;
        p.clientSecretCipher = clientSecretCipher;
        p.timeoutSeconds = timeoutSeconds;
        return p;
    }

    /**
     * Edit verb for PATCH semantics (null field = keep stored value, exactly
     * like {@link com.keplura.server.admin.AiProvider#applyEdit}): a
     * null secret cipher KEEPS the stored one.
     */
    public void applyEdit(String name, String issuer, String discoveryUrl, String clientId,
                          String newSecretCipher, Integer timeoutSeconds) {
        if (name != null) {
            this.name = name;
        }
        if (issuer != null) {
            this.issuer = issuer;
        }
        if (discoveryUrl != null) {
            this.discoveryUrl = discoveryUrl;
        }
        if (clientId != null) {
            this.clientId = clientId;
        }
        if (newSecretCipher != null) {
            this.clientSecretCipher = newSecretCipher;
        }
        if (timeoutSeconds != null) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /** Service-managed activation; keeps {@code activeSlot} in lock-step.
     *  The slot is cleared on the OLD row FIRST by the service (same
     *  transaction): writing our own id while the previous row still holds
     *  a slot would hit the UNIQUE guard mid-flush. */
    public void setActiveFlag(boolean value) {
        this.active = value;
        if (value) {
            this.activeSlot = OidcProvider.PENDING_SLOT;
        } else {
            this.activeSlot = null;
        }
    }

    /** Sentinel replaced by the real id right after the insert (IDENTITY). */
    static final Long PENDING_SLOT = -1L;

    /** Second activation phase: only ever on the row that asked for it. */
    public void confirmActiveSlotIfPending() {
        if (active && OidcProvider.PENDING_SLOT.equals(this.activeSlot) && id != null) {
            this.activeSlot = id;
        }
    }

    public boolean hasSecret() {
        return clientSecretCipher != null && !clientSecretCipher.isBlank();
    }

    @PrePersist
    void stamp() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        // activeSlot CANNOT be stamped here: with IDENTITY generation the id
        // only exists after the insert — the service sets it in a second
        // phase inside the same transaction (ai_model default-slot pattern).
    }

    @PreUpdate
    void restamp() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getDiscoveryUrl() {
        return discoveryUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecretCipher() {
        return clientSecretCipher;
    }

    public boolean isActive() {
        return active;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
