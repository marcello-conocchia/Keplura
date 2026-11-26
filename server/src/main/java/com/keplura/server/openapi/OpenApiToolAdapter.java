package com.keplura.server.openapi;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.mcp.McpToolAdapter;
import com.keplura.server.tool.ToolAdapter;
import com.keplura.server.tool.ToolContext;
import com.keplura.server.tool.ToolDefinition;
import com.keplura.server.tool.ToolRisk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TU-06a: OpenAPI operations as {@link ToolAdapter}. Definitions come only from the stored, admin-loaded snapshot;
 * risk classes and approvals only from the admin. A call goes to exactly the admin's fixed base URL plus the
 * snapshot's method and path; the document's {@code servers} list is never used. Decides nothing about execution
 * — {@link com.keplura.server.tool.ToolExecutionService} does.
 */
@Component
public class OpenApiToolAdapter implements ToolAdapter {

    public static final String KIND = "openapi";
    public static final String AUTH_NONE = "none";
    public static final String AUTH_BEARER = "bearer";
    /** TU-06b: each person uses an own OAuth connection; an admin token may only load the document. */
    public static final String AUTH_OAUTH = "oauth";
    public static final Set<String> AUTH_MODES = Set.of(AUTH_NONE, AUTH_BEARER, AUTH_OAUTH);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final OpenApiClient client;
    private final com.keplura.server.oauth.OAuthConnectionService oauth;

    public OpenApiToolAdapter(JdbcTemplate jdbc, SecretCipher cipher, OpenApiClient client) {
        this(jdbc, cipher, client, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OpenApiToolAdapter(JdbcTemplate jdbc, SecretCipher cipher, OpenApiClient client,
                              com.keplura.server.oauth.OAuthConnectionService oauth) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
        this.oauth = oauth;
    }

    @Override
    public String kind() { return KIND; }

    record Server(long id, String name, String specUrl, String baseUrl, String authMode, String tokenEnc,
                  String operationsJson, String enabledTools, String toolRisks, boolean internal, long revision,
                  boolean hasClient) {
        boolean personal() { return AUTH_OAUTH.equals(authMode()); }
    }

    private List<Server> servers(Long spaceId) {
        return jdbc.query("""
                select s.id, s.name, s.spec_url, s.base_url, s.auth_mode, s.token_enc, s.operations_json, s.enabled_tools,
                       s.tool_risks, coalesce(b.internal,false), coalesce(b.revision,0),
                       exists(select 1 from tool_oauth_client k where k.kind='openapi' and k.server_id=s.id) from openapi_server s
                left join openapi_org_boundary b on b.server_id=s.id and b.org_id=(select org_id from space where id=?)
                where s.enabled = true and (s.all_spaces = true
                   or exists (select 1 from openapi_server_space x where x.server_id = s.id and x.space_id = ?))
                order by s.id""", (rs, n) -> new Server(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10),
                rs.getLong(11), rs.getBoolean(12)), spaceId, spaceId);
    }

    /** Without a person (no context) personal servers offer nothing: fail closed. */
    @Override
    public List<ToolDefinition> definitions(Long spaceId) {
        return definitions(spaceId, null);
    }

    @Override
    public List<ToolDefinition> definitionsFor(ToolContext context) {
        return definitions(context.spaceId(), context);
    }

    private com.keplura.server.oauth.OAuthConnectionService.Connection connection(ToolContext context, Server s) {
        if (context == null || oauth == null || !s.hasClient()) return null;
        return oauth.connection(context.userId(), context.orgId(), KIND, s.id()).orElse(null);
    }

    private List<ToolDefinition> definitions(Long spaceId, ToolContext context) {
        List<ToolDefinition> out = new ArrayList<>();
        if (spaceId == null) return out;
        for (Server s : servers(spaceId)) {
            if (!AUTH_MODES.contains(s.authMode())) continue; // unknown access mode: nothing usable
            String access = "";
            if (s.personal()) {
                var connection = connection(context, s);
                if (connection == null) continue; // TU-06b: not offered without the person's own connection
                access = com.keplura.server.oauth.OAuthConnectionService.fingerprintPart(connection);
            }
            Set<String> approved = McpToolAdapter.approvals(s.enabledTools());
            Map<String, ToolRisk> risks = McpToolAdapter.risks(s.toolRisks());
            for (OpenApiDocument.Operation op : OpenApiDocument.fromJson(s.operationsJson())) {
                if (op.operationId() == null || op.problem() != null || op.inputSchema() == null) continue;
                out.add(new ToolDefinition(KIND + ":" + s.id() + ":" + op.operationId(), KIND,
                        McpToolAdapter.functionName("o", s.id(), op.operationId()), op.operationId(), s.id(), s.name(),
                        op.description(), op.inputSchema(), risks.get(op.operationId()), approved.contains(op.operationId()),
                        fingerprint(s, op, access), s.internal()));
            }
        }
        return out;
    }

    @Override
    public List<ConnectionHint> missingConnections(ToolContext context) {
        List<ConnectionHint> out = new ArrayList<>();
        if (context == null || context.spaceId() == null) return out;
        for (Server s : servers(context.spaceId())) {
            if (!s.personal() || !s.hasClient()) continue;
            Map<String, ToolRisk> risks = McpToolAdapter.risks(s.toolRisks());
            boolean usable = McpToolAdapter.approvals(s.enabledTools()).stream().anyMatch(risks::containsKey);
            if (usable && connection(context, s) == null) out.add(new ConnectionHint(KIND, s.id(), s.name()));
        }
        return out;
    }

    /** Server identity, base URL, document URL, access and the exact operation; boundary revision last. */
    static String fingerprint(Server s, OpenApiDocument.Operation op, String access) {
        String server = McpToolAdapter.sha256(s.id() + "\n" + s.baseUrl() + "\n" + s.specUrl() + "\n" + s.authMode()
                + "\n" + (s.tokenEnc() == null ? "" : s.tokenEnc()));
        return server + ":op:" + McpToolAdapter.sha256(OpenApiDocument.canonical(op)) + access + ":boundary:" + s.revision();
    }

    @Override
    public String invoke(ToolDefinition tool, JsonNode arguments) {
        throw new ToolNotStartedException("Der Ausführungskontext fehlt. Das Werkzeug wurde nicht aufgerufen.");
    }

    @Override
    public String invoke(ToolContext context, ToolDefinition tool, JsonNode arguments) {
        return invokeWithArtifacts(context, tool, arguments, null).text();
    }

    /** TU-06d: a binary response (image, PDF, CSV, ...) becomes an artifact of the card; the model gets a placeholder. */
    @Override
    public Result invokeWithArtifacts(ToolContext context, ToolDefinition tool, JsonNode arguments, Long promptMessageId) {
        Server server = servers(context.spaceId()).stream().filter(s -> s.id() == tool.sourceId()).findFirst().orElse(null);
        if (server == null) throw new ToolNotStartedException("Der Werkzeugserver ist nicht mehr aktiv.");
        OpenApiDocument.Operation op = OpenApiDocument.fromJson(server.operationsJson()).stream()
                .filter(o -> tool.toolName().equals(o.operationId()) && o.problem() == null).findFirst().orElse(null);
        var connection = server.personal() ? connection(context, server) : null;
        if (server.personal() && connection == null) {
            throw new ToolNotStartedException(com.keplura.server.oauth.OAuthConnectionService.MISSING);
        }
        String access = connection == null ? "" : com.keplura.server.oauth.OAuthConnectionService.fingerprintPart(connection);
        if (op == null || !fingerprint(server, op, access).equals(tool.fingerprint())) {
            throw new ToolNotStartedException("Das Verbindungsziel hat sich geändert. Das Werkzeug wurde nicht aufgerufen.");
        }
        String url = url(server.baseUrl(), op, arguments);
        String token = connection != null
                ? oauth.accessToken(context.userId(), context.orgId(), KIND, server.id(), connection.id())
                : bearer(server);
        String body = op.hasBody() && arguments != null && arguments.has("body") ? arguments.get("body").toString() : null;
        OpenApiClient.Response response;
        try {
            response = client.call(op.method(), url, token, body, true);
        } catch (OpenApiClient.OpenApiException e) {
            if (!e.started()) throw new ToolNotStartedException(e.getMessage() + " Das Werkzeug wurde nicht aufgerufen.");
            throw new ToolInvocationException(e.getMessage());
        }
        int status = response.status();
        if (status / 100 == 2 && (response.tooLarge() || response.body().length > 0) && !OpenApiClient.isText(response.contentType())) {
            var file = response.tooLarge()
                    ? new Artifact("antwort", response.contentType(), null, "Die Datei ist größer als 5 MB.")
                    : new Artifact("antwort", response.contentType(), response.body());
            return new Result("Der Werkzeugserver hat eine Datei geliefert.", List.of(file));
        }
        return new Result(result(response, token));
    }

    private String bearer(Server server) {
        if (AUTH_NONE.equals(server.authMode())) return "";
        if (AUTH_BEARER.equals(server.authMode())) {
            if (server.tokenEnc() == null) return "";
            try {
                return cipher.decrypt(server.tokenEnc());
            } catch (RuntimeException e) {
                throw new ToolNotStartedException("Der Zugang zum Werkzeugserver ist nicht lesbar. Das Werkzeug wurde nicht aufgerufen.");
            }
        }
        throw new ToolNotStartedException("Die Zugangsart des Werkzeugservers wird nicht unterstützt. Das Werkzeug wurde nicht aufgerufen.");
    }

    static String result(OpenApiClient.Response response, String token) {
        int status = response.status();
        if (status / 100 == 3) throw new ToolInvocationException("Der Werkzeugserver hat weitergeleitet. Weiterleitungen werden nicht verfolgt.");
        if (status == 401 || status == 403) throw new ToolInvocationException("Zugriff abgelehnt.");
        if (status / 100 != 2) throw new ToolInvocationException("Der Werkzeugserver hat die Anfrage abgelehnt (HTTP " + status + ").");
        if (response.body().length == 0) return "Der Aufruf war erfolgreich (keine Antwortdaten).";
        if (!OpenApiClient.isText(response.contentType())) {
            throw new ToolInvocationException("Der Werkzeugserver hat einen nicht unterstützten Antworttyp gesendet.");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
        } catch (CharacterCodingException e) {
            throw new ToolInvocationException("Der Werkzeugserver hat keinen gültigen Text gesendet.");
        }
        return token == null || token.isEmpty() ? text : text.replace(token, "[Zugangsdaten ausgeblendet]");
    }

    /**
     * Base URL + snapshot path. Path parameters are percent-encoded single segments: values with {@code /},
     * {@code \}, or equal to {@code .}/{@code ..} are refused before any dial.
     */
    static String url(String baseUrl, OpenApiDocument.Operation op, JsonNode arguments) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = op.path();
        StringBuilder query = new StringBuilder();
        for (OpenApiDocument.Param p : op.params()) {
            JsonNode value = arguments == null ? null : arguments.get(p.name());
            if (p.in().equals("path")) {
                String text = scalar(value);
                if (text == null || text.isEmpty() || text.equals(".") || text.equals("..") || text.contains("/") || text.contains("\\")) {
                    throw new ToolNotStartedException("Ein Pfadparameter ist leer oder enthält unzulässige Zeichen. Das Werkzeug wurde nicht aufgerufen.");
                }
                path = path.replace("{" + p.name() + "}", encode(text));
            } else if (value != null && !value.isNull()) {
                List<JsonNode> values = new ArrayList<>();
                if (value.isArray()) value.forEach(values::add); else values.add(value);
                for (JsonNode v : values) {
                    String text = scalar(v);
                    if (text == null) throw new ToolNotStartedException("Ein Query-Parameter ist ungültig. Das Werkzeug wurde nicht aufgerufen.");
                    if (!query.isEmpty()) query.append('&');
                    query.append(encode(p.name())).append('=').append(encode(text));
                }
            }
        }
        if (path.contains("{") || path.contains("}")) {
            throw new ToolNotStartedException("Ein Pfadparameter fehlt. Das Werkzeug wurde nicht aufgerufen.");
        }
        return base + path + (query.isEmpty() ? "" : "?" + query);
    }

    private static String scalar(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isString()) return v.asString();
        if (v.isNumber() || v.isBoolean()) return v.toString();
        return null;
    }

    /** RFC 3986 unreserved characters stay, every other byte is percent-encoded. */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return out.toString();
    }
}
