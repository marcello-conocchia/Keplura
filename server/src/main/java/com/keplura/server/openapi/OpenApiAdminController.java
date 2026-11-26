package com.keplura.server.openapi;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.mcp.McpToolAdapter;
import com.keplura.server.tool.ToolRisk;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Administration → OpenAPI-Werkzeugserver (TU-06a). Unter {@code /api/v1/admin/**} (Session + CSRF + ADMIN).
 * Token write-only und verschlüsselt. Jede Operation ist zunächst unklassifiziert und nicht freigegeben; der Admin
 * setzt je Operation eine Risikoklasse und gibt einzeln frei (nie aus HTTP-Methode o. ä. abgeleitet). Ein
 * geändertes Ziel oder geänderter Zugang hebt alle Freigaben auf; „Aktualisieren“ behält eine Freigabe nur für
 * unverändert gebliebene Operationen.
 */
@RestController
@RequestMapping("/api/v1/admin/openapi-servers")
public class OpenApiAdminController {

    public record ToolView(String name, String method, String path, String description, boolean enabled, String risk,
                           boolean usable, String problem) { }

    public record ServerView(Long id, String name, String specUrl, String baseUrl, String authMode, boolean hasToken,
                             boolean enabled, List<ToolView> tools, Instant lastCheckedAt, String lastError,
                             boolean allSpaces, List<Long> spaceIds) { }

    /** token: null = unverändert, "" = löschen. enabledTools/toolRisks wie bei MCP. */
    public record Input(String name, String specUrl, String baseUrl, String authMode, String token, Boolean enabled,
                        List<String> enabledTools, Boolean allSpaces, List<Long> spaceIds, Map<String, String> toolRisks) { }

    public record ErrorResponse(String error) { }

    private static final int MAX_SNAPSHOT_CHARS = 1_000_000;

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final OpenApiClient client;
    private final TransactionTemplate tx;

    public OpenApiAdminController(JdbcTemplate jdbc, SecretCipher cipher, OpenApiClient client,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private com.keplura.server.oauth.OAuthConnectionService oauth;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOauth(com.keplura.server.oauth.OAuthConnectionService oauth) { this.oauth = oauth; }

    @GetMapping
    public List<ServerView> list() {
        return jdbc.query("""
                select id, name, spec_url, base_url, auth_mode, token_enc, enabled, operations_json, enabled_tools,
                       tool_risks, last_checked_at, last_error, all_spaces from openapi_server order by name, id""", (rs, n) -> {
            Set<String> on = McpToolAdapter.approvals(rs.getString(9));
            Map<String, ToolRisk> risks = McpToolAdapter.risks(rs.getString(10));
            List<ToolView> tools = new ArrayList<>();
            for (OpenApiDocument.Operation op : OpenApiDocument.fromJson(rs.getString(8))) {
                boolean usable = op.operationId() != null && op.problem() == null;
                ToolRisk risk = op.operationId() == null ? null : risks.get(op.operationId());
                tools.add(new ToolView(op.operationId(), op.method(), op.path(), op.description(),
                        usable && risk != null && on.contains(op.operationId()), risk == null ? null : risk.name(), usable, op.problem()));
            }
            Timestamp ts = rs.getTimestamp(11);
            return new ServerView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6) != null, rs.getBoolean(7), tools, ts == null ? null : ts.toInstant(), rs.getString(12),
                    rs.getBoolean(13), jdbc.queryForList("select space_id from openapi_server_space where server_id = ? order by space_id",
                    Long.class, rs.getLong(1)));
        });
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Input in) {
        String mode = in.authMode() == null ? OpenApiToolAdapter.AUTH_NONE : in.authMode().strip().toLowerCase(Locale.ROOT);
        String err = validate(in.name(), in.specUrl(), in.baseUrl(), mode);
        if (err != null) return bad(err);
        String enc = null;
        if (OpenApiToolAdapter.AUTH_BEARER.equals(mode) && (in.token() == null || in.token().isBlank())) {
            return bad("Bitte ein Token angeben oder die Zugangsart „keiner“ wählen.");
        }
        if (!OpenApiToolAdapter.AUTH_NONE.equals(mode) && in.token() != null && !in.token().isBlank()) {
            // oauth: the optional admin token only loads the document, calls use the person's connection.
            if (!cipher.available()) return bad("Tokens können nicht gespeichert werden: KEPLURA_SECRETS_MASTER_KEY fehlt.");
            enc = cipher.encrypt(in.token().strip());
        }
        String encrypted = enc;
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var st = connection.prepareStatement("""
                    insert into openapi_server (name, spec_url, base_url, auth_mode, token_enc, enabled, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)""", new String[]{"id"});
            st.setString(1, in.name().strip());
            st.setString(2, in.specUrl().strip());
            st.setString(3, in.baseUrl().strip());
            st.setString(4, mode);
            st.setString(5, encrypted);
            st.setBoolean(6, in.enabled() == null || in.enabled());
            st.setTimestamp(7, Timestamp.from(Instant.now()));
            return st;
        }, keys);
        Long id = Objects.requireNonNull(keys.getKey(), "OpenAPI id missing").longValue();
        refresh(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(find(id));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable("id") Long id, @RequestBody Input in) {
        var locked = jdbc.query("select id from openapi_server where id = ? for update", (rs, n) -> rs.getLong(1), id);
        if (locked.isEmpty()) return ResponseEntity.notFound().build();
        ServerView cur = find(id);
        if (cur == null) return ResponseEntity.notFound().build();
        String name = in.name() == null ? cur.name() : in.name();
        String specUrl = in.specUrl() == null ? cur.specUrl() : in.specUrl();
        String baseUrl = in.baseUrl() == null ? cur.baseUrl() : in.baseUrl();
        String mode = in.authMode() == null ? cur.authMode() : in.authMode().strip().toLowerCase(Locale.ROOT);
        String err = validate(name, specUrl, baseUrl, mode);
        if (err != null) return bad(err);
        if (in.toolRisks() != null) {
            for (String v : in.toolRisks().values()) {
                if (v != null && !v.isBlank() && ToolRisk.parse(v) == null) {
                    return bad("Unbekannte Risikoklasse. Erlaubt sind READ, WRITE und CRITICAL.");
                }
            }
        }
        boolean tokenChanged = in.token() != null || !mode.equals(cur.authMode());
        if (tokenChanged) {
            String enc = null;
            String token = in.token();
            if (OpenApiToolAdapter.AUTH_BEARER.equals(mode) && (token == null || token.isBlank())) {
                return bad("Bitte ein Token angeben oder die Zugangsart „keiner“ wählen.");
            }
            if (!OpenApiToolAdapter.AUTH_NONE.equals(mode) && token != null && !token.isBlank()) {
                if (!cipher.available()) return bad("Tokens können nicht gespeichert werden: KEPLURA_SECRETS_MASTER_KEY fehlt.");
                enc = cipher.encrypt(token.strip());
            }
            jdbc.update("update openapi_server set token_enc = ?, auth_mode = ? where id = ?", enc, mode, id);
        }
        jdbc.update("update openapi_server set name = ?, spec_url = ?, base_url = ?, enabled = ? where id = ?", name.strip(),
                specUrl.strip(), baseUrl.strip(), in.enabled() == null ? cur.enabled() : in.enabled(), id);
        boolean changedTarget = !specUrl.strip().equals(cur.specUrl()) || !baseUrl.strip().equals(cur.baseUrl()) || tokenChanged;
        if (changedTarget) {
            // A new target or identity is a new trust boundary: never carry classes or approvals over.
            jdbc.update("update openapi_server set operations_json = null, enabled_tools = null, tool_risks = null, "
                    + "last_checked_at = null, last_error = null where id = ?", id);
        }
        // TU-06b: a new target or leaving personal access ends every personal connection of this server.
        if (oauth != null) {
            if (!OpenApiToolAdapter.AUTH_OAUTH.equals(mode)) oauth.removeClient("openapi", id);
            else if (!baseUrl.strip().equals(cur.baseUrl()) || !specUrl.strip().equals(cur.specUrl())) oauth.invalidate("openapi", id);
        }
        if (!changedTarget && (in.enabledTools() != null || in.toolRisks() != null)) {
            Map<String, ToolView> known = new LinkedHashMap<>();
            cur.tools().stream().filter(t -> t.name() != null).forEach(t -> known.put(t.name(), t));
            Map<String, ToolRisk> risks = McpToolAdapter.risks(
                    jdbc.queryForObject("select tool_risks from openapi_server where id = ?", String.class, id));
            Set<String> approved = new LinkedHashSet<>(McpToolAdapter.approvals(
                    jdbc.queryForObject("select enabled_tools from openapi_server where id = ?", String.class, id)));
            if (in.toolRisks() != null) {
                for (var e : in.toolRisks().entrySet()) {
                    if (!known.containsKey(e.getKey())) continue;
                    ToolRisk risk = ToolRisk.parse(e.getValue() == null ? "" : e.getValue());
                    if (Objects.equals(risks.get(e.getKey()), risk)) continue;
                    approved.remove(e.getKey()); // a new class is a new decision
                    if (risk == null) risks.remove(e.getKey()); else risks.put(e.getKey(), risk);
                }
            }
            if (in.enabledTools() != null) {
                approved = new LinkedHashSet<>(in.enabledTools().stream().filter(known::containsKey).distinct().toList());
            }
            approved.removeIf(t -> !known.containsKey(t) || risks.get(t) == null || !known.get(t).usable());
            jdbc.update("update openapi_server set enabled_tools = ?, tool_risks = ? where id = ?",
                    String.join("\n", approved), McpToolAdapter.risksJson(risks), id);
        }
        if (in.allSpaces() != null) jdbc.update("update openapi_server set all_spaces = ? where id = ?", in.allSpaces(), id);
        if (in.spaceIds() != null) {
            jdbc.update("delete from openapi_server_space where server_id = ?", id);
            for (Long sp : in.spaceIds().stream().filter(Objects::nonNull).distinct().toList()) {
                Integer exists = jdbc.queryForObject("select count(*) from space where id = ?", Integer.class, sp);
                if (exists != null && exists > 0) jdbc.update("insert into openapi_server_space (server_id, space_id) values (?, ?)", id, sp);
            }
        }
        return ResponseEntity.ok(find(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        jdbc.update("delete from openapi_server where id = ?", id);
        if (oauth != null) oauth.removeClient("openapi", id);
        else jdbc.update("delete from tool_oauth_client where kind='openapi' and server_id=?", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refreshEndpoint(@PathVariable("id") Long id) {
        if (find(id) == null) return ResponseEntity.notFound().build();
        refresh(id);
        return ResponseEntity.ok(find(id));
    }

    private void refresh(Long id) {
        var row = jdbc.queryForMap("select spec_url, base_url, auth_mode, token_enc from openapi_server where id = ?", id);
        Timestamp now = Timestamp.from(Instant.now());
        Object enc = row.get("token_enc");
        try {
            String bearer = "";
            // The admin's token only goes to the document URL when it lives on the tool server's own origin.
            if (enc != null && sameOrigin(row.get("spec_url").toString(), row.get("base_url").toString())) {
                bearer = cipher.decrypt(enc.toString());
            }
            String json = OpenApiDocument.toJson(OpenApiDocument.parse(client.fetchDocument(row.get("spec_url").toString(), bearer)));
            if (json.length() > MAX_SNAPSHOT_CHARS) throw new OpenApiDocument.InvalidDocument("Das OpenAPI-Dokument ist zu umfangreich.");
            if (!bearer.isEmpty() && json.contains(bearer)) json = json.replace(bearer, "[Zugangsdaten ausgeblendet]");
            String snapshot = json;
            tx.executeWithoutResult(status -> {
                var current = jdbc.queryForList("select spec_url, base_url, auth_mode, token_enc, operations_json, enabled_tools, "
                        + "tool_risks from openapi_server where id = ? for update", id);
                if (current.isEmpty()) return;
                var c = current.getFirst();
                if (!Objects.equals(c.get("spec_url"), row.get("spec_url")) || !Objects.equals(c.get("base_url"), row.get("base_url"))
                        || !Objects.equals(c.get("auth_mode"), row.get("auth_mode")) || !Objects.equals(c.get("token_enc"), enc)) return;
                Map<String, String> before = new HashMap<>();
                Object prev = c.get("operations_json");
                for (var op : OpenApiDocument.fromJson(prev == null ? null : prev.toString())) {
                    if (op.operationId() != null) before.put(op.operationId(), OpenApiDocument.canonical(op));
                }
                Set<String> unchanged = new LinkedHashSet<>();
                for (var op : OpenApiDocument.fromJson(snapshot)) {
                    if (op.operationId() != null && op.problem() == null
                            && OpenApiDocument.canonical(op).equals(before.get(op.operationId()))) unchanged.add(op.operationId());
                }
                Object prevApproved = c.get("enabled_tools");
                List<String> kept = McpToolAdapter.approvals(prevApproved == null ? null : prevApproved.toString()).stream()
                        .filter(unchanged::contains).toList();
                Object prevRisks = c.get("tool_risks");
                Map<String, ToolRisk> risks = McpToolAdapter.risks(prevRisks == null ? null : prevRisks.toString());
                risks.keySet().retainAll(unchanged);
                jdbc.update("update openapi_server set operations_json = ?, enabled_tools = ?, tool_risks = ?, last_checked_at = ?, "
                        + "last_error = null where id = ?", snapshot, String.join("\n", kept), McpToolAdapter.risksJson(risks), now, id);
            });
        } catch (RuntimeException e) {
            String msg = e instanceof OpenApiDocument.InvalidDocument || e instanceof OpenApiClient.OpenApiException
                    ? e.getMessage() : "Das OpenAPI-Dokument konnte nicht geladen werden.";
            jdbc.update("update openapi_server set last_checked_at = ?, last_error = ? where id = ? and spec_url = ? "
                    + "and token_enc is not distinct from ?", now, msg.length() > 500 ? msg.substring(0, 500) : msg, id,
                    row.get("spec_url"), enc);
        }
    }

    static boolean sameOrigin(String a, String b) {
        try {
            URI x = URI.create(a.strip());
            URI y = URI.create(b.strip());
            return Objects.equals(x.getScheme(), y.getScheme()) && Objects.equals(x.getHost(), y.getHost())
                    && x.getPort() == y.getPort();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ServerView find(Long id) {
        return list().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    static String validate(String name, String specUrl, String baseUrl, String mode) {
        if (name == null || name.isBlank() || name.strip().length() > 120) return "Bitte einen Namen angeben (max. 120 Zeichen).";
        if (!validUrl(specUrl)) return "Die Adresse des OpenAPI-Dokuments muss eine gültige http(s)-Adresse ohne Zugangsdaten sein.";
        if (!validUrl(baseUrl)) return "Die Basis-Adresse muss eine gültige http(s)-Adresse ohne Zugangsdaten sein.";
        URI base = URI.create(baseUrl.strip());
        if (base.getRawQuery() != null || base.getRawFragment() != null) return "Die Basis-Adresse darf keine Abfrage und keinen Anker enthalten.";
        if (mode == null || !OpenApiToolAdapter.AUTH_MODES.contains(mode)) return "Unbekannte Zugangsart.";
        return null;
    }

    private static boolean validUrl(String url) {
        if (url == null || url.strip().length() > 500) return false;
        String u = url.strip();
        if (!(u.startsWith("https://") || u.startsWith("http://"))) return false;
        try {
            SsrfGuard.parseShape(u);
            return true;
        } catch (com.keplura.server.admin.AdminException e) {
            return false;
        }
    }

    private static ResponseEntity<ErrorResponse> bad(String m) {
        return ResponseEntity.badRequest().body(new ErrorResponse(m));
    }
}
