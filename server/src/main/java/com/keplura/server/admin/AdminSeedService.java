package com.keplura.server.admin;

import com.keplura.server.chat.ChatProperties;
import com.keplura.server.gateway.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-KI transition (docs/adr/0008 §5.3): the explicit, one-click "Aus
 * .env-Vorgaben anlegen" offer instead of silent start-time migration. The
 * button creates provider+model rows mirroring the CURRENT ENV gateway/chat
 * config so an existing deployment can move to DB upkeep without retyping —
 * and only while the provider table is still empty (idempotent, never
 * overwrites operator edits). The ENV API key is copied through the cipher
 * (encrypted at rest); nothing reads the ENV values back out afterwards.
 *
 * <p>Why not automatic at migration time: a typo'd ENV would be cemented
 * into the DB permanently, and an auto-import would contradict the "no
 * silent fallbacks" posture — the admin CONFIRMS the import by clicking.
 */
@Service
public class AdminSeedService {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedService.class);

    private final AiProviderRepository providers;
    private final AiModelRepository models;
    private final GatewayProperties envProps;
    private final ChatProperties envChat;
    private final SecretCipher secretCipher;

    public AdminSeedService(AiProviderRepository providers,
                            AiModelRepository models,
                            GatewayProperties envProps,
                            ChatProperties envChat,
                            SecretCipher secretCipher) {
        this.providers = providers;
        this.models = models;
        this.envProps = envProps;
        this.envChat = envChat;
        this.secretCipher = secretCipher;
    }

    /** What the seed WOULD create from the current ENV (no writes, no secret shown). */
    public record SeedOffer(boolean available, boolean hasKey, String providerType,
                            String baseUrl, String chatModel) {
    }

    public record SeedResult(boolean created, String message) {
    }

    public SeedOffer offer() {
        boolean configured = envProps.configured();
        return new SeedOffer(configured && providers.count() == 0,
                !envProps.apiKey().isEmpty(),
                envProps.provider().isEmpty() ? "api" : envProps.provider(),
                configured ? envProps.baseUrl() : "",
                configured ? envChat.model() : "");
    }

    @Transactional
    public SeedResult seedFromEnv() {
        if (!envProps.configured()) {
            return new SeedResult(false, "ENV-Konfiguration nicht gesetzt — es gibt nichts zu übernehmen.");
        }
        if (providers.count() > 0) {
            return new SeedResult(false, "Es existieren bereits Provider — Import nur auf leerer Tabelle.");
        }
        String cipher = null;
        if (!envProps.apiKey().isEmpty() && secretCipher.available()) {
            cipher = secretCipher.encrypt(envProps.apiKey());
        }
        String type = envProps.provider().isEmpty() ? "api" : envProps.provider();
        AiProvider provider = providers.saveAndFlush(AiProvider.create(
                "ENV-Import " + type, type, envProps.baseUrl(), cipher,
                (int) Math.max(AiProvider.MIN_TIMEOUT_SECONDS,
                        Math.min(AiProvider.MAX_TIMEOUT_SECONDS, envProps.timeoutSeconds())),
                true));
        String modelId = envChat.model() == null || envChat.model().isBlank()
                ? "local-chat-dev" : envChat.model();
        AiModel model = models.saveAndFlush(AiModel.create(provider.getId(),
                "ENV-Import " + modelId, modelId, AiModel.TYPE_CHAT, null, null, null, true));
        model.setDefault(true);
        models.saveAndFlush(model);
        log.info("admin seeded provider id={} model id={} from ENV values (secret copied: {})",
                provider.getId(), model.getId(), cipher != null);
        return new SeedResult(true, "Provider und Chat-Modell aus den ENV-Vorgaben angelegt.");
    }
}
