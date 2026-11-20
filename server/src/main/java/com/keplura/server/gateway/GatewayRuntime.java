package com.keplura.server.gateway;

import com.keplura.server.admin.AiModel;
import com.keplura.server.admin.AiProvider;
import com.keplura.server.admin.AiProviderRepository;
import com.keplura.server.admin.AiModelRepository;
import com.keplura.server.admin.RuntimeProviderConfigResolver;
import com.keplura.server.admin.RuntimeProviderConfigResolver.ResolvedModelConfig;
import com.keplura.server.admin.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * ADMIN-KI Scope C: the ONE place that answers "what is the effective gateway
 * configuration for THIS request" — DB rows win, .env is the fallback
 * (docs/adr/0008 §5). The chat path ({@link ChatService side}), the raw
 * gateway proxy and the public {@code /system/info} projection all read
 * through here instead of the boot-time {@link GatewayProperties} bean, so
 * enabling a provider/model in the admin panel takes effect on the next
 * request without a restart, and deactivating every row returns the system to
 * exactly the pre-ADMIN-KI ENV behaviour (the documented Rückfall).
 *
 * <p>Secret handling: the provider's ciphertext is decrypted per resolution
 * and lives ONLY in the returned immutable {@link GatewayProperties} — the
 * same leak-proof container the ENV path already used (never a response,
 * header or log line). An undecryptable secret (master key rotated) degrades
 * to "no key" with one warning; it never boot-fails and never crashes chat.
 *
 * <p>Allow-list semantics (ADR §2 row 4): with DB config active, the
 * enabled CHAT model rows of enabled providers ARE the allow-list — the ENV
 * {@code allowed-models} pin no longer applies. The transport caps
 * (stream/idle/bytes) stay ENV values: the admin surface edits provider and
 * model selection, not the DoS bounds.
 */
@Service
public class GatewayRuntime {

    private static final Logger log = LoggerFactory.getLogger(GatewayRuntime.class);

    private final GatewayProperties envProps;
    private final RuntimeProviderConfigResolver resolver;
    private final AiModelRepository modelRepository;
    private final AiProviderRepository providerRepository;
    private final SecretCipher secretCipher;
    /** False for the standalone/test factory: the resolver beans are absent by design. */
    private final boolean dbBacked;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayRuntime(GatewayProperties envProps,
                          RuntimeProviderConfigResolver resolver,
                          AiModelRepository modelRepository,
                          AiProviderRepository providerRepository,
                          SecretCipher secretCipher) {
        this.envProps = envProps;
        this.resolver = resolver;
        this.modelRepository = modelRepository;
        this.providerRepository = providerRepository;
        this.secretCipher = secretCipher;
        this.dbBacked = true;
    }

    private GatewayRuntime(GatewayProperties envProps) {
        this.envProps = envProps;
        this.resolver = null;
        this.modelRepository = null;
        this.providerRepository = null;
        this.secretCipher = null;
        this.dbBacked = false;
    }

    /**
     * Standalone factory (MockMvc unit tests of controller wiring): pure ENV
     * behaviour, no DB resolution — the pre-ADMIN-KI contract.
     */
    public static GatewayRuntime envOnly(GatewayProperties envProps) {
        return new GatewayRuntime(envProps);
    }

    /** The boot-resolved ENV config (caps donor for DB resolutions + pure-ENV mode). */
    public GatewayProperties envProperties() {
        return envProps;
    }

    /** True when an enabled DB default chat pair drives the request path right now. */
    @Transactional(readOnly = true)
    public boolean dbChatActive() {
        return dbBacked && resolver.chatConfig().isPresent();
    }

    /**
     * Effective config for this request: DB default-chat resolution when
     * present, otherwise the ENV bean unchanged. Never throws on config
     * absence — "no provider configured" stays representable (configured()
     * == false) exactly like the M4a/M4c boot state.
     */
    @Transactional(readOnly = true)
    public GatewayProperties effective() {
        if (!dbBacked) {
            return envProps;
        }
        Optional<ResolvedModelConfig> db = resolver.chatConfig();
        if (db.isEmpty()) {
            return envProps;
        }
        return fromDb(db.get());
    }

    private GatewayProperties fromDb(ResolvedModelConfig pair) {
        AiProvider p = pair.provider();
        AiModel m = pair.model();
        Optional<ProviderPreset> preset = ProviderPreset.resolve(p.getProviderType());
        String apiKey = "";
        if (p.hasSecret()) {
            if (!secretCipher.available()) {
                // provider saved before/without a master key, or key rotated:
                // honest degraded call (no auth) — logged by class context only.
                log.warn("provider {} has a secret but no master key is configured; dialing without auth",
                        p.getId());
            } else {
                try {
                    apiKey = secretCipher.decrypt(p.getSecretCipher());
                } catch (RuntimeException e) {
                    log.warn("provider {} secret undecryptable (master key rotated?); dialing without auth",
                            p.getId());
                    apiKey = "";
                }
            }
        }
        // The allow-list = every enabled CHAT model on every ENABLED provider
        // (ADR §2 row 4); the default row is always inside it by construction
        // (its provider is enabled — the resolver proved that this transaction).
        Set<String> allowed = new LinkedHashSet<>();
        for (AiModel model : modelRepository.findByModelTypeAndEnabledTrueOrderByIdAsc(AiModel.TYPE_CHAT)) {
            boolean providerEnabled = providerRepository.findById(model.getProviderId())
                    .map(AiProvider::isEnabled).orElse(false);
            if (providerEnabled) {
                allowed.add(model.getTechnicalModelId());
            }
        }
        allowed.add(m.getTechnicalModelId());
        return new GatewayProperties(
                preset.map(ProviderPreset::id).orElse("db"),
                preset.map(ProviderPreset::authKind).orElse(ProviderPreset.AuthKind.BEARER),
                p.getBaseUrl(),
                apiKey,
                Set.copyOf(allowed),
                Math.max(1, p.getTimeoutSeconds()),
                envProps.streamIdleSeconds(),
                envProps.streamDeadlineSeconds(),
                envProps.maxRequestBytes(),
                envProps.maxResponseBytes(),
                envProps.maxMessageChars(),
                envProps.maxMessages());
    }

    /**
     * The public closed-vocabulary projection (none|local|configured), now
     * DB-aware: an active DB ollama provider reads "local" exactly like the
     * ollama ENV preset did — the privacy posture shown on the login screen
     * keeps telling the truth after the config moved into the table.
     */
    @Transactional(readOnly = true)
    public String modelState() {
        GatewayProperties effective = effective();
        if (!effective.provider().isEmpty()) {
            return ProviderPreset.resolve(effective.provider())
                    .map(ProviderPreset::publicModelState)
                    .orElse("configured");
        }
        return effective.configured() ? "configured" : "none";
    }
}
