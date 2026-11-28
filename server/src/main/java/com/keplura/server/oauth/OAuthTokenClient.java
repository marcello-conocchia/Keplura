package com.keplura.server.oauth;

import com.keplura.server.admin.AdminException;
import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.http.PinnedHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TU-06b: talks to an admin-configured OAuth token endpoint (code exchange, refresh, revocation). Same posture as
 * the OIDC token client of ADR-0009: SSRF-guarded admin target, no redirects, no proxy from the environment,
 * bounded body, client secret only as HTTP Basic header. HTTPS only ({@link OAuthTransportPolicy}), checked before
 * anything is sent, and the connection is pinned to the checked address ({@link PinnedHttp}). No response text, header or token ever reaches a log,
 * an exception message or a caller-visible text.
 */
@Component
public class OAuthTokenClient {

    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_TOKEN_CHARS = 8000;
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** {@code expiresIn} in seconds or {@code null}; {@code refreshToken} may be {@code null}. */
    public record Tokens(String accessToken, String refreshToken, Long expiresIn) {
        @Override public String toString() { return "Tokens[redacted]"; }
    }

    /** Fixed, token-free failure; {@code rejected} = the endpoint answered and refused (e.g. invalid_grant). */
    public static class TokenException extends RuntimeException {
        private final boolean rejected;
        TokenException(boolean rejected) {
            super(rejected ? "token endpoint refused" : "token endpoint unavailable", null, false, false);
            this.rejected = rejected;
        }
        public boolean rejected() { return rejected; }
    }

    private final PinnedHttp http;
    private final SsrfGuard guard;
    private final OAuthTransportPolicy transport;

    @Autowired
    public OAuthTokenClient(SsrfGuard guard, OAuthTransportPolicy transport) { this(guard, transport, new PinnedHttp()); }

    public OAuthTokenClient(SsrfGuard guard, OAuthTransportPolicy transport, PinnedHttp http) {
        this.guard = guard;
        this.transport = transport;
        this.http = http;
    }

    /** Whether a personal bearer token may be sent to this resource URL (HTTPS only, see {@link OAuthTransportPolicy}). */
    public boolean allowsCredentialTarget(String url) {
        return transport.allows(url);
    }

    public Tokens exchange(String tokenUrl, String clientId, String clientSecret, String code, String redirectUri, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", verifier);
        form.put("client_id", clientId);
        return tokens(post(tokenUrl, clientId, clientSecret, form), null);
    }

    public Tokens refresh(String tokenUrl, String clientId, String clientSecret, String refreshToken, String scopes) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        if (scopes != null && !scopes.isBlank()) form.put("scope", scopes);
        return tokens(post(tokenUrl, clientId, clientSecret, form), refreshToken);
    }

    /** Best effort (RFC 7009); never throws. */
    public void revoke(String revokeUrl, String clientId, String clientSecret, String token, String hint) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("token", token);
            form.put("token_type_hint", hint);
            form.put("client_id", clientId);
            post(revokeUrl, clientId, clientSecret, form);
        } catch (RuntimeException ignored) {
            // The local tokens are already deleted; a failed revocation changes nothing for Keplura.
        }
    }

    private JsonNode post(String url, String clientId, String clientSecret, Map<String, String> form) {
        URI uri;
        try {
            // Plain HTTP never sees a code, verifier, secret or token: refused before anything is sent.
            if (!transport.allows(url)) throw new TokenException(false);
            uri = SsrfGuard.parseShape(url);
        } catch (AdminException | IllegalArgumentException e) {
            throw new TokenException(false);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (clientSecret != null && !clientSecret.isEmpty()) {
            String pair = enc(clientId) + ":" + enc(clientSecret);
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        try {
            PinnedHttp.Response r = http.send("POST", uri, headers, body.getBytes(StandardCharsets.UTF_8),
                    h -> new PinnedHttp.Limit(MAX_BYTES, false), TIMEOUT, guard::allowsAdminRoute);
            if (r.status() / 100 != 2) throw new TokenException(r.status() / 100 == 4);
            if (r.body().length == 0) return JsonMapper.builder().build().createObjectNode();
            return JsonMapper.builder().build().readTree(r.body());
        } catch (TokenException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenException(false);
        }
    }

    private static Tokens tokens(JsonNode json, String previousRefresh) {
        String access = json.path("access_token").isString() ? json.path("access_token").asString() : "";
        String type = json.path("token_type").isString() ? json.path("token_type").asString().toLowerCase(Locale.ROOT) : "bearer";
        if (access.isEmpty() || access.length() > MAX_TOKEN_CHARS || !type.equals("bearer") || !printable(access)) {
            throw new TokenException(true);
        }
        String refresh = json.path("refresh_token").isString() ? json.path("refresh_token").asString() : previousRefresh;
        if (refresh != null && (refresh.isEmpty() || refresh.length() > MAX_TOKEN_CHARS || !printable(refresh))) refresh = null;
        Long expiresIn = json.path("expires_in").canConvertToLong() ? json.path("expires_in").asLong() : null;
        if (expiresIn != null && expiresIn <= 0) expiresIn = 0L;
        return new Tokens(access, refresh, expiresIn);
    }

    /** Header-safe token text: visible ASCII only (no CR/LF injection into Authorization headers). */
    private static boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7e) return false;
        }
        return true;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
