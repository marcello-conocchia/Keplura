package com.keplura.server.openapi;

import com.keplura.server.admin.AdminException;
import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.http.PinnedHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * TU-06a HTTP transport for OpenAPI tool servers. Every dial goes through {@link PinnedHttp}: the host is resolved
 * once, every answer must pass {@link SsrfGuard#allowsAdminRoute} (admin targets, operator CIDR exceptions apply)
 * and the socket connects to exactly that checked address. No redirects, no proxy from the environment, bounded
 * bodies with a hard deadline. Error texts are fixed: no address, header or body.
 */
@Component
public class OpenApiClient {

    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);
    /** Text results (JSON, plain text). */
    public static final int MAX_TEXT_BYTES = 2_000_000;
    /** Binary results (TU-06d artifacts); never more than one artifact's limit. */
    public static final int MAX_BINARY_BYTES = 5_000_000;

    /** {@code tooLarge}: a binary body exceeded {@link #MAX_BINARY_BYTES}; it was not kept (TU-06d notes it). */
    public record Response(int status, String contentType, byte[] body, boolean tooLarge) {
        public Response(int status, String contentType, byte[] body) { this(status, contentType, body, false); }
    }

    public static class OpenApiException extends RuntimeException {
        private final boolean started;
        public OpenApiException(String message, boolean started) {
            super(message);
            this.started = started;
        }
        /** {@code false}: nachweislich kein Request abgesetzt (Ziel abgelehnt, Adresse ungültig). */
        public boolean started() { return started; }
    }

    private final PinnedHttp http;
    private final SsrfGuard guard;

    @Autowired
    public OpenApiClient(SsrfGuard guard) { this(guard, new PinnedHttp()); }

    public OpenApiClient(SsrfGuard guard, PinnedHttp http) {
        this.guard = guard;
        this.http = http;
    }

    /** Loads the OpenAPI document (JSON, at most 1 MB). */
    public byte[] fetchDocument(String url, String bearer) {
        Response r = send("GET", url, bearer, null, "application/json", OpenApiDocument.MAX_DOCUMENT_BYTES, false);
        if (r.status() / 100 == 3) throw new OpenApiException("Das Dokument wird weitergeleitet. Weiterleitungen werden nicht verfolgt.", true);
        if (r.status() == 401 || r.status() == 403) throw new OpenApiException("Zugriff auf das OpenAPI-Dokument abgelehnt.", true);
        if (r.status() / 100 != 2) throw new OpenApiException("Das OpenAPI-Dokument konnte nicht geladen werden (HTTP " + r.status() + ").", true);
        return r.body();
    }

    /**
     * Exactly one request. {@code allowBinary}: the body may be up to {@link #MAX_BINARY_BYTES} when the response
     * declares a non-text type; text stays at {@link #MAX_TEXT_BYTES}.
     */
    public Response call(String method, String url, String bearer, String jsonBody, boolean allowBinary) {
        return send(method, url, bearer, jsonBody, "application/json, text/plain" + (allowBinary ? ", */*;q=0.1" : ""),
                allowBinary ? MAX_BINARY_BYTES : MAX_TEXT_BYTES, allowBinary);
    }

    private Response send(String method, String url, String bearer, String jsonBody, String accept, int maxBytes,
                          boolean binaryAllowed) {
        URI uri;
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            uri = SsrfGuard.parseShape(url);
            headers.put("Accept", accept);
            if (bearer != null && !bearer.isEmpty()) headers.put("Authorization", "Bearer " + bearer);
            if (jsonBody != null) headers.put("Content-Type", "application/json");
        } catch (AdminException | IllegalArgumentException e) {
            throw new OpenApiException("Die Serveradresse ist ungültig.", false);
        }
        String[] type = {""};
        try {
            PinnedHttp.Response r = http.send(method, uri, headers,
                    jsonBody == null ? null : jsonBody.getBytes(StandardCharsets.UTF_8), h -> {
                        type[0] = h.getOrDefault("content-type", "").toLowerCase(Locale.ROOT).strip();
                        boolean binary = binaryAllowed && !isText(type[0]);
                        // A binary body above the cap is dropped and noted instead of failing the call.
                        return new PinnedHttp.Limit(binary ? maxBytes : Math.min(maxBytes, MAX_TEXT_BYTES), binary);
                    }, CALL_TIMEOUT, guard::allowsAdminRoute);
            String contentType = r.header("content-type").toLowerCase(Locale.ROOT).strip();
            if (r.status() / 100 != 2) return new Response(r.status(), contentType, new byte[0]);
            return new Response(r.status(), contentType, r.body(), r.truncated());
        } catch (PinnedHttp.Refused e) {
            throw new OpenApiException("Dieses Verbindungsziel ist nicht freigegeben. Bitte die Server-Freigabeliste prüfen.", false);
        } catch (PinnedHttp.Timeout e) {
            throw new OpenApiException("Der Werkzeugserver hat nicht rechtzeitig geantwortet.", true);
        } catch (IllegalArgumentException e) {
            throw new OpenApiException("Die Serveradresse ist ungültig.", false);
        } catch (IOException e) {
            throw new OpenApiException("Der Werkzeugserver ist nicht erreichbar oder hat keine gültige, begrenzte Antwort gesendet.", true);
        }
    }

    static boolean isText(String contentType) {
        return contentType.startsWith("application/json") || contentType.startsWith("text/plain")
                || contentType.endsWith("+json") || contentType.contains("+json;");
    }
}
