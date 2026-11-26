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

/** TU-05d: ask_user interrupts a question; the validated answer resumes it exactly once. */
class UserQuestionLoopTest {
    private static final JsonMapper M = JsonMapper.builder().build();
    private static final String ASK = UserQuestionToolAdapter.FUNCTION;
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
        final List<JsonNode> arguments = new ArrayList<>();
        @Override public String kind() { return "fixture"; }
        @Override public List<ToolDefinition> definitions(Long space) {
            return List.of(def("search", ToolRisk.READ), def("write", ToolRisk.WRITE), def("critical", ToolRisk.CRITICAL));
        }
        ToolDefinition def(String name, ToolRisk risk) {
            return new ToolDefinition("fixture:1:" + name, kind(), name, name, 1L, "Fixture", "desc",
                    M.readTree("{\"type\":\"object\"}"), risk, true, "fp");
        }
        @Override public String invoke(ToolDefinition t, JsonNode args) {
            calls.add(t.toolName());
            arguments.add(args);
            return "found " + args;
        }
    }

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:askuser-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into organization(id,name,created_at) values(1,'Org',current_timestamp)");
        jdbc.update("insert into space(id,org_id,name,created_at) values(1,1,'Space',current_timestamp)");
        jdbc.update("insert into membership(org_id,user_id,role,created_at) values(1,1,'OWNER',current_timestamp)");
        jdbc.update("insert into chat_conversation(id,org_id,space_id,created_by,title,created_at,updated_at) values(1,1,1,1,'Chat',current_timestamp,current_timestamp)");
        jdbc.update("insert into chat_message(id,conversation_id,role,content,model,citations_json,status,created_at) values(1,1,'user','Bezahle die Rechnung','','[]','complete',current_timestamp)");
        adapter = new Fake();
        execution = new ToolExecutionService(jdbc, List.of(adapter, new UserQuestionToolAdapter()), new ToolAuditLog(jdbc));
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
                {"model":"test","stream":true,"messages":[{"role":"user","content":"Bezahle die Rechnung"}]}""")), false, 1L);
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

    private static final String QUESTION = "{\"question\":\"Welche Rechnung?\",\"options\":[\"R-1\",\"R-2\"]}";

    private String read(GatewayClient.UpstreamStream stream) throws Exception {
        try (stream) { return new String(stream.body().readAllBytes(), StandardCharsets.UTF_8); }
    }

    private String run() throws Exception {
        return read(loop.run(1L, 1L, ctx, "Bezahle die Rechnung", ChatProperties.of("test", 5, 2, 200, 100), config));
    }

    private ToolExecutionService.ToolCallView questionCard() {
        return execution.cards(1L, 1L, 1L).stream().filter(c -> c.kind().equals(ToolExecutionService.KIND_QUESTION))
                .findFirst().orElseThrow();
    }

    private void ask(String args) throws Exception {
        script = (i, b) -> i == 0 ? plan(call(ASK, args)) : answer("Welche Rechnung meinst du?");
        run();
        assertThat(questionCard().status()).isEqualTo("PENDING");
    }

    private String resume(ToolExecutionService.Decision.Done done) throws Exception {
        var continuation = execution.takeContinuation(1L, 1L, 1L, done.card().id(), done.continuationToken());
        assertThat(continuation).isNotNull();
        return read(loop.resume(ctx, continuation, ChatProperties.of("test", 5, 2, 200, 100), config));
    }

    @Test void aQuestionInterruptsAndTheChosenOptionResumesTheQuestionExactlyOnce() throws Exception {
        ask(QUESTION);
        var card = questionCard();
        assertThat(card.question().text()).isEqualTo("Welche Rechnung?");
        assertThat(card.question().options()).containsExactly("R-1", "R-2");
        assertThat(card.question().freeText()).isFalse();
        assertThat(adapter.calls).isEmpty();

        var done = (ToolExecutionService.Decision.Done) execution.decide(1L, 1L, 1L, card.id(), true, "R-2");
        assertThat(done.card().status()).isEqualTo("SUCCEEDED");
        assertThat(done.card().result()).isEqualTo("R-2");
        assertThat(done.continuationToken()).isNotNull();

        script = (i, b) -> {
            if (b.has("tools")) {
                assertThat(b.toString()).contains("R-2");
                assertThat(b.path("tools").toString()).doesNotContain(ASK);
                return requests.size() == 3 ? plan(call("search", "{\"invoice\":\"R-2\"}")) : answer("unused");
            }
            return answer("Rechnung R-2 gefunden.");
        };
        assertThat(resume(done)).contains("Rechnung R-2 gefunden.");
        assertThat(adapter.calls).containsExactly("search");
        assertThat(execution.takeContinuation(1L, 1L, 1L, done.card().id(), done.continuationToken())).isNull();
    }

    @Test void answersThatDoNotFitTheQuestionAreRejectedWithoutConsumingTheCard() throws Exception {
        ask(QUESTION);
        var card = questionCard();
        assertThat(execution.decide(1L, 1L, 1L, card.id(), true, "R-3")).isInstanceOf(ToolExecutionService.Decision.Invalid.class);
        assertThat(execution.decide(1L, 1L, 1L, card.id(), true, null)).isInstanceOf(ToolExecutionService.Decision.Invalid.class);
        assertThat(execution.decide(1L, 1L, 1L, card.id(), true, " ")).isInstanceOf(ToolExecutionService.Decision.Invalid.class);
        assertThat(execution.decide(1L, 1L, 1L, card.id(), true, "x".repeat(UserQuestionToolAdapter.MAX_ANSWER + 1)))
                .isInstanceOf(ToolExecutionService.Decision.Invalid.class);
        assertThat(questionCard().status()).isEqualTo("PENDING");
        // Another person's or chat's attempt finds nothing.
        assertThat(execution.decide(2L, 1L, 1L, card.id(), true, "R-1")).isInstanceOf(ToolExecutionService.Decision.NotFound.class);
        assertThat(execution.decide(1L, 1L, 1L, card.id(), true, "R-1")).isInstanceOf(ToolExecutionService.Decision.Done.class);
        assertThat(execution.decide(1L, 1L, 1L, card.id(), true, "R-2")).isInstanceOf(ToolExecutionService.Decision.Conflict.class);
    }

    @Test void freeTextIsAcceptedWithoutOptionsOrWhenAllowedAndControlCharactersAreDropped() throws Exception {
        ask("{\"question\":\"Für welchen Kunden?\"}");
        var done = (ToolExecutionService.Decision.Done) execution.decide(1L, 1L, 1L, questionCard().id(), true, " Kunde\u0007 Nord ");
        assertThat(done.card().result()).isEqualTo("Kunde Nord");
    }

    @Test void ordinaryCallsNeverTakeAnAnswerAndRejectingAQuestionResumesWithoutIt() throws Exception {
        script = (i, b) -> i == 0 ? plan(call("critical", "{}")) : answer("Warte");
        run();
        var critical = execution.cards(1L, 1L, 1L).getFirst();
        assertThat(execution.decide(1L, 1L, 1L, critical.id(), true, "Ja")).isInstanceOf(ToolExecutionService.Decision.Invalid.class);
        assertThat(adapter.calls).isEmpty();

        jdbc.update("insert into chat_message(id,conversation_id,role,content,model,citations_json,status,created_at) values(2,1,'user','Neu','','[]','complete',current_timestamp)");
        ctx = new ChatService.TurnContext(1L, "[]", ctx.canonicalRequestBody(), false, 2L);
        int base = requests.size();
        script = (i, b) -> i == base ? plan(call(ASK, QUESTION)) : answer("Welche?");
        read(loop.run(1L, 1L, ctx, "Neu", ChatProperties.of("test", 5, 2, 200, 100), config));
        var card = questionCard();
        var done = (ToolExecutionService.Decision.Done) execution.decide(1L, 1L, 1L, card.id(), false, null);
        assertThat(done.card().status()).isEqualTo("REJECTED");
        script = (i, b) -> {
            if (b.has("tools")) assertThat(b.toString()).contains("nicht beantworten");
            return answer("Ohne Angabe weiter.");
        };
        assertThat(resume(done)).contains("Ohne Angabe weiter.");
    }

    @Test void onlyOneQuestionPerQuestionAndNeverNextToAConfirmation() throws Exception {
        script = (i, b) -> i == 0 ? plan(call(ASK, QUESTION), call(ASK, QUESTION), call("critical", "{}")) : answer("?");
        run();
        var cards = execution.cards(1L, 1L, 1L);
        assertThat(cards.stream().filter(c -> c.status().equals("PENDING"))).hasSize(1)
                .allMatch(c -> c.kind().equals(ToolExecutionService.KIND_QUESTION));
        assertThat(cards.stream().filter(c -> c.status().equals("BLOCKED"))).hasSize(2);
    }

    @Test void afterAnAnswerAtMostOneConfirmedFollowUpActionAndNoFurtherQuestion() throws Exception {
        ask(QUESTION);
        var done = (ToolExecutionService.Decision.Done) execution.decide(1L, 1L, 1L, questionCard().id(), true, "R-1");
        script = (i, b) -> {
            if (b.has("tools") && requests.size() == 3) return plan(call("write", "{\"invoice\":\"R-1\"}"),
                    call("write", "{\"invoice\":\"R-2\"}"), call(ASK, QUESTION));
            return answer("Zahlung zur Bestätigung vorgelegt.");
        };
        resume(done);
        assertThat(adapter.calls).isEmpty(); // the follow-up is only a card, never executed in the turn
        var cards = execution.cards(1L, 1L, 1L);
        var followUp = cards.stream().filter(c -> c.tool().equals("write") && c.status().equals("PENDING")).toList();
        assertThat(followUp).hasSize(1);
        assertThat(followUp.getFirst().parentCallId()).isEqualTo(done.card().id());
        assertThat(cards.stream().filter(c -> c.status().equals("BLOCKED"))).hasSize(2); // second write, second question
        var confirmed = (ToolExecutionService.Decision.Done) execution.decide(1L, 1L, 1L, followUp.getFirst().id(), true);
        assertThat(confirmed.card().status()).isEqualTo("SUCCEEDED");
        assertThat(adapter.calls).containsExactly("write");
        assertThat(confirmed.continuationToken()).isNull();
    }

    /** Review finding 1: an open question interrupts everything planned after it in the same model batch. */
    @Test void nothingRunsAfterAQuestionInTheSameBatch() throws Exception {
        script = (i, b) -> i == 0
                ? plan(call(ASK, QUESTION), call("write", "{\"invoice\":\"R-1\"}"), call("search", "{}"))
                : answer("Welche Rechnung?");
        run();
        assertThat(adapter.calls).isEmpty();
        var cards = execution.cards(1L, 1L, 1L);
        assertThat(cards.stream().filter(c -> c.status().equals("PENDING")))
                .singleElement().matches(c -> c.kind().equals(ToolExecutionService.KIND_QUESTION));
        assertThat(cards.stream().filter(c -> c.tool().equals("write") || c.tool().equals("search")))
                .hasSize(2).allMatch(c -> c.status().equals("BLOCKED"));
    }

    @Test void aQuestionAloneNeverTurnsAnOrdinaryChatIntoAToolTurn() {
        var only = new ToolExecutionService(jdbc, List.of(new UserQuestionToolAdapter()), new ToolAuditLog(jdbc));
        assertThat(only.openTurn(only.resolveContext(1L, 1L, 1L), 1L).offered()).isEmpty();
        assertThat(only.hasTools(1L, 1L, 1L)).isFalse();
        assertThat(execution.openTurn(execution.resolveContext(1L, 1L, 1L), 1L).offered()).containsKey(ASK);
    }
}
