package com.keplura.server.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentValidatorTest {

    private static final JsonMapper M = JsonMapper.builder().build();

    private static JsonNode j(String json) {
        return M.readTree(json);
    }

    private static final JsonNode ECHO = j("""
            {"$schema":"http://json-schema.org/draft-07/schema#","type":"object",
             "properties":{"message":{"type":"string","description":"Text","minLength":1,"maxLength":10},
                           "count":{"type":"integer","minimum":1,"maximum":5},
                           "mode":{"enum":["a","b"]},
                           "tags":{"type":"array","items":{"type":"string"},"maxItems":2},
                           "opt":{"anyOf":[{"type":"string"},{"type":"null"}]}},
             "required":["message"],"additionalProperties":false}""");

    @Test void commonMcpSchemasAreSupported() {
        assertThat(ToolArgumentValidator.unsupportedReason(ECHO)).isNull();
        assertThat(ToolArgumentValidator.unsupportedReason(null)).isNull();
        assertThat(ToolArgumentValidator.unsupportedReason(j("{\"type\":\"object\"}"))).isNull();
    }

    @Test void unsupportedKeywordsBlockTheWholeTool() {
        for (String schema : new String[]{
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"^(a+)+$\"}}}",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/x\"}},\"$defs\":{\"x\":{}}}",
                "{\"type\":\"object\",\"if\":{},\"then\":{}}",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}}}",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"date\"}}}",
                "{\"type\":\"string\"}",
                "[]"}) {
            assertThat(ToolArgumentValidator.unsupportedReason(j(schema))).as(schema).isNotNull();
            assertThat(ToolArgumentValidator.validate(j(schema), j("{}"))).as(schema).isNotNull();
        }
    }

    @Test void deeplyNestedSchemasAreRejected() {
        String inner = "{\"type\":\"string\"}";
        for (int i = 0; i < ToolArgumentValidator.MAX_DEPTH + 2; i++) {
            inner = "{\"type\":\"object\",\"properties\":{\"a\":" + inner + "}}";
        }
        assertThat(ToolArgumentValidator.unsupportedReason(j(inner))).isNotNull();
    }

    @Test void validArgumentsPass() {
        assertThat(ToolArgumentValidator.validate(ECHO, j("{\"message\":\"hi\"}"))).isNull();
        assertThat(ToolArgumentValidator.validate(ECHO,
                j("{\"message\":\"hi\",\"count\":3.0,\"mode\":\"b\",\"tags\":[\"x\"],\"opt\":null}"))).isNull();
    }

    @Test void invalidArgumentsAreRejected() {
        for (String args : new String[]{
                "{}",                                  // required missing
                "{\"message\":5}",                     // wrong type
                "{\"message\":\"\"}",                  // minLength
                "{\"message\":\"01234567890\"}",       // maxLength
                "{\"message\":\"x\",\"count\":0}",     // minimum
                "{\"message\":\"x\",\"count\":2.5}",   // integer
                "{\"message\":\"x\",\"mode\":\"c\"}",  // enum
                "{\"message\":\"x\",\"tags\":[1]}",    // items
                "{\"message\":\"x\",\"tags\":[\"a\",\"b\",\"c\"]}", // maxItems
                "{\"message\":\"x\",\"opt\":3}",       // anyOf
                "{\"message\":\"x\",\"extra\":1}",     // additionalProperties:false
        }) {
            assertThat(ToolArgumentValidator.validate(ECHO, j(args))).as(args).isNotNull();
        }
        assertThat(ToolArgumentValidator.validate(ECHO, j("[]"))).isNotNull();
    }

    @Test void missingSchemaMeansNoArguments() {
        assertThat(ToolArgumentValidator.validate(null, j("{}"))).isNull();
        assertThat(ToolArgumentValidator.validate(null, j("{\"a\":1}"))).isNotNull();
    }

    @Test void oneOfNeedsExactlyOneMatch() {
        JsonNode s = j("{\"type\":\"object\",\"properties\":{\"v\":{\"oneOf\":[{\"type\":\"number\"},{\"type\":\"integer\"}]}}}");
        assertThat(ToolArgumentValidator.validate(s, j("{\"v\":1.5}"))).isNull();
        assertThat(ToolArgumentValidator.validate(s, j("{\"v\":1}"))).isNotNull();
    }

    // --- Review round 1 (PR #15): invalid schemas fail closed, huge numbers never throw ---

    @Test void invalidSchemaShapesAreBlockedNotTreatedAsMissing() {
        for (String schema : new String[]{"false", "true", "null", "[]", "\"object\"", "42", "{}"}) {
            JsonNode node = j(schema);
            assertThat(ToolArgumentValidator.absent(node)).as(schema).isFalse();
            assertThat(ToolArgumentValidator.unsupportedReason(node)).as(schema).isNotNull();
            assertThat(ToolArgumentValidator.validate(node, j("{}"))).as(schema).isNotNull();
        }
        assertThat(ToolArgumentValidator.absent(M.missingNode())).isTrue();
        assertThat(ToolArgumentValidator.absent(null)).isTrue();
    }

    @Test void hugeOrNonFiniteSchemaNumbersBlockWithoutException() {
        for (String bound : new String[]{
                "\"minLength\":18446744073709551617", "\"maxItems\":99999999999999999999999",
                "\"minLength\":-1", "\"maxLength\":1.5", "\"minimum\":1e400", "\"maximum\":-1e400",
                "\"exclusiveMinimum\":\"1\""}) {
            String schema = "{\"type\":\"object\",\"properties\":{\"x\":{" + bound + "}}}";
            assertThat(ToolArgumentValidator.unsupportedReason(j(schema))).as(schema).isNotNull();
            assertThat(ToolArgumentValidator.validate(j(schema), j("{\"x\":\"a\"}"))).as(schema).isNotNull();
        }
        for (String value : new String[]{"[1e400]", "{\"a\":1e400}"}) {
            assertThat(ToolArgumentValidator.unsupportedReason(j(
                    "{\"type\":\"object\",\"properties\":{\"x\":{\"enum\":[" + value + "]}}}"))).as(value).isNotNull();
        }
    }

    @Test void hugeBigIntegerBoundsStillWorkWhenFinite() {
        JsonNode s = j("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\",\"maximum\":18446744073709551617}}}");
        assertThat(ToolArgumentValidator.unsupportedReason(s)).isNull();
        assertThat(ToolArgumentValidator.validate(s, j("{\"x\":18446744073709551616}"))).isNull();
        assertThat(ToolArgumentValidator.validate(s, j("{\"x\":18446744073709551618}"))).isNotNull();
    }

    @Test void nonFiniteArgumentsAreRejectedWithoutException() {
        JsonNode s = j("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\",\"minimum\":0}}}");
        assertThat(ToolArgumentValidator.validate(s, j("{\"x\":1e400}"))).isNotNull();
        assertThat(ToolArgumentValidator.validate(j("{\"type\":\"object\"}"), j("{\"x\":-1e400}"))).isNotNull();
    }

    @Test void enumAndConstCompareByJsonSchemaValueEquality() {
        JsonNode e = j("{\"type\":\"object\",\"properties\":{\"x\":{\"enum\":[1, [2, {\"a\":3}]]},\"c\":{\"const\":{\"n\":[1.0]}}}}");
        assertThat(ToolArgumentValidator.validate(e, j("{\"x\":1.0}"))).isNull();
        assertThat(ToolArgumentValidator.validate(e, j("{\"x\":[2.0, {\"a\":3.00}]}"))).isNull();
        assertThat(ToolArgumentValidator.validate(e, j("{\"c\":{\"n\":[1]}}"))).isNull();
        assertThat(ToolArgumentValidator.validate(e, j("{\"x\":1.5}"))).isNotNull();
        assertThat(ToolArgumentValidator.validate(e, j("{\"x\":\"1\"}"))).isNotNull();
        assertThat(ToolArgumentValidator.validate(e, j("{\"c\":{\"n\":[1],\"m\":1}}"))).isNotNull();
    }
}
