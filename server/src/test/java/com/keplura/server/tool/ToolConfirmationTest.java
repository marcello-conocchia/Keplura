package com.keplura.server.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TU-02: cards and concrete confirmations. {@link Fake#invocations} stands for the external
 * system — every blocked, rejected, expired, replayed or racing decision must leave it untouched,
 * and a confirmed call must run exactly once with exactly the stored arguments.
 */
class ToolConfirmationTest {

    private static final JsonMapper M = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = M.readTree("""
            {"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}""");

    static class Fake implements ToolAdapter {
        final AtomicInteger invocations = new AtomicInteger();
        final List<JsonNode> received = new CopyOnWriteArrayList<>();
        volatile ToolRisk risk = ToolRisk.CRITICAL;
        volatile boolean approved = true;
        volatile String fingerprint = "fp-1";
        volatile JsonNode schema = SCHEMA;
        volatile RuntimeException failure;

        @Override public String kind() { return "fake"; }

        @Override public List<ToolDefinition> definitions(Long spaceId) {
            List<ToolDefinition> out = new ArrayList<>();
            out.add(new ToolDefinition("fake:1:delete", "fake", "f__delete", "delete", 1L, "Fake", "Löscht", schema, risk,
                    approved, fingerprint));
            out.add(new ToolDefinition("fake:1:read", "fake", "f__read", "read", 1L, "Fake", "Liest", SCHEMA, ToolRisk.READ,
                    true, fingerprint));
            return out;
        }

        @Override public String invoke(ToolDefinition tool, JsonNode arguments) {
            invocations.incrementAndGet();
            received.add(arguments);
            if (failure != null) throw failure;
            return "erledigt " + arguments.path("id").asString("");
        }
    }

    private JdbcTemplate jdbc;
    private Fake fake;
    private ToolExecutionService service;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));
    private final long user = 4711;
    private long orgId;
    private long chat;

    @BeforeEach void setup() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tool-confirm-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        orgId = id("insert into organization (name, created_at) values ('Org', current_timestamp)");
        jdbc.update("insert into membership (org_id, user_id, role, created_at) values (?, ?, 'OWNER', current_timestamp)", orgId, user);
        long space = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Raum', current_timestamp)");
        chat = id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) values ("
                + orgId + ", " + space + ", " + user + ", 'Chat', current_timestamp, current_timestamp)");
        fake = new Fake();
        service = new ToolExecutionService(jdbc, List.of(fake), new ToolAuditLog(jdbc), new ToolCallStore(jdbc),
                System::nanoTime, now::get);
    }

    private long id(String sql) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement(sql, new String[]{"id"}), keys);
        return keys.getKey().longValue();
    }

    private ToolExecutionService.ToolTurn turn() {
        return service.openTurn(service.resolveContext(user, orgId, chat), 99L);
    }

    /** Plans one CRITICAL call and returns its pending card. */
    private ToolExecutionService.ToolCallView planCritical(String id) {
        var outcome = turn().call("f__delete", "{\"id\":\"" + id + "\"}");
        assertThat(outcome.invoked()).isFalse();
        assertThat(outcome.text()).contains("NOCH NICHT ausgeführt");
        var cards = service.cards(user, orgId, chat);
        return cards.getLast();
    }

    private ToolExecutionService.Decision confirm(String cardId) {
        return service.decide(user, orgId, chat, cardId, true);
    }

    @Test void criticalIsOfferedButOnlyPlannedInTheTurn() {
        assertThat(turn().offered()).containsKeys("f__delete", "f__read");
        var card = planCritical("T-1");
        assertThat(card.status()).isEqualTo("PENDING");
        assertThat(card.needsConfirmation()).isTrue();
        assertThat(card.risk()).isEqualTo("CRITICAL");
        assertThat(card.promptMessageId()).isEqualTo(99L);
        assertThat(card.arguments().path("id").asString()).isEqualTo("T-1");
        assertThat(card.expiresAt()).isEqualTo(now.get().plus(ToolCallStore.CONFIRMATION_TTL));
        assertThat(fake.invocations).hasValue(0);
        assertThat(jdbc.queryForObject("select count(*) from tool_call_log", Integer.class)).isZero();
    }

    @Test void confirmationRunsExactlyTheStoredCallOnceWithAuditFirst() {
        var card = planCritical("T-7");
        var decision = confirm(card.id());
        assertThat(decision).isInstanceOf(ToolExecutionService.Decision.Done.class);
        var done = ((ToolExecutionService.Decision.Done) decision).card();
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.result()).isEqualTo("erledigt T-7");
        assertThat(fake.invocations).hasValue(1);
        assertThat(fake.received.getFirst().toString()).isEqualTo("{\"id\":\"T-7\"}");
        var audit = jdbc.queryForMap("select ok, risk, tool_call_id from tool_call_log");
        assertThat(audit.get("OK")).isEqualTo(true);
        assertThat(audit.get("RISK")).isEqualTo("CRITICAL");
        assertThat(audit.get("TOOL_CALL_ID")).isNotNull();
        // replay of the same HTTP decision: conflict, never a second external call
        assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(service.decide(user, orgId, chat, card.id(), false)).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(fake.invocations).hasValue(1);
    }

    @Test void rejectionEndsTheCallWithoutExternalCall() {
        var card = planCritical("T-1");
        var rejected = service.decide(user, orgId, chat, card.id(), false);
        assertThat(((ToolExecutionService.Decision.Done) rejected).card().status()).isEqualTo("REJECTED");
        assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void expiredConfirmationIsNeverExecuted() {
        var card = planCritical("T-1");
        now.set(now.get().plus(ToolCallStore.CONFIRMATION_TTL));
        assertThat(service.cards(user, orgId, chat).getLast().status()).isEqualTo("EXPIRED");
        var decision = confirm(card.id());
        assertThat(decision).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(((ToolExecutionService.Decision.Conflict) decision).card().status()).isEqualTo("EXPIRED");
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void parallelConfirmationsAndRejectionsCauseAtMostOneCall() throws Exception {
        var card = planCritical("T-1");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(12)) {
            List<Future<ToolExecutionService.Decision>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                boolean yes = i % 3 != 0;
                results.add(pool.submit(() -> { start.await(); return service.decide(user, orgId, chat, card.id(), yes); }));
            }
            start.countDown();
            int done = 0;
            for (var f : results) if (f.get(20, TimeUnit.SECONDS) instanceof ToolExecutionService.Decision.Done) done++;
            assertThat(done).isEqualTo(1);
        }
        assertThat(fake.invocations.get()).isLessThanOrEqualTo(1);
        String status = service.cards(user, orgId, chat).getLast().status();
        assertThat(status).isIn("SUCCEEDED", "REJECTED");
        assertThat(fake.invocations.get()).isEqualTo(status.equals("SUCCEEDED") ? 1 : 0);
    }

    @Test void foreignPeopleContextsAndIdsCannotSeeOrDecide() {
        var card = planCritical("T-1");
        long otherOrg = id("insert into organization (name, created_at) values ('Other', current_timestamp)");
        long otherChat = id("insert into chat_conversation (org_id, space_id, created_by, title, created_at, updated_at) "
                + "select org_id, space_id, created_by, 'B', current_timestamp, current_timestamp from chat_conversation where id = " + chat);
        assertThat(service.decide(4712L, orgId, chat, card.id(), true)).isInstanceOf(ToolExecutionService.Decision.NotFound.class);
        assertThat(service.decide(user, otherOrg, chat, card.id(), true)).isInstanceOf(ToolExecutionService.Decision.NotFound.class);
        assertThat(service.decide(user, orgId, otherChat, card.id(), true)).isInstanceOf(ToolExecutionService.Decision.NotFound.class);
        assertThat(service.decide(user, orgId, chat, "00000000-0000-0000-0000-000000000000", true))
                .isInstanceOf(ToolExecutionService.Decision.NotFound.class);
        assertThat(service.decide(user, orgId, chat, "x".repeat(500), true)).isInstanceOf(ToolExecutionService.Decision.NotFound.class);
        assertThat(service.cards(4712L, orgId, chat)).isEmpty();
        assertThat(service.cards(user, orgId, otherChat)).isEmpty();
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void revokedMembershipPreventsExecution() {
        var card = planCritical("T-1");
        jdbc.update("delete from membership where user_id = ?", user);
        assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.NotFound.class);
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void anyChangeOfApprovalClassDefinitionOrTargetInvalidatesTheConfirmation() {
        List<Runnable> changes = List.of(
                () -> fake.approved = false,
                () -> fake.risk = ToolRisk.READ,
                () -> fake.fingerprint = "fp-2",
                () -> fake.schema = M.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}"));
        for (Runnable change : changes) {
            fake.approved = true; fake.risk = ToolRisk.CRITICAL; fake.fingerprint = "fp-1"; fake.schema = SCHEMA;
            var card = planCritical("T-1");
            change.run();
            var decision = confirm(card.id());
            assertThat(((ToolExecutionService.Decision.Done) decision).card().status()).isEqualTo("BLOCKED");
            assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        }
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void conversationMovedToAnotherSpaceInvalidatesTheConfirmation() {
        var card = planCritical("T-1");
        long otherSpace = id("insert into space (org_id, name, created_at) values (" + orgId + ", 'Zwei', current_timestamp)");
        jdbc.update("update chat_conversation set space_id = ? where id = ?", otherSpace, chat);
        assertThat(((ToolExecutionService.Decision.Done) confirm(card.id())).card().status()).isEqualTo("BLOCKED");
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void auditOutageBlocksTheConfirmedCall() {
        var card = planCritical("T-1");
        jdbc.execute("drop table tool_call_log");
        var done = ((ToolExecutionService.Decision.Done) confirm(card.id())).card();
        assertThat(done.status()).isEqualTo("BLOCKED");
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void failureAfterStartIsReportedAndNeverRetried() {
        fake.failure = new IllegalStateException("host 10.0.0.9 secret");
        var card = planCritical("T-1");
        var done = ((ToolExecutionService.Decision.Done) confirm(card.id())).card();
        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.error()).doesNotContain("10.0.0.9", "secret");
        assertThat(done.result()).isNull();
        assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(fake.invocations).hasValue(1);
    }

    @Test void newMessageCancelsPendingConfirmations() {
        var card = planCritical("T-1");
        service.cancelPending(user, chat);
        assertThat(service.cards(user, orgId, chat).getLast().status()).isEqualTo("CANCELLED");
        assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void readRunsImmediatelyWithAnHonestCardAndBlockedPlansAreShownAsBlocked() {
        var t = turn();
        assertThat(t.call("f__read", "{\"id\":\"A\"}").invoked()).isTrue();
        assertThat(t.call("f__read", "{\"id\":5}").invoked()).isFalse();
        var cards = service.cards(user, orgId, chat);
        assertThat(cards).extracting(ToolExecutionService.ToolCallView::status).containsExactly("SUCCEEDED", "BLOCKED");
        assertThat(cards.getFirst().result()).isEqualTo("erledigt A");
        assertThat(cards.getFirst().needsConfirmation()).isFalse();
        assertThat(cards.getLast().error()).contains("Eingabeschema");
        assertThat(fake.invocations).hasValue(1);
    }

    @Test void pendingPlansCountTowardsTheEightCallLimit() {
        var t = turn();
        for (int i = 0; i < 8; i++) t.call("f__delete", "{\"id\":\"" + i + "\"}");
        assertThat(t.call("f__read", "{\"id\":\"A\"}").text()).contains("Höchstzahl");
        assertThat(service.cards(user, orgId, chat)).filteredOn(c -> c.status().equals("PENDING")).hasSize(8);
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void cardsCarryNoSecretsOrInternalBindings() {
        planCritical("T-1");
        String json = M.writeValueAsString(service.cards(user, orgId, chat));
        assertThat(json).doesNotContain("fp-1", "definitionHash", "fingerprint", "userId", "orgId", "spaceId", "fake:1:delete");
    }

    @Test void hangingRunningCallIsShownAsUnclear() {
        var card = planCritical("T-1");
        jdbc.update("update tool_call set status = 'RUNNING', started_at = ? where public_id = ?",
                java.sql.Timestamp.from(now.get()), card.id());
        now.set(now.get().plus(ToolCallStore.RUNNING_STALE_AFTER).plus(Duration.ofSeconds(1)));
        assertThat(service.cards(user, orgId, chat).getLast().status()).isEqualTo("UNKNOWN");
        assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(fake.invocations).hasValue(0);
    }
}
