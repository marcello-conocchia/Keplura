package com.keplura.server.mcp;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpAdminConcurrencyTest {
    private JdbcTemplate jdbc;
    private McpClient client;
    private McpAdminController controller;

    @BeforeEach void setup() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mcp-admin-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        client = mock(McpClient.class);
        controller = new McpAdminController(jdbc, null, client,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        when(client.listTools(anyString(), anyString())).thenReturn(java.util.List.of(new McpClient.Tool("echo", "Echo", null)));
    }

    private McpAdminController.ServerView create(String name) {
        return (McpAdminController.ServerView) controller.create(new McpAdminController.Input(
                name, "http://localhost/mcp", null, true, null, null, null)).getBody();
    }

    @Test void concurrentCreationReturnsEachOwnNameAndGeneratedId() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<McpAdminController.ServerView>>();
            for (int n = 0; n < 6; n++) { final int i = n; futures.add(pool.submit(() -> create("Server-" + i))); }
            var ids = new java.util.HashSet<Long>();
            for (int n = 0; n < 6; n++) {
                var server = futures.get(n).get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(server.name()).isEqualTo("Server-" + n);
                assertThat(ids.add(server.id())).isTrue();
                assertThat(server.tools()).noneMatch(McpAdminController.ToolView::enabled);
            }
        }
    }

    @Test void refreshCannotRestorePermissionRevokedWhileNetworkRequestRuns() {
        var server = create("Server");
        jdbc.update("update mcp_server set enabled_tools = 'echo' where id = ?", server.id());
        when(client.listTools(anyString(), anyString())).thenAnswer(invocation -> {
            jdbc.update("update mcp_server set enabled_tools = null where id = ?", server.id());
            return java.util.List.of(new McpClient.Tool("echo", "Echo", null));
        });
        var refreshed = (McpAdminController.ServerView) controller.refreshEndpoint(server.id()).getBody();
        assertThat(refreshed.tools()).noneMatch(McpAdminController.ToolView::enabled);
    }

    @Test void refreshCannotAttachOldMetadataToChangedEndpoint() {
        var server = create("Server");
        when(client.listTools(anyString(), anyString())).thenAnswer(invocation -> {
            jdbc.update("update mcp_server set url = 'http://other/mcp', tools_json = null, enabled_tools = null where id = ?", server.id());
            return java.util.List.of(new McpClient.Tool("echo", "Echo", null));
        });
        var refreshed = (McpAdminController.ServerView) controller.refreshEndpoint(server.id()).getBody();
        assertThat(refreshed.url()).isEqualTo("http://other/mcp");
        assertThat(refreshed.tools()).isEmpty();
    }
}
