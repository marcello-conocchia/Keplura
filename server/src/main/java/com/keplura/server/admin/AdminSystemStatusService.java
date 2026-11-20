package com.keplura.server.admin;

import com.keplura.server.gateway.GatewayRuntime;
import com.keplura.server.search.EmbeddingService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ADMIN-KI Scope E: the admin system-status projection. Contract: every
 * component answers {@code ok | degraded | unreachable | not_configured}
 * and NOTHING else leaves — no base URLs, no model internals beyond the
 * operator-visible name, no secrets, no exception text. A logged-in NON-admin
 * never reaches this service (the interceptor 403s at the wire); a logged-in
 * admin seeing their own deployment's shape is the whole purpose of the page.
 *
 * <p>Provider liveness deliberately does NOT dial: the passive projection
 * (row exists + enabled + default resolves) is the honest "konfiguriert"
 * state; active dialing stays on the explicit per-provider test endpoint
 * (user gesture, SSRF-guarded), not on a page refresh loop.
 */
@Service
public class AdminSystemStatusService {

    public record ComponentState(String state, String detail) {
        static ComponentState ok() {
            return new ComponentState("ok", "");
        }

        static ComponentState of(String state) {
            return new ComponentState(state, "");
        }
    }

    private final AiProviderRepository providerRepository;
    private final AiModelRepository modelRepository;
    private final GatewayRuntime gatewayRuntime;
    private final RuntimeProviderConfigResolver resolver;
    private final JdbcTemplate jdbc;
    private final SecretCipher secretCipher;
    private final EmbeddingService embeddingService;
    // Scope D: the chunking/RAG app-Properties shown read-only (ADR-0008 §2
    // rows 14/15 — first round displays them; edits stay ENV like everything
    // that is not provider/model config).
    private final int chunkSizeChars;
    private final int chunkOverlapChars;
    private final int chatContextMessages;
    private final int chatRagTopK;
    private final int chatMaxPromptChars;
    private final int chatContextChunkChars;

    public AdminSystemStatusService(AiProviderRepository providerRepository,
                                    AiModelRepository modelRepository,
                                    GatewayRuntime gatewayRuntime,
                                    RuntimeProviderConfigResolver resolver,
                                    JdbcTemplate jdbc,
                                    SecretCipher secretCipher,
                                    EmbeddingService embeddingService,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${keplura.search.chunk-size-chars:800}") int chunkSizeChars,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${keplura.search.chunk-overlap-chars:100}") int chunkOverlapChars,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${keplura.chat.context-messages:20}") int chatContextMessages,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${keplura.chat.rag-top-k:4}") int chatRagTopK,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${keplura.chat.max-prompt-chars:4000}") int chatMaxPromptChars,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${keplura.chat.context-chunk-chars:700}") int chatContextChunkChars) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.gatewayRuntime = gatewayRuntime;
        this.resolver = resolver;
        this.jdbc = jdbc;
        this.secretCipher = secretCipher;
        this.embeddingService = embeddingService;
        this.chunkSizeChars = chunkSizeChars;
        this.chunkOverlapChars = chunkOverlapChars;
        this.chatContextMessages = chatContextMessages;
        this.chatRagTopK = chatRagTopK;
        this.chatMaxPromptChars = chatMaxPromptChars;
        this.chatContextChunkChars = chatContextChunkChars;
    }

    @Transactional(readOnly = true)
    public Map<String, ComponentState> status() {
        Map<String, ComponentState> out = new LinkedHashMap<>();
        out.put("backend", ComponentState.ok()); // answering this call IS the proof
        out.put("database", databaseState());
        out.put("providers", providersState());
        out.put("chatModel", chatModelState());
        out.put("embeddingModel", embeddingModelState());
        out.put("secretStore", secretCipher.available()
                ? ComponentState.ok()
                : new ComponentState("degraded", "master key not configured — provider secrets cannot be stored"));
        return out;
    }

    private ComponentState databaseState() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ComponentState.ok();
        } catch (DataAccessException e) {
            // class name only — connection strings and drivers stay internal
            return new ComponentState("unreachable", e.getClass().getSimpleName());
        }
    }

    private ComponentState providersState() {
        boolean anyEnabled = providerRepository.existsByEnabledTrue();
        return anyEnabled ? ComponentState.ok() : ComponentState.of("not_configured");
    }

    private ComponentState chatModelState() {
        Optional<RuntimeProviderConfigResolver.ResolvedModelConfig> db = resolver.chatConfig();
        if (db.isPresent()) {
            return ComponentState.ok();
        }
        // no DB default: the ENV fallback decides (same honesty as /system/info)
        return gatewayRuntime.envProperties().configured()
                ? ComponentState.ok()
                : ComponentState.of("not_configured");
    }

    private ComponentState embeddingModelState() {
        Optional<RuntimeProviderConfigResolver.ResolvedModelConfig> db = resolver.embeddingConfig();
        if (db.isPresent()) {
            return ComponentState.ok();
        }
        // "local" ENV hash backend counts as configured-but-degraded: honest
        // retrieval needs a real embedding model (documented M3d posture).
        return new ComponentState("degraded", "ENV backend " + embeddingService.getClass().getSimpleName());
    }

    /** RAG/Scope D settings view: app-level values, read-only in this round. */
    @Transactional(readOnly = true)
    public Map<String, Object> ragSettings() {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<AiModel> activeDefault = modelRepository
                .findFirstByModelTypeAndIsDefaultTrueAndEnabledTrueOrderByIdAsc(AiModel.TYPE_EMBEDDING);
        out.put("activeEmbeddingModel", activeDefault.map(AiModel::getName).orElse(""));
        out.put("activeEmbeddingDim", activeDefault.map(m -> (Object) m.getEmbeddingDim())
                .orElse(embeddingService.dimension()));
        out.put("envEmbeddingBackend", embeddingService instanceof RuntimeEmbeddingService bridge
                ? bridge.envBackend().getClass().getSimpleName()
                : embeddingService.getClass().getSimpleName());
        out.put("reindexOnModelChange", false);
        out.put("reindexHint", "Ein Wechsel des Embedding-Modells löst KEINE automatische "
                + "Neuindexierung aus. Bestehende Dokumente behalten ihre Vektoren des alten "
                + "Modells; für konsistente Ergebnisse einzeln neu hochladen.");
        out.put("chunking", Map.of(
                "chunkSizeChars", chunkSizeChars,
                "chunkOverlapChars", chunkOverlapChars,
                "editable", false,
                "note", "Chunking-Werte sind Deployment-Properties (ENV) und hier bewusst "
                        + "read-only — eine Aenderung hätte Index-Konsequenzen für alle Dokumente."));
        out.put("chat", Map.of(
                "contextMessages", chatContextMessages,
                "ragTopK", chatRagTopK,
                "maxPromptChars", chatMaxPromptChars,
                "contextChunkChars", chatContextChunkChars,
                "editable", false));
        return out;
    }
}
