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
    private static final JsonNode LOOSE = M.readTree("{\"type\":\"object\"}");
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
        volatile Runnable onInvoke = () -> { };

        @Override public String kind() { return "fake"; }

        @Override public List<ToolDefinition> definitions(Long spaceId) {
            List<ToolDefinition> out = new ArrayList<>();
            out.add(new ToolDefinition("fake:1:delete", "fake", "f__delete", "delete", 1L, "Fake", "Löscht", schema, risk,
                    approved, fingerprint));
            out.add(new ToolDefinition("fake:1:read", "fake", "f__read", "read", 1L, "Fake", "Liest", SCHEMA, ToolRisk.READ,
                    true, fingerprint));
            out.add(new ToolDefinition("fake:1:any", "fake", "f__any", "any", 1L, "Fake", "Nimmt alles", LOOSE, ToolRisk.READ,
                    true, fingerprint));
            return out;
        }

        @Override public String invoke(ToolDefinition tool, JsonNode arguments) {
            invocations.incrementAndGet();
            received.add(arguments);
            onInvoke.run();
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
    private Long promptId;

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

    @Test void simultaneousDifferentConfirmationsSerializeAndShareTime() throws Exception {
        var turn = turn();
        turn.call("f__delete", "{\"id\":\"one\"}");
        turn.call("f__delete", "{\"id\":\"two\"}");
        var cards = service.cards(user,orgId,chat);
        assertThat(cards).hasSize(2);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maxActive = new java.util.concurrent.atomic.AtomicInteger();
        fake.onInvoke = () -> {
            int current=active.incrementAndGet(); maxActive.accumulateAndGet(current,Math::max);
            try { Thread.sleep(50); } catch(InterruptedException e) { throw new RuntimeException(e); }
            now.updateAndGet(t -> t.plusSeconds(121));
            active.decrementAndGet();
        };
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first=pool.submit(() -> { start.await(); return service.decide(user,orgId,chat,cards.get(0).id(),true); });
            var second=pool.submit(() -> { start.await(); return service.decide(user,orgId,chat,cards.get(1).id(),true); });
            start.countDown();
            assertThat(first.get()).isInstanceOf(ToolExecutionService.Decision.Done.class);
            assertThat(second.get()).isInstanceOf(ToolExecutionService.Decision.Done.class);
        }
        assertThat(maxActive.get()).isEqualTo(1);
        assertThat(fake.invocations.get()).isEqualTo(1);
        assertThat(service.cards(user,orgId,chat)).filteredOn(c -> c.status().equals("BLOCKED")).hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void guardTimeoutMarksUnstartedCardBlocked(boolean critical) {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        service = new ToolExecutionService(jdbc, List.of(fake), new ToolAuditLog(jdbc), new ToolCallStore(jdbc),
                () -> clock.getAndAdd(ToolExecutionService.MAX_TOOL_TIME_PER_TURN.toNanos()), now::get);
        assertGuardFailureIsBlocked(critical, false);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void interruptedGuardWaitMarksUnstartedCardBlocked(boolean critical) {
        try {
            assertGuardFailureIsBlocked(critical, true);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // do not pass the test thread's interrupt to another test
        }
    }

    private void assertGuardFailureIsBlocked(boolean critical, boolean interrupt) {
        var turn = turn();
        String callId = null;
        if (critical) {
            turn.call("f__delete", "{\"id\":\"one\"}");
            callId = service.cards(user, orgId, chat).getFirst().id();
        }
        jdbc.update("insert into tool_execution_guard(prompt_message_id,conversation_id,lease) values(?,?,'crashed-holder')", promptId, chat);
        if (interrupt) Thread.currentThread().interrupt();
        if (critical) {
            var done = (ToolExecutionService.Decision.Done) service.decide(user, orgId, chat, callId, true);
            assertThat(done.card().status()).isEqualTo("BLOCKED");
            assertThat(done.continuationToken()).isNull();
            assertThat(service.decide(user, orgId, chat, callId, true)).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        } else {
            var outcome = turn.call("f__read", "{\"id\":\"one\"}");
            assertThat(outcome.invoked()).isFalse();
            assertThat(outcome.text()).contains("nicht aufgerufen");
        }
        assertThat(fake.invocations.get()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tool_call_log", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select lease from tool_execution_guard where prompt_message_id=?", String.class, promptId)).isEqualTo("crashed-holder");
        now.updateAndGet(t -> t.plusSeconds(360));
        assertThat(service.cards(user, orgId, chat)).singleElement().satisfies(card -> {
            assertThat(card.status()).isEqualTo("BLOCKED");
            assertThat(card.error()).contains("nicht aufgerufen");
        });
    }

    private long id(String sql) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> c.prepareStatement(sql, new String[]{"id"}), keys);
        return keys.getKey().longValue();
    }

    private ToolExecutionService.ToolTurn turn() {
        if (promptId == null) promptId = userMessage("Current question");
        return service.openTurn(service.resolveContext(user, orgId, chat), promptId);
    }

    private long userMessage(String text) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> {
            var st = c.prepareStatement("""
                    insert into chat_message (conversation_id, role, content, model, citations_json, status, created_at)
                    values (?, 'user', ?, '', '[]', 'complete', current_timestamp)""", new String[]{"id"});
            st.setLong(1, chat);
            st.setString(2, text);
            return st;
        }, keys);
        return keys.getKey().longValue();
    }

    /** {"values":[1e2, ...]}: raw text fits the turn limit, the canonical form (100.0) does not. */
    private static String inflatingArguments() {
        StringBuilder sb = new StringBuilder("{\"values\":[");
        for (int i = 0; i < 3000; i++) sb.append(i == 0 ? "" : ",").append("1e2");
        return sb.append("]}").toString();
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

    @Test void savingSameSelectionKeepsTheCardButChangingAndRestoringNeverRevivesIt() {
        var enabled = M.readTree("{\"selectedIds\":[\"fake:1:delete\"]}");
        service.setChatToolSelection(user, orgId, chat, enabled);
        var oldCard = planCritical("old");
        service.setChatToolSelection(user, orgId, chat, enabled);
        assertThat(service.cards(user, orgId, chat).getLast().status()).isEqualTo("PENDING");

        service.setChatToolSelection(user, orgId, chat, M.readTree("{\"selectedIds\":[]}"));
        service.setChatToolSelection(user, orgId, chat, enabled);
        assertThat(service.cards(user, orgId, chat).getLast().status()).isEqualTo("CANCELLED");
        assertThat(confirm(oldCard.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(fake.invocations).hasValue(0);

        promptId = userMessage("New question");
        var fresh = planCritical("new");
        assertThat(confirm(fresh.id())).isInstanceOf(ToolExecutionService.Decision.Done.class);
        assertThat(fake.invocations).hasValue(1);
    }

    @Test void criticalIsOfferedButOnlyPlannedInTheTurn() {
        assertThat(turn().offered()).containsKeys("f__delete", "f__read");
        var card = planCritical("T-1");
        assertThat(card.status()).isEqualTo("PENDING");
        assertThat(card.needsConfirmation()).isTrue();
        assertThat(card.risk()).isEqualTo("CRITICAL");
        assertThat(card.promptMessageId()).isEqualTo(promptId);
        assertThat(card.argumentsText()).isEqualTo("{\"id\":\"T-1\"}");
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

    // ---- Review round 1 (PR #17) ----

    @Test void canonicalArgumentsBeyondTheLimitBlockInsteadOfBecomingEmpty() {
        fake.schema = LOOSE;
        String raw = inflatingArguments();
        assertThat(raw.length()).isLessThan(ToolExecutionService.MAX_ARGUMENT_CHARS);
        var outcome = turn().call("f__delete", raw);
        assertThat(outcome.invoked()).isFalse();
        assertThat(outcome.text()).contains("zu groß");
        var card = service.cards(user, orgId, chat).getLast();
        assertThat(card.status()).isEqualTo("BLOCKED");
        assertThat(card.argumentsText()).isNull(); // never a stand-in {}
        assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(turn().call("f__any", raw).invoked()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from tool_call where arguments_json = '{}'", Integer.class)).isZero();
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void cardTextIsExactlyWhatTheAdapterReceives() {
        String raw = "{\"n\":1e2,\"big\":9007199254740993,\"s\":\"x\"}";
        assertThat(turn().call("f__any", raw).invoked()).isTrue();
        var card = service.cards(user, orgId, chat).getLast();
        assertThat(card.argumentsText()).isEqualTo(fake.received.getLast().toString());
        assertThat(card.argumentsText()).contains("9007199254740993");
        assertThat(card.argumentFields()).extracting(ToolExecutionService.ArgumentField::value)
                .contains("9007199254740993", "x");

        fake.schema = LOOSE;
        var planned = turn().call("f__delete", raw);
        assertThat(planned.invoked()).isFalse();
        var pending = service.cards(user, orgId, chat).getLast();
        confirm(pending.id());
        assertThat(fake.received.getLast().toString()).isEqualTo(pending.argumentsText());
    }

    @Test void longAndManyFieldsAreCompleteInTheExactText() {
        fake.schema = LOOSE;
        StringBuilder raw = new StringBuilder("{");
        for (int i = 0; i < 20; i++) raw.append("\"k").append(i).append("\":").append(i).append(',');
        raw.append("\"cmd\":\"").append("x".repeat(450)).append("; delete_all\",\"delete_all\":true}");
        turn().call("f__delete", raw.toString());
        var card = service.cards(user, orgId, chat).getLast();
        assertThat(card.status()).isEqualTo("PENDING");
        assertThat(card.argumentFields()).hasSize(22);
        assertThat(card.argumentsText()).contains("; delete_all", "\"delete_all\":true");
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void aSlowPlanCannotCreateADecidableCardAfterANewerQuestion() {
        long first = userMessage("Lösche T-1");
        var slowTurn = service.openTurn(service.resolveContext(user, orgId, chat), first); // model still planning
        userMessage("Doch nicht"); // another tab sends a new message …
        service.cancelPending(user, chat); // … which cancels what exists so far (nothing yet)
        var outcome = slowTurn.call("f__delete", "{\"id\":\"T-1\"}"); // the old plan arrives late
        assertThat(outcome.invoked()).isFalse();
        assertThat(outcome.text()).contains("weitergeführt oder gestoppt");
        assertThat(service.cards(user, orgId, chat)).noneMatch(c -> c.status().equals("PENDING"));
        for (var card : service.cards(user, orgId, chat)) {
            assertThat(confirm(card.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        }
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void aNewerQuestionInvalidatesAnExistingCardAtomicallyAtDecision() {
        long first = userMessage("Lösche T-1");
        service.openTurn(service.resolveContext(user, orgId, chat), first).call("f__delete", "{\"id\":\"T-1\"}");
        var card = service.cards(user, orgId, chat).getLast();
        assertThat(card.status()).isEqualTo("PENDING");
        userMessage("Neue Frage"); // committed by another request, without cancelPending reaching this card
        assertThat(service.cards(user, orgId, chat).getLast().status()).isEqualTo("CANCELLED");
        var decision = confirm(card.id());
        assertThat(decision).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        assertThat(((ToolExecutionService.Decision.Conflict) decision).card().status()).isEqualTo("CANCELLED");
        assertThat(fake.invocations).hasValue(0);
    }

    @Test void stopEndsPendingAndStillPlanningTurns() {
        long first = userMessage("Lösche T-1");
        var turn = service.openTurn(service.resolveContext(user, orgId, chat), first);
        turn.call("f__delete", "{\"id\":\"T-1\"}");
        var existing = service.cards(user, orgId, chat).getLast();
        assertThat(service.stop(user, orgId, chat)).isTrue();
        assertThat(service.cards(user, orgId, chat).getLast().status()).isEqualTo("CANCELLED");
        assertThat(confirm(existing.id())).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
        // the same (stopped) turn plans once more afterwards: never decidable
        assertThat(turn.call("f__delete", "{\"id\":\"T-2\"}").invoked()).isFalse();
        assertThat(service.cards(user, orgId, chat)).noneMatch(c -> c.status().equals("PENDING"));
        assertThat(service.stop(4712L, orgId, chat)).isFalse();
        assertThat(fake.invocations).hasValue(0);
        // a later question starts a fresh generation: its plans work again
        long next = userMessage("Lösche T-3");
        service.openTurn(service.resolveContext(user, orgId, chat), next).call("f__delete", "{\"id\":\"T-3\"}");
        assertThat(service.cards(user, orgId, chat).getLast().status()).isEqualTo("PENDING");
    }

    @Test void confirmedCallsShareTheTurnTimeBudget() {
        var t = turn();
        for (int i = 0; i < 3; i++) t.call("f__delete", "{\"id\":\"T-" + i + "\"}");
        fake.onInvoke = () -> now.set(now.get().plusSeconds(70));
        var cards = service.cards(user, orgId, chat);
        assertThat(((ToolExecutionService.Decision.Done) confirm(cards.get(0).id())).card().status()).isEqualTo("SUCCEEDED");
        assertThat(((ToolExecutionService.Decision.Done) confirm(cards.get(1).id())).card().status()).isEqualTo("SUCCEEDED");
        var third = ((ToolExecutionService.Decision.Done) confirm(cards.get(2).id())).card();
        assertThat(third.status()).isEqualTo("BLOCKED");
        assertThat(third.error()).contains("Zeitbudget");
        assertThat(fake.invocations).hasValue(2);
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
