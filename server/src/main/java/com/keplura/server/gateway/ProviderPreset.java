package com.keplura.server.gateway;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * First-class model-provider presets (M4c). The PO decision of 06.09. is that
 * all three model backends must be operable: A) Ollama on the local box,
 * B) vLLM on a GPU server, C) a hosted OpenAI-compatible API.
 *
 * <p>All three speak the OpenAI chat-completions protocol (Ollama through its
 * own {@code /v1} compatibility endpoint), so ONE code path serves them —
 * what differs per preset is only the defaults: the base URL to dial, the
 * model name to expect, and whether a bearer key is required at all (Ollama
 * and vLLM expose no auth surface on their OpenAI-compat endpoint; the hosted
 * API requires one). A preset is therefore a bundle of defaults the raw
 * {@code keplura.gateway.*} ENV can still override per value.
 *
 * <p>Leak discipline is unchanged by this class: the preset NAME is the only
 * provider fact that may ever surface (as the boolean-shaped
 * {@code model} state on {@code /api/v1/system/info}); base URL and key never
 * leave the JVM.
 */
public enum ProviderPreset {

    OLLAMA("ollama", "Ollama (lokaler OpenAI-kompatibler Server)",
            "http://127.0.0.1:11434/v1", AuthKind.NONE, false, "qwen2.5:0.5b", "local"),
    VLLM("vllm", "vLLM (GPU-Server)",
            "http://127.0.0.1:8000/v1", AuthKind.NONE, false, "default", "configured"),
    API("api", "Gehostete OpenAI-kompatible API",
            "https://api.openai.com/v1", AuthKind.BEARER, true, "gpt-4o-mini", "configured");

    /** What Authorization header the upstream request carries. */
    public enum AuthKind {
        /** No Authorization header at all — even when a stray key is configured. */
        NONE,
        /** {@code Authorization: Bearer <key>} when a key is configured. */
        BEARER
    }

    private static final Set<String> NAMES = Set.of("ollama", "vllm", "api");
    /** Values a human clearly means as "no preset" — legacy raw config, no error. */
    private static final Set<String> NONE_NAMES = Set.of("none", "off", "disabled", "aus");

    private final String id;
    private final String label;
    private final String defaultBaseUrl;
    private final AuthKind authKind;
    private final boolean requiresKey;
    /** Model name expected by this backend when KEPLURA_CHAT_MODEL is unset. */
    private final String defaultModel;
    /** The value surfaced on /api/v1/system/info as the `model` state. */
    private final String publicModelState;

    ProviderPreset(String id, String label, String defaultBaseUrl,
                   AuthKind authKind, boolean requiresKey,
                   String defaultModel, String publicModelState) {
        this.id = id;
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.authKind = authKind;
        this.requiresKey = requiresKey;
        this.defaultModel = defaultModel;
        this.publicModelState = publicModelState;
    }

    /**
     * Resolve the raw {@code keplura.provider} value. Unknown or malformed
     * values are a hard configuration error — NEVER a silent fallback to a
     * different backend: a typo must not quietly point traffic at a surprise
     * upstream (the same fail-loudly posture the search-store pin uses).
     *
     * @return empty when the value means "no preset" (blank/none/off/...),
     *         which keeps the legacy raw-config path (M4a) in force; the
     *         resolved preset otherwise
     * @throws IllegalStateException for a value that names no known preset
     */
    public static Optional<ProviderPreset> resolve(String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || NONE_NAMES.contains(v)) {
            return Optional.empty();
        }
        if (!NAMES.contains(v)) {
            throw new IllegalStateException(
                    "unknown model provider '" + safeEcho(raw)
                            + "' (expected ollama, vllm or api)");
        }
        return Optional.of(valueOf(v.toUpperCase(Locale.ROOT)));
    }

    /** Whether a raw config value names a valid preset (boot validation). */
    public static boolean isKnown(String raw) {
        try {
            resolve(raw);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Echo at most 32 sanitized chars of a bad value back — never the raw blob. */
    private static String safeEcho(String raw) {
        String s = raw == null ? "" : raw.strip().replaceAll("[^A-Za-z0-9._:/-]", "?");
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public AuthKind authKind() {
        return authKind;
    }

    public boolean requiresKey() {
        return requiresKey;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public String publicModelState() {
        return publicModelState;
    }
}
