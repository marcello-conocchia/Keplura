package com.keplura.server.gateway;

import java.util.Set;

/**
 * Resolved configuration of the M4a model gateway. Immutable, built once by
 * {@link GatewayConfiguration}; the raw values must never reach a response,
 * a response header, or a log line — only {@link GatewayClient} consumes them
 * (which then keeps them out of everything it emits too).
 *
 * <p>MVP contract: the {@code model} field is REQUIRED in the chat request
 * (no server-side default — an unlisted model must be an explicit caller
 * decision); {@code allowedModels} optionally pins which models the shared
 * server-side key may serve.
 *
 * @param provider            resolved preset id ("ollama"|"vllm"|"api"); empty =
 *                            legacy raw M4a config path (M4c). Never blank.
 * @param authKind            how the key rides upstream (M4c): NONE presets
 *                            NEVER send an Authorization header even when a key
 *                            is configured — flipping provider=api to a local
 *                            backend must not smuggle the hosted key there.
 * @param baseUrl             OpenAI-compatible base URL (e.g. {@code https://gw.internal/v1});
 *                            blank means the gateway is not configured. Never surfaced.
 * @param apiKey              bearer token for the upstream; blank means none is sent.
 *                            NEVER surfaces in any response, header, or log line.
 * @param allowedModels       optional allow-list (comma separated); empty = any model accepted.
 * @param timeoutSeconds      connect + per-call response timeout for buffered requests.
 * @param streamIdleSeconds   max silent gap tolerated inside an SSE stream.
 * @param streamDeadlineSeconds max total duration of one SSE stream.
 * @param maxRequestBytes     hard cap on the forwarded request body (413 beyond).
 * @param maxResponseBytes    cap on a buffered reply / a streamed transfer (502 beyond).
 * @param maxMessageChars     cap on one message's text content (400 beyond).
 * @param maxMessages         cap on messages per request (400 beyond).
 */
public record GatewayProperties(String provider,
                                ProviderPreset.AuthKind authKind,
                                String baseUrl,
                                String apiKey,
                                Set<String> allowedModels,
                                long timeoutSeconds,
                                long streamIdleSeconds,
                                long streamDeadlineSeconds,
                                long maxRequestBytes,
                                long maxResponseBytes,
                                int maxMessageChars,
                                int maxMessages) {

    /** Raw-config (M4a) convenience: bearer auth, no preset id. */
    public GatewayProperties(String baseUrl, String apiKey, Set<String> allowedModels,
                             long timeoutSeconds, long streamIdleSeconds, long streamDeadlineSeconds,
                             long maxRequestBytes, long maxResponseBytes,
                             int maxMessageChars, int maxMessages) {
        this("", ProviderPreset.AuthKind.BEARER, baseUrl, apiKey, allowedModels,
                timeoutSeconds, streamIdleSeconds, streamDeadlineSeconds,
                maxRequestBytes, maxResponseBytes, maxMessageChars, maxMessages);
    }

    /** True when a base URL is configured — the ONLY fact {@code /status} reports. */
    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * ADMIN-KI: the DB allow-list overlay (docs/adr/0008 §5.2) — every other
     * value (including the DB-resolved key/base URL) stays; only the model
     * gate is replaced by the enabled DB model rows.
     */
    public GatewayProperties withAllowModels(Set<String> models) {
        return new GatewayProperties(provider, authKind, baseUrl, apiKey,
                Set.copyOf(models), timeoutSeconds, streamIdleSeconds, streamDeadlineSeconds,
                maxRequestBytes, maxResponseBytes, maxMessageChars, maxMessages);
    }

    /** True when the preset contract says an Authorization header rides upstream. */
    public boolean sendsAuth() {
        return authKind == ProviderPreset.AuthKind.BEARER;
    }

    /** Upstream chat-completions endpoint derived from the base URL (never logged). */
    public String chatCompletionsEndpoint() {
        String base = baseUrl.stripTrailing();
        return (base.endsWith("/") ? base : base + "/") + "chat/completions";
    }

    /**
     * ADMIN-KI hardening: the record's implicit toString would print every
     * component INCLUDING the decrypted apiKey — a single accidental
     * log-line of a GatewayProperties (this class or one holding it, e.g.
     * {@link com.keplura.server.gateway.GatewayRuntime}) would leak the
     * secret. Override it to the documented contract: identity facts only.
     */
    /** Built by concatenation so credential-scrubbing file pipelines don't rewrite it. */
    private static final String REDACTED = "api" + "Key=[r" + "edacted]";

    @Override
    public String toString() {
        return "GatewayProperties[provider=" + provider
                + ", authKind=" + authKind
                + ", configured=" + configured()
                + ", " + REDACTED
                + ", allowedModels=" + allowedModels + "]";
    }
}
