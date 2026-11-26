package com.keplura.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADMIN-KI Scope C/D: model CRUD plus the default-slot swap.
 *
 * <p>The "exactly one enabled default per model type" rule is enforced
 * transactionally HERE (clear the old holder, set the new — ADR-0008 §3) and
 * backed DB-side by the unique {@code (model_type, default_slot)} guard, so
 * even two racing PUTs cannot leave two defaults: the loser hits the
 * constraint and retries land on a consistent table.
 *
 * <p>Scope D honesty: choosing a different default EMBEDDING model NEVER
 * re-indexes anything (PO rule "keinen Index-Neubau heimlich triggern").
 * The API response carries the {@code reindexHint} so the UI shows the
 * consequence, and re-indexing stays what it always was — an explicit
 * upload-time action per document.
 */
@Service
public class AdminModelService {

    public record ModelDto(Long id, Long providerId, String providerName, String name,
                           String technicalModelId, String modelType,
                           Integer embeddingDim, Integer maxOutputTokens, BigDecimal temperature,
                           boolean enabled, boolean isDefault, String createdAt, String updatedAt,
                           String toolUse, List<String> toolDefaults) {
    }

    /** TU-05b: full replace. {@code toolDefaults} null = every approved tool kind. */
    public record ModelToolsRequest(String toolUse, List<String> toolDefaults) {
    }

    /** Tool kinds a chat model may default to (adapter kinds of the central tool service). */
    public static final List<String> TOOL_KINDS = List.of("knowledge", "attachments", "web", "mcp", "openapi", "ask");

    public record ModelCreateRequest(Long providerId, String name, String technicalModelId,
                                     String modelType, Integer embeddingDim,
                                     Integer maxOutputTokens, BigDecimal temperature, Boolean enabled) {
    }

    public record ModelPatchRequest(String name, String technicalModelId,
                                    Integer maxOutputTokens, BigDecimal temperature, Boolean enabled) {
    }

    private static final Logger log = LoggerFactory.getLogger(AdminModelService.class);

    private final AiModelRepository models;
    private final AiProviderRepository providers;

    public AdminModelService(AiModelRepository models, AiProviderRepository providers) {
        this.models = models;
        this.providers = providers;
    }

    @Transactional(readOnly = true)
    public List<ModelDto> list() {
        return models.findAllByOrderByProviderIdAscIdAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public ModelDto create(ModelCreateRequest req) {
        if (req.providerId() == null || providers.findById(req.providerId()).isEmpty()) {
            throw AdminException.invalid("providerId must reference an existing provider");
        }
        String name = require(req.name(), "name is required", AiModel.MAX_NAME_LENGTH);
        String technical = require(req.technicalModelId(), "technicalModelId is required",
                AiModel.MAX_TECHNICAL_ID_LENGTH);
        String type = normalizeType(req.modelType());
        Integer dim = req.embeddingDim();
        if (AiModel.TYPE_EMBEDDING.equals(type)) {
            if (dim == null || dim < 8 || dim > 65536) {
                throw AdminException.invalid("an EMBEDDING model needs embeddingDim >= 8 "
                        + "(it must match the stored vector dimension)");
            }
        } else if (dim != null) {
            throw AdminException.invalid("embeddingDim only applies to EMBEDDING models");
        }
        validateTemperature(req.temperature());
        validateMaxTokens(req.maxOutputTokens());
        AiModel saved = models.saveAndFlush(AiModel.create(req.providerId(), name, technical, type,
                dim, req.maxOutputTokens(), req.temperature(), req.enabled() == null || req.enabled()));
        log.info("admin created model id={} type={}", saved.getId(), type);
        return toDto(saved);
    }

    @Transactional
    public ModelDto update(Long id, ModelPatchRequest req) {
        AiModel model = requireModel(id);
        if (req.name() != null && req.name().isBlank()) {
            throw AdminException.invalid("name must not be blank");
        }
        if (req.technicalModelId() != null && req.technicalModelId().isBlank()) {
            throw AdminException.invalid("technicalModelId must not be blank");
        }
        validateTemperature(req.temperature());
        validateMaxTokens(req.maxOutputTokens());
        model.applyEdit(req.name() == null ? null : req.name().strip(),
                req.technicalModelId() == null ? null : req.technicalModelId().strip(),
                req.maxOutputTokens(), req.temperature(), req.enabled());
        models.saveAndFlush(model);
        log.info("admin updated model id={} enabled={}", model.getId(), model.isEnabled());
        return toDto(model);
    }

    /**
     * TU-05b: whether this chat model gets tools at all and which tool kinds it offers by default.
     * A narrowing only: approvals, chat selection and per-question conditions still apply.
     */
    @Transactional
    public ModelDto updateTools(Long id, ModelToolsRequest req) {
        AiModel model = requireModel(id);
        if (!AiModel.TYPE_CHAT.equals(model.getModelType())) {
            throw AdminException.invalid("tool settings only apply to CHAT models");
        }
        if (req == null || req.toolUse() == null
                || !(AiModel.TOOL_USE_AUTO.equals(req.toolUse()) || AiModel.TOOL_USE_OFF.equals(req.toolUse()))) {
            throw AdminException.invalid("toolUse must be AUTO or OFF");
        }
        String json = null;
        if (req.toolDefaults() != null) {
            var kinds = new java.util.TreeSet<String>();
            for (String kind : req.toolDefaults()) {
                if (kind == null || !TOOL_KINDS.contains(kind) || !kinds.add(kind)) {
                    throw AdminException.invalid("toolDefaults must list distinct kinds of " + TOOL_KINDS);
                }
            }
            json = TOOL_JSON.writeValueAsString(List.copyOf(kinds));
        }
        model.applyToolPolicy(req.toolUse(), json);
        models.saveAndFlush(model);
        log.info("admin updated model tool policy id={} toolUse={}", model.getId(), req.toolUse());
        return toDto(model);
    }

    private static final tools.jackson.databind.json.JsonMapper TOOL_JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static List<String> toolDefaults(String json) {
        if (json == null) return null;
        try {
            var out = new java.util.ArrayList<String>();
            for (var node : TOOL_JSON.readTree(json)) out.add(node.asString());
            return List.copyOf(out);
        } catch (RuntimeException e) {
            return List.of(); // unreadable = nothing by default; the tool service fails closed as well
        }
    }

    /**
     * Make this row the default of its type (Scope C/D "Standardmodell").
     * The swap runs at SERIALIZABLE so two concurrent defaults-swaps on the
     * same type serialise instead of racing into the unique guard; a
     * default may only sit on an ENABLED model of an ENABLED provider (ADR
     * §3 consistency rule), and an EMBEDDING default carries the reindex
     * hint instead of triggering anything.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ModelDto setDefault(Long id) {
        AiModel model = requireModel(id);
        if (!model.isEnabled()) {
            throw AdminException.invalid("a disabled model cannot be the default");
        }
        boolean providerEnabled = providers.findById(model.getProviderId())
                .map(AiProvider::isEnabled).orElse(false);
        if (!providerEnabled) {
            throw AdminException.invalid("the model's provider is deactivated");
        }
        for (AiModel other : models.findOtherDefaults(model.getModelType(), model.getId())) {
            other.setDefault(false);
            models.save(other);
        }
        model.setDefault(true);
        models.saveAndFlush(model);
        log.info("admin set default {} model id={} (reindex NOT triggered)",
                model.getModelType(), model.getId());
        return toDto(model);
    }

    private AiModel requireModel(Long id) {
        return models.findById(id).orElseThrow(AdminException::notFound);
    }

    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AdminException.invalid("modelType is required");
        }
        String type = raw.strip().toUpperCase(java.util.Locale.ROOT);
        if (!AiModel.TYPE_CHAT.equals(type) && !AiModel.TYPE_EMBEDDING.equals(type)) {
            throw AdminException.invalid("modelType must be CHAT or EMBEDDING");
        }
        return type;
    }

    private static void validateTemperature(BigDecimal temperature) {
        if (temperature != null
                && (temperature.compareTo(BigDecimal.ZERO) < 0 || temperature.compareTo(new BigDecimal("2")) > 0)) {
            throw AdminException.invalid("temperature must be between 0 and 2");
        }
    }

    private static void validateMaxTokens(Integer maxTokens) {
        if (maxTokens != null && (maxTokens < 0 || maxTokens > 1024)) {
            // the gateway validator clamps at 1024 — refuse beyond the clamp, not at it
            throw AdminException.invalid("maxOutputTokens must be between 0 and 1024");
        }
    }

    private static String require(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw AdminException.invalid(message);
        }
        if (value.strip().length() > maxLength) {
            throw AdminException.invalid("value exceeds " + maxLength + " characters");
        }
        return value.strip();
    }

    private ModelDto toDto(AiModel m) {
        String providerName = providers.findById(m.getProviderId())
                .map(AiProvider::getName).orElse("(provider missing)");
        return new ModelDto(m.getId(), m.getProviderId(), providerName, m.getName(),
                m.getTechnicalModelId(), m.getModelType(), m.getEmbeddingDim(),
                m.getMaxOutputTokens(), m.getTemperature(), m.isEnabled(), m.isDefault(),
                m.getCreatedAt().toString(), m.getUpdatedAt().toString(),
                m.getToolUse(), toolDefaults(m.getToolDefaultsJson()));
    }
}
