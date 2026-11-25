package com.keplura.server.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An AI model row (ADMIN-KI SCOPE C, docs/adr/0008 §3): a named model that
 * lives on exactly one {@link AiProvider}, typed CHAT or EMBEDDING, with the
 * optional generation knobs the PO spec allows (max output tokens,
 * temperature) and an embedding dimension for EMBEDDING rows.
 *
 * <p>Default-flag consistency ("exactly one enabled default per model type")
 * is enforced TWO ways: transactionally in {@link AiModelService} (the
 * service clears the sibling default when setting a new one) and DB-side via
 * the computed {@code default_slot} column — set to this row's id when it is
 * the default and unique per type, NULL otherwise. A partial index would be
 * the PG-idiomatic guard but is not H2-portable (V10 header), hence this.
 *
 * <p>{@code embeddingDim} is deliberately NOT editable after creation:
 * the pgvector twin column pins the dimension (ADR-0007) and a silent change
 * would corrupt the index. Changing models NEVER re-indexes anything
 * (Scope D — the UI carries the explicit hint instead).
 */
@Entity
@Table(name = "ai_model",
        uniqueConstraints = @UniqueConstraint(
                name = "ai_model_default_slot_key",
                columnNames = {"model_type", "default_slot"}))
public class AiModel {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_TECHNICAL_ID_LENGTH = 300;
    public static final String TYPE_CHAT = "CHAT";
    public static final String TYPE_EMBEDDING = "EMBEDDING";
    public static final String TOOL_USE_AUTO = "AUTO";
    public static final String TOOL_USE_OFF = "OFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "technical_model_id", nullable = false, length = MAX_TECHNICAL_ID_LENGTH)
    private String technicalModelId;

    @Column(name = "model_type", nullable = false, length = 20)
    private String modelType;

    @Column(name = "embedding_dim")
    private Integer embeddingDim;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "temperature", precision = 4, scale = 3)
    private BigDecimal temperature;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    /** Consistency guard column: own id when {@code isDefault}, else NULL (see class doc). */
    @Column(name = "default_slot")
    private Long defaultSlot;

    /** TU-05b: AUTO (default) or OFF — OFF never offers tools for this model. */
    @Column(name = "tool_use", nullable = false, length = 10)
    private String toolUse = TOOL_USE_AUTO;

    /** TU-05b: null = every approved tool kind, otherwise a JSON array of tool kinds. */
    @Column(name = "tool_defaults_json")
    private String toolDefaultsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiModel() {
        // JPA proxy constructor
    }

    public static AiModel create(Long providerId, String name, String technicalModelId,
                                 String modelType, Integer embeddingDim,
                                 Integer maxOutputTokens, BigDecimal temperature,
                                 boolean enabled) {
        AiModel m = new AiModel();
        m.providerId = providerId;
        m.name = name;
        m.technicalModelId = technicalModelId;
        m.modelType = modelType;
        m.embeddingDim = embeddingDim;
        m.maxOutputTokens = maxOutputTokens;
        m.temperature = temperature;
        m.enabled = enabled;
        return m;
    }

    /**
     * Edit verb for PATCH (null field = unchanged). Type, provider and
     * embedding dimension are immutable after creation — a model that means
     * something else is a NEW row, not a mutated one (index + default-slot
     * integrity).
     */
    public void applyEdit(String name, String technicalModelId, Integer maxOutputTokens,
                          BigDecimal temperature, Boolean enabled) {
        if (name != null) {
            this.name = name;
        }
        if (technicalModelId != null) {
            this.technicalModelId = technicalModelId;
        }
        if (maxOutputTokens != null) {
            this.maxOutputTokens = maxOutputTokens;
        }
        if (temperature != null) {
            this.temperature = temperature;
        }
        if (enabled != null) {
            this.enabled = enabled;
            if (!enabled) {
                setDefault(false); // a disabled row can never hold the default slot
            }
        }
    }

    /** TU-05b: full replace of the tool policy; the service validates the values. */
    public void applyToolPolicy(String toolUse, String toolDefaultsJson) {
        this.toolUse = toolUse;
        this.toolDefaultsJson = toolDefaultsJson;
    }

    public String getToolUse() {
        return toolUse;
    }

    public String getToolDefaultsJson() {
        return toolDefaultsJson;
    }

    /** Service-managed default flag; keeps {@code defaultSlot} in lock-step. */
    public void setDefault(boolean value) {
        if (value && !enabled) {
            throw AdminException.invalid("a disabled model cannot be the default");
        }
        this.isDefault = value;
        this.defaultSlot = value ? id : null;
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

    public Long getProviderId() {
        return providerId;
    }

    public String getName() {
        return name;
    }

    public String getTechnicalModelId() {
        return technicalModelId;
    }

    public String getModelType() {
        return modelType;
    }

    public Integer getEmbeddingDim() {
        return embeddingDim;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
