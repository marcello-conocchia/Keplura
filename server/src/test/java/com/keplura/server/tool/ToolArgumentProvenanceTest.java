package com.keplura.server.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentProvenanceTest {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final tools.jackson.databind.JsonNode schema=mapper.readTree("""
        {"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer","default":5},
        "mode":{"enum":["exact","fuzzy"]},"nested":{"type":"object","properties":{"value":{"type":"string"}}}}}
        """);
    @ParameterizedTest
    @ValueSource(strings={"{\"query\":\"PUBLIC ticket 42\"}","{\"query\":\"café 42\"}",
            "{\"query\":\" public   ticket \\n42\"}","{\"count\":5,\"mode\":\"exact\"}",
            "{\"nested\":{\"value\":\"ticket\"}}","{\"flag\":null}","{\"flag\":true}"})
    void allowedVocabularyAndSchemaValuesAreLiteral(String args) {
        assertThat(ToolArgumentProvenance.allowed(mapper.readTree(args),schema,
                ToolArgumentProvenance.tokens("Public ticket 42 café flag"))).isTrue();
    }
    @ParameterizedTest
    @ValueSource(strings={"{\"query\":\"PRIVATE_SALARY_9100\"}","{\"query\":\"public PRIVATE_SALARY_9100 ticket\"}",
            "{\"query\":\"PrivateSalary9100\"}","{\"query\":\"public 9100\"}","{\"PRIVATE_NAME\":true}",
            "{\"nested\":{\"value\":\"private marker\"}}","{\"query\":\"ticket@\"}"})
    void anyUnknownWordNumberOrDynamicKeyRequiresDisclosure(String args) {
        assertThat(ToolArgumentProvenance.allowed(mapper.readTree(args),schema,
                ToolArgumentProvenance.tokens("Public ticket 42 café flag"))).isFalse();
    }
    @Test void publicExternalResultsCanExtendTheVocabulary() {
        var tokens=ToolArgumentProvenance.tokens("ticket");
        tokens.addAll(ToolArgumentProvenance.tokens("External result: ABC 123"));
        assertThat(ToolArgumentProvenance.allowed(mapper.readTree("{\"query\":\"ABC 123\"}"),schema,tokens)).isTrue();
    }
}
