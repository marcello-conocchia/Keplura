package com.keplura.server.oauth;

import com.keplura.server.admin.AdminException;
import com.keplura.server.admin.SecretCipher;
import com.keplura.server.admin.SsrfGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Administration → persönliche Verbindungen (TU-06b) unter {@code /api/v1/admin/**} (Session + CSRF + ADMIN).
 * Der Admin trägt Autorisierungs- und Token-Endpunkt, Client-ID, optional Client-Secret (write-only, verschlüsselt),
 * Widerrufs-Endpunkt und Scopes ein. Die Ansicht zeigt nur die Zahl verbundener Personen, nie Tokens oder Konten.
 */
@RestController
@RequestMapping("/api/v1/admin/oauth-clients")
public class OAuthClientAdminController {

    public record ClientView(String kind, long serverId, String serverName, boolean configured, String authorizeUrl,
                             String tokenUrl, String revokeUrl, String clientId, boolean hasClientSecret, String scopes,
                             int connectedCount) { }

    /** clientSecret: null = unverändert, "" = entfernen. */
    public record Input(String authorizeUrl, String tokenUrl, String revokeUrl, String clientId, String clientSecret, String scopes) {
        @Override public String toString() { return "Input[redacted]"; }
    }

    public record ErrorResponse(String error) { }

    private final JdbcTemplate jdbc;
    private final OAuthConnectionService oauth;
    private final SecretCipher cipher;

    public OAuthClientAdminController(JdbcTemplate jdbc, OAuthConnectionService oauth, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.oauth = oauth;
        this.cipher = cipher;
    }

    /** Every server that may use personal connections: all MCP servers and OpenAPI servers in access mode "oauth". */
    @GetMapping
    public List<ClientView> list() {
        List<ClientView> out = new ArrayList<>();
        record Row(String kind, long id, String name) { }
        List<Row> rows = new ArrayList<>(jdbc.query("select id, name from mcp_server order by name, id",
                (rs, n) -> new Row("mcp", rs.getLong(1), rs.getString(2))));
        rows.addAll(jdbc.query("select id, name from openapi_server where auth_mode='oauth' order by name, id",
                (rs, n) -> new Row("openapi", rs.getLong(1), rs.getString(2))));
        for (Row r : rows) {
            var c = oauth.client(r.kind(), r.id());
            out.add(c.map(x -> new ClientView(r.kind(), r.id(), r.name(), true, x.authorizeUrl(), x.tokenUrl(), x.revokeUrl(),
                            x.clientId(), x.clientSecretEnc() != null, x.scopes(), oauth.connectedCount(r.kind(), r.id())))
                    .orElse(new ClientView(r.kind(), r.id(), r.name(), false, null, null, null, null, false, null, 0)));
        }
        return out;
    }

    @PutMapping("/{kind}/{serverId}")
    public ResponseEntity<?> put(@PathVariable("kind") String kind, @PathVariable("serverId") long serverId, @RequestBody Input in) {
        if (!exists(kind, serverId)) return ResponseEntity.notFound().build();
        if (kind.equals("openapi") && !"oauth".equals(jdbc.queryForObject("select auth_mode from openapi_server where id=?", String.class, serverId))) {
            return bad("Bitte zuerst beim OpenAPI-Server die Zugangsart „persönlich (OAuth)“ wählen.");
        }
        String err = validate(in);
        if (err != null) return bad(err);
        if (in.clientSecret() != null && !in.clientSecret().isBlank() && !cipher.available()) {
            return bad("Secrets können nicht gespeichert werden: KEPLURA_SECRETS_MASTER_KEY fehlt.");
        }
        oauth.putClient(kind, serverId, in.authorizeUrl(), in.tokenUrl(), in.revokeUrl(), in.clientId(), in.clientSecret(), in.scopes());
        return ResponseEntity.ok(list().stream().filter(v -> v.kind().equals(kind) && v.serverId() == serverId).findFirst().orElseThrow());
    }

    /** Switches personal access off: the client and every connection of this server are removed at once. */
    @DeleteMapping("/{kind}/{serverId}")
    public ResponseEntity<Void> delete(@PathVariable("kind") String kind, @PathVariable("serverId") long serverId) {
        if (!OAuthConnectionService.KINDS.contains(kind)) return ResponseEntity.notFound().build();
        oauth.removeClient(kind, serverId);
        return ResponseEntity.noContent().build();
    }

    private boolean exists(String kind, long serverId) {
        if (!OAuthConnectionService.KINDS.contains(kind)) return false;
        String table = kind.equals("openapi") ? "openapi_server" : "mcp_server";
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where id=?", Integer.class, serverId);
        return n != null && n > 0;
    }

    static String validate(Input in) {
        if (in == null) return "Bitte die Angaben vollständig ausfüllen.";
        if (!url(in.authorizeUrl())) return "Der Autorisierungs-Endpunkt muss eine gültige http(s)-Adresse ohne Zugangsdaten sein.";
        if (!url(in.tokenUrl())) return "Der Token-Endpunkt muss eine gültige http(s)-Adresse ohne Zugangsdaten sein.";
        if (in.revokeUrl() != null && !in.revokeUrl().isBlank() && !url(in.revokeUrl())) {
            return "Der Widerrufs-Endpunkt muss eine gültige http(s)-Adresse ohne Zugangsdaten sein.";
        }
        if (in.clientId() == null || in.clientId().isBlank() || in.clientId().strip().length() > 300) return "Bitte eine Client-ID angeben (max. 300 Zeichen).";
        if (in.scopes() != null && (in.scopes().length() > 1000 || in.scopes().chars().anyMatch(c -> c < 0x20))) return "Die Scopes sind ungültig.";
        if (in.clientSecret() != null && in.clientSecret().length() > 2000) return "Das Client-Secret ist zu lang.";
        return null;
    }

    private static boolean url(String u) {
        if (u == null || u.isBlank() || u.strip().length() > 500) return false;
        String s = u.strip();
        if (!(s.startsWith("https://") || s.startsWith("http://")) || s.contains("#")) return false;
        try {
            SsrfGuard.parseShape(s);
            return true;
        } catch (AdminException e) {
            return false;
        }
    }

    private static ResponseEntity<ErrorResponse> bad(String m) {
        return ResponseEntity.badRequest().body(new ErrorResponse(m));
    }
}
