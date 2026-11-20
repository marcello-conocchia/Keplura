package com.keplura.server.oidc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.keplura.server.admin.AdminException;
import com.keplura.server.admin.SsrfGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC discovery + JWKS loader (docs/adr/0009 §4/§8). The ONLY place the
 * server dials IdP infrastructure in the login path — every dial passes the
 * ADMIN-KI route policy first ({@link SsrfGuard#validateForDial}: DNS
 * resolved and checked, loopback/link-local/CGNAT refused unless ops
 * allow-list it; redirects NEVER followed by the dedicated client; hard
 * timeouts from the provider row; bodies capped at {@value #MAX_BYTES}).
 *
 * <p>Refusal vocabulary mirrors the guard's recon posture: every failure —
 * policy veto, DNS, timeout, malformed JSON, issuer mismatch — collapses to
 * {@link OidcException#upstream()} (or refused for the issuer mismatch, a
 * config fact the ADMIN sees on save). Nothing naming host, URL or cause
 * ever leaves (GatewayClient discipline: class names in logs only).
 *
 * <p>Cache: JWKS per provider with 5-minute TTL; an unknown kid triggers AT
 * MOST one refresh per validation (replay-safe against a kid-probing
 * hammer: the refresh itself is rate-bounded by the TTL window). Discovery
 * is re-fetched per login begin on purpose (ADR-0009 §8: stale endpoint
 * data must never outlive an IdP host move; one small capped GET per login
 * is the cost).
 */
@Service
public class OidcDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(OidcDiscoveryClient.class);

    static final int MAX_BYTES = 64 * 1024;
    static final Duration JWKS_TTL = Duration.ofMinutes(5);

    /** Parsed discovery state: the three endpoints + the validated issuer. */
    public record Discovery(String issuer, String authorizationEndpoint,
                            String tokenEndpoint, String jwksUri) {
    }

    private record JwksCacheEntry(Map<String, PublicKey> keys, Instant fetchedAt) {
    }

    private final HttpClient client;
    private final SsrfGuard ssrfGuard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<Long, JwksCacheEntry> jwksCache = new ConcurrentHashMap<>();

    public OidcDiscoveryClient(@Qualifier("oidcHttpClient") HttpClient client, SsrfGuard ssrfGuard) {
        this.client = client;
        this.ssrfGuard = ssrfGuard;
    }

    /**
     * Fetch and validate the discovery document. {@code expectedIssuer} must
     * equal the document's {@code issuer} string-exactly (ADR-0009 §4) —
     * a mismatch is a misconfiguration or a hostile row, refused not
     * silently tolerated. Timeout is the row's value, hard-capped.
     */
    public Discovery discover(OidcProvider provider) {
        JsonNode doc = getJson(provider.getDiscoveryUrl(), timeout(provider));
        String issuer = doc.path("issuer").asString(null);
        if (issuer == null || !OidcCodec.equalsConstantTime(issuer, provider.getIssuer())) {
            log.warn("oidc discovery: issuer mismatch for provider={}", provider.getId());
            throw OidcException.refused();
        }
        String authz = endpoint(doc, "authorization_endpoint");
        String token = endpoint(doc, "token_endpoint");
        String jwks = endpoint(doc, "jwks_uri");
        return new Discovery(issuer, authz, token, jwks);
    }

    /** kid → RSA public key for the jwks URI (cached; refresh-on-unknown). */
    public Map<String, PublicKey> jwks(Long providerId, String jwksUri, int timeoutSeconds) {
        JwksCacheEntry entry = jwksCache.get(providerId);
        Map<String, PublicKey> keys = (entry != null && entry.fetchedAt().plus(JWKS_TTL).isAfter(Instant.now()))
                ? entry.keys() : null;
        if (keys == null) {
            keys = fetchJwks(jwksUri, timeoutSeconds);
            jwksCache.put(providerId, new JwksCacheEntry(keys, Instant.now()));
        }
        return keys;
    }

    /** One TTL-bounded refresh for an unknown kid; returns null when the
     *  cache was hot (the caller must then refuse — replay protection). */
    public Map<String, PublicKey> refreshJwksOnce(Long providerId, String jwksUri, int timeoutSeconds) {
        JwksCacheEntry entry = jwksCache.get(providerId);
        if (entry != null && entry.fetchedAt().plus(JWKS_TTL).isAfter(Instant.now())) {
            return null; // cache was fresh: the kid is unknown for real — refuse
        }
        Map<String, PublicKey> keys = fetchJwks(jwksUri, timeoutSeconds);
        jwksCache.put(providerId, new JwksCacheEntry(keys, Instant.now()));
        return keys;
    }

    /** Test seam: drop the cached JWKS for a provider. */
    public void evict(Long providerId) {
        jwksCache.remove(providerId);
    }

    private Map<String, PublicKey> fetchJwks(String jwksUri, int timeoutSeconds) {
        JsonNode doc = getJson(jwksUri, Duration.ofSeconds(timeoutSeconds));
        Map<String, PublicKey> keys = new HashMap<>();
        var it = JwsVerifier.keys(doc);
        while (it.hasNext()) {
            JsonNode jwk = it.next();
            if (!"sig".equals(jwk.path("use").asString("sig"))) {
                continue; // encryption keys never verify signatures
            }
            String kid = jwk.path("kid").asString(null);
            if (kid == null || kid.isBlank() || kid.length() > 200) {
                continue;
            }
            keys.put(kid, JwsVerifier.rsaPublicFromJwk(jwk));
        }
        if (keys.isEmpty()) {
            throw OidcException.upstream();
        }
        return Map.copyOf(keys);
    }

    private static Duration timeout(OidcProvider provider) {
        return Duration.ofSeconds(Math.max(OidcProvider.MIN_TIMEOUT_SECONDS,
                Math.min(OidcProvider.MAX_TIMEOUT_SECONDS, provider.getTimeoutSeconds())));
    }

    private static String endpoint(JsonNode doc, String field) {
        String v = doc.path(field).asString(null);
        if (v == null || v.isBlank() || v.length() > OidcProvider.MAX_URL_LENGTH) {
            throw OidcException.upstream();
        }
        return v;
    }

    /** SSRF-guarded GET of a JSON document with hard size + timeout caps. */
    private JsonNode getJson(String rawUrl, Duration timeout) {
        URI uri;
        try {
            uri = com.keplura.server.admin.SsrfGuard.parseShape(rawUrl);
            ssrfGuard.validateForDial(rawUrl);
        } catch (AdminException e) {
            // The guard's refusal is uniform by design; map it into the OIDC
            // vocabulary — the browser learns "unreachable", nothing more.
            // POLICY_REFUSED keeps the distinction for the ADMIN dial-check
            // only (same generic message; §8), never for the login path.
            throw OidcException.policyRefused();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                drainCapped(response.body());
                log.warn("oidc dial: upstream answered http {}", status);
                throw OidcException.upstream();
            }
            byte[] body = drainCapped(response.body());
            return mapper.readTree(body);
        } catch (OidcException e) {
            throw e;
        } catch (IOException e) {
            // class name only: JDK IO messages embed host/port
            log.warn("oidc dial: IO failure ({})", e.getClass().getSimpleName());
            throw OidcException.upstream();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw OidcException.upstream();
        }
    }

    /** Read at most MAX_BYTES + 1 then close (an oversized doc is corrupt). */
    private static byte[] drainCapped(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("oidc response exceeds cap");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** UTF-8 of a capped body (helper for the token client). */
    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
