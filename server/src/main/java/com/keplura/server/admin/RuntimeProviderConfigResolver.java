package com.keplura.server.admin;

import com.keplura.server.chat.ChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ADMIN-KI Scope C/D: the single bridge from DB rows to the runtime
 * chat/embedding path (docs/adr/0008 §5). The whole transition strategy
 * lives here:
 *
 * <ul>
 *   <li><b>DB precedence</b> — when an enabled default CHAT model sits on an
 *       enabled provider, {@link #chatConfig()} resolves it and the
 *       {@link GatewayRuntime} builds the dial config from those rows; the
 *       ENV values stop mattering for chat.</li>
 *   <li><b>ENV fallback</b> — with no such row (fresh migration, or every
 *       admin row deactivated) resolution returns empty and the boot-time
 *       ENV-resolved config stays in force unchanged. The documented
 *       "Rückfall": deactivate all DB rows and the system behaves EXACTLY
 *       like before ADMIN-KI (ADR §5.4) — reversal is an operator action,
 *       not a data migration.</li>
 *   <li>A deactivated provider or model never resolves (PO test: "deaktivierter
 *       Provider wird nicht genutzt") — the enabled flag is re-proven inside
 *       the resolution transaction.</li>
 * </ul>
 *
 * <p>Read-per-request, no cache (ADR-0004 posture): provider/model edits
 * take effect on the next call without a restart — that is the point of the
 * admin surface — and each resolution is two indexed reads on a handful of
 * rows.
 */
@Service
public class RuntimeProviderConfigResolver {

    public static final String TYPE_CHAT = AiModel.TYPE_CHAT;
    public static final String TYPE_EMBEDDING = AiModel.TYPE_EMBEDDING;

    private static final Logger log = LoggerFactory.getLogger(RuntimeProviderConfigResolver.class);

    /** A resolved DB-backed provider+model pair. Never carries secret material. */
    public record ResolvedModelConfig(AiProvider provider, AiModel model) {
    }

    private final AiProviderRepository providerRepository;
    private final AiModelRepository modelRepository;

    public RuntimeProviderConfigResolver(AiProviderRepository providerRepository,
                                         AiModelRepository modelRepository) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
    }

    /** The enabled default CHAT model on an enabled provider, or empty (= ENV path). */
    @Transactional(readOnly = true)
    public Optional<ResolvedModelConfig> chatConfig() {
        return resolve(AiModel.TYPE_CHAT);
    }

    /** The enabled default EMBEDDING model on an enabled provider, or empty. */
    @Transactional(readOnly = true)
    public Optional<ResolvedModelConfig> embeddingConfig() {
        return resolve(AiModel.TYPE_EMBEDDING);
    }

    private Optional<ResolvedModelConfig> resolve(String type) {
        Optional<AiModel> defaultModel =
                modelRepository.findFirstByModelTypeAndIsDefaultTrueAndEnabledTrueOrderByIdAsc(type);
        if (defaultModel.isEmpty()) {
            return Optional.empty();
        }
        AiModel model = defaultModel.get();
        AiProvider provider = providerRepository.findById(model.getProviderId())
                .filter(AiProvider::isEnabled)
                .orElse(null);
        if (provider == null) {
            // Honest, quiet: the row is set up wrong (default on a deactivated
            // provider). Fall back to ENV rather than failing the product path;
            // the admin system-status surface names the inconsistency.
            log.warn("default {} model {} sits on a non-enabled provider — using ENV config",
                    type, model.getId());
            return Optional.empty();
        }
        return Optional.of(new ResolvedModelConfig(provider, model));
    }

    /**
     * Chat model-name + max-tokens overlay: the ENV {@link ChatProperties}
     * keep everything except what an enabled DB default explicitly pins
     * (technical model id; max_output_tokens clamped to the gateway cap).
     */
    @Transactional(readOnly = true)
    public ChatProperties effectiveChatProperties(ChatProperties env) {
        return chatConfig().map(pair -> env.withModelResolution(
                        pair.model().getTechnicalModelId(), pair.model().getMaxOutputTokens()))
                .orElse(env);
    }
}
