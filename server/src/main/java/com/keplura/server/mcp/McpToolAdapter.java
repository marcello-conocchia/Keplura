package com.keplura.server.mcp;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.oauth.OAuthConnectionService;
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
    private final OAuthConnectionService oauth;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public McpToolAdapter(JdbcTemplate jdbc, SecretCipher cipher, McpClient client) {
        this(jdbc, cipher, client, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public McpToolAdapter(JdbcTemplate jdbc, SecretCipher cipher, McpClient client, OAuthConnectionService oauth) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
        this.oauth = oauth;
    }

    @Override
    public String kind() {
        return KIND;
    }

    private record Server(long id, String name, String url, String tokenEnc, String toolsJson, String enabledTools,
                          String toolRisks, boolean internal, long revision, boolean personal, boolean bridge) { }

    private List<Server> servers(Long spaceId) {
        return jdbc.query("""
                select s.id, s.name, s.url, s.token_enc, s.tools_json, s.enabled_tools, s.tool_risks,
                       coalesce(b.internal,s.bridge),coalesce(b.revision,0),
                       s.auth_mode <> 'shared', s.bridge from mcp_server s
                left join mcp_org_boundary b on b.server_id=s.id and b.org_id=(select org_id from space where id=?)
                where s.enabled = true and (s.all_spaces = true
                   or exists (select 1 from mcp_server_space x where x.server_id = s.id and x.space_id = ?))
                order by s.id""", (rs, n) -> new Server(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getLong(9), rs.getBoolean(10), rs.getBoolean(11)),
                spaceId, spaceId);
    }

    /** Without a person (no context) personal servers offer nothing: fail closed. */
    @Override
    public List<ToolDefinition> definitions(Long spaceId) {
        return definitions(spaceId, null);
    }

    @Override
    public List<ToolDefinition> definitionsFor(com.keplura.server.tool.ToolContext context) {
        return definitions(context.spaceId(), context);
    }

    private List<ToolDefinition> definitions(Long spaceId, com.keplura.server.tool.ToolContext context) {
        List<ToolDefinition> out = new ArrayList<>();
        if (spaceId == null) return out;
        for (Server s : servers(spaceId)) {
            if (s.toolsJson() == null || s.toolsJson().isBlank()) continue;
            String access = "";
            if (s.personal()) {
                // TU-06b: tools of a personal server exist for this person only with an own, valid connection.
                var connection = context == null || oauth == null ? null
                        : oauth.connection(context.userId(), context.orgId(), KIND, s.id()).orElse(null);
                if (connection == null) continue;
                access = OAuthConnectionService.fingerprintPart(connection);
            }
            Set<String> approved = approvals(s.enabledTools());
            Map<String, ToolRisk> risks = risks(s.toolRisks());
            String fingerprint = fingerprint(s.id(), s.url(), s.tokenEnc()) + (s.bridge() ? ":bridge" : "") + access + ":boundary:" + s.revision();
            for (JsonNode t : mapper.readTree(s.toolsJson())) {
                String name = t.path("name").asString("");
                if (name.isEmpty()) continue;
                JsonNode schema = schemaOf(t);
                out.add(new ToolDefinition(KIND + ":" + s.id() + ":" + name, KIND, functionName(s.id(), name), name,
                        s.id(), s.name(), t.path("description").asString(""), schema, risks.get(name),
                        approved.contains(name), fingerprint, s.internal()));
            }
        }
        return out;
    }

    @Override
    public List<ConnectionHint> missingConnections(com.keplura.server.tool.ToolContext context) {
        List<ConnectionHint> out = new ArrayList<>();
        if (context == null || context.spaceId() == null) return out;
        for (Server s : servers(context.spaceId())) {
            if (!s.personal() || s.toolsJson() == null) continue;
            Map<String, ToolRisk> risks = risks(s.toolRisks());
            boolean usable = approvals(s.enabledTools()).stream().anyMatch(risks::containsKey);
            if (usable && (oauth == null || oauth.connection(context.userId(), context.orgId(), KIND, s.id()).isEmpty())) {
                out.add(new ConnectionHint(KIND, s.id(), s.name()));
            }
        }
        return out;
    }

    @Override
    public String invoke(ToolDefinition tool, JsonNode arguments) {
        throw new ToolNotStartedException("Der Ausführungskontext fehlt. Das Werkzeug wurde nicht aufgerufen.");
    }

    @Override
    public String invoke(com.keplura.server.tool.ToolContext context, ToolDefinition tool, JsonNode arguments) {
        return invokeWithArtifacts(context, tool, arguments, null).text();
    }

    /** TU-06d: images and embedded resource blobs become artifacts of the card; the model gets placeholders only. */
    @Override
    public Result invokeWithArtifacts(com.keplura.server.tool.ToolContext context, ToolDefinition tool, JsonNode arguments,
                                      Long promptMessageId) {
        var rows = jdbc.queryForList("select url, token_enc, auth_mode, bridge from mcp_server where id = ? and enabled = true", tool.sourceId());
        if (rows.isEmpty()) throw new ToolNotStartedException("Der MCP-Server ist nicht mehr aktiv.");
        Object url = rows.getFirst().get("url");
        Object enc = rows.getFirst().get("token_enc");
        String urlText = url == null ? null : url.toString();
        String encText = enc == null ? null : enc.toString();
        var revisions=jdbc.queryForList("select revision from mcp_org_boundary where server_id=? and org_id=?",Long.class,tool.sourceId(),context.orgId());
        long revision=revisions.isEmpty()?0:revisions.getFirst();
        // Stored auth mode, never inferred from a client row: personal without a client fails closed (no connection).
        boolean personal = !"shared".equals(String.valueOf(rows.getFirst().get("auth_mode")));
        boolean bridge = Boolean.TRUE.equals(rows.getFirst().get("bridge"));
        if (bridge && personal) throw new ToolNotStartedException(OAuthConnectionService.MISSING);
        OAuthConnectionService.Connection connection = null;
        if (personal) {
            connection = oauth == null ? null
                    : oauth.connection(context.userId(), context.orgId(), KIND, tool.sourceId()).orElse(null);
            if (connection == null) throw new ToolNotStartedException(OAuthConnectionService.MISSING);
        }
        String access = connection == null ? "" : OAuthConnectionService.fingerprintPart(connection);
        if (!(fingerprint(tool.sourceId(), urlText, encText)+(bridge ? ":bridge" : "")+access+":boundary:"+revision).equals(tool.fingerprint())) {
            throw new ToolNotStartedException("Das Verbindungsziel hat sich geändert. Das Werkzeug wurde nicht aufgerufen.");
        }
        String token;
        if (connection != null) {
            // The admin key of a personal server is only for loading the tool list, never for a person's call.
            token = oauth.accessToken(context.userId(), context.orgId(), KIND, tool.sourceId(), connection.id(), urlText);
        } else {
            try {
                token = encText == null ? "" : cipher.decrypt(encText);
            } catch (RuntimeException e) {
                throw new ToolNotStartedException("Der Zugang zum MCP-Server ist nicht lesbar. Das Werkzeug wurde nicht aufgerufen.");
            }
        }
        try {
            var result = bridge ? client.callToolWithContent(urlText, token, tool.toolName(), arguments, true)
                    : client.callToolWithContent(urlText, token, tool.toolName(), arguments);
            if (result == null) throw new ToolInvocationException("Der Werkzeug-Aufruf ist fehlgeschlagen.");
            return new Result(result.text(), result.artifacts());
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
        return functionName("s", serverId, tool);
    }

    /** Shared by all admin-server adapters ({@code s} = MCP, {@code o} = OpenAPI). */
    public static String functionName(String kindPrefix, Long serverId, String tool) {
        String clean = tool.replaceAll("[^a-zA-Z0-9_-]", "_");
        String prefix = kindPrefix + serverId + "__";
        if (clean.equals(tool) && prefix.length() + clean.length() <= 64) return prefix + clean;
        String suffix = sha256(tool).substring(0, 12);
        int room = 64 - prefix.length() - suffix.length() - 1;
        return prefix + clean.substring(0, Math.min(room, clean.length())) + "_" + suffix;
    }

    /** Server, Adresse und verschlüsselter Zugang; ändert sich eines davon, ist es ein neues Ziel. */
    static String fingerprint(Long serverId, String url, String tokenEnc) {
        return sha256(serverId + "\n" + url + "\n" + (tokenEnc == null ? "" : tokenEnc));
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    public static Set<String> approvals(String lines) {
        return lines == null || lines.isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(lines.split("\n")));
    }

    /** {@code tool_risks}: JSON-Objekt Werkzeugname → READ/WRITE/CRITICAL. Unlesbar = alles unklassifiziert. */
    public static Map<String, ToolRisk> risks(String json) {
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

    public static String risksJson(Map<String, ToolRisk> risks) {
        Map<String, String> plain = new LinkedHashMap<>();
        risks.forEach((k, v) -> plain.put(k, v.name()));
        return plain.isEmpty() ? null : JsonMapper.builder().build().writeValueAsString(plain);
    }
}
