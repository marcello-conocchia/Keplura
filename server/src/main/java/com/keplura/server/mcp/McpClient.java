package com.keplura.server.mcp;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimaler MCP-Client (Streamable HTTP, JSON-RPC 2.0): initialize → notifications/initialized →
 * tools/list bzw. tools/call. Antworten als JSON oder SSE ({@code data:}-Zeilen). Keine Redirects,
 * Antwort-Obergrenze 2 MB, Zeitlimit 20 s. Das Token geht nur als Bearer-Header an die eingetragene URL.
 */
@Component
public class McpClient {

    public static final String PROTOCOL = "2025-06-18";
    static final int MAX_BYTES = 2_000_000;
    /** TU-06d: a tools/call response may carry up to four base64 files of 5 MB each (10 MB per question). */
    static final int MAX_CALL_BYTES = 16_000_000;

    public record Tool(String name, String description, JsonNode inputSchema) {
    }

    public static class McpException extends RuntimeException {
        public McpException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final com.keplura.server.admin.SsrfGuard guard;

    public McpClient(com.keplura.server.admin.SsrfGuard guard) { this.guard = guard; }

    @jakarta.annotation.PreDestroy
    public void close() { http.close(); }

    /** Eine Sitzung: initialisieren, dann {@code action} ausführen. */
    private JsonNode session(String url, String token, String method, ObjectNode params) {
        ObjectNode init = mapper.createObjectNode();
        init.put("protocolVersion", PROTOCOL);
        init.putObject("capabilities");
        init.putObject("clientInfo").put("name", "Keplura").put("version", "1");
        Exchange first = post(url, token, null, rpc(1, "initialize", init));
        try {
            if (first.body.has("error") || !PROTOCOL.equals(first.body.path("result").path("protocolVersion").asString(""))) {
                throw new McpException("Die MCP-Initialisierung wurde abgelehnt oder die Protokollversion wird nicht unterstützt.");
            }
            post(url, token, first.sessionId, notification("notifications/initialized"));
            Exchange r = post(url, token, first.sessionId, rpc(2, method, params), "tools/call".equals(method) ? MAX_CALL_BYTES : MAX_BYTES);
            if (r.body.has("error")) throw new McpException("Der MCP-Server hat die Anfrage abgelehnt.");
            return r.body.path("result");
        } finally {
            terminate(url, token, first.sessionId);
        }
    }

    public List<Tool> listTools(String url, String token) {
        JsonNode result = session(url, token, "tools/list", mapper.createObjectNode());
        List<Tool> out = new ArrayList<>();
        for (JsonNode t : result.path("tools")) {
            String name = t.path("name").asString("");
            if (name.isEmpty() || name.length() > 128 || (token != null && !token.isEmpty() && name.contains(token))) continue;
            String desc = safe(t.path("description").asString(""), token);
            // Keep whatever the server sent (also invalid shapes like false/[]/"x"): the central
            // validator must see it to block the tool. Only a missing key means "no schema".
            JsonNode schema = t.has("inputSchema") ? t.get("inputSchema") : null;
            if (schema != null && token != null && !token.isEmpty()) {
                String escaped = mapper.writeValueAsString(token);
                schema = mapper.readTree(mapper.writeValueAsString(schema)
                        .replace(escaped.substring(1, escaped.length() - 1), "[Zugangsdaten ausgeblendet]"));
            }
            out.add(new Tool(name, desc.length() > 1000 ? desc.substring(0, 1000) : desc, schema));
            if (out.size() >= 128) break;
        }
        return out;
    }

    /** Für Stufe 2 (Chat): Werkzeug aufrufen, Text-Inhalte zusammenfügen. */
    public String callTool(String url, String token, String name, JsonNode arguments) {
        return callToolWithContent(url, token, name, arguments).text();
    }

    /** Text plus TU-06d artifacts ({@code image} and embedded {@code resource} blobs). */
    public record CallResult(String text, List<com.keplura.server.tool.ToolAdapter.Artifact> artifacts) { }

    public CallResult callToolWithContent(String url, String token, String name, JsonNode arguments) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", name);
        p.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        JsonNode result = session(url, token, "tools/call", p);
        if (result.path("isError").asBoolean(false)) throw new McpException("Das Werkzeug konnte die Anfrage nicht ausführen.");
        StringBuilder sb = new StringBuilder();
        List<com.keplura.server.tool.ToolAdapter.Artifact> files = new ArrayList<>();
        for (JsonNode c : result.path("content")) {
            String type = c.path("type").asString("");
            if ("text".equals(type)) sb.append(c.path("text").asString("")).append('\n');
            else if ("image".equals(type) || "audio".equals(type)) {
                files.add(artifact(type, c.path("mimeType").asString(""), c.path("data")));
            } else if ("resource".equals(type)) {
                JsonNode r = c.path("resource");
                if (r.path("blob").isString()) files.add(artifact(r.path("uri").asString(""), r.path("mimeType").asString(""), r.path("blob")));
                else if (r.path("text").isString()) sb.append(r.path("text").asString("")).append('\n');
            }
            if (files.size() > 64) break;
        }
        return new CallResult(safe(sb.toString().strip(), token), List.copyOf(files));
    }

    private static com.keplura.server.tool.ToolAdapter.Artifact artifact(String name, String mimeType, JsonNode data) {
        String encoded = data.isString() ? data.asString() : "";
        if ((long) encoded.length() > (com.keplura.server.tool.ToolArtifactStore.MAX_BYTES / 3 + 2) * 4) {
            return new com.keplura.server.tool.ToolAdapter.Artifact(name, mimeType, null, "Die Datei ist größer als 5 MB.");
        }
        try {
            return new com.keplura.server.tool.ToolAdapter.Artifact(name, mimeType, java.util.Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException e) {
            return new com.keplura.server.tool.ToolAdapter.Artifact(name, mimeType, null, "Die Datei ist nicht gültig kodiert.");
        }
    }

    private static String safe(String text, String token) {
        return token == null || token.isEmpty() ? text : text.replace(token, "[Zugangsdaten ausgeblendet]");
    }

    private record Exchange(String sessionId, JsonNode body) {
    }

    private Exchange post(String url, String token, String sessionId, ObjectNode payload) {
        return post(url, token, sessionId, payload, MAX_BYTES);
    }

    private Exchange post(String url, String token, String sessionId, ObjectNode payload, int maxBytes) {
        try {
            guard.validateForDial(url);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
            if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
            if (!"initialize".equals(payload.path("method").asString(""))) b.header("MCP-Protocol-Version", PROTOCOL);
            HttpResponse<java.io.InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            String sid = r.headers().firstValue("Mcp-Session-Id").orElse(sessionId);
            try (var input = new com.keplura.server.http.BoundedHttpBody(r.body(), maxBytes, Duration.ofSeconds(20))) {
                if ((r.statusCode() == 202 || r.statusCode() == 204) && !payload.has("id")) {
                    return new Exchange(sid, mapper.createObjectNode());
                }
                if (r.statusCode() == 401 || r.statusCode() == 403) throw new McpException("Zugriff abgelehnt. Bitte das Token prüfen.");
                if (r.statusCode() / 100 != 2) throw new McpException("Der MCP-Server hat die Anfrage abgelehnt (HTTP " + r.statusCode() + ").");
                String ct = r.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
                JsonNode response;
                if (ct.startsWith("text/event-stream")) response = readSse(input, payload.path("id"));
                else if (ct.startsWith("application/json")) response = mapper.readTree(input.readAllBytes());
                else throw new McpException("Der MCP-Server hat ein ungültiges Antwortformat gesendet.");
                if (!matches(response, payload.path("id"))) throw new McpException("Die MCP-Antwort passt nicht zur Anfrage.");
                return new Exchange(sid, response);
            }
        } catch (com.keplura.server.admin.AdminException e) {
            throw new McpException("Dieses Verbindungsziel ist nicht freigegeben. Bitte die Server-Freigabeliste prüfen.");
        } catch (McpException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Die Anfrage wurde unterbrochen.");
        } catch (IllegalArgumentException e) {
            throw new McpException("Die Serveradresse ist ungültig.");
        } catch (Exception e) {
            throw new McpException("Der MCP-Server ist nicht erreichbar oder hat keine gültige, begrenzte Antwort gesendet.");
        }
    }

    private boolean matches(JsonNode response, JsonNode id) {
        return response != null && response.isObject() && "2.0".equals(response.path("jsonrpc").asString(""))
                && response.path("id").equals(id) && (response.has("result") || response.has("error"));
    }

    private JsonNode readSse(java.io.InputStream input, JsonNode id) throws java.io.IOException {
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder event = new StringBuilder();
        for (String line; (line = reader.readLine()) != null;) {
            if (line.isEmpty() && !event.isEmpty()) {
                JsonNode message = mapper.readTree(event.toString());
                event.setLength(0);
                if (matches(message, id)) return message;
            } else if (line.startsWith("data:")) {
                event.append(line.substring(5).stripLeading()).append('\n');
            }
        }
        if (!event.isEmpty()) {
            JsonNode message = mapper.readTree(event.toString());
            if (matches(message, id)) return message;
        }
        throw new McpException("Der MCP-Server hat keine passende Antwort gesendet.");
    }

    private void terminate(String url, String token, String sessionId) {
        if (sessionId == null) return;
        try {
            guard.validateForDial(url);
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                    .header("Mcp-Session-Id", sessionId).header("MCP-Protocol-Version", PROTOCOL).DELETE();
            if (token != null && !token.isEmpty()) request.header("Authorization", "Bearer " + token);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Best-effort cleanup, never retry a tool call or expose server text.
        }
    }

    private ObjectNode rpc(int id, String method, ObjectNode params) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0").put("id", id).put("method", method);
        n.set("params", params);
        return n;
    }

    private ObjectNode notification(String method) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0").put("method", method);
        return n;
    }
}
