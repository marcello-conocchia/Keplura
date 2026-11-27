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
import com.keplura.server.oauth.OAuthTransportPolicy;
import com.keplura.server.oidc.OidcProperties;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * local OpenAPI resource server, both counting requests, through the real {@link ToolExecutionService}.
 * H2 by default. KEPLURA_TEST_POSTGRES_URL runs the same tests in an isolated PostgreSQL schema and verifies lock order.
 */
class OAuthConnectionTest {

    private static final String CLIENT_SECRET = "CLIENT_SECRET_77aa";
    /** Fixed app address the callback hands back to (keplura.oidc.desktop-redirect-uri). */
    private static final String APP = "http://localhost:1420/";
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
    /** When set, the token endpoint signals {@link #tokenArrived} and waits for this gate (race tests). */
    private volatile CountDownLatch tokenGate;
    private volatile CountDownLatch tokenArrived;
    private final AtomicInteger token2Requests = new AtomicInteger();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-26T10:00:00Z"));

    private JdbcTemplate jdbc;
    private JdbcTemplate postgresAdmin;
    private String postgresSchema;
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
        as.createContext("/token2", e -> { token2Requests.incrementAndGet(); e.getRequestBody().readAllBytes(); send(e, 400, "application/json", "{}"); });
        as.createContext("/token", e -> {
            tokenRequests.incrementAndGet();
            if (tokenGate != null) {
                tokenArrived.countDown();
                try { tokenGate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
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

        var ds = dataSource();
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
        // Local test authorization server over plain HTTP: allowed for literal loopback only by the test setting.
        var transport = new OAuthTransportPolicy(true);
        oauth = new OAuthConnectionService(jdbc, cipher, new OAuthTokenClient(guard, transport), now::get);
        var beans = new StaticListableBeanFactory();
        beans.addBean("oidcProperties", new OidcProperties(APP, null, 600, 60));
        connections = new OAuthConnectionController(jdbc, oauth, beans.getBeanProvider(OidcProperties.class));
        clients = new OAuthClientAdminController(jdbc, oauth, cipher, transport);
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

    private javax.sql.DataSource dataSource() {
        String url = System.getenv("KEPLURA_TEST_POSTGRES_URL");
        if (url == null || url.isBlank()) {
            var ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:oauth-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            return ds;
        }
        String user = System.getenv().getOrDefault("KEPLURA_TEST_POSTGRES_USER", "keplura");
        String password = System.getenv().getOrDefault("KEPLURA_TEST_POSTGRES_PASSWORD", "");
        postgresAdmin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        postgresSchema = "oauth_test_" + UUID.randomUUID().toString().replace("-", "");
        postgresAdmin.execute("create schema " + postgresSchema);
        var ds = new DriverManagerDataSource(url, user, password);
        var properties = new Properties();
        properties.setProperty("currentSchema", postgresSchema);
        properties.setProperty("ApplicationName", postgresSchema);
        ds.setConnectionProperties(properties);
        return ds;
    }

    private OAuthClientAdminController.Input clientInput(String scopes) {
        String a = "http://127.0.0.1:" + as.getAddress().getPort();
        return new OAuthClientAdminController.Input(a + "/authorize", a + "/token", a + "/revoke", "keplura-client", CLIENT_SECRET, scopes);
    }

    @AfterEach void stop() {
        if (logs != null) ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        as.stop(0);
        resource.stop(0);
        if (postgresSchema != null) postgresAdmin.execute("drop schema if exists " + postgresSchema + " cascade");
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
        var started = connections.start(orgId, kind, server, false, request(session));
        assertThat(started.getStatusCode().value()).isEqualTo(200);
        return authorize((OAuthConnectionController.StartView) started.getBody(), login);
    }

    private Redirect authorize(OAuthConnectionController.StartView view, String login) throws Exception {
        lastAuthorization = view.authorizationUrl();
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(view.authorizationUrl() + "&login=" + login)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(302);
            Map<String, String> q = form(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
            return new Redirect(q.get("state"), q.get("code"), view.flowId(), view.authorizationUrl());
        }
    }

    /** The browser comes back: returns the handoff from {@code #oauth=…}, or null for {@code #oauth-error}. */
    private String callback(String state, String code, MockHttpSession browser) {
        var response = connections.callback(state, code, null, request(browser));
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        String location = response.getHeaders().getLocation().toString();
        assertThat(location).startsWith("/#oauth");
        return location.startsWith("/#oauth=") ? location.substring("/#oauth=".length()) : null;
    }

    private String callback(Redirect r, MockHttpSession browser) {
        return callback(r.state(), r.code(), browser);
    }

    private String status(Redirect r, MockHttpSession session) {
        var s = connections.status(orgId, r.flowId(), request(session));
        return s.getStatusCode().value() == 404 ? "404" : s.getBody().status();
    }

    /** The app presents the handoff with its own session. */
    private String complete(String handoff, MockHttpSession session) {
        var s = connections.complete(orgId, new OAuthConnectionController.CompleteInput(handoff), request(session));
        return s.getStatusCode().value() == 404 ? "404" : s.getBody().status();
    }

    private void connect(MockHttpSession session, String login) throws Exception {
        var r = authorize(session, "openapi", serverId, login);
        String handoff = callback(r, null); // app window: no API session in the navigation cookies
        assertThat(handoff).matches("[0-9a-f]{64}");
        assertThat(complete(handoff, session)).isEqualTo("CONNECTED");
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
        assertThat(callback("not-the-state", r.code(), null)).isNull();
        assertThat(status(r, aliceSession)).isEqualTo("PENDING");
        // wrong person: Bob's browser session returns Alice's flow → the flow ends
        assertThat(callback(r, bobSession)).isNull();
        assertThat(status(r, aliceSession)).isEqualTo("FAILED");
        assertThat(tokenRequests).hasValue(0);

        // Only the starting person AND session redeem the handoff, exactly once.
        var second = authorize(aliceSession, "openapi", serverId, "alice");
        String handoff = callback(second, aliceSession);
        assertThat(handoff).isNotNull();
        assertThat(status(second, aliceSession)).isEqualTo("PENDING"); // the status alone never exchanges
        assertThat(complete(handoff, session(alice))).isEqualTo("404"); // another session of the right person
        assertThat(complete(handoff, bobSession)).isEqualTo("404");
        assertThat(connections.status(orgId, second.flowId(), request(bobSession)).getStatusCode().value()).isEqualTo(404);
        assertThat(complete("0".repeat(64), aliceSession)).isEqualTo("404");
        assertThat(complete(handoff, aliceSession)).isEqualTo("CONNECTED");
        assertThat(complete(handoff, aliceSession)).isEqualTo("404"); // single use
        assertThat(status(second, aliceSession)).isEqualTo("CONNECTED");
        // The same state/code again is refused, and the authorization server refuses a used code.
        assertThat(callback(second, null)).isNull();
        var third = authorize(aliceSession, "openapi", serverId, "alice");
        String replay = callback(third.state(), second.code(), null);
        assertThat(complete(replay, aliceSession)).isEqualTo("FAILED");
        assertThat(usedCodes).containsKey(second.code());

        // Expired flow (session gone or too slow): refused at callback and reported as expired.
        var late = authorize(bobSession, "openapi", serverId, "bob");
        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertThat(callback(late, null)).isNull();
        assertThat(status(late, bobSession)).isEqualTo("FAILED");
        var later = authorize(bobSession, "openapi", serverId, "bob");
        String slow = callback(later, null);
        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertThat(complete(slow, bobSession)).isEqualTo("EXPIRED");
        assertThat(turn(bob, bobChat).offered()).isEmpty();
    }

    /** Review finding 1: a forwarded authorization link must not bind the other person's provider account. */
    @Test void forwardedAuthorizationLinkOpenedInAnotherSignedOutBrowserBindsNothing() throws Exception {
        var started = connections.start(orgId, "openapi", serverId, false, request(aliceSession));
        var view = (OAuthConnectionController.StartView) started.getBody();
        // Alice forwards the link; Bob opens it in a different browser without a Keplura session and authorizes.
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(view.authorizationUrl() + "&login=bob")).build(),
                    HttpResponse.BodyHandlers.discarding());
            Map<String, String> q = form(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
            // The handoff lands in BOB's browser, never with Alice's session.
            String inBobsBrowser = callback(q.get("state"), q.get("code"), null);
            assertThat(inBobsBrowser).isNotNull();
            assertThat(status(new Redirect(null, null, view.flowId(), null), aliceSession)).isEqualTo("PENDING");
            // Even if Bob (signed in to Keplura later) presents it, it is not his flow.
            assertThat(complete(inBobsBrowser, bobSession)).isEqualTo("404");
        }
        assertThat(tokenRequests).hasValue(0); // Bob's code was never exchanged
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isZero();
        assertThat(turn(alice, aliceChat).offered()).isEmpty();
    }

    /** Review finding 2: disconnect while the code exchange is still running stores nothing afterwards. */
    @Test void wrongOrganizationDoesNotConsumeTheHandoffOrContactTheProvider() throws Exception {
        long otherOrg = id("insert into organization (name, created_at) values ('Andere Org', current_timestamp)");
        jdbc.update("insert into membership (org_id, user_id, role, created_at) values (?, ?, 'MEMBER', current_timestamp)", otherOrg, alice);
        var r = authorize(aliceSession, "openapi", serverId, "alice");
        var handoff = callback(r, null);
        var wrong = connections.complete(otherOrg, new OAuthConnectionController.CompleteInput(handoff), request(aliceSession));
        assertThat(wrong.getStatusCode().value()).isEqualTo(404);
        assertThat(tokenRequests).hasValue(0);
        assertThat(oauth.connection(alice, orgId, "openapi", serverId)).isEmpty();
        assertThat(complete(handoff, aliceSession)).isEqualTo("CONNECTED");
        assertThat(oauth.connection(alice, otherOrg, "openapi", serverId)).isEmpty();
        assertThat(tokenRequests).hasValue(1);
    }

    @Test void callbackKeepsTheAppReturnSelectedAtStart() throws Exception {
        var beans = new StaticListableBeanFactory();
        String publicOrigin = "https://keplura.example.test";
        beans.addBean("oidcProperties", new OidcProperties(APP, publicOrigin, 600, 60));
        var controller = new OAuthConnectionController(jdbc, oauth, beans.getBeanProvider(OidcProperties.class));

        var web = request(aliceSession);
        web.addHeader("Origin", publicOrigin);
        URI callback = URI.create(publicOrigin + OAuthConnectionService.CALLBACK_PATH);
        URI landing = callback.resolve(returnLocation(controller, false, web, callback.toString()));
        assertThat(landing.toString()).startsWith(publicOrigin + "/#oauth=");
        assertThat(complete(landing.getFragment().substring("oauth=".length()), aliceSession)).isEqualTo("CONNECTED");

        var dev = request(aliceSession);
        dev.addHeader("Origin", "http://localhost:1420");
        assertThat(returnLocation(controller, false, dev, callback.toString())).startsWith(APP + "#oauth=");
        assertThat(returnLocation(controller, true, request(aliceSession), callback.toString())).startsWith(APP + "#oauth=");
        var otherOrigin = request(aliceSession);
        otherOrigin.addHeader("Origin", "https://unrelated.example.test");
        assertThat(returnLocation(controller, false, otherOrigin, callback.toString())).startsWith("/#oauth=");
    }

    private String returnLocation(OAuthConnectionController controller, boolean desktop,
                                  MockHttpServletRequest request, String expectedCallback) throws Exception {
        var start = controller.start(orgId, "openapi", serverId, desktop, request);
        assertThat(start.getStatusCode().value()).isEqualTo(200);
        var view = (OAuthConnectionController.StartView) start.getBody();
        assertThat(form(URI.create(view.authorizationUrl()).getRawQuery()).get("redirect_uri")).isEqualTo(expectedCallback);
        var authorization = authorize(view, "alice");
        var callback = controller.callback(authorization.state(), authorization.code(), null, request(null));
        assertThat(callback.getStatusCode().value()).isEqualTo(302);
        return callback.getHeaders().getLocation().toString();
    }

    @Test void adminChangeAndCompletionTakeTheClientLockInTheSameOrder() throws Exception {
        var r = authorize(aliceSession, "openapi", serverId, "alice");
        var handoff = callback(r, null);
        tokenArrived = new CountDownLatch(1);
        tokenGate = new CountDownLatch(1);
        var result = new AtomicReference<String>();
        Thread exchange = Thread.ofVirtual().start(() -> result.set(complete(handoff, aliceSession)));
        try {
            assertThat(tokenArrived.await(5, TimeUnit.SECONDS)).isTrue();
            var admin = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            admin.executeWithoutResult(st -> {
                jdbc.queryForObject("select revision from tool_oauth_client where kind='openapi' and server_id=? for update",
                        Long.class, serverId);
                tokenGate.countDown();
                if (postgresAdmin != null) awaitBlockedCompletion();
                var changed = clientInput("read write");
                oauth.putClient("openapi", serverId, changed.authorizeUrl(), changed.tokenUrl(),
                        changed.revokeUrl(), changed.clientId(), null, changed.scopes());
            });
        } finally {
            tokenGate.countDown();
            exchange.join(Duration.ofSeconds(10));
        }
        assertThat(result).hasValue("FAILED");
        assertThat(oauth.connection(alice, orgId, "openapi", serverId)).isEmpty();
    }

    /** PostgreSQL observes finalization blocked on the admin's client lock before the admin deletes the flow. */
    private void awaitBlockedCompletion() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            Boolean blocked = postgresAdmin.queryForObject("""
                    select exists(select 1 from pg_stat_activity where application_name=?
                      and wait_event_type='Lock' and query like '%tool_oauth_client%for update%')""",
                    Boolean.class, postgresSchema);
            if (Boolean.TRUE.equals(blocked)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Completion did not wait on the client row");
    }

    @Test void disconnectDuringARunningExchangeIsNotUndone() throws Exception {
        var r = authorize(aliceSession, "openapi", serverId, "alice");
        String handoff = callback(r, null);
        tokenArrived = new CountDownLatch(1);
        tokenGate = new CountDownLatch(1);
        var result = new AtomicReference<String>();
        Thread exchange = Thread.ofVirtual().start(() -> result.set(complete(handoff, aliceSession)));
        assertThat(tokenArrived.await(10, TimeUnit.SECONDS)).isTrue(); // exchange is paused at the token endpoint
        connections.disconnect(orgId, "openapi", serverId, request(aliceSession));
        tokenGate.countDown();
        exchange.join(10_000);
        tokenGate = null;
        assertThat(result.get()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isZero();
        assertThat(turn(alice, aliceChat).offered()).isEmpty();
    }

    /** Review finding 2: an endpoint change during a refresh never sends the old refresh token to the new endpoint. */
    @Test void adminChangeDuringRefreshNeverSendsTheOldRefreshTokenToTheNewEndpoint() throws Exception {
        expiresIn = 10; // within the early-renewal window
        connect(aliceSession, "alice");
        var t = turn(alice, aliceChat);
        tokenArrived = new CountDownLatch(1);
        tokenGate = new CountDownLatch(1);
        var out = new AtomicReference<ToolExecutionService.Outcome>();
        Thread call = Thread.ofVirtual().start(() -> out.set(t.call(fn(), "{}")));
        assertThat(tokenArrived.await(10, TimeUnit.SECONDS)).isTrue(); // refresh is paused at the OLD token endpoint
        String a = "http://127.0.0.1:" + as.getAddress().getPort();
        clients.put("openapi", serverId, new OAuthClientAdminController.Input(a + "/authorize", a + "/token2", a + "/revoke",
                "keplura-client", CLIENT_SECRET, "read"));
        tokenGate.countDown();
        call.join(10_000);
        tokenGate = null;
        assertThat(out.get().invoked()).isFalse();
        assertThat(token2Requests).hasValue(0);
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isZero();
    }

    /** Review finding 2: the client generation survives deletion and recreation; an old row never revives. */
    @Test void recreatedClientNeverRevivesAnOldConnection() throws Exception {
        connect(aliceSession, "alice");
        var old = jdbc.queryForMap("select * from tool_oauth_connection");
        clients.delete("openapi", serverId);
        assertThat(clients.put("openapi", serverId, clientInput("read")).getStatusCode().value()).isEqualTo(200);
        jdbc.update("""
                insert into tool_oauth_connection(user_id, org_id, kind, server_id, client_revision, access_token_enc, refresh_token_enc,
                  expires_at, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?)""", old.get("user_id"), old.get("org_id"), old.get("kind"),
                old.get("server_id"), old.get("client_revision"), old.get("access_token_enc"), old.get("refresh_token_enc"),
                old.get("expires_at"), old.get("created_at"), old.get("updated_at"));
        assertThat(oauth.connection(alice, orgId, "openapi", serverId)).isEmpty();
        assertThat(turn(alice, aliceChat).offered()).isEmpty();
        assertThat(oauth.disconnect(alice, orgId, "openapi", serverId)).isFalse();
        assertThat(revokeRequests).hasValue(0); // an old token never goes to a newer client's revocation endpoint
        assertThat(jdbc.queryForObject("select count(*) from tool_oauth_connection", Integer.class)).isZero();
    }

    /** Review finding 4: OAuth endpoints and personal resource calls are HTTPS-only before anything is sent. */
    @Test void plainHttpIsRefusedForOAuthEndpointsAndPersonalResourceCallsBeforeAnyRequest() throws Exception {
        var strict = OAuthTransportPolicy.httpsOnly();
        assertThat(strict.allows("https://login.example.com/token")).isTrue();
        assertThat(strict.allows("http://127.0.0.1:8080/token")).isFalse();
        var testing = new OAuthTransportPolicy(true);
        assertThat(testing.allows("http://127.0.0.1:8080/token")).isTrue();
        assertThat(testing.allows("http://localhost:8080/token")).isFalse(); // a name, not a literal loopback IP
        assertThat(testing.allows("http://10.0.0.5/token")).isFalse();
        assertThat(testing.allows("https://user:pw@login.example.com/")).isFalse();

        var strictClients = new OAuthClientAdminController(jdbc, oauth, cipher, strict);
        assertThat(strictClients.put("openapi", serverId, clientInput("read")).getStatusCode().value()).isEqualTo(400);
        var strictTokens = new OAuthTokenClient(new SsrfGuard("127.0.0.0/8"), strict);
        String a = "http://127.0.0.1:" + as.getAddress().getPort();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strictTokens.exchange(a + "/token", "keplura-client", CLIENT_SECRET,
                "CODE", APP, "verifier")).isInstanceOf(OAuthTokenClient.TokenException.class);
        assertThat(tokenRequests).hasValue(0);

        connect(aliceSession, "alice");
        int before = resourceRequests.get();
        var strictService = new OAuthConnectionService(jdbc, cipher, strictTokens, now::get);
        var connection = oauth.connection(alice, orgId, "openapi", serverId).orElseThrow();
        String rbase = "http://127.0.0.1:" + resource.getAddress().getPort();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strictService.accessToken(alice, orgId, "openapi", serverId,
                connection.id(), rbase + "/me")).isInstanceOf(ToolAdapter.ToolNotStartedException.class);
        assertThat(resourceRequests).hasValue(before);
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

        String db = postgresAdmin == null ? String.join("\n", jdbc.queryForList("SCRIPT", String.class))
                : List.of("tool_oauth_client", "tool_oauth_connection", "tool_oauth_flow", "tool_call", "tool_call_log")
                    .stream().map(table -> jdbc.queryForList("select * from " + table).toString()).reduce("", String::concat);
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
        assertThat(complete(callback(r, aliceSession), aliceSession)).isEqualTo("CONNECTED");
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

    /** Review finding 3: a personal MCP server never falls back to the shared admin key by removing its client. */
    @Test void personalMcpWithoutClientFailsClosedAndSwitchingOffClearsApprovals() throws Exception {
        String adminKey = "MCP_ADMIN_LIST_KEY_31";
        jdbc.update("""
                insert into mcp_server (name,url,token_enc,enabled,tools_json,enabled_tools,tool_risks,created_at,all_spaces)
                values ('Mail','http://127.0.0.1:9/mcp',?,true,'[{"name":"inbox","inputSchema":{"type":"object"}}]','inbox',
                '{"inbox":"READ"}',current_timestamp,true)""", cipher.encrypt(adminKey));
        long mcp = jdbc.queryForObject("select id from mcp_server", Long.class);
        String tool = "s" + mcp + "__inbox";
        var used = new CopyOnWriteArrayList<String>();
        when(mcpClient.callToolWithContent(anyString(), anyString(), eq("inbox"), any())).thenAnswer(i -> {
            used.add(i.getArgument(1));
            return new McpClient.CallResult("ok", List.of());
        });
        assertThat(turn(alice, aliceChat).offered()).containsKey(tool); // shared mode: admin key
        assertThat(clients.put("mcp", mcp, clientInput("mail")).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select auth_mode from mcp_server where id=?", String.class, mcp)).isEqualTo("personal");
        // The client row disappears by any other path: personal mode stays and fails closed.
        jdbc.update("delete from tool_oauth_client where kind='mcp' and server_id=?", mcp);
        assertThat(turn(alice, aliceChat).offered()).doesNotContainKey(tool);
        // The explicit switch back clears the approvals: nothing runs with the admin key until approved again.
        assertThat(clients.put("mcp", mcp, clientInput("mail")).getStatusCode().value()).isEqualTo(200);
        assertThat(clients.delete("mcp", mcp).getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select auth_mode from mcp_server where id=?", String.class, mcp)).isEqualTo("shared");
        assertThat(jdbc.queryForObject("select enabled_tools from mcp_server where id=?", String.class, mcp)).isNull();
        assertThat(turn(alice, aliceChat).offered()).doesNotContainKey(tool);
        assertThat(used).doesNotContain(adminKey);
    }
}
