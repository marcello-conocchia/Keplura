package com.keplura.server.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Wires the M4a model gateway. The gateway beans are ALWAYS present (the
 * status endpoint must answer even when unconfigured); an empty base URL
 * simply keeps the gateway in the configured=false state where every chat
 * call gets a clean 503 before touching an upstream.
 *
 * <p>M4c layering: the raw {@code keplura.gateway.*} config stays the
 * single source of truth for everything (full M4a compatibility). The
 * {@code keplura.provider} preset (ollama|vllm|api) only fills the base-URL
 * and auth-kind DEFAULTS when the raw ENV left them unset — choosing a
 * provider is configuration, not a code fork.
 *
 * <p>All values resolve from ENV (see application.properties); a key lives
 * only inside {@link GatewayProperties} in this JVM and is consumed solely by
 * {@link GatewayClient} — it is never a response field, a response header,
 * or a logged token.
 *
 * <p>Boot-time honesty (M4c): an unknown provider value fails the boot
 * (fail-loudly, same posture as the search-store pin), and a hosted-API
 * preset without a key logs a WARNING at startup — the operator learns the
 * gateway cannot serve before the first user hits a 502.
 */
@Configuration
public class GatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    /**
     * Resolve the provider preset from the raw config value. Unknown names
     * throw {@link IllegalStateException} — in a Spring @Bean factory method
     * that aborts context startup (Boot-failure), which is the point.
     */
    static Optional<ProviderPreset> resolvePreset(String rawProvider) {
        return ProviderPreset.resolve(rawProvider);
    }

    @Bean
    GatewayProperties gatewayProperties(
            @Value("${keplura.provider:}") String provider,
            @Value("${keplura.gateway.base-url:}") String baseUrl,
            @Value("${keplura.gateway.api-key:}") String apiKey,
            @Value("${keplura.gateway.allowed-models:}") String allowedModels,
            @Value("${keplura.gateway.timeout-seconds:30}") long timeoutSeconds,
            @Value("${keplura.gateway.stream-idle-seconds:60}") long streamIdleSeconds,
            @Value("${keplura.gateway.stream-deadline-seconds:300}") long streamDeadlineSeconds,
            @Value("${keplura.gateway.max-request-bytes:1048576}") long maxRequestBytes,
            @Value("${keplura.gateway.max-response-bytes:8388608}") long maxResponseBytes,
            @Value("${keplura.gateway.max-message-chars:65536}") int maxMessageChars,
            @Value("${keplura.gateway.max-messages:256}") int maxMessages) {
        Optional<ProviderPreset> preset = resolvePreset(provider);
        String trimmedBase = baseUrl == null ? "" : baseUrl.trim();
        String trimmedKey = apiKey == null ? "" : apiKey.trim();
        String effectiveBase = trimmedBase;
        ProviderPreset.AuthKind authKind = ProviderPreset.AuthKind.BEARER;
        String providerId = "";
        if (preset.isPresent()) {
            ProviderPreset p = preset.get();
            providerId = p.id();
            authKind = p.authKind();
            if (effectiveBase.isEmpty()) {
                effectiveBase = p.defaultBaseUrl();
            }
            if (p.requiresKey() && trimmedKey.isEmpty()) {
                // loud, but keyless-safe: only the preset NAME is logged.
                log.warn("provider '{}' requires an API key; set KEPLURA_GATEWAY_API_KEY "
                        + "— the gateway stays non-functional until it is present", p.id());
            }
        }
        Set<String> allowed = new LinkedHashSet<>();
        Arrays.stream(allowedModels.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .forEach(allowed::add);
        return new GatewayProperties(
                providerId,
                authKind,
                effectiveBase,
                trimmedKey,
                Set.copyOf(allowed),
                Math.max(1, timeoutSeconds),
                Math.max(1, streamIdleSeconds),
                Math.max(streamIdleSeconds, streamDeadlineSeconds),
                Math.max(1024, maxRequestBytes),
                Math.max(1024, maxResponseBytes),
                Math.max(1, maxMessageChars),
                Math.max(1, maxMessages));
    }

    @Bean
    HttpClient gatewayHttpClient(GatewayProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                // NEVER follow redirects: a malicious or misconfigured upstream
                // could otherwise point the bearer key at a third host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    GatewayClient gatewayClient(HttpClient gatewayHttpClient, GatewayProperties props) {
        return new GatewayClient(gatewayHttpClient, props);
    }
}
