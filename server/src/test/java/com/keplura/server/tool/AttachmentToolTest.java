package com.keplura.server.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** TU-04c: only this chat's attachments, only references persisted, guessed IDs never reach the text column. */
class AttachmentToolTest {
    private static final JsonMapper M = JsonMapper.builder().build();
    private static final String SECRET = "PRIVATE_ATTACHMENT_4711";
    private final AtomicInteger textReads = new AtomicInteger();
    private final AtomicBoolean textUnavailable = new AtomicBoolean();
    private JdbcTemplate jdbc;
    private AttachmentToolAdapter adapter;
    private External external;
    private ToolExecutionService service;

    /** External READ target that counts every real invocation. */
    static final class External implements ToolAdapter {
        final AtomicInteger invocations = new AtomicInteger();
        @Override public String kind() { return "external"; }
        @Override public List<ToolDefinition> definitions(Long spaceId) {
            var schema = M.createObjectNode().put("type", "object");
            schema.putObject("properties").putObject("message").put("type", "string");
            return List.of(new ToolDefinition("external:1:echo", "external", "s1__echo", "echo", 1L, "Extern", "Echo",
                    schema, ToolRisk.READ, true, "fp", false));
        }
        @Override public String invoke(ToolDefinition tool, JsonNode arguments) { invocations.incrementAndGet(); return "ok"; }
    }

    @BeforeEach void setup() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:attachment-tools-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds) {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                if (sql.contains("text_content")) {
                    textReads.incrementAndGet();
                    if (textUnavailable.get()) throw new org.springframework.dao.DataAccessResourceFailureException("temporary attachment failure");
                }
                return super.query(sql, mapper, args);
            }
        };
        jdbc.update("insert into organization(id,name,created_at) values(1,'Org',current_timestamp),(2,'Other',current_timestamp)");
        jdbc.update("insert into space(id,org_id,name,created_at) values(1,1,'Space',current_timestamp),(2,1,'Space 2',current_timestamp),(3,2,'Foreign',current_timestamp)");
        jdbc.update("insert into membership(org_id,user_id,role,created_at) values(1,1,'OWNER',current_timestamp),(1,2,'MEMBER',current_timestamp),(2,1,'OWNER',current_timestamp)");
        conversation(1, 1, 1, 1);
        conversation(2, 1, 1, 1);
        conversation(3, 1, 1, 2);
        conversation(4, 2, 3, 1);
        jdbc.update("insert into org_tool_setting(org_id,knowledge_enabled) values(1,true),(2,true)");
        message(1, 1);
        // 30 000 + 30 000 characters: the hit at 45 000 lies behind the 40 000-character prompt budget.
        attachment(10, 1, 1, "eins.txt", "a".repeat(30_000));
        attachment(11, 1, 1, "zwei.txt", "b".repeat(15_000) + "Stichwort " + SECRET + " " + "c".repeat(14_966));
        attachment(20, 2, 1, "anderer-chat.txt", "Stichwort FOREIGN_CHAT_TEXT");
        attachment(30, 3, 2, "andere-person.txt", "Stichwort FOREIGN_USER_TEXT");
        attachment(40, 4, 1, "andere-org.txt", "Stichwort FOREIGN_ORG_TEXT");
        adapter = new AttachmentToolAdapter(jdbc);
        external = new External();
        service = new ToolExecutionService(jdbc, List.of(adapter, external), new ToolAuditLog(jdbc));
    }

    private void conversation(long id, long org, long space, long user) {
        jdbc.update("insert into chat_conversation(id,org_id,space_id,created_by,title,created_at,updated_at) values(?,?,?,?,'Chat',current_timestamp,current_timestamp)",
                id, org, space, user);
    }
    private void message(long id, long conversation) {
        jdbc.update("insert into chat_message(id,conversation_id,role,content,model,citations_json,status,created_at) values(?,?,'user','stichwort finden','','[]','complete',current_timestamp)",
                id, conversation);
    }
    private void attachment(long id, long conversation, long user, String name, String text) {
        jdbc.update("insert into chat_attachment(id,conversation_id,filename,text_content,char_count,truncated,created_by,created_at) values(?,?,?,?,?,?,?,current_timestamp)",
                id, conversation, name, text, text.length(), text.length() >= 30_000, user);
    }
    private ToolExecutionService.ToolTurn turn(long prompt) {
        var turn = service.openTurn(service.resolveContext(1L, 1L, 1L), prompt);
        turn.startQuestion("stichwort finden", "");
        return turn;
    }
    private String stored(String tool) {
        return jdbc.queryForObject("select result_text from tool_call where tool_name=? order by id desc fetch first 1 rows only", String.class, tool);
    }
    private ToolExecutionService.ToolCallView card(String tool) {
        return service.cards(1L, 1L, 1L).stream().filter(c -> c.tool().equals(tool)).reduce((a, b) -> b).orElseThrow();
    }

    @Test void searchFindsTextBehindThePromptBudgetAndReadReturnsIt() {
        var turn = turn(1);
        assertThat(turn.offered()).containsKeys("attachment_list", "attachment_search", "attachment_read").containsKey("s1__echo");
        assertThat(turn.call("attachment_search", "{\"query\":\"STICHWORT\"}").invoked()).isTrue();
        JsonNode hits = M.readTree(stored("attachment_search"));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).path("attachmentId").asLong()).isEqualTo(11);
        assertThat(hits.get(0).path("offset").asInt()).isEqualTo(15_000);
        assertThat(card("attachment_search").result()).contains("[Anhang 11] zwei.txt, Position 15000", SECRET);
        assertThat(card("attachment_search").result().length()).isLessThan(500);

        assertThat(turn.call("attachment_read", "{\"attachmentId\":11,\"offset\":15000,\"length\":40}").invoked()).isTrue();
        assertThat(M.readTree(stored("attachment_read")).get(0).path("length").asInt()).isEqualTo(40);
        assertThat(card("attachment_read").result()).contains("Zeichen 15000–15040 von 30000", "Stichwort " + SECRET);
        // the question's own query stays visible; the position taken from the internal hit is not stored
        assertThat(card("attachment_search").argumentsText()).isEqualTo("{\"query\":\"STICHWORT\"}");
        assertThat(card("attachment_read").argumentsText()).isNull();
    }

    private List<String> column(String name) {
        return jdbc.queryForList("select " + name + " from tool_call order by id", String.class);
    }
    private void assertNoPrivateCopies() {
        for (String c : List.of("arguments_json", "result_text", "error"))
            assertThat(column(c)).allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain(SECRET, "zwei.txt"));
        assertThat(jdbc.queryForList("select dialogue_json from tool_question", String.class))
                .allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain(SECRET, "zwei.txt"));
    }
    private void assertNothingVisibleAfterDeletion() {
        jdbc.update("delete from chat_attachment where id=11");
        assertThat(service.cards(1L, 1L, 1L).toString()).doesNotContain(SECRET, "zwei.txt");
        assertNoPrivateCopies();
    }

    @Test void privateFollowUpSearchIsExecutedButItsArgumentsAreNotKept() {
        var turn = turn(1);
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        // the model re-uses a private value and a file name it only learned from the result
        assertThat(turn.call("attachment_search", "{\"query\":\"" + SECRET + "\"}").invoked()).isTrue();
        assertThat(turn.call("attachment_search", "{\"query\":\"zwei.txt\"}").invoked()).isTrue();
        assertThat(column("arguments_json")).containsExactly("{\"query\":\"stichwort\"}", null, null);
        assertNoPrivateCopies();
        assertNothingVisibleAfterDeletion();
    }

    @Test void pendingExternalProposalKeepsArgumentsOnlyWhileItCanRun() {
        var turn = turn(1);
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        turn.call("s1__echo", "{\"message\":\"zwei.txt " + SECRET + "\"}");
        assertThat(card("echo").argumentsText()).contains(SECRET);
        jdbc.update("delete from chat_attachment where id=11");
        assertThat(card("echo").status()).isEqualTo("CANCELLED");
        assertNothingVisibleAfterDeletion();
        assertThat(external.invocations).hasValue(0);
    }

    @Test void rejectedAndExpiredPrivateProposalsDropTheirArguments() {
        var turn = turn(1);
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        turn.call("s1__echo", "{\"message\":\"" + SECRET + "\"}");
        assertThat(service.decide(1L, 1L, 1L, card("echo").id(), false)).isInstanceOf(ToolExecutionService.Decision.Done.class);
        assertThat(card("echo").status()).isEqualTo("REJECTED");
        message(2, 1);
        var next = turn(2);
        next.call("attachment_search", "{\"query\":\"stichwort\"}");
        next.call("s1__echo", "{\"message\":\"" + SECRET + "\"}");
        jdbc.update("update tool_call set expires_at=? where status='PENDING'", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        assertThat(card("echo").status()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForList("select status from tool_call where tool_name='echo' order by id", String.class)).containsExactly("REJECTED", "EXPIRED");
        assertNothingVisibleAfterDeletion();
        assertThat(external.invocations).hasValue(0);
    }

    @Test void confirmedPrivateDisclosureRunsOnceAndThenDropsItsArguments() {
        var turn = turn(1);
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        turn.call("s1__echo", "{\"message\":\"" + SECRET + "\"}");
        service.decide(1L, 1L, 1L, card("echo").id(), true);
        assertThat(external.invocations).hasValue(1);
        assertThat(card("echo").status()).isEqualTo("SUCCEEDED");
        assertThat(column("arguments_json")).allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain(SECRET));
    }

    @Test void unknownModelKeysNeverReachErrorsCardsOrDialogue() {
        var turn = turn(1);
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        var outcome = turn.call("attachment_read", "{\"attachmentId\":11,\"offset\":0,\"" + SECRET + "\":true}");
        assertThat(outcome.invoked()).isFalse();
        assertThat(outcome.text()).doesNotContain(SECRET).contains(ToolArgumentValidator.UNKNOWN_FIELD);
        assertThat(card("attachment_read").error()).doesNotContain(SECRET);
        assertNoPrivateCopies();
        assertNothingVisibleAfterDeletion();
        assertThat(card("attachment_read").error()).isNull();
    }

    @Test void onlyReferencesArePersistedInCardsAndDialogue() {
        var turn = turn(1);
        turn.call("attachment_list", "{}");
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        turn.call("attachment_read", "{\"attachmentId\":11,\"offset\":14990}");

        for (String column : List.of("result_text", "arguments_json", "error"))
            assertThat(jdbc.queryForList("select " + column + " from tool_call", String.class))
                    .allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain(SECRET, "zwei.txt", "eins.txt", "bbbb"));
        assertThat(jdbc.queryForList("select dialogue_json from tool_question", String.class))
                .allSatisfy(v -> assertThat(String.valueOf(v)).doesNotContain(SECRET, "zwei.txt"));
        assertThat(jdbc.queryForObject("select internal_seen from tool_question where prompt_message_id=1", Boolean.class)).isTrue();
        assertThat(card("attachment_list").result()).contains("[Anhang 10] eins.txt, 30000 Zeichen, beim Hochladen gekürzt", "[Anhang 11] zwei.txt")
                .doesNotContain("anderer-chat", "andere-person", "andere-org");
    }

    @Test void readIsBoundedAtTheEndOfTheAttachment() {
        var turn = turn(1);
        turn.call("attachment_read", "{\"attachmentId\":11,\"offset\":29990,\"length\":4000}");
        assertThat(M.readTree(stored("attachment_read")).get(0).path("length").asInt()).isEqualTo(10);
        turn.call("attachment_read", "{\"attachmentId\":11,\"offset\":30000}");
        assertThat(M.readTree(stored("attachment_read")).get(0).path("length").asInt()).isZero();
        assertThat(card("attachment_read").result()).contains("liegt am Ende (30000 Zeichen)");
        turn.call("attachment_read", "{\"attachmentId\":10,\"offset\":0}");
        assertThat(M.readTree(stored("attachment_read")).get(0).path("length").asInt()).isEqualTo(2000);
        assertThat(card("attachment_read").result().length()).isLessThanOrEqualTo(ToolExecutionService.MAX_RESULT_CHARS);
    }

    @Test void searchIsCaseInsensitiveNfcLiteralAndCapsHitsAndExcerpts() {
        jdbc.update("delete from chat_attachment where conversation_id=1");
        attachment(12, 1, 1, "umlaut.txt", "Ärger mit (a+b) ".repeat(20));
        var turn = turn(1);
        // decomposed A + combining diaeresis in the query, composed Ä in the text
        turn.call("attachment_search", "{\"query\":\"a\\u0308RGER\"}");
        assertThat(M.readTree(stored("attachment_search"))).hasSize(8);
        assertThat(card("attachment_search").result()).contains("Position 0", "Position 16");
        for (var line : card("attachment_search").result().split("\n"))
            assertThat(line.length()).isLessThanOrEqualTo(AttachmentToolAdapter.MAX_EXCERPT + 2);
        turn.call("attachment_search", "{\"query\":\"(a+b)\"}");
        assertThat(M.readTree(stored("attachment_search")).get(0).path("offset").asInt()).isEqualTo(10);
        turn.call("attachment_search", "{\"query\":\"a.b\"}");
        assertThat(M.readTree(stored("attachment_search"))).isEmpty();
        assertThat(card("attachment_search").result()).contains("Keine Treffer.");
    }

    @ParameterizedTest @ValueSource(longs = {20, 30, 40, 999})
    void foreignOrGuessedIdsNeverReachTheTextColumn(long id) {
        var turn = turn(1);
        textReads.set(0);
        assertThat(turn.call("attachment_read", "{\"attachmentId\":" + id + ",\"offset\":0}").invoked()).isFalse();
        assertThat(textReads).hasValue(0);
        message(2, 1);
        var next = turn(2);
        assertThat(next.call("attachment_search", "{\"query\":\"Stichwort\",\"attachmentId\":" + id + "}").invoked()).isFalse();
        assertThat(textReads).hasValue(0);
        assertThat(service.cards(1L, 1L, 1L).toString()).doesNotContain("FOREIGN_");
    }

    @Test void foreignContextsAreNotOfferedAndCannotExecute() {
        for (var context : List.of(new ToolContext(2L, 1L, 1L, 1L), new ToolContext(1L, 2L, 1L, 1L),
                new ToolContext(1L, 1L, 2L, 1L), new ToolContext(1L, 1L, 1L, 3L))) {
            assertThat(adapter.ownedIds(context)).isEmpty();
            textReads.set(0);
            var other = service.openTurn(context, 1L);
            assertThat(other.call("attachment_read", "{\"attachmentId\":11,\"offset\":0}").invoked()).isFalse();
            assertThat(other.call("attachment_search", "{\"query\":\"Stichwort\"}").invoked()).isFalse();
            assertThat(textReads).hasValue(0);
        }
        // the owner of chat 3 sees only their own attachment
        var owner = service.resolveContext(2L, 1L, 3L);
        assertThat(adapter.ownedIds(owner)).containsExactly(30L);
    }

    @Test void notOfferedWithoutAttachmentsWhenDisabledOrForAnotherOrg() {
        assertThat(service.openTurn(service.resolveContext(1L, 1L, 1L)).offered()).containsKey("attachment_search");
        jdbc.update("update org_tool_setting set knowledge_enabled=false where org_id=1");
        assertThat(service.openTurn(service.resolveContext(1L, 1L, 1L)).offered()).doesNotContainKey("attachment_search");
        jdbc.update("update org_tool_setting set knowledge_enabled=true where org_id=1");
        jdbc.update("delete from chat_attachment where conversation_id=1");
        assertThat(service.openTurn(service.resolveContext(1L, 1L, 1L)).offered()).doesNotContainKeys("attachment_list", "attachment_search", "attachment_read");
        assertThat(service.resolveContext(1L, 2L, 1L)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"query\":\"\"}", "{\"query\":\"   \"}", "{\"query\":1}", "{}", "{\"query\":\"x\",\"regex\":true}",
            "{\"query\":\"x\",\"attachmentId\":0}", "{\"query\":\"x\",\"attachmentId\":1e400}", "{\"query\":\"x\",\"attachmentId\":10.5}"})
    void invalidSearchArgumentsAreBlockedServerSide(String raw) {
        var turn = turn(1);
        textReads.set(0);
        assertThat(turn.call("attachment_search", raw).invoked()).isFalse();
        assertThat(textReads).hasValue(0);
    }

    @Test void tooLongQueryIsBlocked() {
        var turn = turn(1);
        textReads.set(0);
        assertThat(turn.call("attachment_search", "{\"query\":\"" + "x".repeat(201) + "\"}").invoked()).isFalse();
        assertThat(textReads).hasValue(0);
        assertThat(turn.call("attachment_search", "{\"query\":\"" + "b".repeat(200) + "\"}").invoked()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"attachmentId\":11,\"offset\":-1}", "{\"attachmentId\":11,\"offset\":0,\"length\":0}",
            "{\"attachmentId\":11,\"offset\":0,\"length\":4001}", "{\"attachmentId\":11}", "{\"attachmentId\":11,\"offset\":1e400}",
            "{\"attachmentId\":11,\"offset\":0.5}", "{\"attachmentId\":11,\"offset\":\"0\"}", "{\"attachmentId\":11,\"offset\":1000001}",
            "{\"attachmentId\":11,\"offset\":0,\"conversationId\":2}"})
    void invalidReadArgumentsAreBlockedServerSide(String raw) {
        var turn = turn(1);
        textReads.set(0);
        assertThat(turn.call("attachment_read", raw).invoked()).isFalse();
        assertThat(textReads).hasValue(0);
    }

    @Test void deletedAttachmentDisappearsEndsTheRoundAndCancelsPendingDisclosure() {
        var turn = turn(1);
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        // internal text in an external READ becomes a card, never an automatic call
        var outcome = turn.call("s1__echo", "{\"message\":\"" + SECRET + "\"}");
        assertThat(outcome.invoked()).isFalse();
        assertThat(card("echo").status()).isEqualTo("PENDING");
        assertThat(card("echo").mayContainInternal()).isTrue();
        assertThat(external.invocations).hasValue(0);
        assertThat(turn.active()).isTrue();
        jdbc.update("delete from chat_attachment where id=11");
        assertThat(turn.active()).isFalse();
        var search = card("attachment_search");
        assertThat(search.result()).isNull();
        assertThat(search.sourceUnavailable()).isTrue();
        assertThat(card("echo").status()).isEqualTo("CANCELLED");
        assertThat(service.cards(1L, 1L, 1L).toString()).doesNotContain(SECRET);
        assertThat(external.invocations).hasValue(0);
    }

    @Test void deletingAnUnreferencedAttachmentDoesNotEndTheRound() {
        var turn = turn(1);
        turn.call("attachment_read", "{\"attachmentId\":11,\"offset\":15000,\"length\":10}");
        jdbc.update("delete from chat_attachment where id=10");
        assertThat(turn.active()).isTrue();
        assertThat(card("attachment_read").result()).contains("Stichwort");
    }

    @Test void publicTextFromTheQuestionStillRunsExternallyAfterAnAttachmentHit() {
        var turn = turn(1);
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        assertThat(turn.call("s1__echo", "{\"message\":\"stichwort finden\"}").invoked()).isTrue();
        assertThat(external.invocations).hasValue(1);
    }

    @Test void temporaryDatabaseFailureHidesTextButKeepsPendingCards() {
        var turn = turn(1);
        turn.call("attachment_search", "{\"query\":\"stichwort\"}");
        turn.call("s1__echo", "{\"message\":\"" + SECRET + "\"}");
        textUnavailable.set(true);
        var hidden = card("echo");
        assertThat(hidden.status()).isEqualTo("PENDING");
        assertThat(hidden.sourceTemporarilyUnavailable()).isTrue();
        assertThat(hidden.argumentsText()).isNull();
        assertThat(card("attachment_search").result()).isNull();
        assertThat(turn.active()).isFalse();
        textUnavailable.set(false);
        assertThat(card("echo").status()).isEqualTo("PENDING");
        assertThat(card("attachment_search").result()).contains(SECRET);
    }

    @Test void previousQuestionReferencesAreNotEvidenceAfterDeletion() {
        turn(1).call("attachment_search", "{\"query\":\"stichwort\"}");
        message(2, 1);
        jdbc.update("delete from chat_attachment where id=11");
        assertThat(service.completedEvidence(1L, 1L, 1L, 2L)).doesNotContain(SECRET);
        assertThat(service.cards(1L, 1L, 1L).toString()).doesNotContain(SECRET);
    }
}
