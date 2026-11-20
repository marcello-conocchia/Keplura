package com.keplura.server.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Wiring for the OIDC login path (docs/adr/0009). Two walls by construction:
 *
 * <ul>
 *   <li>A dedicated {@link HttpClient} — NEVER follows redirects (ADR-0008
 *       §7 posture: a discovery/token/JWKS target must not pivot the dial
 *       to a fourth host; same rationale as gatewayHttpClient).</li>
 *   <li>The desktop handoff URI is ENV-fixed (no reflected redirects,
 *       ADR-0009 §7): absolute http(s)/tauri, no userinfo/query/fragment —
 *       a bad value fails the boot loudly.</li>
 * </ul>
 */
@Configuration
public class OidcConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OidcConfiguration.class);

    /** Fixed per-call connect timeout for IdP dials (route policy is per row). */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    HttpClient oidcHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    OidcProperties oidcProperties(
            @Value("${keplura.oidc.desktop-redirect-uri:http://localhost:1420/}") String desktopRedirectUri,
            @Value("${keplura.oidc.public-base-url:}") String publicBaseUrl,
            @Value("${keplura.oidc.binding-ttl-seconds:600}") long bindingTtlSeconds,
            @Value("${keplura.oidc.handoff-ttl-seconds:60}") long handoffTtlSeconds) {
        URI redirect = validateDesktopRedirect(desktopRedirectUri);
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        if (!base.isEmpty()) {
            URI u;
            try {
                u = com.keplura.server.admin.SsrfGuard.parseShape(base); // shape only — dial policy guards the row URLs
            } catch (com.keplura.server.admin.AdminException e) {
                throw new IllegalStateException("keplura.oidc.public-base-url is malformed: " + e.getMessage());
            }
            if (u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null) {
                throw new IllegalStateException(
                        "keplura.oidc.public-base-url must be an origin without path/query/credentials");
            }
            base = trimTrailingSlash(base);
        }
        long ttl = Math.max(60, Math.min(3600, bindingTtlSeconds));
        long handoff = Math.max(10, Math.min(600, handoffTtlSeconds));
        log.info("oidc: desktop handoff target fixed to {}", redirect);
        return new OidcProperties(redirect.toString(), base, ttl, handoff);
    }

    /**
     * ADR-0009 §7: the handoff target is configuration, never caller input.
     * Absolute, scheme http(s) or tauri, host present, NO userinfo/query/
     * fragment (we append our own fragment). Bad config = broken boot.
     */
    private static URI validateDesktopRedirect(String raw) {
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("keplura.oidc.desktop-redirect-uri is malformed", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("tauri")) {
            throw new IllegalStateException(
                    "keplura.oidc.desktop-redirect-uri must be http(s):// or tauri:// — got scheme '" + scheme + "'");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("keplura.oidc.desktop-redirect-uri must name a host");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "keplura.oidc.desktop-redirect-uri must carry no userinfo, query or fragment");
        }
        return uri;
    }

    static String trimTrailingSlash(String url) {
        String v = url;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
