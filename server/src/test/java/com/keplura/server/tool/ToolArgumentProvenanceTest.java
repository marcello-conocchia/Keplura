package com.keplura.server.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentProvenanceTest {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final tools.jackson.databind.JsonNode schema=mapper.readTree("""
        {"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer","default":5},
        "mode":{"enum":["exact","fuzzy"]},"nested":{"type":"object","properties":{"value":{"type":"string"}}},
        "ids":{"type":"array","items":{"type":"string"}}}}
        """);
    private static ToolArgumentProvenance.Sources sources(String question,String... external) {
        return ToolArgumentProvenance.Sources.of(question,List.of(external));
    }
    private boolean allowed(String args,ToolArgumentProvenance.Sources sources) {
        return ToolArgumentProvenance.allowed(mapper.readTree(args),schema,sources);
    }

    @ParameterizedTest
    @ValueSource(strings={"{\"query\":\"PUBLIC ticket 42\"}","{\"query\":\"ticket 42 café\"}","{\"query\":\"Ticket 42 Café\"}",
            "{\"count\":5,\"mode\":\"exact\"}","{\"nested\":{\"value\":\"ticket\"}}","{\"flag\":null}","{\"flag\":true}",
            "{\"query\":\"42\"}","{\"query\":\"\"}"})
    void contiguousValuesFromTheQuestionAndSchemaValuesAreAutomatic(String args) {
        assertThat(allowed(args,sources("Public ticket 42 café flag"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"{\"query\":\"PRIVATE_SALARY_9100\"}","{\"query\":\"public PRIVATE_SALARY_9100 ticket\"}",
            "{\"query\":\"PrivateSalary9100\"}","{\"query\":\"public 9100\"}","{\"PRIVATE_NAME\":true}",
            "{\"nested\":{\"value\":\"private marker\"}}","{\"query\":\"ticket@\"}",
            // reordering / recombining public tokens is a free encoding channel
            "{\"query\":\"café 42\"}","{\"query\":\"42 ticket\"}",
            // whitespace variants could encode data while the tokens stay equal
            "{\"query\":\" public   ticket \\n42\"}","{\"query\":\"public  ticket\"}","{\"query\":\"ticket \"}"})
    void anythingNotLiterallyContiguousRequiresDisclosure(String args) {
        assertThat(allowed(args,sources("Public ticket 42 café flag"))).isFalse();
    }

    /** Review PR #26: digits/letters that are public one by one must not spell a private value. */
    @ParameterizedTest
    @ValueSource(strings={"{\"query\":\"9 1 0 0\"}","{\"query\":\"9-1-0-0\"}","{\"query\":\"9.1.0.0\"}",
            "{\"ids\":[\"9\",\"1\",\"0\",\"0\"]}","{\"query\":\"9\"}","{\"query\":\"a n n a\"}","{\"ids\":[\"a\",\"n\",\"n\",\"a\"]}",
            "{\"query\":\"Seite 0 Treffer 1\"}"})
    void spelledOrSplitPrivateValuesNeverRunAutomatically(String args) {
        // question and an ordinary external result: every single digit/letter above occurs publicly somewhere
        var s=sources("Suche Ticket zum Druckerproblem","Treffer 1 von 9, Seite 0 - 2 offene Tickets; a n gezeigt");
        assertThat(allowed(args,s)).isFalse();
    }

    @Test void contiguousExternalResultsStayValidSources() {
        var s=sources("ticket","External result: ABC 123, ticket T-4711 is open");
        assertThat(allowed("{\"query\":\"ABC 123\"}",s)).isTrue();
        assertThat(allowed("{\"query\":\"T-4711\"}",s)).isTrue();
        assertThat(allowed("{\"query\":\"ticket T-4711 is open\"}",s)).isTrue();
        // only one source per value: no stitching across the question and a result
        assertThat(allowed("{\"query\":\"ABC 123 ticket\"}",sources("ABC 123","ticket only"))).isFalse();
    }

    @Test void shortValuesOnlyFromTheQuestionItself() {
        var s=sources("Seite 2 bitte","Ergebnis 7 von 9");
        assertThat(allowed("{\"query\":\"2\"}",s)).isTrue();
        assertThat(allowed("{\"query\":\"7\"}",s)).isFalse();
        assertThat(allowed("{\"query\":\"7 von 9\"}",s)).isTrue();
    }

    @Test void tooManyFreeValuesInOneCallNeedACard() {
        var s=sources("alpha beta gamma delta epsilon zeta theta iota kappa");
        assertThat(allowed("{\"ids\":[\"alpha\",\"beta\",\"gamma\",\"delta\",\"epsilon\",\"zeta\",\"theta\",\"iota\"]}",s)).isTrue();
        assertThat(allowed("{\"ids\":[\"alpha\",\"beta\",\"gamma\",\"delta\",\"epsilon\",\"zeta\",\"theta\",\"iota\",\"kappa\"]}",s)).isFalse();
    }

    /** Re-review PR #26 (minor): structured schema values must not bypass the canonical-text check. */
    @ParameterizedTest
    @ValueSource(strings={
            "{\"type\":\"object\",\"default\":{\"q\":\"public\\u2009ticket\"}}",
            "{\"type\":\"object\",\"const\":{\"q\":\"public\\u2009ticket\"}}",
            "{\"type\":\"object\",\"enum\":[{\"q\":\"public\\u2009ticket\"}]}",
            "{\"type\":\"array\",\"default\":[\"public\\u2009ticket\"]}",
            "{\"type\":\"object\",\"default\":{\"q\":[\"public  ticket\"]}}",
            "{\"type\":\"object\",\"default\":{\"q\":\"cafe\\u0301\"}}",
            "{\"type\":\"object\",\"default\":{\"k\\u2009ey\":\"public ticket\"}}"})
    void structuredSchemaValuesNeedCanonicalTextToo(String rawSchema) {
        var structured=mapper.readTree(rawSchema);
        var args=structured.has("default")?structured.get("default"):structured.has("const")?structured.get("const"):structured.get("enum").get(0);
        // the value equals the schema block exactly, but its strings are not canonical → card
        assertThat(ToolArgumentProvenance.allowed(args,structured,sources("unrelated"))).isFalse();
    }

    @Test void canonicalStructuredSchemaValuesStayAutomatic() {
        var structured=mapper.readTree("{\"type\":\"object\",\"default\":{\"q\":\"public ticket\",\"tags\":[\"a b\",\"café\"]}}");
        assertThat(ToolArgumentProvenance.allowed(structured.get("default"),structured,sources("unrelated"))).isTrue();
    }
}
