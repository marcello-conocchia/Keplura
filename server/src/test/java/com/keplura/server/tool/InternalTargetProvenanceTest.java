package com.keplura.server.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review PR #26 (minor): results of an admin-marked INTERNAL MCP target are internal data too.
 * They are never provenance sources and they mark the question, so an external READ cannot
 * carry them out automatically, even without a knowledge search in the same question.
 */
class InternalTargetProvenanceTest {
    private static final JsonMapper M = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = M.readTree("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");

    static final class Target implements ToolAdapter {
        final String kind; final boolean internal; final AtomicInteger invocations = new AtomicInteger();
        final List<String> arguments = new ArrayList<>(); String result = "ok";
        Target(String kind, boolean internal) { this.kind = kind; this.internal = internal; }
        @Override public String kind() { return kind; }
        @Override public List<ToolDefinition> definitions(Long spaceId) {
            return List.of(new ToolDefinition(kind + ":1:lookup", kind, kind + "__lookup", "lookup", 1L, kind, "desc",
                    SCHEMA, ToolRisk.READ, true, "fp", internal));
        }
        @Override public String invoke(ToolDefinition tool, JsonNode args) {
            invocations.incrementAndGet(); arguments.add(args.toString()); return result;
        }
    }

    private JdbcTemplate jdbc;
    private ToolExecutionService service;
    private Target internal;
    private Target external;

    @BeforeEach void setup() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:internal-target-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into organization(id,name,created_at) values(1,'Org',current_timestamp)");
        jdbc.update("insert into space(id,org_id,name,created_at) values(1,1,'Space',current_timestamp)");
        jdbc.update("insert into membership(org_id,user_id,role,created_at) values(1,1,'OWNER',current_timestamp)");
        jdbc.update("insert into chat_conversation(id,org_id,space_id,created_by,title,created_at,updated_at) values(1,1,1,1,'Chat',current_timestamp,current_timestamp)");
        jdbc.update("insert into chat_message(id,conversation_id,role,content,model,citations_json,status,created_at) values(1,1,'user','Suche Kunde zum Ticket','','[]','complete',current_timestamp)");
        internal = new Target("internal", true);
        external = new Target("external", false);
        service = new ToolExecutionService(jdbc, List.of(internal, external), new ToolAuditLog(jdbc));
    }

    private ToolExecutionService.ToolTurn question() {
        var turn = service.openTurn(service.resolveContext(1L, 1L, 1L), 1L);
        turn.startQuestion("Suche Kunde zum Ticket", "");
        return turn;
    }

    @Test void internalTargetDataNeverLeavesToAnExternalTargetAutomatically() {
        internal.result = "Kunde KUNDE_MUELLER_4711, Vertrag geheim";
        var turn = question();
        assertThat(turn.call("internal__lookup", "{\"q\":\"Kunde\"}").invoked()).isTrue();
        assertThat(jdbc.queryForObject("select internal_seen from tool_question where prompt_message_id=1", Boolean.class)).isTrue();

        var outcome = turn.call("external__lookup", "{\"q\":\"KUNDE_MUELLER_4711\"}");
        assertThat(outcome.invoked()).isFalse();
        assertThat(external.invocations).hasValue(0);
        assertThat(service.cards(1L, 1L, 1L)).filteredOn(c -> c.tool().equals("lookup") && c.status().equals("PENDING"))
                .singleElement().satisfies(c -> assertThat(c.mayContainInternal()).isTrue());
    }

    @Test void questionTextStillRunsAutomaticallyAfterInternalTargetData() {
        internal.result = "Kunde KUNDE_MUELLER_4711";
        var turn = question();
        turn.call("internal__lookup", "{\"q\":\"Kunde\"}");
        assertThat(turn.call("external__lookup", "{\"q\":\"Kunde zum Ticket\"}").invoked()).isTrue();
        assertThat(external.arguments).containsExactly("{\"q\":\"Kunde zum Ticket\"}");
    }

    @Test void emptyInternalResultDoesNotMarkTheQuestion() {
        internal.result = "";
        var turn = question();
        turn.call("internal__lookup", "{\"q\":\"Kunde\"}");
        assertThat(jdbc.queryForObject("select internal_seen from tool_question where prompt_message_id=1", Boolean.class)).isFalse();
    }

    @Test void externalResultsAreKeptAsSeparateContiguousSources() {
        external.result = "Treffer 1 von 9, Seite 0";
        var turn = question();
        turn.call("external__lookup", "{\"q\":\"Ticket\"}");
        internal.result = "Gehalt 9100";
        turn.call("internal__lookup", "{\"q\":\"Kunde\"}");
        // every digit of 9100 is public somewhere, but never as one contiguous value
        var spelled = turn.call("external__lookup", "{\"q\":\"9 1 0 0\"}");
        assertThat(spelled.invoked()).isFalse();
        assertThat(external.invocations).hasValue(1);
        var stored = jdbc.queryForObject("select public_tokens from tool_question where prompt_message_id=1", String.class);
        assertThat(M.readTree(stored)).hasSize(1);
        assertThat(M.readTree(stored).get(0).asString()).contains("Treffer 1 von 9");
    }

    /**
     * Re-review PR #26 (major): Unicode separators between otherwise public words encoded a private
     * number (codepoint − U+2000 = digit) and the call ran automatically. Through the real central service:
     * a card instead of an external call, and the exact text stays on the card.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
            "public\\u2009ticket\\u2001lookup\\u2000result\\u2000page",   // the reproduced 9100 encoding
            "public\\u00A0ticket lookup result page", "public\\u202Fticket lookup result page",
            "public\\u3000ticket lookup result page", "public\\u1680ticket lookup result page",
            "public\\u205Fticket lookup result page", "public\\u2028ticket lookup result page",
            "public\\u0085ticket lookup result page", "public\\tticket lookup result page",
            "public  ticket lookup result page", "public ticket lookup result page ", "\\u2000public ticket lookup result page",
            "cafe\\u0301 public ticket lookup result page"})
    void nonCanonicalSpellingOfPublicTextBecomesACardNotAnExternalCall(String value) {
        external.result = "public ticket lookup result page café public ticket lookup result page";
        var turn = question();
        assertThat(turn.call("external__lookup", "{\"q\":\"Ticket\"}").invoked()).isTrue();
        internal.result = "Gehalt 9100";
        turn.call("internal__lookup", "{\"q\":\"Kunde\"}");
        String args = "{\"q\":\"" + value + "\"}";

        var outcome = turn.call("external__lookup", args);

        assertThat(outcome.invoked()).isFalse();
        assertThat(external.invocations).hasValue(1);
        var card = service.cards(1L, 1L, 1L).stream().filter(c -> c.status().equals("PENDING")).toList();
        assertThat(card).singleElement().satisfies(c -> {
            assertThat(c.mayContainInternal()).isTrue();
            assertThat(M.readTree(c.argumentsText()).path("q").asString()).isEqualTo(M.readTree(args).path("q").asString());
        });
    }

    @Test void canonicalPublicTextStillRunsAutomatically() {
        external.result = "public ticket lookup result page";
        var turn = question();
        turn.call("external__lookup", "{\"q\":\"Ticket\"}");
        internal.result = "Gehalt 9100";
        turn.call("internal__lookup", "{\"q\":\"Kunde\"}");
        assertThat(turn.call("external__lookup", "{\"q\":\"public ticket lookup result page\"}").invoked()).isTrue();
        assertThat(external.arguments.getLast()).isEqualTo("{\"q\":\"public ticket lookup result page\"}");
    }
}
