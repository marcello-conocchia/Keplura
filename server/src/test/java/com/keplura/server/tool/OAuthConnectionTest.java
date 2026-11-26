package com.keplura.server.tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keplura.server.admin.SecretCipher;
import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.auth.AuthPrincipal;
import com.keplura.server.auth.AuthService;
import com.keplura.server.mcp.McpClient;
import com.keplura.server.mcp.McpToolAdapter;
import com.keplura.server.oauth.OAuthClientAdminController;
import com.keplura.server.oauth.OAuthConnectionController;
import com.keplura.server.oauth.OAuthConnectionService;
import com.keplura.server.oauth.OAuthTokenClient;
import com.keplura.server.openapi.OpenApiAdminController;
import com.keplura.server.openapi.OpenApiClient;
import com.keplura.server.openapi.OpenApiToolAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TU-06b: personal OAuth connections (Authorization Code + PKCE S256) against a local authorization server and a
 * local OpenAPI resource server, both counting requests, through the real {@link ToolExecutionService} on H2.
 */
class OAuthConnectionTest {

    private static final String CLIENT_SECRET = "CLIENT_SECRET_77aa";
    private static final JsonMapper M = JsonMapper.builder().build();

    private HttpServer as;
    private HttpServer resource;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger refreshRequests = new AtomicInteger();
    private final AtomicInteger revokeRequests = new AtomicInteger();
    private final AtomicInteger resourceRequests = new AtomicInteger();
    private final List<String> resourceAuth = new CopyOnWriteArrayList<>();
    /** code -> [challenge, redirect, login] ; used codes stay in usedCodes */
    private final Map<String, String[]> codes = new ConcurrentHashMap<>();
    private final Map<String, String> usedCodes = new ConcurrentHashMap<>();
    private final Map<String, String> accessTokens = new ConcurrentHashMap<>(); // token -> login
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>(); // token -> login
    private final List<String> issued = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile long expiresIn = 3600;
    private volatile boolean refreshFails;
    private volatile String lastAuthorization;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-26T10:00:00Z"));

    private JdbcTemplate jdbc;
    private SecretCipher cipher;
    private OAuthConnectionService oauth;
    private OAuthConnectionController connections;
    private OAuthClientAdminController clients;
    private OpenApiAdminController openapi;
    private McpClient mcpClient;
    private ToolExecutionService service;
    private ListAppender<ILoggingEvent> logs;
    private final long alice = 4711;
    private final long bob = 4712;
    private long orgId;
    private long aliceChat;
    private long bobChat;
    private long serverId;
    private final MockHttpSession aliceSession = session(alice);
    private final MockHttpSession bobSession = session(bob);

    private static MockHttpSession session(long user) {
        var s = new MockHttpSession();
        s.setAttribute(AuthService.SESSION_PRINCIPAL_ATTR, new AuthPrincipal(user, "P" + user));
        return s;
    }

    private static MockHttpServletRequest request(MockHttpSession session) {
        var r = new MockHttpServletRequest();
        if (session != null) r.setSession(session);
        return r;
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static void send(HttpExchange e, int status, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", type);
        e.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (var out = e.getResponseBody()) { out.write(b); }
        e.close();
    }

    private String tokens(String login) {
        int n = counter.incrementAndGet();
        String access = "AT-" + login + "-" + n + "-c0ffee";
        String refresh = "RT-" + login + "-" + n + "-beef42";
        accessTokens.put(access, login);
        refreshTokens.put(refresh, login);
        issued.add(access);
        issued.add(refresh);
        return "{\"access_token\":\"" + access + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn
                + ",\"refresh_token\":\"" + refresh + "\"}";
    }

    @BeforeEach void setup() throws Exception {
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/authorize", e -> {
            Map<String, String> q = form(e.getRequestURI().getRawQuery());
            if (!"code".equals(q.get("response_type")) || !"keplura-client".equals(q.get("client_id"))
                    || !"S256".equals(q.get("code_challenge_method")) || q.get("state") == null || q.get("code_challenge") == null) {
                send(e, 400, "text/plain", "bad");
                return;
            }
            String code = "CODE-" + counter.incrementAndGet() + "-5ea1";
            codes.put(code, new String[]{q.get("code_challenge"), q.get("redirect_uri"), q.get("login")});
            issued.add(code);
            e.getResponseHeaders().set("Location", q.get("redirect_uri") + "?code=" + code + "&state=" + q.get("state"));
            send(e, 302, "text/plain", "");
        });
        as.createContext("/token", e -> {
            tokenRequests.incrementAndGet();
            Map<String, String> f = form(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String basic = "Basic " + Base64.getEncoder().encodeToString(("keplura-client:" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            if (!basic.equals(e.getRequestHeaders().getFirst("Authorization"))) { send(e, 401, "application/json", "{\"error\":\"invalid_client\"}"); return; }
            if ("authorization_code".equals(f.get("grant_type"))) {
                String[] c = codes.remove(f.getOrDefault("code", ""));
                if (c == null) { send(e, 400, "application/json", "{\"error\":\"invalid_grant\"}"); return; }
                usedCodes.put(f.get("code"), "used");
                try {
                    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(f.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                    if (!challenge.equals(c[0]) || !c[1].equals(f.get("redirect_uri"))) { send(e, 400, "application/json", "{\"error\":\"invalid_grant\"}"); return; }
                } catch (Exception ex) { send(e, 500, "text/plain", "x"); return; }
                send(e, 200, "application/json", tokens(c[2]));
            } else if ("refresh_token".equals(f.get("grant_type"))) {
                refreshRequests.incrementAndGet();
                String login = refreshTokens.remove(f.getOrDefault("refresh_token", ""));
                if (login == null || refreshFails) { send(e, 400, "application/json", "{\"error\":\"invalid_grant\"}"); return; }
                send(e, 200, "application/json", tokens(login));
            } else send(e, 400, "application/json", "{\"error\":\"unsupported_grant_type\"}");
        });
        as.createContext("/revoke", e -> { revokeRequests.incrementAndGet(); e.getRequestBody().readAllBytes(); send(e, 200, "text/plain", ""); });
        as.start();
        resource = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resource.createContext("/", e -> {
            if (e.getRequestURI().getPath().equals("/openapi.json")) {
                send(e, 200, "application/json", """
                        {"openapi":"3.0.3","info":{"title":"R","version":"1"},
                         "paths":{"/me":{"get":{"operationId":"whoAmI","summary":"Wer bin ich"}}}}""");
                return;
            }
            resourceRequests.incrementAndGet();
            String auth = String.valueOf(e.getRequestHeaders().getFirst("Authorization"));
            resourceAuth.add(auth);
            String login = auth.startsWith("Bearer ") ? accessTokens.get(auth.substring(7)) : null;
            if (login == null) { send(e, 401, "application/json", "{}"); return; }
            send(e, 200, "application/json", "{\"who\":\"" + login + "\"}");
        });
        resource.start();

        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:oauth-" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        orgId = id("insert into organization (name, created_at) values ('Org', current_timestamp)");
        for (long u : List.of(alice, bob)) {
            jdbc.update("insert into membership (org_id, user_id, role, created_at) values (?, ?, 'MEMBER', current_timestamp)", orgId, u);
        }
        long space = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Raum', current_timestamp)");
        aliceChat = chat(space, alice);
        bobChat = chat(space, bob);
        cipher = new SecretCipher("oauth-test-master-key");
        var guard = new SsrfGuard("127.0.0.0/8");
        oauth = new OAuthConnectionService(jdbc, cipher, new OAuthTokenClient(guard), now::get);
        connections = new OAuthConnectionController(jdbc, oauth, new StaticListableBeanFactory().getBeanProvider(
                com.keplura.server.oidc.OidcProperties.class));
        clients = new OAuthClientAdminController(jdbc, oauth, cipher);
        var openApiClient = new OpenApiClient(guard);
        openapi = new OpenApiAdminController(jdbc, cipher, openApiClient, new DataSourceTransactionManager(ds));
        java.lang.reflect.Method set = OpenApiAdminController.class.getDeclaredMethod("setOauth", OAuthConnectionService.class);
        set.setAccessible(true);
        set.invoke(openapi, oauth);
        mcpClient = mock(McpClient.class);
        service = new ToolExecutionService(jdbc, List.of(new OpenApiToolAdapter(jdbc, cipher, openApiClient, oauth),
                new McpToolAdapter(jdbc, cipher, mcpClient, oauth)), new ToolAuditLog(jdbc));

        String rbase = "http://127.0.0.1:" + resource.getAddress().getPort();
        var created = openapi.create(new OpenApiAdminController.Input("Konto", rbase + "/openapi.json", rbase, "oauth", null, true,
                null, true, null, null));
        serverId = ((OpenApiAdminController.ServerView) created.getBody()).id();
        openapi.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, null, true, null, null));
        openapi.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, null, null, null, Map.of("whoAmI", "READ")));
        openapi.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, List.of("whoAmI"), null, null, null));
        assertThat(clients.put("openapi", serverId, clientInput("read")).getStatusCode().value()).isEqualTo(200);

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    private OAuthClientAdminController.Input clientInput(String scopes) {
        String a = "http://127.0.0.1:" + as.getAddress().getPort();
        return new OAuthClientAdminController.Input(a + "/authorize", a + "/token", a + "/revoke", "keplura-client", CLIENT_SECRET, scopes);
    }

    @AfterEach void stop() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        as.stop(0);
        resource.stop(0);
    }

    private long id(String sql) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement(sql, new String[]{"id"}), keys);
        return keys.getKey().longValue();
    }

    private long chat(long space, long user) {
        return id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) values ("
                + orgId + ", " + space + ", " + user + ", 'Chat', current_timestamp, current_timestamp)");
    }

    private record Redirect(String state, String code, String flowId, String authorizationUrl) { }

    /** Start in Keplura, let the AS authenticate {@code login}, return what came back to the callback URL. */
    private Redirect authorize(MockHttpSession session, String kind, long server, String login) throws Exception {
        var started = connections.start(orgId, kind, server, request(session));
        assertThat(started.getStatusCode().value()).isEqualTo(200);
        var view = (OAuthConnectionController.StartView) started.getBody();
        lastAuthorization = view.authorizationUrl();
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(view.authorizationUrl() + "&login=" + login)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(302);
            Map<String, String> q = form(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
            return new Redirect(q.get("state"), q.get("code"), view.flowId(), view.authorizationUrl());
        }
    }

    private int callback(Redirect r, MockHttpSession browser) {
        return connections.callback(r.state(), r.code(), null, request(browser)).getStatusCode().value();
    }

    private String status(Redirect r, MockHttpSession session) {
        var s = connections.status(orgId, r.flowId(), request(session));
        return s.getStatusCode().value() == 404 ? "404" : s.getBody().status();
    }

    private void connect(MockHttpSession session, String login) throws Exception {
        var r = authorize(session, "openapi", serverId, login);
        assertThat(callback(r, null)).isEqualTo(200); // system browser: no Keplura session
        assertThat(status(r, session)).isEqualTo("CONNECTED");
    }

    private ToolExecutionService.ToolTurn turn(long user, long chat) {
        return service.openTurn(service.resolveContext(user, orgId, chat));
    }

    private String fn() { return "o" + serverId + "__whoAmI"; }

    @Test void flowWithStateAndPkceConnectsEachPersonWithOwnTokens() throws Exception {
        assertThat(turn(alice, aliceChat).offered()).isEmpty();
        var hints = service.chatToolSelection(alice, orgId, aliceChat).connectionsRequired();
        assertThat(hints).containsExactly(new ToolAdapter.ConnectionHint("openapi", serverId, "Konto"));

        connect(aliceSession, "alice");
        assertThat(lastAuthorization).contains("code_challenge_method=S256").contains("state=").doesNotContain("code_verifier");
        assertThat(service.chatToolSelection(alice, orgId, aliceChat).connectionsRequired()).isEmpty();
        var a = turn(alice, aliceChat);
        assertThat(a.offered()).containsOnlyKeys(fn());
        assertThat(turn(bob, bobChat).offered()).isEmpty(); // Bob has no connection of his own
        var out = a.call(fn(), "{}");
        assertThat(out.invoked()).isTrue();
        assertThat(out.text()).contains("\"who\":\"alice\"");

        connect(bobSession, "bob");
        assertThat(turn(bob, bobChat).call(fn(), "{}").text()).contains("\"who\":\"bob\"");
        assertThat(turn(alice, aliceChat).call(fn(), "{}").text()).contains("\"who\":\"alice\"");
        assertThat(resourceAuth).hasSize(3).allSatisfy(h -> assertThat(h).startsWith("Bearer AT-"));
        assertThat(resourceAuth.get(1)).contains("-bob-");
        assertThat(connections.list(orgId, request(aliceSession))).singleElement().satisfies(v -> assertThat(v.connected()).isTrue());
        assertThat(clients.list().stream().filter(c -> c.kind().equals("openapi")).findFirst().orElseThrow().connectedCount()).isEqualTo(2);
        assertThat(tokenRequests).hasValue(2);
    }

    @Test void wrongStateWrongUserReusedCodeAndExpiredFlowsAreRefused() throws Exception {
        var r = authorize(aliceSession, "openapi", serverId, "alice");
        // wrong state: nothing recorded
        assertThat(connections.callback("not-the-state", r.code(), null, request(null)).getStatusCode().value()).isEqualTo(400);
        assertThat(status(r, aliceSession)).isEqualTo("PENDING");
        // wrong person: Bob's browser session returns Alice's flow → the flow ends
        assertThat(callback(r, bobSession)).isEqualTo(400);
        assertThat(status(r, aliceSession)).isEqualTo("FAILED");
        assertThat(tokenRequests).hasValue(0);

        // Another session of the right person cannot complete; Bob never sees Alice's flow.
        var second = authorize(aliceSession, "openapi", serverId, "alice");
        assertThat(callback(second, aliceSession)).isEqualTo(200);
        assertThat(status(second, session(alice))).isEqualTo("404");
        assertThat(connections.status(orgId, second.flowId(), request(bobSession)).getStatusCode().value()).isEqualTo(404);
        assertThat(status(second, aliceSession)).isEqualTo("CONNECTED");
        // The same state/code again is refused, and the authorization server refuses a used code.
        assertThat(callback(second, null)).isEqualTo(400);
        var third = authorize(aliceSession, "openapi", serverId, "alice");
        assertThat(connections.callback(third.state(), second.code(), null, request(null)).getStatusCode().value()).isEqualTo(200);
        assertThat(status(third, aliceSession)).isEqualTo("FAILED");
        assertThat(usedCodes).containsKey(second.code());

        // Expired flow (session gone or too slow): refused at callback and reported as expired.
        var late = authorize(bobSession, "openapi", serverId, "bob");
        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertThat(callback(late, null)).isEqualTo(400);
        assertThat(status(late, bobSession)).isEqualTo("FAILED");
        var later = authorize(bobSession, "openapi", serverId, "bob");
        assertThat(callback(later, null)).isEqualTo(200);
        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertThat(status(later, bobSession)).isEqualTo("EXPIRED");
        assertThat(turn(bob, bobChat).offered()).isEmpty();
    }

    @Test void tokensAreOnlyStoredEncryptedAndNeverReachModelCardsAuditLogsOrApiResponses() throws Exception {
        connect(aliceSession, "alice");
        var t = turn(alice, aliceChat);
        String model = t.toolsJson().toString() + t.call(fn(), "{}").text();
        expiresIn = 1; // force a renewal on the next call
        connect(bobSession, "bob");
        model += turn(bob, bobChat).call(fn(), "{}").text();
        model += turn(bob, bobChat).call(fn(), "{}").text();
        assertThat(refreshRequests).hasValueGreaterThanOrEqualTo(1);

        String db = String.join("\n", jdbc.queryForList("SCRIPT", String.class));
        String api = M.writeValueAsString(List.of(connections.list(orgId, request(aliceSession)), connections.list(orgId, request(bobSession)),
                clients.list(), openapi.list(), service.cards(alice, orgId, aliceChat), service.cards(bob, orgId, bobChat),
                service.chatToolSelection(alice, orgId, aliceChat)));
        String logText = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(issued).isNotEmpty();
        for (String secret : issued) {
            assertThat(db).as("database").doesNotContain(secret);
            assertThat(api).as("api").doesNotContain(secret);
            assertThat(model).as("model").doesNotContain(secret);
            assertThat(logText).as("logs").doesNotContain(secret);
        }
        for (String text : List.of(db, api, model, logText)) assertThat(text).doesNotContain(CLIENT_SECRET);
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isEqualTo(2);
    }

    @Test void expiredTokenIsRenewedByTheServerAndAFailedRenewalEndsWithAFixedCardWithoutRetry() throws Exception {
        expiresIn = 10; // within the early-renewal window
        connect(aliceSession, "alice");
        long connection = jdbc.queryForObject("select id from tool_oauth_connection", Long.class);
        assertThat(turn(alice, aliceChat).call(fn(), "{}").text()).contains("alice");
        assertThat(refreshRequests).hasValue(1);
        assertThat(jdbc.queryForObject("select id from tool_oauth_connection", Long.class)).isEqualTo(connection);

        refreshFails = true;
        int before = resourceRequests.get();
        var t = turn(alice, aliceChat);
        var out = t.call(fn(), "{}");
        assertThat(out.invoked()).isFalse();
        assertThat(out.text()).isEqualTo(OAuthConnectionService.RECONNECT);
        assertThat(t.call(fn(), "{}").invoked()).isFalse(); // no second attempt in this turn
        assertThat(resourceRequests).hasValue(before);
        assertThat(refreshRequests).hasValue(2);
        assertThat(service.cards(alice, orgId, aliceChat)).anySatisfy(c -> {
            assertThat(c.error()).isEqualTo(OAuthConnectionService.RECONNECT);
            assertThat(c.status()).isEqualTo("BLOCKED");
        });
        assertThat(turn(alice, aliceChat).offered()).isEmpty();
    }

    @Test void disconnectAdminChangesServerDeletionAndLeavingTheOrgTakeEffectImmediately() throws Exception {
        connect(aliceSession, "alice");
        var opened = turn(alice, aliceChat);
        assertThat(connections.disconnect(orgId, "openapi", serverId, request(aliceSession)).getStatusCode().value()).isEqualTo(204);
        assertThat(revokeRequests).hasValue(1);
        assertThat(opened.call(fn(), "{}").invoked()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isZero();

        connect(aliceSession, "alice");
        var again = turn(alice, aliceChat);
        clients.put("openapi", serverId, clientInput("read write")); // changed scopes
        assertThat(again.call(fn(), "{}").invoked()).isFalse();
        assertThat(turn(alice, aliceChat).offered()).isEmpty();

        connect(aliceSession, "alice");
        String rbase = "http://127.0.0.1:" + resource.getAddress().getPort();
        openapi.update(serverId, new OpenApiAdminController.Input(null, null, rbase + "/", null, null, null, null, null, null, null));
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isZero();

        openapi.refreshEndpoint(serverId);
        openapi.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, null, null, null, Map.of("whoAmI", "READ")));
        openapi.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, List.of("whoAmI"), null, null, null));
        connect(aliceSession, "alice");
        assertThat(turn(alice, aliceChat).offered()).containsOnlyKeys(fn());
        jdbc.update("delete from membership where user_id=? and org_id=?", alice, orgId); // membership gone: unusable at once
        assertThat(oauth.connection(alice, orgId, "openapi", serverId)).isEmpty();
        oauth.removeMember(alice, orgId);
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isZero();

        connect(bobSession, "bob");
        openapi.delete(serverId);
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_client", Integer.class)).isZero();
    }

    @Test void personalMcpServerUsesOnlyThePersonsTokenNeverTheAdminKey() throws Exception {
        String adminKey = "MCP_ADMIN_LIST_KEY_31";
        jdbc.update("""
                insert into mcp_server (name,url,token_enc,enabled,tools_json,enabled_tools,tool_risks,created_at,all_spaces)
                values ('Mail','http://127.0.0.1:9/mcp',?,true,'[{"name":"inbox","inputSchema":{"type":"object"}}]','inbox',
                '{"inbox":"READ"}',current_timestamp,true)""", cipher.encrypt(adminKey));
        long mcp = jdbc.queryForObject("select id from mcp_server", Long.class);
        assertThat(clients.put("mcp", mcp, clientInput("mail")).getStatusCode().value()).isEqualTo(200);
        assertThat(turn(alice, aliceChat).offered()).doesNotContainKey("s" + mcp + "__inbox");
        var r = authorize(aliceSession, "mcp", mcp, "alice");
        assertThat(r.authorizationUrl()).contains("resource=http%3A%2F%2F127.0.0.1%3A9%2Fmcp");
        assertThat(callback(r, aliceSession)).isEqualTo(200);
        assertThat(status(r, aliceSession)).isEqualTo("CONNECTED");
        var used = new AtomicReference<String>();
        when(mcpClient.callToolWithContent(eq("http://127.0.0.1:9/mcp"), anyString(), eq("inbox"), any())).thenAnswer(i -> {
            used.set(i.getArgument(1));
            return new McpClient.CallResult("3 neue Mails", List.of());
        });
        var out = turn(alice, aliceChat).call("s" + mcp + "__inbox", "{}");
        assertThat(out.invoked()).isTrue();
        assertThat(used.get()).startsWith("AT-alice-").isNotEqualTo(adminKey);
        assertThat(turn(bob, bobChat).offered()).doesNotContainKey("s" + mcp + "__inbox");
    }
}
