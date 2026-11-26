package com.keplura.server.openapi;

import com.keplura.server.admin.AdminException;
import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.http.BoundedHttpBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;

/**
 * TU-06a HTTP transport for OpenAPI tool servers. Every dial goes through {@link SsrfGuard#validateForDial}
 * (admin targets, operator CIDR exceptions apply), never follows redirects, ignores proxies from the
 * environment and reads bounded bodies with a hard deadline. Error texts are fixed: no address, header or body.
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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();
    private final SsrfGuard guard;

    public OpenApiClient(SsrfGuard guard) { this.guard = guard; }

    @jakarta.annotation.PreDestroy
    public void close() { http.close(); }

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
        HttpRequest request;
        try {
            guard.validateForDial(url);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(CALL_TIMEOUT).header("Accept", accept);
            if (bearer != null && !bearer.isEmpty()) b.header("Authorization", "Bearer " + bearer);
            if (jsonBody != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            request = b.build();
        } catch (AdminException e) {
            throw new OpenApiException("Dieses Verbindungsziel ist nicht freigegeben. Bitte die Server-Freigabeliste prüfen.", false);
        } catch (IllegalArgumentException e) {
            throw new OpenApiException("Die Serveradresse ist ungültig.", false);
        }
        try {
            HttpResponse<java.io.InputStream> r = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String contentType = r.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).strip();
            int cap = maxBytes;
            boolean binary = binaryAllowed && !isText(contentType);
            if (binaryAllowed && !binary) cap = Math.min(maxBytes, MAX_TEXT_BYTES);
            // A binary body may reach the cap by one byte: then it is dropped and noted instead of failing the call.
            try (var input = new BoundedHttpBody(r.body(), binary ? cap + 1 : cap, CALL_TIMEOUT)) {
                if (r.statusCode() / 100 != 2) return new Response(r.statusCode(), contentType, new byte[0]);
                byte[] body = binary ? input.readNBytes(cap + 1) : input.readAllBytes();
                if (binary && body.length > cap) return new Response(r.statusCode(), contentType, new byte[0], true);
                return new Response(r.statusCode(), contentType, body);
            }
        } catch (HttpTimeoutException e) {
            throw new OpenApiException("Der Werkzeugserver hat nicht rechtzeitig geantwortet.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenApiException("Die Anfrage wurde unterbrochen.", true);
        } catch (IOException e) {
            throw new OpenApiException("Der Werkzeugserver ist nicht erreichbar oder hat keine gültige, begrenzte Antwort gesendet.", true);
        }
    }

    static boolean isText(String contentType) {
        return contentType.startsWith("application/json") || contentType.startsWith("text/plain")
                || contentType.endsWith("+json") || contentType.contains("+json;");
    }
}
