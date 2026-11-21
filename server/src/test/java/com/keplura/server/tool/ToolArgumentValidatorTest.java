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
}
