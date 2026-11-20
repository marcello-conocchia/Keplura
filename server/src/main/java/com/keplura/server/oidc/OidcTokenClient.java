package com.keplura.server.oidc;

import com.keplura.server.admin.AdminException;
import com.keplura.server.admin.SsrfGuard;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Authorization-code exchange at the IdP token endpoint (docs/adr/0009 §1,
 * the ONLY grant implemented). Served to us by {@link OidcLoginService} with
 * the SSRF-guarded token URL from discovery; the request body is
 * form-urlencoded with grant_type=authorization_code + code_verifier (PKCE).
 *
 * <p>Client authentication: the secret (when the row has one) travels ONLY
 * as the HTTP Basic header over the same URL the token is fetched from —
 * never in a log line, never in a response. A provider row without a secret
 * is treated as a public client: client_id in the form body (RFC 6749 §2.1),
 * which is also exactly how a PKCE public client is specced to authenticate
 * (OAuth 2.0 for Native Apps / RFC 8252).
 *
 * <p>Failure vocabulary: any non-2xx, any missing id_token, any unreadable
 * body → {@link OidcException#upstream()} (the IdP spoke but refused) or
 * {@link OidcException#refused()} (no token to validate). No upstream JSON
 * ever reaches a caller or a log.
 */
@Service
public class OidcTokenClient {

    private static final Logger log = LoggerFactory.getLogger(OidcTokenClient.class);
    static final int MAX_BYTES = 64 * 1024;

    /** Token response: id_token is required, access_token optional (at_hash). */
    public record TokenResponse(String idToken, String accessToken) {
    }

    private final HttpClient client;
    private final SsrfGuard ssrfGuard;

    public OidcTokenClient(@Qualifier("oidcHttpClient") HttpClient client,
                           SsrfGuard ssrfGuard) {
        this.client = client;
        this.ssrfGuard = ssrfGuard;
    }

    public TokenResponse exchange(String tokenEndpointUrl, String clientId, String clientSecretOrNull,
                                  String code, String redirectUri, String pkceVerifier,
                                  int timeoutSeconds) {
        String body = "grant_type=authorization_code"
                + "&code=" + form(code)
                + "&redirect_uri=" + form(redirectUri)
                + "&code_verifier=" + form(pkceVerifier)
                + "&client_id=" + form(clientId);
        HttpRequest.Builder builder;
        try {
            URI uri = SsrfGuard.parseShape(tokenEndpointUrl);
            ssrfGuard.validateForDial(tokenEndpointUrl);
            builder = HttpRequest.newBuilder(uri);
        } catch (AdminException e) {
            throw OidcException.upstream();
        }
        if (clientSecretOrNull != null && !clientSecretOrNull.isBlank()) {
            // Basic auth per OIDC Core §9.1 (client_id:secret, base64url-safe
            // via the standard basic charset); URL-encode both per RFC 6749 §3.2.1
            String pair = form(clientId) + ":" + form(clientSecretOrNull);
            builder.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
            // client_id stays in the form body too (harmless, some IdPs demand it)
        }
        builder.timeout(Duration.ofSeconds(Math.max(1, Math.min(300, timeoutSeconds))))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<InputStream> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            byte[] raw = drainCapped(response.body());
            if (status < 200 || status >= 300) {
                log.warn("oidc token endpoint refused (http {})", status);
                throw OidcException.upstream();
            }
            JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(raw);
            String idToken = json.path("id_token").asString(null);
            String accessToken = json.path("access_token").asString(null);
            if (idToken == null || idToken.isBlank() || idToken.length() > 8192) {
                throw OidcException.refused();
            }
            return new TokenResponse(idToken, accessToken);
        } catch (OidcException e) {
            throw e;
        } catch (IOException e) {
            log.warn("oidc token dial: IO failure ({})", e.getClass().getSimpleName());
            throw OidcException.upstream();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw OidcException.upstream();
        }
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] drainCapped(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("token response exceeds cap");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
