package com.keplura.server.oauth;

import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persönliche Verbindungen (TU-06b). {@code /api/v1/orgs/{orgId}/connections/**} läuft hinter Session + CSRF; die
 * Person muss Mitglied der Organisation sein. Die Anmeldung läuft wie der OIDC-Login (ADR-0009) im App-Fenster selbst:
 * Der Callback {@value OAuthConnectionService#CALLBACK_PATH} ist ohne API-Sitzung erreichbar (Navigations-Cookies der
 * Webview), nimmt nur den Code einer offenen, frischen Anmeldung an, spiegelt nie Parameter zurück und leitet mit
 * einem Einmal-Code im Fragment auf die beim Start gewählte App-Adresse zurück. Abgeschlossen wird erst, wenn die startende Sitzung
 * diesen Code vorlegt ({@code POST .../connections/complete}). Ein weitergegebener Anmeldelink nützt damit niemandem.
 */
@RestController
public class OAuthConnectionController {

    public record ConnectionView(String kind, long serverId, String name, boolean connected, Instant connectedSince) { }

    public record StartView(String flowId, String authorizationUrl) { }

    public record StatusView(String status) { }

    public record CompleteInput(String handoff) {
        @Override public String toString() { return "CompleteInput[redacted]"; }
    }

    public record CompleteView(String status, String kind, long serverId) { }

    private final JdbcTemplate jdbc;
    private final OAuthConnectionService oauth;
    private final ObjectProvider<com.keplura.server.oidc.OidcProperties> oidcProperties;

    public OAuthConnectionController(JdbcTemplate jdbc, OAuthConnectionService oauth,
                                     ObjectProvider<com.keplura.server.oidc.OidcProperties> oidcProperties) {
        this.jdbc = jdbc;
        this.oauth = oauth;
        this.oidcProperties = oidcProperties;
    }

    private long member(long orgId, HttpServletRequest request) {
        var session = request.getSession(false);
        var principal = session == null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        if (!(principal instanceof AuthPrincipal p)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Integer count = jdbc.queryForObject("select count(*) from membership where org_id=? and user_id=?", Integer.class, orgId, p.userId());
        if (count == null || count != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return p.userId();
    }

    private record Available(String kind, long id, String name, String target) { }

    /** Personal servers usable somewhere in this organization (enabled, assigned to one of its spaces, client configured). */
    private List<Available> available(long orgId) {
        List<Available> out = new ArrayList<>(jdbc.query("""
                select s.id, s.name, s.url from mcp_server s join tool_oauth_client k on k.kind='mcp' and k.server_id=s.id
                where s.enabled=true and s.auth_mode='personal' and (s.all_spaces=true or exists(select 1 from mcp_server_space x join space p on p.id=x.space_id
                  where x.server_id=s.id and p.org_id=?)) order by s.name, s.id""",
                (rs, n) -> new Available("mcp", rs.getLong(1), rs.getString(2), rs.getString(3)), orgId));
        out.addAll(jdbc.query("""
                select s.id, s.name, s.base_url from openapi_server s join tool_oauth_client k on k.kind='openapi' and k.server_id=s.id
                where s.enabled=true and s.auth_mode='oauth' and (s.all_spaces=true or exists(select 1 from openapi_server_space x
                  join space p on p.id=x.space_id where x.server_id=s.id and p.org_id=?)) order by s.name, s.id""",
                (rs, n) -> new Available("openapi", rs.getLong(1), rs.getString(2), rs.getString(3)), orgId));
        return out;
    }

    @GetMapping("/api/v1/orgs/{orgId}/connections")
    public List<ConnectionView> list(@PathVariable("orgId") long orgId, HttpServletRequest request) {
        long user = member(orgId, request);
        List<ConnectionView> out = new ArrayList<>();
        for (Available a : available(orgId)) {
            var since = oauth.connection(user, orgId, a.kind(), a.id()).flatMap(c -> jdbc.query(
                    "select created_at from tool_oauth_connection where id=?", (rs, n) -> rs.getTimestamp(1).toInstant(), c.id())
                    .stream().findFirst());
            out.add(new ConnectionView(a.kind(), a.id(), a.name(), since.isPresent(), since.orElse(null)));
        }
        return out;
    }

    @PostMapping("/api/v1/orgs/{orgId}/connections/{kind}/{serverId}/start")
    public ResponseEntity<?> start(@PathVariable("orgId") long orgId, @PathVariable("kind") String kind,
                                   @PathVariable("serverId") long serverId,
                                   @RequestParam(name = "desktop", defaultValue = "false") boolean desktop,
                                   HttpServletRequest request) {
        long user = member(orgId, request);
        var target = available(orgId).stream().filter(a -> a.kind().equals(kind) && a.id() == serverId).findFirst();
        if (target.isEmpty()) return ResponseEntity.notFound().build();
        try {
            var started = oauth.start(user, orgId, kind, serverId, request.getSession(false).getId(), callbackUri(request),
                    appRedirectUri(desktop, request), kind.equals("mcp") ? target.get().target() : null);
            oauth.sweepFlows();
            return ResponseEntity.ok(new StartView(started.flowId(), started.authorizationUrl()));
        } catch (OAuthConnectionService.OAuthException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new StatusView(e.getMessage()));
        }
    }

    @GetMapping("/api/v1/orgs/{orgId}/connections/flows/{flowId}")
    public ResponseEntity<StatusView> status(@PathVariable("orgId") long orgId, @PathVariable("flowId") String flowId,
                                             HttpServletRequest request) {
        long user = member(orgId, request);
        if (flowId == null || flowId.length() != 36) return ResponseEntity.notFound().build();
        return oauth.status(user, orgId, flowId, request.getSession(false).getId())
                .map(s -> ResponseEntity.ok(new StatusView(s.name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The app presents the handoff from {@code #oauth=…}; only the starting person + session can redeem it, once. */
    @PostMapping("/api/v1/orgs/{orgId}/connections/complete")
    public ResponseEntity<CompleteView> complete(@PathVariable("orgId") long orgId,
                                                 @org.springframework.web.bind.annotation.RequestBody(required = false) CompleteInput in,
                                                 HttpServletRequest request) {
        long user = member(orgId, request);
        if (in == null || in.handoff() == null) return ResponseEntity.notFound().build();
        return oauth.complete(user, orgId, request.getSession(false).getId(), in.handoff())
                .map(c -> ResponseEntity.ok(new CompleteView(c.status().name(), c.kind(), c.serverId())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/v1/orgs/{orgId}/connections/{kind}/{serverId}")
    public ResponseEntity<Void> disconnect(@PathVariable("orgId") long orgId, @PathVariable("kind") String kind,
                                           @PathVariable("serverId") long serverId, HttpServletRequest request) {
        long user = member(orgId, request);
        oauth.disconnect(user, orgId, kind, serverId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 302 to the app address bound to the flow, with {@code #oauth=<handoff>} or {@code #oauth-error}.
     * An unknown flow returns to the server's own web UI with an error fragment.
     */
    @GetMapping(OAuthConnectionService.CALLBACK_PATH)
    public ResponseEntity<String> callback(@RequestParam(name = "state", required = false) String state,
                                           @RequestParam(name = "code", required = false) String code,
                                           @RequestParam(name = "error", required = false) String error,
                                           HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object principal = session == null ? null : session.getAttribute(AuthService.SESSION_PRINCIPAL_ATTR);
        Long browserUser = principal instanceof AuthPrincipal p ? p.userId() : null;
        Optional<OAuthConnectionService.CallbackReturn> result;
        try {
            result = oauth.callback(state, code, error, browserUser);
        } catch (RuntimeException e) {
            result = Optional.empty();
        }
        String target = result.map(OAuthConnectionService.CallbackReturn::appRedirectUri).orElse("/");
        String fragment = result.map(OAuthConnectionService.CallbackReturn::handoff)
                .map(handoff -> "#oauth=" + handoff).orElse("#oauth-error");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target + fragment))
                .header("Referrer-Policy", "no-referrer").header("Cache-Control", "no-store").build();
    }

    private String appRedirectUri(boolean desktop, HttpServletRequest request) {
        var props = oidcProperties.getIfAvailable();
        String configured = props == null ? null : props.desktopRedirectUri();
        if (desktop) {
            if (configured == null) throw new OAuthConnectionService.OAuthException("Die App-Rückkehradresse ist nicht eingerichtet.");
            return configured;
        }
        // The deployed web app shares the callback's origin, including behind TLS termination. A separate dev
        // frontend may return to the configured address only when the start request came from that exact origin.
        if (configured != null) {
            URI app = URI.create(configured);
            String origin = app.getScheme() + "://" + app.getRawAuthority();
            if (origin.equals(request.getHeader("Origin"))) return configured;
        }
        return "/";
    }

    /** Same derivation as the OIDC callback: the fixed public origin wins, never a caller parameter. */
    String callbackUri(HttpServletRequest request) {
        var props = oidcProperties.getIfAvailable();
        String base = props == null || props.publicBaseUrl() == null ? "" : props.publicBaseUrl();
        if (base.isEmpty()) {
            base = request.getScheme() + "://" + request.getServerName();
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(request.getScheme()) && port == 80) || ("https".equals(request.getScheme()) && port == 443);
            if (!defaultPort) base = base + ":" + port;
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + OAuthConnectionService.CALLBACK_PATH;
    }
}
