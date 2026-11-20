package com.keplura.server.mcp;

import com.keplura.server.admin.SecretCipher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Administration → MCP-Server (Stufe 1). Unter {@code /api/v1/admin/**} (Session + CSRF + ADMIN).
 * Token write-only, mit {@link SecretCipher} verschlüsselt. Werkzeuge sind standardmäßig NICHT
 * freigegeben — der Admin hakt jedes einzeln an (ROADMAP: ein MCP-Server entscheidet nie selbst).
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-servers")
public class McpAdminController {

    public record ToolView(String name, String description, boolean enabled) {
    }

    public record ServerView(Long id, String name, String url, boolean hasToken, boolean enabled, List<ToolView> tools,
                             Instant lastCheckedAt, String lastError, boolean allSpaces, List<Long> spaceIds) {
    }

    /** token: null = unverändert, "" = löschen. enabledTools: null = unverändert. */
    public record Input(String name, String url, String token, Boolean enabled, List<String> enabledTools,
                        Boolean allSpaces, List<Long> spaceIds) {
    }

    public record ErrorResponse(String error) {
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final McpClient client;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public McpAdminController(JdbcTemplate jdbc, SecretCipher cipher, McpClient client,
                              org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @GetMapping
    public List<ServerView> list() {
        return jdbc.query("select id, name, url, token_enc, enabled, tools_json, enabled_tools, last_checked_at, last_error, all_spaces from mcp_server order by name",
                (rs, n) -> {
                    Set<String> on = names(rs.getString(7));
                    List<ToolView> tools = new ArrayList<>();
                    String json = rs.getString(6);
                    if (json != null && !json.isBlank()) {
                        for (JsonNode t : mapper.readTree(json)) {
                            String name = t.path("name").asString("");
                            tools.add(new ToolView(name, t.path("description").asString(""), on.contains(name)));
                        }
                    }
                    Timestamp ts = rs.getTimestamp(8);
                    return new ServerView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4) != null,
                            rs.getBoolean(5), tools, ts == null ? null : ts.toInstant(), rs.getString(9), rs.getBoolean(10),
                            jdbc.queryForList("select space_id from mcp_server_space where server_id = ? order by space_id",
                                    Long.class, rs.getLong(1)));
                });
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Input in) {
        String err = validate(in.name(), in.url());
        if (err != null) return bad(err);
        String enc = null;
        if (in.token() != null && !in.token().isBlank()) {
            if (!cipher.available()) return bad("Tokens können nicht gespeichert werden: KEPLURA_SECRETS_MASTER_KEY fehlt.");
            enc = cipher.encrypt(in.token().strip());
        }
        String encrypted = enc;
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "insert into mcp_server (name, url, token_enc, enabled, created_at) values (?, ?, ?, ?, ?)", new String[]{"id"});
            statement.setString(1, in.name().strip());
            statement.setString(2, in.url().strip());
            statement.setString(3, encrypted);
            statement.setBoolean(4, in.enabled() == null || in.enabled());
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            return statement;
        }, keys);
        Long id = java.util.Objects.requireNonNull(keys.getKey(), "MCP id missing").longValue();
        refresh(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(find(id));
    }

    @PatchMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> update(@PathVariable("id") Long id, @RequestBody Input in) {
        var locked = jdbc.query("select id from mcp_server where id = ? for update", (rs, n) -> rs.getLong(1), id);
        if (locked.isEmpty()) return ResponseEntity.notFound().build();
        ServerView cur = find(id);
        if (cur == null) return ResponseEntity.notFound().build();
        String name = in.name() == null ? cur.name() : in.name();
        String url = in.url() == null ? cur.url() : in.url();
        String err = validate(name, url);
        if (err != null) return bad(err);
        if (in.token() != null) {
            String enc = null;
            if (!in.token().isBlank()) {
                if (!cipher.available()) return bad("Tokens können nicht gespeichert werden: KEPLURA_SECRETS_MASTER_KEY fehlt.");
                enc = cipher.encrypt(in.token().strip());
            }
            jdbc.update("update mcp_server set token_enc = ? where id = ?", enc, id);
        }
        jdbc.update("update mcp_server set name = ?, url = ?, enabled = ? where id = ?", name.strip(), url.strip(),
                in.enabled() == null ? cur.enabled() : in.enabled(), id);
        boolean changedTarget = !url.strip().equals(cur.url()) || in.token() != null;
        if (changedTarget) {
            // A changed endpoint/token is a new trust boundary: never carry
            // old approvals to another server or identity.
            jdbc.update("update mcp_server set tools_json = null, enabled_tools = null, last_checked_at = null, last_error = null where id = ?", id);
        }
        if (!changedTarget && in.enabledTools() != null) {
            Set<String> known = new LinkedHashSet<>();
            cur.tools().forEach(t -> known.add(t.name()));
            List<String> on = in.enabledTools().stream().filter(known::contains).distinct().toList();
            jdbc.update("update mcp_server set enabled_tools = ? where id = ?", String.join("\n", on), id);
        }
        if (in.allSpaces() != null) jdbc.update("update mcp_server set all_spaces = ? where id = ?", in.allSpaces(), id);
        if (in.spaceIds() != null) {
            jdbc.update("delete from mcp_server_space where server_id = ?", id);
            for (Long sp : in.spaceIds().stream().distinct().toList()) {
                Integer exists = jdbc.queryForObject("select count(*) from space where id = ?", Integer.class, sp);
                if (exists != null && exists > 0) {
                    jdbc.update("insert into mcp_server_space (server_id, space_id) values (?, ?)", id, sp);
                }
            }
        }
        return ResponseEntity.ok(find(id));
    }

    public record SpaceOption(Long id, String name, String orgName) {
    }

    /** Alle Arbeitsbereiche für die Freigabe-Auswahl. */
    @GetMapping("/spaces")
    public List<SpaceOption> spaces() {
        return jdbc.query("select s.id, s.name, o.name from space s join organization o on o.id = s.org_id order by o.name, s.name",
                (rs, n) -> new SpaceOption(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    /** Letzte Werkzeug-Aufrufe (ohne Inhalte). */
    @GetMapping("/calls")
    public List<java.util.Map<String, Object>> calls() {
        return jdbc.query("""
                select called_at, user_id, org_id, space_id, server_name, tool_name, duration_ms, ok, error
                from mcp_call_log order by called_at desc limit 100""", (rs, n) -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("called_at", rs.getTimestamp(1).toInstant().toString());
            row.put("user_id", rs.getLong(2));
            row.put("org_id", rs.getLong(3));
            row.put("space_id", rs.getLong(4));
            row.put("server_name", rs.getString(5));
            row.put("tool_name", rs.getString(6));
            row.put("duration_ms", rs.getLong(7));
            row.put("ok", rs.getBoolean(8));
            row.put("error", rs.getString(9) == null ? null : "Der Werkzeug-Aufruf ist fehlgeschlagen oder sein Abschluss wurde nicht bestätigt.");
            return row;
        });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        jdbc.update("delete from mcp_server where id = ?", id);
        return ResponseEntity.noContent().build();
    }

    /** Verbinden und Werkzeugliste neu laden. Freigaben verschwundener Werkzeuge entfallen. */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refreshEndpoint(@PathVariable("id") Long id) {
        if (find(id) == null) return ResponseEntity.notFound().build();
        refresh(id);
        return ResponseEntity.ok(find(id));
    }

    private void refresh(Long id) {
        var row = jdbc.queryForMap("select url, token_enc, enabled_tools from mcp_server where id = ?", id);
        String token = "";
        Object enc = row.get("token_enc");
        Timestamp now = Timestamp.from(Instant.now());
        try {
            if (enc != null) token = cipher.decrypt(enc.toString());
            List<McpClient.Tool> tools = client.listTools(row.get("url").toString(), token);
            String json = mapper.writeValueAsString(tools.stream()
                    .map(t -> {
                        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("name", t.name());
                        m.put("description", t.description());
                        if (t.inputSchema() != null && t.inputSchema().isObject()) m.put("inputSchema", t.inputSchema());
                        return m;
                    }).toList());
            if (json.length() > 32000) throw new McpClient.McpException("Der Server bietet zu viele Werkzeuge an.");
            tx.executeWithoutResult(status -> {
                var current = jdbc.queryForList("select url, token_enc, enabled_tools from mcp_server where id = ? for update", id);
                if (current.isEmpty() || !java.util.Objects.equals(current.getFirst().get("url"), row.get("url"))
                        || !java.util.Objects.equals(current.getFirst().get("token_enc"), enc)) return;
                Set<String> available = new LinkedHashSet<>();
                tools.forEach(t -> available.add(t.name()));
                Object prev = current.getFirst().get("enabled_tools");
                List<String> kept = names(prev == null ? null : prev.toString()).stream().filter(available::contains).toList();
                jdbc.update("update mcp_server set tools_json = ?, enabled_tools = ?, last_checked_at = ?, last_error = null where id = ?",
                        json, String.join("\n", kept), now, id);
            });
        } catch (RuntimeException e) {
            String msg = e instanceof McpClient.McpException ? e.getMessage() : "Die Verbindung ist fehlgeschlagen.";
            jdbc.update("update mcp_server set last_checked_at = ?, last_error = ? where id = ? and url = ? and token_enc is not distinct from ?",
                    now, msg.length() > 500 ? msg.substring(0, 500) : msg, id, row.get("url"), enc);
        }
    }

    private ServerView find(Long id) {
        return list().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    private static Set<String> names(String s) {
        return s == null || s.isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(s.split("\n")));
    }

    private static String validate(String name, String url) {
        if (name == null || name.isBlank() || name.strip().length() > 120) return "Bitte einen Namen angeben (max. 120 Zeichen).";
        if (url == null || url.strip().length() > 500 || !(url.strip().startsWith("https://") || url.strip().startsWith("http://"))) {
            return "Die Adresse muss mit http:// oder https:// beginnen.";
        }
        try { com.keplura.server.admin.SsrfGuard.parseShape(url); }
        catch (com.keplura.server.admin.AdminException e) {
            return "Bitte eine gültige Serveradresse ohne eingebettete Zugangsdaten angeben.";
        }
        return null;
    }

    private static ResponseEntity<ErrorResponse> bad(String m) {
        return ResponseEntity.badRequest().body(new ErrorResponse(m));
    }
}
