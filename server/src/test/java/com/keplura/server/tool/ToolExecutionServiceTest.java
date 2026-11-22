package com.keplura.server.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Central execution path (TU-01): every blocked case must end WITHOUT an external call.
 * {@link FakeAdapter#invocations} stands for the external system.
 */
class ToolExecutionServiceTest {

    private static final JsonMapper M = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = M.readTree("""
            {"type":"object","properties":{"message":{"type":"string"}},"required":["message"],"additionalProperties":false}""");

    private JdbcTemplate jdbc;
    private FakeAdapter adapter;
    private ToolExecutionService service;
    private final AtomicLong clock = new AtomicLong();
    private long user;
    private long orgId;
    private long space;
    private long chat;

    static class FakeAdapter implements ToolAdapter {
        final AtomicInteger invocations = new AtomicInteger();
        ToolRisk risk = ToolRisk.READ;
        boolean approved = true;
        JsonNode schema = SCHEMA;
        String fingerprint = "fp-1";
        String result = "ok";
        RuntimeException failure;
        Consumer<FakeAdapter> duringInvoke = a -> { };
        List<ToolDefinition> extra = new ArrayList<>();

        @Override public String kind() { return "fake"; }

        @Override public List<ToolDefinition> definitions(Long spaceId) {
            List<ToolDefinition> out = new ArrayList<>();
            out.add(def("echo", risk, approved));
            out.addAll(extra);
            return out;
        }

        ToolDefinition def(String name, ToolRisk r, boolean ok) {
            return new ToolDefinition("fake:1:" + name, "fake", "f__" + name, name, 1L, "Fake", "desc", schema, r, ok, fingerprint);
        }

        @Override public String invoke(ToolDefinition tool, JsonNode arguments) {
            invocations.incrementAndGet();
            duringInvoke.accept(this);
            if (failure != null) throw failure;
            return result;
        }
    }

    @BeforeEach void setup() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tool-exec-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        user = insertUser("a@example.com");
        orgId = id("insert into organization (name, created_at) values ('Org', current_timestamp)");
        jdbc.update("insert into membership (org_id, user_id, role, created_at) values (?, ?, 'OWNER', current_timestamp)", orgId, user);
        space = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Raum', current_timestamp)");
        chat = id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) values ("
                + orgId + ", " + space + ", " + user + ", 'Chat', current_timestamp, current_timestamp)");
        adapter = new FakeAdapter();
        service = new ToolExecutionService(jdbc, List.of(adapter), new ToolAuditLog(jdbc), clock::get);
    }

    private long nextUser = 4711;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"{\"n\":1e400}","{\"n\":-1e400}","{\"n\":NaN}","{\"n\":Infinity}",
            "{\"n\":-Infinity}","{\"n\":[{\"nested\":1e400}]}"})
    void rawNonFiniteNumbersCannotBecomeExecutableStrings(String args) {
        adapter.schema=M.readTree("{\"type\":\"object\"}");
        var turn=service.openTurn(service.resolveContext(user,orgId,chat));
        var result=turn.call("f__echo",args);
        assertThat(result.invoked()).isFalse();
        assertThat(adapter.invocations.get()).isZero();
        assertThat(service.cards(user,orgId,chat)).noneMatch(c -> c.status().equals("PENDING") || c.status().equals("SUCCEEDED"));
    }

    /** membership/chat_conversation carry plain user ids (no FK, V1 baseline), so no app_user row is needed. */
    private long insertUser(String email) {
        return nextUser++;
    }

    private long id(String sql) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement(sql, new String[]{"id"}), keys);
        return keys.getKey().longValue();
    }

    private ToolExecutionService.ToolTurn turn() {
        return service.openTurn(service.resolveContext(user, orgId, chat));
    }

    private int auditRows() {
        return jdbc.queryForObject("select count(*) from tool_call_log", Integer.class);
    }

    @Test void approvedReadToolRunsWithAuditBeforeExecution() {
        adapter.duringInvoke = a -> assertThat(jdbc.queryForObject(
                "select count(*) from tool_call_log where ok = false and risk = 'READ'", Integer.class)).isEqualTo(1);
        var outcome = turn().call("f__echo", "{\"message\":\"hi\"}");
        assertThat(outcome.invoked()).isTrue();
        assertThat(outcome.text()).contains("Fremdinhalt", "ok");
        assertThat(adapter.invocations).hasValue(1);
        var row = jdbc.queryForMap("select * from tool_call_log");
        assertThat(row.get("OK")).isEqualTo(true);
        assertThat(row.get("TOOL_ID")).isEqualTo("fake:1:echo");
        assertThat(row.get("USER_ID")).isEqualTo(user);
        assertThat(row.get("SPACE_ID")).isEqualTo(space);
    }

    @Test void approvedWriteToolRuns() {
        adapter.risk = ToolRisk.WRITE;
        assertThat(turn().call("f__echo", "{\"message\":\"hi\"}").invoked()).isTrue();
        assertThat(adapter.invocations).hasValue(1);
    }

    @Test void unclassifiedAndUnapprovedToolsAreNotOfferedNorExecuted_criticalOnlyPlanned() {
        adapter.extra = List.of(adapter.def("unclassified", null, true), adapter.def("pending", ToolRisk.WRITE, false),
                adapter.def("critical", ToolRisk.CRITICAL, true));
        var turn = turn();
        // TU-02: an approved CRITICAL tool is offered, but the turn only plans it (pending card)
        assertThat(turn.offered()).containsOnlyKeys("f__echo", "f__critical");
        for (String fn : List.of("f__unclassified", "f__pending", "f__critical")) {
            assertThat(turn.call(fn, "{\"message\":\"hi\"}").invoked()).isFalse();
        }
        assertThat(adapter.invocations).hasValue(0);
        assertThat(auditRows()).isZero();
        // openTurn without a question id: a CRITICAL plan cannot even become a pending card
        assertThat(jdbc.queryForObject("select status from tool_call where function_name = 'f__critical'", String.class))
                .isEqualTo("BLOCKED");
    }

    @Test void criticalCanNeverRunInsideTheTurnEvenIfReclassifiedMeanwhile() {
        var turn = turn();
        adapter.risk = ToolRisk.CRITICAL; // reclassified while the model was thinking
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").invoked()).isFalse();
        assertThat(adapter.invocations).hasValue(0);
        assertThat(ToolExecutionService.blockReason(adapter.def("x", ToolRisk.CRITICAL, true))).isNull(); // offerable
    }

    @Test void unsupportedSchemaIsNotOffered() {
        adapter.schema = M.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/x\"}}}");
        assertThat(turn().offered()).isEmpty();
    }

    @Test void invalidArgumentsNeverReachTheTool() {
        var turn = turn();
        for (String args : List.of("{}", "{\"message\":1}", "{\"message\":\"x\",\"more\":1}", "[1]", "not json",
                "{\"message\":\"" + "x".repeat(ToolExecutionService.MAX_ARGUMENT_CHARS) + "\"}")) {
            var outcome = turn.call("f__echo", args);
            assertThat(outcome.invoked()).as(args).isFalse();
            assertThat(outcome.text()).as(args).isNotBlank();
        }
        assertThat(adapter.invocations).hasValue(0);
        assertThat(auditRows()).isZero();
    }

    @Test void foreignOrLostContextIsRejected() {
        long other = insertUser("b@example.com");
        assertThat(service.resolveContext(other, orgId, chat)).isNull();
        long otherOrg = id("insert into organization (name, created_at) values ('Other', current_timestamp)");
        assertThat(service.resolveContext(user, otherOrg, chat)).isNull();
        assertThat(service.openTurn(null).offered()).isEmpty();

        var turn = turn();
        jdbc.update("delete from membership where user_id = ?", user); // access revoked during the model request
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").invoked()).isFalse();
        assertThat(adapter.invocations).hasValue(0);
    }

    @Test void conversationMovedToAnotherSpaceIsRejected() {
        var turn = turn();
        long otherSpace = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Zwei', current_timestamp)");
        jdbc.update("update chat_conversation set space_id = ? where id = ?", otherSpace, chat);
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").invoked()).isFalse();
        assertThat(adapter.invocations).hasValue(0);
    }

    @Test void revokedApprovalOrChangedTargetPreventsExecution() {
        var turn = turn();
        adapter.approved = false;
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").invoked()).isFalse();
        adapter.approved = true;
        adapter.fingerprint = "fp-2";
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").invoked()).isFalse();
        adapter.fingerprint = "fp-1";
        adapter.risk = null;
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").invoked()).isFalse();
        assertThat(adapter.invocations).hasValue(0);
    }

    @Test void missingAuditStoragePreventsExecution() {
        var turn = turn();
        jdbc.execute("drop table tool_call_log");
        var outcome = turn.call("f__echo", "{\"message\":\"hi\"}");
        assertThat(outcome.invoked()).isFalse();
        assertThat(adapter.invocations).hasValue(0);
    }

    @Test void atMostEightCallsPerTurn() {
        var turn = turn();
        for (int i = 0; i < 10; i++) turn.call("f__echo", "{\"message\":\"hi\"}");
        assertThat(adapter.invocations).hasValue(ToolExecutionService.MAX_CALLS_PER_TURN);
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").text()).contains("Höchstzahl");
    }

    @Test void blockedAttemptsAlsoCountTowardsTheLimit() {
        var turn = turn();
        for (int i = 0; i < 8; i++) turn.call("unknown", "{}");
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").invoked()).isFalse();
        assertThat(adapter.invocations).hasValue(0);
    }

    @Test void exhaustedTimeBudgetBlocksFurtherCalls() {
        adapter.duringInvoke = a -> clock.addAndGet(ToolExecutionService.MAX_TOOL_TIME_PER_TURN.toNanos());
        var turn = turn();
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").invoked()).isTrue();
        var second = turn.call("f__echo", "{\"message\":\"hi\"}");
        assertThat(second.invoked()).isFalse();
        assertThat(second.text()).contains("Zeitbudget");
        assertThat(adapter.invocations).hasValue(1);
    }

    @Test void resultsAreCappedAndFailuresStayGenericWithoutRetry() {
        adapter.result = "x".repeat(ToolExecutionService.MAX_RESULT_CHARS + 50);
        var turn = turn();
        assertThat(turn.call("f__echo", "{\"message\":\"hi\"}").text()).contains("[gekürzt]")
                .hasSizeLessThan(ToolExecutionService.MAX_RESULT_CHARS + 200);
        adapter.failure = new IllegalStateException("internal host 10.0.0.5 secret");
        var failed = turn.call("f__echo", "{\"message\":\"hi\"}");
        assertThat(failed.invoked()).isTrue();
        assertThat(failed.text()).doesNotContain("10.0.0.5", "secret");
        assertThat(adapter.invocations).hasValue(2); // exactly once per call, never repeated
        assertThat(jdbc.queryForObject("select count(*) from tool_call_log where ok = false and error is not null", Integer.class))
                .isEqualTo(1);
    }

    @Test void notStartedFailuresAreReportedAsNotInvoked() {
        adapter.failure = new ToolAdapter.ToolNotStartedException("Ziel geändert.");
        var outcome = turn().call("f__echo", "{\"message\":\"hi\"}");
        assertThat(outcome.invoked()).isFalse();
    }

    @Test void adapterOutageFailsClosed() {
        ToolAdapter broken = new FakeAdapter() {
            @Override public String kind() { return "broken"; }
            @Override public List<ToolDefinition> definitions(Long spaceId) { throw new IllegalStateException("db down"); }
        };
        var svc = new ToolExecutionService(jdbc, List.of(broken, adapter), new ToolAuditLog(jdbc), clock::get);
        assertThat(svc.openTurn(svc.resolveContext(user, orgId, chat)).offered()).containsOnlyKeys("f__echo");
    }

    @Test void toolsJsonOffersSchemaAndSafeDefault() {
        adapter.schema = null;
        var json = turn().toolsJson();
        assertThat(json).hasSize(1);
        assertThat(json.get(0).path("function").path("name").asString()).isEqualTo("f__echo");
        assertThat(json.get(0).path("function").path("parameters").path("additionalProperties").asBoolean(true)).isFalse();
    }
}
