package com.keplura.server.mcp;

import com.keplura.server.admin.SsrfGuard;
import com.keplura.server.tool.ToolAuditLog;
import com.keplura.server.tool.ToolExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review round 1 on PR #15: a tool whose input schema is invalid or not safely checkable must stay
 * blocked through the whole real path — fake MCP server → refresh → admin list → classify/approve →
 * central execution — and must never reach {@code tools/call}. The admin list must keep working.
 */
class McpSchemaFailClosedTest {

    private final tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    private com.sun.net.httpserver.HttpServer server;
    private volatile String toolsJson = "[]";
    private final AtomicInteger toolCalls = new AtomicInteger();
    private JdbcTemplate jdbc;
    private McpAdminController admin;
    private ToolExecutionService execution;
    private String url;
    private long user = 4711;
    private long orgId;
    private long chat;

    @BeforeEach void setup() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asString("");
            if (method.startsWith("notifications/")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            var response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            switch (method) {
                case "initialize" -> response.putObject("result").put("protocolVersion", McpClient.PROTOCOL);
                case "tools/list" -> response.putObject("result").set("tools", json.readTree(toolsJson));
                case "tools/call" -> {
                    toolCalls.incrementAndGet();
                    response.putObject("result").putArray("content").addObject().put("type", "text").put("text", "ok");
                }
                default -> response.putObject("error").put("code", -32601).put("message", "unknown");
            }
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mcp-schema-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        var client = new McpClient(new SsrfGuard("127.0.0.0/8"));
        admin = new McpAdminController(jdbc, null, client, new DataSourceTransactionManager(ds));
        execution = new ToolExecutionService(jdbc, List.of(new McpToolAdapter(jdbc, null, client)), new ToolAuditLog(jdbc));
        orgId = id("insert into organization (name, created_at) values ('Org', current_timestamp)");
        jdbc.update("insert into membership (org_id, user_id, role, created_at) values (?, ?, 'OWNER', current_timestamp)", orgId, user);
        long space = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Raum', current_timestamp)");
        chat = id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) values ("
                + orgId + ", " + space + ", " + user + ", 'Chat', current_timestamp, current_timestamp)");
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    private long id(String sql) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement(sql, new String[]{"id"}), keys);
        return keys.getKey().longValue();
    }

    /** Connect (refresh), share with all spaces, classify READ and approve — the admin happy path. */
    private long connectClassifyApprove(String toolDefinition) {
        toolsJson = "[" + toolDefinition + "]";
        var created = (McpAdminController.ServerView) admin.create(new McpAdminController.Input(
                "Fake", url, null, true, null, null, null, null)).getBody();
        long id = created.id();
        assertThat(created.lastError()).isNull();
        admin.update(id, new McpAdminController.Input(null, null, null, null, null, true, null, Map.of("t", "READ")));
        admin.update(id, new McpAdminController.Input(null, null, null, null, List.of("t"), null, null, null));
        return id;
    }

    private McpAdminController.ToolView view() {
        return admin.list().getFirst().tools().getFirst();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "true", "null", "[]", "\"object\"", "{}",
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"minLength\":18446744073709551617}}}",
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\",\"maximum\":1e400}}}"})
    void invalidOrUncheckableSchemaStaysBlockedEndToEnd(String schema) {
        long id = connectClassifyApprove("{\"name\":\"t\",\"description\":\"T\",\"inputSchema\":" + schema + "}");

        var v = view(); // admin list keeps working and shows the tool as blocked
        assertThat(v.schemaSupported()).isFalse();
        assertThat(v.schemaProblem()).isNotBlank();
        assertThat(v.enabled()).isFalse();
        assertThat(admin.refreshEndpoint(id).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select enabled_tools from mcp_server", String.class)).isEmpty();

        // Even with an approval forced into the DB, the central service neither offers nor runs it.
        jdbc.update("update mcp_server set enabled_tools = 't', tool_risks = '{\"t\":\"READ\"}'");
        var turn = execution.openTurn(execution.resolveContext(user, orgId, chat));
        assertThat(turn.offered()).isEmpty();
        assertThat(turn.call("s" + id + "__t", "{}").invoked()).isFalse();
        assertThat(toolCalls).hasValue(0);
        assertThat(jdbc.queryForObject("select count(*) from tool_call_log", Integer.class)).isZero();
    }

    @Test void missingSchemaMeansNoArgumentsAndRunsOnlyWithEmptyObject() {
        long id = connectClassifyApprove("{\"name\":\"t\",\"description\":\"T\"}");
        var v = view();
        assertThat(v.schemaSupported()).isTrue();
        assertThat(v.enabled()).isTrue();
        var turn = execution.openTurn(execution.resolveContext(user, orgId, chat));
        assertThat(turn.offered()).containsOnlyKeys("s" + id + "__t");
        assertThat(turn.call("s" + id + "__t", "{\"a\":1}").invoked()).isFalse();
        assertThat(toolCalls).hasValue(0);
        assertThat(turn.call("s" + id + "__t", "{}").invoked()).isTrue();
        assertThat(toolCalls).hasValue(1);
    }

    @Test void validSchemaStillWorks() {
        long id = connectClassifyApprove("{\"name\":\"t\",\"inputSchema\":{\"type\":\"object\",\"properties\":"
                + "{\"x\":{\"type\":\"integer\",\"enum\":[1]}},\"required\":[\"x\"]}}");
        var turn = execution.openTurn(execution.resolveContext(user, orgId, chat));
        assertThat(turn.call("s" + id + "__t", "{\"x\":1.0}").invoked()).isTrue(); // 1.0 == 1 (JSON Schema equality)
        assertThat(turn.call("s" + id + "__t", "{\"x\":2}").invoked()).isFalse();
        assertThat(toolCalls).hasValue(1);
    }
}
