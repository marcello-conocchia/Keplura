package com.keplura.server.mcp;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.tool.ToolAdapter;
import com.keplura.server.tool.ToolDefinition;
import com.keplura.server.tool.ToolRisk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP als {@link ToolAdapter} (TU-01). Liefert die im Arbeitsbereich angebundenen Werkzeuge
 * aktiver Server samt Admin-Risikoklasse und Freigabe; führt bereits zentral geprüfte Aufrufe über
 * {@link McpClient} aus. Entscheidet selbst nichts über Ausführung — das macht
 * {@link com.keplura.server.tool.ToolExecutionService}. MCP-Annotationen (z. B. {@code readOnlyHint})
 * setzen nie eine Risikoklasse.
 */
@Component
public class McpToolAdapter implements ToolAdapter {

    public static final String KIND = "mcp";

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final McpClient client;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public McpToolAdapter(JdbcTemplate jdbc, SecretCipher cipher, McpClient client) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public List<ToolDefinition> definitions(Long spaceId) {
        List<ToolDefinition> out = new ArrayList<>();
        if (spaceId == null) return out;
        jdbc.query("""
                select s.id, s.name, s.url, s.token_enc, s.tools_json, s.enabled_tools, s.tool_risks from mcp_server s
                where s.enabled = true and (s.all_spaces = true
                   or exists (select 1 from mcp_server_space x where x.server_id = s.id and x.space_id = ?))
                order by s.id""", rs -> {
            String json = rs.getString(5);
            if (json == null || json.isBlank()) return;
            long serverId = rs.getLong(1);
            Set<String> approved = approvals(rs.getString(6));
            Map<String, ToolRisk> risks = risks(rs.getString(7));
            String fingerprint = fingerprint(serverId, rs.getString(3), rs.getString(4));
            for (JsonNode t : mapper.readTree(json)) {
                String name = t.path("name").asString("");
                if (name.isEmpty()) continue;
                JsonNode schema = schemaOf(t);
                out.add(new ToolDefinition(KIND + ":" + serverId + ":" + name, KIND, functionName(serverId, name), name,
                        serverId, rs.getString(2), t.path("description").asString(""), schema, risks.get(name),
                        approved.contains(name), fingerprint));
            }
        }, spaceId);
        return out;
    }

    @Override
    public String invoke(ToolDefinition tool, JsonNode arguments) {
        var rows = jdbc.queryForList("select url, token_enc from mcp_server where id = ? and enabled = true", tool.sourceId());
        if (rows.isEmpty()) throw new ToolNotStartedException("Der MCP-Server ist nicht mehr aktiv.");
        Object url = rows.getFirst().get("url");
        Object enc = rows.getFirst().get("token_enc");
        String urlText = url == null ? null : url.toString();
        String encText = enc == null ? null : enc.toString();
        if (!fingerprint(tool.sourceId(), urlText, encText).equals(tool.fingerprint())) {
            throw new ToolNotStartedException("Das Verbindungsziel hat sich geändert. Das Werkzeug wurde nicht aufgerufen.");
        }
        String token;
        try {
            token = encText == null ? "" : cipher.decrypt(encText);
        } catch (RuntimeException e) {
            throw new ToolNotStartedException("Der Zugang zum MCP-Server ist nicht lesbar. Das Werkzeug wurde nicht aufgerufen.");
        }
        try {
            return client.callTool(urlText, token, tool.toolName(), arguments);
        } catch (McpClient.McpException e) {
            throw new ToolInvocationException(e.getMessage());
        } catch (RuntimeException e) {
            throw new ToolInvocationException("Der Werkzeug-Aufruf ist fehlgeschlagen.");
        }
    }

    /**
     * Gespeichertes Eingabeschema unverändert (auch ungültige Formen wie {@code false}, Liste, Text
     * oder JSON-{@code null}); nur ein fehlender Schlüssel ergibt {@code null} = „kein Schema“.
     */
    static JsonNode schemaOf(JsonNode storedTool) {
        return storedTool.has("inputSchema") ? storedTool.get("inputSchema") : null;
    }

    /** OpenAI-Funktionsnamen: ^[a-zA-Z0-9_-]{1,64}$, eindeutig je Server. */
    static String functionName(Long serverId, String tool) {
        String clean = tool.replaceAll("[^a-zA-Z0-9_-]", "_");
        String prefix = "s" + serverId + "__";
        if (clean.equals(tool) && prefix.length() + clean.length() <= 64) return prefix + clean;
        String suffix = sha256(tool).substring(0, 12);
        int room = 64 - prefix.length() - suffix.length() - 1;
        return prefix + clean.substring(0, Math.min(room, clean.length())) + "_" + suffix;
    }

    /** Server, Adresse und verschlüsselter Zugang; ändert sich eines davon, ist es ein neues Ziel. */
    static String fingerprint(Long serverId, String url, String tokenEnc) {
        return sha256(serverId + "\n" + url + "\n" + (tokenEnc == null ? "" : tokenEnc));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    static Set<String> approvals(String lines) {
        return lines == null || lines.isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(lines.split("\n")));
    }

    /** {@code tool_risks}: JSON-Objekt Werkzeugname → READ/WRITE/CRITICAL. Unlesbar = alles unklassifiziert. */
    static Map<String, ToolRisk> risks(String json) {
        Map<String, ToolRisk> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode node = JsonMapper.builder().build().readTree(json);
            if (!node.isObject()) return out;
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                ToolRisk r = e.getValue().isString() ? ToolRisk.parse(e.getValue().asString()) : null;
                if (r != null) out.put(e.getKey(), r);
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    static String risksJson(Map<String, ToolRisk> risks) {
        Map<String, String> plain = new LinkedHashMap<>();
        risks.forEach((k, v) -> plain.put(k, v.name()));
        return plain.isEmpty() ? null : JsonMapper.builder().build().writeValueAsString(plain);
    }
}
