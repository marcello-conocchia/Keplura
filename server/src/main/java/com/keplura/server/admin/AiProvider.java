package com.keplura.server.admin;

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
 * An AI provider row (ADMIN-KI SCOPE B, docs/adr/0008 §3): one OpenAI-
 * compatible backend (Ollama / vLLM / hosted api — the closed vocabulary of
 * {@link com.keplura.server.gateway.ProviderPreset}, reused as-is).
 *
 * <p>Leak discipline by field shape: {@code secretCipher} holds ONLY the
 * AES-GCM ciphertext of the API key (never plaintext, never derivable back
 * without the master key), {@code baseUrl} may name an internal host and is
 * therefore never projected into the system-status surface or any log line.
 * The entity itself must never be serialised into an API response —
 * {@link AdminProviderService} maps explicit DTOs.
 *
 * <p>No delete surface (PO functions: create/edit/deactivate/test/status):
 * {@code enabled=false} IS the deactivation and the runtime resolver filters
 * on it, so a provider can be retired without orphaning model rows.
 */
@Entity
@Table(name = "ai_provider")
public class AiProvider {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_BASE_URL_LENGTH = 500;
    public static final int MIN_TIMEOUT_SECONDS = 1;
    public static final int MAX_TIMEOUT_SECONDS = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = MAX_NAME_LENGTH)
    private String name;

    /** ProviderPreset id: "ollama" | "vllm" | "api" (CHECK-validated in V10). */
    @Column(name = "provider_type", nullable = false, length = 20)
    private String providerType;

    @Column(name = "base_url", nullable = false, length = MAX_BASE_URL_LENGTH)
    private String baseUrl;

    /** AES-GCM ciphertext (Base64) of the API key; NULL = no secret set. */
    @Column(name = "secret_cipher", length = 1000)
    private String secretCipher;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 30;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiProvider() {
        // JPA proxy constructor
    }

    /**
     * Create fully formed (the entity has no general setters — edits go
     * through the explicit {@link #applyEdit} verb). {@code secretCipher} is
     * the ALREADY-ENCRYPTED value or null; the raw key must never reach here.
     */
    public static AiProvider create(String name, String providerType, String baseUrl,
                                    String secretCipher, int timeoutSeconds, boolean enabled) {
        AiProvider p = new AiProvider();
        p.name = name;
        p.providerType = providerType;
        p.baseUrl = baseUrl;
        p.secretCipher = secretCipher;
        p.timeoutSeconds = timeoutSeconds;
        p.enabled = enabled;
        return p;
    }

    /**
     * Edit verb for PATCH semantics: blank/null {@code newSecretCipher} KEEPS
     * the stored secret (PO Scope B: "leeres Secret = alten Wert behalten");
     * a non-null ciphertext replaces it.
     */
    public void applyEdit(String name, String baseUrl, Integer timeoutSeconds,
                          Boolean enabled, String newSecretCipher) {
        // PATCH semantics (see javadoc): every null field keeps its stored
        // value — the service merges BEFORE calling, but the entity must
        // never be able to null out a NOT NULL column by omission.
        if (name != null) {
            this.name = name;
        }
        if (baseUrl != null) {
            this.baseUrl = baseUrl;
        }
        if (timeoutSeconds != null) {
            this.timeoutSeconds = timeoutSeconds;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (newSecretCipher != null) {
            this.secretCipher = newSecretCipher;
        }
    }

    public boolean hasSecret() {
        return secretCipher != null && !secretCipher.isBlank();
    }

    @PrePersist
    void stamp() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
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

    public String getProviderType() {
        return providerType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /** Ciphertext only — callers that need the plaintext key go through SecretCipher. */
    public String getSecretCipher() {
        return secretCipher;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
