package com.keplura.server.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** TU-05b: the answering chat model narrows the catalog (OFF / default kinds); nothing widens approvals. */
class ModelToolDefaultsTest {

    private static final JsonMapper M = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = M.readTree("""
            {"type":"object","properties":{"message":{"type":"string"}},"required":["message"],"additionalProperties":false}""");

    private JdbcTemplate jdbc;
    private ToolExecutionService service;
    private final Kind alpha = new Kind("alpha");
    private final Kind beta = new Kind("beta");
    private long user = 4711;
    private long orgId;
    private long chat;
    private long provider;

    static final class Kind implements ToolAdapter {
        final String kind;
        final AtomicInteger invocations = new AtomicInteger();
        Kind(String kind) { this.kind = kind; }
        @Override public String kind() { return kind; }
        @Override public List<ToolDefinition> definitions(Long spaceId) {
            return List.of(new ToolDefinition(kind + ":1:run", kind, kind + "_run", "run", 1L, kind, "desc", SCHEMA,
                    ToolRisk.READ, true, "fp"));
        }
        @Override public String invoke(ToolDefinition tool, JsonNode arguments) {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    @BeforeEach void setup() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:model-tools-" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        orgId = id("insert into organization (name, created_at) values ('Org', current_timestamp)");
        jdbc.update("insert into membership (org_id, user_id, role, created_at) values (?, ?, 'OWNER', current_timestamp)", orgId, user);
        long space = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Raum', current_timestamp)");
        chat = id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) values ("
                + orgId + ", " + space + ", " + user + ", 'Chat', current_timestamp, current_timestamp)");
        // H2 cannot evaluate the V10 IN-list CHECKs on the migrated schema (PostgreSQL can); the types are irrelevant here.
        jdbc.update("alter table ai_provider drop constraint ai_provider_type_check");
        jdbc.update("alter table ai_model drop constraint ai_model_type_check");
        jdbc.update("insert into ai_provider (name, provider_type, base_url, created_at, updated_at) values "
                + "(?, ?, ?, current_timestamp, current_timestamp)", "P", "ollama", "http://127.0.0.1:1");
        provider = jdbc.queryForObject("select id from ai_provider where name='P'", Long.class);
        service = new ToolExecutionService(jdbc, List.of(alpha, beta), new ToolAuditLog(jdbc), () -> 0L);
    }

    private long id(String sql) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement(sql, new String[]{"id"}), keys);
        return keys.getKey().longValue();
    }

    private long defaultModel(String toolUse, String defaults) {
        long model = id("insert into ai_model (provider_id, name, technical_model_id, model_type, created_at, updated_at) values ("
                + provider + ", 'Chat', 'm', 'CHAT', current_timestamp, current_timestamp)");
        jdbc.update("update ai_model set is_default=true, default_slot=id, tool_use=?, tool_defaults_json=? where id=?",
                toolUse, defaults, model);
        return model;
    }

    private ToolExecutionService.ToolTurn turn() {
        return service.openTurn(service.resolveContext(user, orgId, chat));
    }

    @Test void withoutADatabaseModelEverythingApprovedStaysOffered() {
        assertThat(turn().offered()).containsOnlyKeys("alpha_run", "beta_run");
        var view = service.chatToolSelection(user, orgId, chat);
        assertThat(view.modelDefaultIds()).isNull();
        assertThat(view.modelToolsOff()).isFalse();
    }

    @Test void offNeverOffersToolsBlocksPlannedCallsAndCannotBeOverriddenByAChatSelection() {
        long model = defaultModel("AUTO", null);
        var opened = turn();
        assertThat(opened.offered()).containsOnlyKeys("alpha_run", "beta_run");
        jdbc.update("update ai_model set tool_use='OFF' where id=?", model);

        var blocked = opened.call("alpha_run", "{\"message\":\"x\"}");
        assertThat(blocked.invoked()).isFalse();
        assertThat(blocked.text()).isEqualTo(ToolExecutionService.MODEL_OFF);
        assertThat(turn().offered()).isEmpty();
        assertThat(service.hasTools(user, orgId, chat)).isFalse();

        service.setChatToolSelection(user, orgId, chat, M.readTree("{\"selectedIds\":[\"alpha:1:run\"]}"));
        assertThat(turn().offered()).isEmpty();
        var view = service.chatToolSelection(user, orgId, chat);
        assertThat(view.modelToolsOff()).isTrue();
        assertThat(view.modelDefaultIds()).isEmpty();
        assertThat(alpha.invocations).hasValue(0);
    }

    @Test void defaultKindsApplyOnlyWithoutAnOwnChatSelectionAndAreRechecked() {
        long model = defaultModel("AUTO", "[\"beta\"]");
        assertThat(turn().offered()).containsOnlyKeys("beta_run");
        assertThat(service.chatToolSelection(user, orgId, chat).modelDefaultIds()).containsExactly("beta:1:run");
        assertThat(turn().call("alpha_run", "{\"message\":\"x\"}").invoked()).isFalse();

        var opened = turn();
        jdbc.update("update ai_model set tool_defaults_json='[\"alpha\"]' where id=?", model);
        assertThat(opened.call("beta_run", "{\"message\":\"x\"}").invoked()).isFalse();
        assertThat(beta.invocations).hasValue(0);

        // An own selection replaces the default kinds, but still only within the approved catalog.
        service.setChatToolSelection(user, orgId, chat, M.readTree("{\"selectedIds\":[\"beta:1:run\"]}"));
        var selected = turn();
        assertThat(selected.offered()).containsOnlyKeys("beta_run");
        assertThat(selected.call("beta_run", "{\"message\":\"x\"}").invoked()).isTrue();
        assertThat(beta.invocations).hasValue(1);
    }

    @Test void emptyDefaultsOfferNothingAndUnreadablePoliciesFailClosed() {
        long model = defaultModel("AUTO", "[]");
        assertThat(turn().offered()).isEmpty();
        jdbc.update("update ai_model set tool_defaults_json='{\"not\":\"a list\"}' where id=?", model);
        assertThat(turn().offered()).isEmpty();
        assertThat(service.chatToolSelection(user, orgId, chat).modelToolsOff()).isTrue();
    }

    @Test void aDisabledModelOrProviderFallsBackToTheUnrestrictedEnvironmentPath() {
        long model = defaultModel("OFF", null);
        assertThat(turn().offered()).isEmpty();
        jdbc.update("update ai_provider set enabled=false where id=?", provider);
        assertThat(turn().offered()).containsOnlyKeys("alpha_run", "beta_run");
        jdbc.update("update ai_provider set enabled=true where id=?", provider);
        jdbc.update("update ai_model set enabled=false, is_default=false, default_slot=null where id=?", model);
        assertThat(turn().offered()).containsOnlyKeys("alpha_run", "beta_run");
    }
}
