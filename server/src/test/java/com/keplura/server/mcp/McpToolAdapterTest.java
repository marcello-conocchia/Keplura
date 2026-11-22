package com.keplura.server.mcp;

import com.keplura.server.tool.ToolAdapter;
import com.keplura.server.tool.ToolDefinition;
import com.keplura.server.tool.ToolRisk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** MCP behind the common tool contract (TU-01): adapter output and admin classification rules. */
class McpToolAdapterTest {

    private static final String ECHO = "[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}},"
            + "{\"name\":\"rx\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"x\"}}}}]";

    private JdbcTemplate jdbc;
    private McpClient client;
    private McpToolAdapter adapter;
    private McpAdminController admin;
    private long serverId;

    @BeforeEach void setup() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mcp-adapter-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("""
                insert into mcp_server (name,url,enabled,tools_json,enabled_tools,created_at,all_spaces)
                values ('Testserver','http://localhost/mcp',true,?, 'echo',current_timestamp,true)""", ECHO);
        serverId = jdbc.queryForObject("select id from mcp_server", Long.class);
        client = mock(McpClient.class);
        adapter = new McpToolAdapter(jdbc, null, client);
        admin = new McpAdminController(jdbc, null, client, new DataSourceTransactionManager(ds));
    }

    private ToolDefinition echo() {
        return adapter.definitions(1L).stream().filter(d -> d.toolName().equals("echo")).findFirst().orElseThrow();
    }

    private McpAdminController.ToolView view(String tool) {
        return admin.list().getFirst().tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
    }

    private void patch(List<String> enabled, Map<String, String> risks) {
        admin.update(serverId, new McpAdminController.Input(null, null, null, null, enabled, null, null, risks));
    }

    @Test void legacyApprovalWithoutRiskClassStaysBlocked() {
        ToolDefinition d = echo();
        assertThat(d.id()).isEqualTo("mcp:" + serverId + ":echo");
        assertThat(d.functionName()).isEqualTo("s" + serverId + "__echo");
        assertThat(d.approved()).isTrue();
        assertThat(d.risk()).isNull(); // V25 does NOT turn old approvals into READ
        var v = view("echo");
        assertThat(v.enabled()).isFalse();
        assertThat(v.legacyApproval()).isTrue();
    }

    @Test void classificationChangeRevokesApprovalUntilExplicitlyApprovedAgain() {
        patch(null, Map.of("echo", "READ"));
        assertThat(echo().risk()).isEqualTo(ToolRisk.READ);
        assertThat(echo().approved()).isFalse();
        patch(List.of("echo"), null);
        assertThat(echo().approved()).isTrue();
        assertThat(view("echo").enabled()).isTrue();
        patch(null, Map.of("echo", "WRITE"));
        assertThat(echo().approved()).isFalse();
        patch(null, Map.of("echo", "WRITE")); // same class again: no change, no new approval
        assertThat(echo().approved()).isFalse();
    }

    @Test void unclassifiedOrUnsupportedToolsCannotBeApproved_criticalCanSinceTu02() {
        patch(List.of("echo", "rx"), null);
        assertThat(echo().approved()).isFalse(); // unclassified
        patch(null, Map.of("echo", "CRITICAL", "rx", "READ"));
        patch(List.of("echo", "rx"), null);
        assertThat(echo().approved()).isTrue(); // CRITICAL: approvable, still runs only after per-call confirmation
        assertThat(view("echo").enabled()).isTrue();
        assertThat(view("rx").schemaSupported()).isFalse();
        assertThat(view("rx").enabled()).isFalse();
    }

    @Test void unknownRiskClassIsRejectedWithoutChanges() {
        var response = admin.update(serverId, new McpAdminController.Input(null, null, null, null, null, null, null,
                Map.of("echo", "SAFE")));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(echo().risk()).isNull();
    }

    @Test void changedTargetClearsClassesAndApprovals() {
        patch(null, Map.of("echo", "READ"));
        patch(List.of("echo"), null);
        admin.update(serverId, new McpAdminController.Input(null, "http://localhost/other", null, null, null, null, null, null));
        assertThat(jdbc.queryForObject("select tool_risks from mcp_server", String.class)).isNull();
        assertThat(adapter.definitions(1L)).isEmpty();
    }

    @Test void refreshDropsClassAndApprovalOfChangedToolDefinitions() {
        patch(null, Map.of("echo", "READ"));
        patch(List.of("echo"), null);
        var schema = tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"type\":\"object\"}");
        when(client.listTools(anyString(), anyString())).thenReturn(List.of(new McpClient.Tool("echo", "Echo", schema)));
        admin.refreshEndpoint(serverId);
        assertThat(echo().risk()).isEqualTo(ToolRisk.READ);
        assertThat(echo().approved()).isTrue();
        when(client.listTools(anyString(), anyString())).thenReturn(List.of(new McpClient.Tool("echo", "Echo – jetzt mit Löschen", schema)));
        admin.refreshEndpoint(serverId);
        assertThat(echo().risk()).isNull();
        assertThat(echo().approved()).isFalse();
    }

    @Test void invokeRefusesChangedTargetBeforeAnyNetworkCall() {
        ToolDefinition d = echo();
        jdbc.update("update mcp_server set url = 'http://localhost/elsewhere'");
        assertThatThrownBy(() -> adapter.invoke(new com.keplura.server.tool.ToolContext(1L,1L,1L,1L),d, null)).isInstanceOf(ToolAdapter.ToolNotStartedException.class);
        jdbc.update("update mcp_server set url = 'http://localhost/mcp', enabled = false");
        assertThatThrownBy(() -> adapter.invoke(new com.keplura.server.tool.ToolContext(1L,1L,1L,1L),d, null)).isInstanceOf(ToolAdapter.ToolNotStartedException.class);
        verifyNoInteractions(client);
    }

    @Test void invokeMapsClientErrorsToSafeMessages() {
        when(client.callTool(anyString(), anyString(), anyString(), any())).thenThrow(new McpClient.McpException("Zugriff abgelehnt."));
        assertThatThrownBy(() -> adapter.invoke(new com.keplura.server.tool.ToolContext(1L,1L,1L,1L),echo(), null)).isInstanceOf(ToolAdapter.ToolInvocationException.class)
                .isNotInstanceOf(ToolAdapter.ToolNotStartedException.class).hasMessage("Zugriff abgelehnt.");
    }

    @Test void disabledServerOrOtherSpaceOffersNothing() {
        jdbc.update("update mcp_server set all_spaces = false");
        assertThat(adapter.definitions(1L)).isEmpty();
        jdbc.update("update mcp_server set all_spaces = true, enabled = false");
        assertThat(adapter.definitions(1L)).isEmpty();
        assertThat(adapter.definitions(null)).isEmpty();
    }

    @Test void journalReadsCentralLogWithIsoTimestamps() {
        jdbc.update("""
                insert into tool_call_log (called_at, user_id, org_id, space_id, conversation_id, adapter, tool_id, source_id,
                  source_name, tool_name, risk, duration_ms, ok, error)
                values (current_timestamp, 1, 1, 1, 1, 'mcp', 'mcp:1:echo', 1, 'Testserver', 'echo', 'READ', 3, true, null)""");
        var calls = admin.calls();
        assertThat(calls).hasSize(1);
        assertThat(java.time.Instant.parse((String) calls.getFirst().get("called_at"))).isNotNull();
        assertThat(calls.getFirst()).containsEntry("server_name", "Testserver").containsEntry("risk", "READ");
    }

    @Test void functionNamesDoNotCollideAfterSanitizingOrTruncation() {
        assertThat(McpToolAdapter.functionName(1L, "a.b")).isNotEqualTo(McpToolAdapter.functionName(1L, "a_b"));
        String first = McpToolAdapter.functionName(1L, "a".repeat(100) + "x");
        String second = McpToolAdapter.functionName(1L, "a".repeat(100) + "y");
        assertThat(first).matches("[a-zA-Z0-9_-]{1,64}").isNotEqualTo(second);
    }
}
