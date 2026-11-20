package com.keplura.server.mcp;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpAuditTest {
    private JdbcTemplate jdbc;
    private McpClient client;
    private McpToolService service;
    private McpToolService.ToolRef tool;

    @BeforeEach void setup() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mcp-audit-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("""
                insert into mcp_server (name,url,enabled,tools_json,enabled_tools,created_at,all_spaces)
                values ('Testserver','http://unused',true,?, 'echo',current_timestamp,true)""",
                "[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]");
        client = mock(McpClient.class);
        service = new McpToolService(jdbc, null, client);
        tool = service.toolsFor(1L).values().iterator().next();
    }

    @Test void auditIsCommittedBeforeToolExecutionAndTimestampIsIso() {
        when(client.callTool(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            assertThat(jdbc.queryForObject("select count(*) from mcp_call_log where ok = false", Integer.class)).isEqualTo(1);
            return "Antwort";
        });
        assertThat(service.call(tool, null, 1L, 1L, 1L, 1L).invoked()).isTrue();
        assertThat(jdbc.queryForObject("select ok from mcp_call_log", Boolean.class)).isTrue();
        var calls = new McpAdminController(jdbc, null, client,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).calls();
        assertThat(java.time.Instant.parse((String) calls.getFirst().get("called_at"))).isNotNull();
    }

    @Test void missingAuditStoragePreventsExecution() {
        jdbc.execute("drop table mcp_call_log");
        assertThat(service.call(tool, null, 1L, 1L, 1L, 1L).invoked()).isFalse();
        verifyNoInteractions(client);
    }

    @Test void permissionRevokedDuringModelRequestPreventsExecution() {
        jdbc.update("update mcp_server set enabled_tools = null");
        assertThat(service.call(tool, null, 1L, 1L, 1L, 1L).invoked()).isFalse();
        verifyNoInteractions(client);
    }

    @Test void functionNamesDoNotCollideAfterSanitizingOrTruncation() {
        assertThat(McpToolService.functionName(1L, "a.b")).isNotEqualTo(McpToolService.functionName(1L, "a_b"));
        String first = McpToolService.functionName(1L, "a".repeat(100) + "x");
        String second = McpToolService.functionName(1L, "a".repeat(100) + "y");
        assertThat(first).matches("[a-zA-Z0-9_-]{1,64}").isNotEqualTo(second);
    }
}
