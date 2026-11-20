package com.keplura.server.admin;

import com.keplura.server.gateway.ProviderPreset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * ADMIN-KI Scope B: provider CRUD with the PO's exact secret discipline —
 * the plaintext key is write-only (never in any response: {@code secretSet}
 * + a constant mask are the only outward facts), an empty secret on edit
 * KEEPS the stored one, a non-empty one replaces it. There is deliberately
 * no delete: {@code enabled=false} is the retirement verb (the model rows
 * keep their anchor and the runtime resolver stops using the provider).
 *
 * <p>base_url IS returned to admins (they must be able to read what they
 * edit); the leak boundary that stays absolute is the SECRET (masked,
 * ciphertext-only) and what the PUBLIC surfaces may show (see
 * {@link AdminSystemStatusService}: names and booleans, never URLs).
 */
@Service
public class AdminProviderService {

    private static final Logger log = LoggerFactory.getLogger(AdminProviderService.class);

    /** API response shape — secret is represented ONLY as set/masked facts. */
    public record ProviderDto(Long id, String name, String providerType, String baseUrl,
                              boolean secretSet, String secretMasked,
                              int timeoutSeconds, boolean enabled,
                              String createdAt, String updatedAt) {
    }

    /** Create request: {@code secret} is write-only plaintext (encrypted here, never echoed). */
    public record ProviderCreateRequest(String name, String providerType, String baseUrl,
                                        String secret, Integer timeoutSeconds, Boolean enabled) {
    }

    /** Patch request: null field = unchanged; blank/empty secret KEEPS the
     *  stored one, a non-empty one replaces it (clearing has no offered verb). */
    public record ProviderPatchRequest(String name, String baseUrl,
                                       Integer timeoutSeconds, Boolean enabled, String secret) {
    }

    private static final Set<String> TYPES = Set.of("ollama", "vllm", "api");

    private final AiProviderRepository repository;
    private final SecretCipher secretCipher;

    public AdminProviderService(AiProviderRepository repository, SecretCipher secretCipher) {
        this.repository = repository;
        this.secretCipher = secretCipher;
    }

    @Transactional(readOnly = true)
    public List<ProviderDto> list() {
        return repository.findAll().stream().map(AdminProviderService::toDto).toList();
    }

    @Transactional
    public ProviderDto create(ProviderCreateRequest req) {
        String name = require(req.name(), "name is required", AiProvider.MAX_NAME_LENGTH);
        String type = require(req.providerType(), "providerType is required", 20);
        if (!TYPES.contains(type.toLowerCase(java.util.Locale.ROOT))
                || !ProviderPreset.isKnown(type.toLowerCase(java.util.Locale.ROOT))) {
            throw AdminException.invalid("providerType must be ollama, vllm or api");
        }
        type = type.toLowerCase(java.util.Locale.ROOT);
        String baseUrl = validateBaseUrl(req.baseUrl());
        int timeout = clampTimeout(req.timeoutSeconds());
        if (repository.existsByNameIgnoreCase(name)) {
            throw AdminException.invalid("a provider with that name already exists");
        }
        String cipher = encryptIfPresent(req.secret());
        AiProvider saved = repository.saveAndFlush(AiProvider.create(
                name, type, baseUrl, cipher, timeout, req.enabled() == null || req.enabled()));
        log.info("admin created provider id={} type={} enabled={}", saved.getId(), type, saved.isEnabled());
        return toDto(saved);
    }

    @Transactional
    public ProviderDto update(Long id, ProviderPatchRequest req) {
        AiProvider provider = requireProvider(id);
        String name = req.name() == null ? provider.getName() : require(req.name(), "name must not be blank",
                AiProvider.MAX_NAME_LENGTH);
        if (!name.equalsIgnoreCase(provider.getName()) && repository.existsByNameIgnoreCase(name)) {
            throw AdminException.invalid("a provider with that name already exists");
        }
        String baseUrl = req.baseUrl() == null ? provider.getBaseUrl() : validateBaseUrl(req.baseUrl());
        // PO Scope B exactly: empty secret on edit = keep the old value; a
        // non-empty one = replace. (Clearing is not an offered verb — edit or
        // deactivate.)
        String newCipher = null;
        if (req.secret() != null && !req.secret().isBlank()) {
            newCipher = encryptIfPresent(req.secret());
        }
        provider.applyEdit(name.strip(), baseUrl,
                req.timeoutSeconds() == null ? null : clampTimeout(req.timeoutSeconds()),
                req.enabled(), newCipher);
        repository.saveAndFlush(provider);
        log.info("admin updated provider id={} enabled={}", provider.getId(), provider.isEnabled());
        return toDto(provider);
    }

    private AiProvider requireProvider(Long id) {
        return repository.findById(id).orElseThrow(AdminException::notFound);
    }

    private String encryptIfPresent(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        if (!secretCipher.available()) {
            throw AdminException.masterKeyMissing();
        }
        if (secret.length() > 512) {
            throw AdminException.invalid("secret too long");
        }
        return secretCipher.encrypt(secret.strip());
    }

    /**
     * Shape validation at write time (scheme/userinfo/port, SsrfGuard
     * parseShape): a provider that could never be dialed honestly is refused
     * as 400 up front. The DIAL-side route policy runs on the test endpoint.
     */
    private static String validateBaseUrl(String raw) {
        var uri = SsrfGuard.parseShape(raw);
        String v = raw.trim();
        if (v.length() > AiProvider.MAX_BASE_URL_LENGTH) {
            throw AdminException.invalid("baseUrl too long");
        }
        if (uri.getPath() != null && !uri.getPath().isBlank() && !uri.getPath().startsWith("/")) {
            throw AdminException.invalid("baseUrl path is invalid");
        }
        return v;
    }

    private static int clampTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return 30;
        }
        if (timeoutSeconds < AiProvider.MIN_TIMEOUT_SECONDS
                || timeoutSeconds > AiProvider.MAX_TIMEOUT_SECONDS) {
            throw AdminException.invalid("timeoutSeconds must be between "
                    + AiProvider.MIN_TIMEOUT_SECONDS + " and " + AiProvider.MAX_TIMEOUT_SECONDS);
        }
        return timeoutSeconds;
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

    /** DTO mapping — the ONE place deciding what leaves the entity: never the cipher. */
    static ProviderDto toDto(AiProvider p) {
        return new ProviderDto(p.getId(), p.getName(), p.getProviderType(), p.getBaseUrl(),
                p.hasSecret(), p.hasSecret() ? SecretCipher.MASK : "",
                p.getTimeoutSeconds(), p.isEnabled(),
                p.getCreatedAt().toString(), p.getUpdatedAt().toString());
    }
}
