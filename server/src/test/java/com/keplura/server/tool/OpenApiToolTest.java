package com.keplura.server.tool;

import com.keplura.server.admin.SecretCipher;
import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.openapi.OpenApiAdminController;
import com.keplura.server.openapi.OpenApiClient;
import com.keplura.server.openapi.OpenApiToolAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TU-06a: OpenAPI operations through the real {@link ToolExecutionService} on H2 against local HTTP servers that
 * count every request. The document's {@code servers} entry points to a decoy server that must never be dialled.
 */
class OpenApiToolTest {

    private static final String TOKEN = "OPENAPI_TOKEN_9c1f5d77";
    private HttpServer api;
    private HttpServer decoy;
    private final AtomicInteger apiRequests = new AtomicInteger();
    private final AtomicInteger decoyRequests = new AtomicInteger();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile String document;
    private volatile int status = 200;
    private volatile byte[] responseBody = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    private volatile String responseType = "application/json";
    private JdbcTemplate jdbc;
    private OpenApiAdminController admin;
    private ToolExecutionService service;
    private SecretCipher cipher;
    private long user = 42;
    private long orgId;
    private long chat;
    private long serverId;
    private String base;

    @BeforeEach void setup() throws Exception {
        decoy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        decoy.createContext("/", exchange -> {
            decoyRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        decoy.start();
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            String raw = exchange.getRequestURI().getRawPath();
            if (raw.equals("/docs/openapi.json")) {
                byte[] doc = document.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, doc.length);
                try (var out = exchange.getResponseBody()) { out.write(doc); }
                return;
            }
            apiRequests.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(exchange.getRequestMethod() + " " + raw + (query == null ? "" : "?" + query) + (body.isEmpty() ? "" : " " + body));
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            if (status / 100 == 3) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + decoy.getAddress().getPort() + "/x");
            exchange.getResponseHeaders().set("Content-Type", responseType);
            byte[] bytes = status / 100 == 2 ? responseBody : "SECRET_ERROR_BODY".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) try (var out = exchange.getResponseBody()) { out.write(bytes); }
            exchange.close();
        });
        api.start();
        base = "http://127.0.0.1:" + api.getAddress().getPort() + "/v1";
        document = documentWith("http://127.0.0.1:" + decoy.getAddress().getPort());

        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:openapi-" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        orgId = id("insert into organization (name, created_at) values ('Org', current_timestamp)");
        jdbc.update("insert into membership (org_id, user_id, role, created_at) values (?, ?, 'OWNER', current_timestamp)", orgId, user);
        long space = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Raum', current_timestamp)");
        chat = id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) values ("
                + orgId + ", " + space + ", " + user + ", 'Chat', current_timestamp, current_timestamp)");
        cipher = new SecretCipher("openapi-test-master-key");
        build(new SsrfGuard("127.0.0.0/8"), new DataSourceTransactionManager(ds));
    }

    private void build(SsrfGuard guard, DataSourceTransactionManager tm) {
        var client = new OpenApiClient(guard);
        admin = new OpenApiAdminController(jdbc, cipher, client, tm);
        service = new ToolExecutionService(jdbc, List.of(new OpenApiToolAdapter(jdbc, cipher, client)), new ToolAuditLog(jdbc));
    }

    @AfterEach void stop() {
        api.stop(0);
        decoy.stop(0);
    }

    private long id(String sql) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement(sql, new String[]{"id"}), keys);
        return keys.getKey().longValue();
    }

    static String documentWith(String servers) {
        return """
            {"openapi":"3.1.0","info":{"title":"T","version":"1"},
             "servers":[{"url":"%s"}],
             "components":{"schemas":{"Item":{"type":"object","properties":{"title":{"type":"string","maxLength":50}},
                 "required":["title"],"additionalProperties":false}},
               "parameters":{"Id":{"name":"id","in":"path","required":true,"schema":{"type":"string"}}}},
             "paths":{
              "/items/{id}":{"parameters":[{"$ref":"#/components/parameters/Id"}],
                "get":{"operationId":"getItem","summary":"Eintrag lesen",
                  "parameters":[{"name":"expand","in":"query","schema":{"type":"array","items":{"type":"string"}}}]},
                "put":{"operationId":"putItem","requestBody":{"required":true,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Item"}}}}}},
              "/search":{"get":{"summary":"ohne id"}},
              "/headers":{"get":{"operationId":"withHeader","parameters":[{"name":"X-Key","in":"header","schema":{"type":"string"}}]}},
              "/cookie":{"get":{"operationId":"withCookie","parameters":[{"name":"c","in":"cookie","schema":{"type":"string"}}]}},
              "/upload":{"post":{"operationId":"upload","requestBody":{"content":{"multipart/form-data":{"schema":{"type":"object"}}}}}},
              "/external":{"post":{"operationId":"external","requestBody":{"content":{"application/json":{"schema":{"$ref":"https://example.org/s.json"}}}}}},
              "/pattern":{"get":{"operationId":"patterned","parameters":[{"name":"q","in":"query","schema":{"type":"string","pattern":"^a"}}]}}
             }}""".formatted(servers);
    }

    private OpenApiAdminController.ServerView create(String authMode, String token) {
        var response = admin.create(new OpenApiAdminController.Input("Tickets", base.replace("/v1", "") + "/docs/openapi.json",
                base, authMode, token, true, null, null, null, null));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var view = (OpenApiAdminController.ServerView) response.getBody();
        serverId = view.id();
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, null, true, null, null));
        return view;
    }

    private void approve(String... ops) {
        Map<String, String> risks = new java.util.HashMap<>();
        for (String op : ops) risks.put(op, "READ");
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, null, null, null, risks));
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, List.of(ops), null, null, null));
    }

    private ToolExecutionService.ToolTurn turn() {
        return service.openTurn(service.resolveContext(user, orgId, chat));
    }

    private String fn(String op) { return "o" + serverId + "__" + op; }

    @Test void onlyOperationsWithIdBecomeToolsAndUnsupportedOnesAreVisiblyBlocked() {
        var view = create("none", null);
        assertThat(view.lastError()).isNull();
        var byName = new java.util.HashMap<String, OpenApiAdminController.ToolView>();
        view.tools().forEach(t -> byName.put(t.name() == null ? t.method() + " " + t.path() : t.name(), t));
        assertThat(byName.get("getItem").usable()).isTrue();
        assertThat(byName.get("putItem").usable()).isTrue();
        assertThat(byName.get("GET /search").usable()).isFalse();
        assertThat(byName.get("GET /search").problem()).contains("keine operationId");
        assertThat(byName.get("withHeader").problem()).contains("Header- und Cookie-Parameter");
        assertThat(byName.get("withCookie").problem()).contains("Header- und Cookie-Parameter");
        assertThat(byName.get("upload").problem()).contains("Datei-Uploads");
        assertThat(byName.get("external").problem()).contains("Externe Referenzen");
        assertThat(byName.get("patterned").problem()).contains("pattern");
        // Nothing approved or classified yet: nothing is offered, nothing reaches the server.
        assertThat(turn().offered()).isEmpty();
        assertThat(turn().call(fn("getItem"), "{\"id\":\"1\"}").invoked()).isFalse();
        // Blocked operations cannot be approved even with a class.
        approve("getItem", "withHeader", "upload");
        assertThat(turn().offered()).containsOnlyKeys(fn("getItem"));
        assertThat(apiRequests).hasValue(0);
        assertThat(decoyRequests).hasValue(0);
    }

    @Test void aClassWithoutApprovalOrAnApprovalWithoutClassOffersNothing() {
        create("none", null);
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, null, null, null, Map.of("getItem", "READ")));
        assertThat(turn().offered()).isEmpty();
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, null, null, null, Map.of("getItem", "")));
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, null, List.of("getItem"), null, null, null));
        assertThat(turn().offered()).isEmpty();
        assertThat(apiRequests).hasValue(0);
    }

    @Test void callsGoExactlyToTheFixedBaseUrlAndSnapshotPathNeverToTheDocumentServers() {
        create("none", null);
        approve("getItem", "putItem");
        var t = turn();
        var got = t.call(fn("getItem"), "{\"id\":\"A 1\",\"expand\":[\"x\",\"y&z\"]}");
        assertThat(got.invoked()).isTrue();
        assertThat(got.text()).contains("{\"ok\":true}");
        var put = turn().call(fn("putItem"), "{\"id\":\"7\",\"body\":{\"title\":\"Neu\"}}");
        assertThat(put.invoked()).isTrue();
        assertThat(received).containsExactly("GET /v1/items/A%201?expand=x&expand=y%26z", "PUT /v1/items/7 {\"title\":\"Neu\"}");
        assertThat(apiRequests).hasValue(2);
        assertThat(decoyRequests).hasValue(0);
    }

    @Test void pathTraversalThroughParametersIsImpossible() {
        create("none", null);
        approve("getItem");
        for (String id : List.of("..", ".", "a/b", "..\\\\x", "")) {
            var out = turn().call(fn("getItem"), "{\"id\":\"" + id + "\"}");
            assertThat(out.invoked()).as(id).isFalse();
        }
        assertThat(apiRequests).hasValue(0);
        assertThat(turn().call(fn("getItem"), "{\"id\":\"%2e%2e\"}").invoked()).isTrue();
        assertThat(received).containsExactly("GET /v1/items/%252e%252e");
    }

    @Test void schemaValidationBlocksBeforeAnyRequest() {
        create("none", null);
        approve("putItem");
        assertThat(turn().call(fn("putItem"), "{\"id\":\"1\",\"body\":{\"title\":\"" + "x".repeat(60) + "\"}}").invoked()).isFalse();
        assertThat(turn().call(fn("putItem"), "{\"id\":\"1\",\"body\":{\"other\":1}}").invoked()).isFalse();
        assertThat(turn().call(fn("putItem"), "{\"id\":\"1\"}").invoked()).isFalse();
        assertThat(apiRequests).hasValue(0);
    }

    @Test void redirectsAreNotFollowedAndErrorsCarryNoAddressOrBody() {
        create("none", null);
        approve("getItem");
        status = 302;
        var out = turn().call(fn("getItem"), "{\"id\":\"1\"}");
        assertThat(out.invoked()).isTrue();
        assertThat(out.text()).contains("Weiterleitungen werden nicht verfolgt").doesNotContain("127.0.0.1");
        assertThat(decoyRequests).hasValue(0);
        status = 500;
        var failed = turn().call(fn("getItem"), "{\"id\":\"1\"}");
        assertThat(failed.text()).contains("HTTP 500").doesNotContain("SECRET_ERROR_BODY").doesNotContain("127.0.0.1");
        assertThat(service.cards(user, orgId, chat)).allSatisfy(c -> {
            assertThat(String.valueOf(c.error())).doesNotContain("127.0.0.1").doesNotContain("SECRET_ERROR_BODY");
        });
    }

    @Test void oversizedOrBinaryResponsesFailAndBearerTokenNeverReachesResults() {
        create("bearer", TOKEN);
        approve("getItem");
        responseBody = ("{\"echo\":\"" + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8);
        var out = turn().call(fn("getItem"), "{\"id\":\"1\"}");
        assertThat(out.invoked()).isTrue();
        assertThat(authHeaders).containsExactly("Bearer " + TOKEN);
        assertThat(out.text()).doesNotContain(TOKEN);
        assertThat(service.cards(user, orgId, chat).getFirst().result()).doesNotContain(TOKEN);
        assertThat(jdbc.queryForObject("select token_enc from openapi_server", String.class)).doesNotContain(TOKEN);
        assertThat(admin.list().toString()).doesNotContain(TOKEN);
        responseBody = new byte[OpenApiClient.MAX_TEXT_BYTES + 10];
        java.util.Arrays.fill(responseBody, (byte) 'a');
        responseType = "text/plain";
        assertThat(turn().call(fn("getItem"), "{\"id\":\"1\"}").text()).contains("keine gültige, begrenzte Antwort");
        // TU-06d: a binary answer becomes a card artifact only after the magic-byte check; the model gets a placeholder.
        responseBody = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        responseType = "image/png";
        assertThat(turn().call(fn("getItem"), "{\"id\":\"1\"}").text()).contains("[Datei verworfen:");
        responseBody = ToolArtifactTest.png(3, 3);
        var image = turn().call(fn("getItem"), "{\"id\":\"1\"}");
        assertThat(image.text()).contains("[Datei-Ergebnis: antwort.png (image/png").doesNotContain("IHDR");
        responseBody = "<html>x</html>".getBytes(StandardCharsets.UTF_8);
        responseType = "text/html";
        assertThat(turn().call(fn("getItem"), "{\"id\":\"1\"}").text()).contains("Dieser Dateityp ist nicht erlaubt.");
        responseBody = new byte[OpenApiClient.MAX_BINARY_BYTES + 5];
        responseType = "application/pdf";
        assertThat(turn().call(fn("getItem"), "{\"id\":\"1\"}").text()).contains("größer als 5 MB");
    }

    @Test void refreshKeepsApprovalsOnlyForUnchangedOperations() {
        create("none", null);
        approve("getItem", "putItem");
        document = document.replace("\"maxLength\":50", "\"maxLength\":80");
        admin.refreshEndpoint(serverId);
        assertThat(turn().offered()).containsOnlyKeys(fn("getItem"));
        // The method of an unchanged operationId moves: a new decision, never carried over.
        document = document.replace("\"get\":{\"operationId\":\"getItem\"", "\"delete\":{\"operationId\":\"getItem\"");
        admin.refreshEndpoint(serverId);
        assertThat(turn().offered()).isEmpty();
    }

    @Test void changedBaseUrlOrTokenDropsApprovalsAndOldDefinitionsAreNotExecuted() {
        create("none", null);
        approve("getItem");
        var opened = turn();
        admin.update(serverId, new OpenApiAdminController.Input(null, null, base + "/other", null, null, null, null, null, null, null));
        assertThat(opened.call(fn("getItem"), "{\"id\":\"1\"}").invoked()).isFalse();
        assertThat(turn().offered()).isEmpty();
        admin.refreshEndpoint(serverId);
        assertThat(turn().offered()).isEmpty(); // the reloaded operations are unclassified again
        approve("getItem");
        var again = turn();
        assertThat(again.offered()).containsOnlyKeys(fn("getItem"));
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, "bearer", TOKEN, null, null, null, null, null));
        assertThat(again.call(fn("getItem"), "{\"id\":\"1\"}").invoked()).isFalse();
        assertThat(apiRequests).hasValue(0);
    }

    @Test void aDisabledServerOrAnotherSpaceOffersNothing() {
        create("none", null);
        approve("getItem");
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, false, null, null, null, null));
        assertThat(turn().offered()).isEmpty();
        admin.update(serverId, new OpenApiAdminController.Input(null, null, null, null, null, true, null, false, List.of(), null));
        assertThat(turn().offered()).isEmpty();
    }

    @Test void unsupportedDocumentsAreRejectedWithAFixedMessage() {
        document = "openapi: 3.1.0\npaths: {}\n";
        assertThat(create("none", null).lastError()).contains("JSON-Format");
        document = "{\"swagger\":\"2.0\",\"paths\":{}}";
        assertThat(admin.refreshEndpoint(serverId).getBody().toString()).contains("OpenAPI 3.0 und 3.1");
        document = "{\"openapi\":\"3.0.3\",\"x\":\"" + "a".repeat(1_000_100) + "\"}";
        assertThat(admin.refreshEndpoint(serverId).getBody().toString()).contains("keine gültige, begrenzte Antwort");
    }

    @Test void withoutOperatorCidrTheLocalTargetIsRefusedBeforeAnyRequest() {
        create("none", null);
        approve("getItem");
        build(new SsrfGuard(""), new DataSourceTransactionManager(jdbc.getDataSource()));
        var out = turn().call(fn("getItem"), "{\"id\":\"1\"}");
        assertThat(out.invoked()).isFalse();
        assertThat(out.text()).contains("nicht freigegeben").doesNotContain("127.0.0.1");
        assertThat(apiRequests).hasValue(0);
    }

    @Test void invalidAdminInputIsRefused() {
        var bad = admin.create(new OpenApiAdminController.Input("X", "ftp://x/doc.json", base, "none", null, true, null, null, null, null));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        bad = admin.create(new OpenApiAdminController.Input("X", base + "/d.json", "http://user:pw@127.0.0.1/", "none", null, true, null, null, null, null));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        bad = admin.create(new OpenApiAdminController.Input("X", base + "/d.json", base + "?a=1", "none", null, true, null, null, null, null));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        bad = admin.create(new OpenApiAdminController.Input("X", base + "/d.json", base, "magic", null, true, null, null, null, null));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        bad = admin.create(new OpenApiAdminController.Input("X", base + "/d.json", base, "bearer", "", true, null, null, null, null));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from openapi_server", Integer.class)).isZero();
    }
}
