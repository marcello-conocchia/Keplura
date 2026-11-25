package com.keplura.server.chat;

import com.keplura.server.gateway.GatewayClient;
import com.keplura.server.gateway.GatewayProperties;
import com.keplura.server.tool.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** TU-05c: large catalogs are sealed whole, but the model only sees what tool_search made visible. */
class ToolSearchLoopTest {
    private static final JsonMapper M = JsonMapper.builder().build();
    private JdbcTemplate jdbc;
    private ToolExecutionService execution;
    private GatewayProperties config;
    private Fake adapter;
    private ChatToolLoop loop;
    private ChatService.TurnContext ctx;
    private final List<JsonNode> requests = new ArrayList<>();
    private BiFunction<Integer, JsonNode, String> script;

    static class Fake implements ToolAdapter {
        final List<String> calls = new ArrayList<>();
        final List<ToolDefinition> tools = new ArrayList<>();
        @Override public String kind() { return "fixture"; }
        @Override public List<ToolDefinition> definitions(Long space) { return tools; }
        void add(String name, ToolRisk risk, String description, boolean approved) {
            tools.add(new ToolDefinition("fixture:1:" + name, kind(), name, name, 1L, "Fixture", description,
                    M.readTree("{\"type\":\"object\"}"), risk, approved, "fp"));
        }
        @Override public String invoke(ToolDefinition t, JsonNode args) {
            calls.add(t.toolName());
            return "result of " + t.toolName();
        }
    }

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:toolsearch-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into organization(id,name,created_at) values(1,'Org',current_timestamp)");
        jdbc.update("insert into space(id,org_id,name,created_at) values(1,1,'Space',current_timestamp)");
        jdbc.update("insert into membership(org_id,user_id,role,created_at) values(1,1,'OWNER',current_timestamp)");
        jdbc.update("insert into chat_conversation(id,org_id,space_id,created_by,title,created_at,updated_at) values(1,1,1,1,'Chat',current_timestamp,current_timestamp)");
        jdbc.update("insert into chat_message(id,conversation_id,role,content,model,citations_json,status,created_at) values(1,1,'user','Pay invoice','','[]','complete',current_timestamp)");
        adapter = new Fake();
        execution = new ToolExecutionService(jdbc, List.of(adapter), new ToolAuditLog(jdbc));
        var gateway = mock(GatewayClient.class);
        config = mock(GatewayProperties.class);
        when(gateway.completeJson(any(), anyString(), same(config))).thenAnswer(inv -> {
            JsonNode body = M.readTree((byte[]) inv.getArgument(0));
            int index = requests.size();
            requests.add(body);
            return new GatewayClient.BufferedUpstreamResponse(200, "application/json",
                    script.apply(index, body).getBytes(StandardCharsets.UTF_8));
        });
        ctx = new ChatService.TurnContext(1L, "[]", M.writeValueAsBytes(M.readTree("""
                {"model":"test","stream":true,"messages":[{"role":"user","content":"Pay invoice"}]}""")), false, 1L);
        loop = new ChatToolLoop(execution, gateway);
    }

    private static JsonNode call(String name, String args) {
        var c = M.createObjectNode().put("id", "call-" + name).put("type", "function");
        c.putObject("function").put("name", name).put("arguments", args);
        return c;
    }

    private static String plan(JsonNode... calls) {
        var root = M.createObjectNode();
        ArrayNode a = root.putArray("choices").addObject().putObject("message").putArray("tool_calls");
        for (JsonNode c : calls) a.add(c);
        return root.toString();
    }

    private static String answer(String text) {
        var root = M.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", text);
        return root.toString();
    }

    private static List<String> toolNames(JsonNode request) {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : request.path("tools")) names.add(tool.path("function").path("name").asString());
        return names;
    }

    private String run() throws Exception {
        try (var stream = loop.run(1L, 1L, ctx, "Pay invoice", ChatProperties.of("test", 5, 2, 200, 100), config)) {
            return new String(stream.body().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void manyTools(int count, String description) {
        for (int i = 0; i < count; i++) adapter.add("tool_" + i, ToolRisk.READ, description + " " + i, true);
    }

    @Test void smallCatalogsAreOfferedUnchangedWithoutSearch() throws Exception {
        manyTools(ToolExecutionService.TOOL_SEARCH_THRESHOLD, "general helper");
        script = (i, b) -> i == 0 ? plan(call("tool_3", "{}")) : answer("done");
        run();
        assertThat(toolNames(requests.getFirst())).hasSize(12).doesNotContain(ToolExecutionService.TOOL_SEARCH);
        assertThat(adapter.calls).containsExactly("tool_3");
    }

    @Test void largeCatalogsStartWithSearchOnlyAndHiddenToolsAreNotExecuted() throws Exception {
        manyTools(15, "general helper");
        adapter.add("invoice_lookup", ToolRisk.READ, "Finds an invoice by number", true);
        adapter.add("invoice_secret", ToolRisk.READ, "invoice data, not approved", false);
        script = (i, b) -> switch (i) {
            case 0 -> plan(call("invoice_lookup", "{}"), call(ToolExecutionService.TOOL_SEARCH, "{\"query\":\"Invoice number\"}"));
            case 1 -> plan(call("invoice_lookup", "{}"), call("invoice_secret", "{}"));
            default -> answer("done");
        };
        run();
        assertThat(toolNames(requests.get(0))).containsExactly(ToolExecutionService.TOOL_SEARCH);
        assertThat(toolNames(requests.get(1))).containsExactly(ToolExecutionService.TOOL_SEARCH, "invoice_lookup");
        assertThat(adapter.calls).containsExactly("invoice_lookup"); // only after the search, the unapproved never
        JsonNode searchResult = null;
        for (JsonNode m : requests.get(1).path("messages"))
            if (m.path("role").asString().equals("tool") && m.path("content").asString().contains("Ab jetzt aufrufbar")) searchResult = m;
        assertThat(searchResult).isNotNull();
        assertThat(searchResult.path("content").asString()).contains("invoice_lookup").doesNotContain("invoice_secret");
        assertThat(execution.cards(1L, 1L, 1L)).anyMatch(c -> c.tool().equals("invoice_lookup") && c.status().equals("BLOCKED"));
    }

    @Test void oneSearchRevealsAtMostEightToolsAndCountsAsACall() throws Exception {
        manyTools(20, "invoice helper");
        script = (i, b) -> switch (i) {
            case 0 -> plan(call(ToolExecutionService.TOOL_SEARCH, "{\"query\":\"invoice\"}"));
            default -> answer("done");
        };
        run();
        assertThat(toolNames(requests.get(1))).hasSize(1 + ToolExecutionService.TOOL_SEARCH_HITS)
                .contains(ToolExecutionService.TOOL_SEARCH);
        assertThat(jdbc.queryForObject("select attempts from tool_question where prompt_message_id=1", Integer.class)).isEqualTo(1);
        JsonNode visible = M.readTree(jdbc.queryForObject("select visible_tools_json from tool_question where prompt_message_id=1", String.class));
        assertThat(visible.size()).isEqualTo(ToolExecutionService.TOOL_SEARCH_HITS);
    }

    @Test void revealedToolsStayVisibleInTheConfirmedContinuation() throws Exception {
        manyTools(14, "general helper");
        adapter.add("pay_invoice", ToolRisk.CRITICAL, "Pays an invoice", true);
        adapter.add("invoice_status", ToolRisk.READ, "Shows the invoice status", true);
        script = (i, b) -> i == 0
                ? plan(call(ToolExecutionService.TOOL_SEARCH, "{\"query\":\"invoice\"}"), call("pay_invoice", "{}"))
                : answer("Waiting for confirmation");
        run();
        var card = execution.cards(1L, 1L, 1L).stream().filter(c -> c.status().equals("PENDING")).findFirst().orElseThrow();
        var done = (ToolExecutionService.Decision.Done) execution.decide(1L, 1L, 1L, card.id(), true);
        assertThat(adapter.calls).containsExactly("pay_invoice");
        var continuation = execution.takeContinuation(1L, 1L, 1L, done.card().id(), done.continuationToken());
        assertThat(continuation).isNotNull();
        assertThat(continuation.turn().searchMode()).isTrue();
        assertThat(continuation.turn().visibleNames()).containsExactlyInAnyOrder("pay_invoice", "invoice_status");
        // READ tools plus the single dependent proposal the confirmed CRITICAL action allows; nothing hidden reappears.
        assertThat(toolNames(M.createObjectNode().set("tools", continuation.turn().toolsJson())))
                .containsExactlyInAnyOrder(ToolExecutionService.TOOL_SEARCH, "invoice_status", "pay_invoice");
    }
}
