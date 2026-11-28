package com.keplura.server.openapi;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding 7: an OpenAPI document cannot escape the fixed base path (also not via percent-encoding an
 * upstream may decode and normalize) and cannot smuggle extra approvals through control characters in an
 * operationId (approvals are stored one name per line).
 */
class OpenApiPathSafetyTest {

    @Test void encodedTraversalMatrixParametersAndDotSegmentsAreBlocked() {
        assertThat(OpenApiDocument.safePath("/items/{id}")).isTrue();
        assertThat(OpenApiDocument.safePath("/v1/users/{user}/files")).isTrue();
        for (String bad : new String[]{"/%2e%2e/admin", "/a/%2E%2E/b", "/a%2fb", "/a/..;/admin", "/a;x=1", "/a/./b", "/a//b",
                "/a/../b", "/a\\b", "/a?x=1", "/a#f", "items", "/{a/b}", "/{x y}", "/a\nb"}) {
            assertThat(OpenApiDocument.safePath(bad)).as(bad).isFalse();
        }
    }

    @Test void finalUrlMustStayBelowTheBasePathAfterCanonicalization() {
        String base = "https://api.example.com/tenant/v1";
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/items/42?q=1")).isTrue();
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/items/a%2520b")).isTrue(); // encoded "%20": harmless
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/../admin")).isFalse();
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/items/%2e%2e")).isFalse();
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/items/%252e%252e")).isFalse(); // decoded twice
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/items/a%2fb")).isFalse();
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/items/..%3bparam")).isFalse();
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/items/..%253bparam")).isFalse();
        assertThat(OpenApiToolAdapter.containedIn(base, base + "/items/..%25253bparam")).isFalse();
        assertThat(OpenApiToolAdapter.containedIn(base + "%3bparam", base + "%3bparam/items")).isFalse();
        assertThat(OpenApiToolAdapter.containedIn(base, "https://api.example.com/tenant/v10/x")).isFalse();
        assertThat(OpenApiToolAdapter.containedIn(base, "https://other.example.com/tenant/v1/x")).isFalse();
    }

    @Test void operationIdsWithControlCharactersAreNotUsable() {
        String doc = """
                {"openapi":"3.0.3","info":{"title":"T","version":"1"},"paths":{
                 "/a":{"get":{"operationId":"listA\\ndeleteAll"}},
                 "/b":{"get":{"operationId":"listB\\u2028x"}},
                 "/c":{"get":{"operationId":"getC"}},
                 "/%2e%2e/admin":{"get":{"operationId":"escape"}}}}""";
        var ops = OpenApiDocument.parse(doc.getBytes(StandardCharsets.UTF_8));
        assertThat(ops).filteredOn(o -> o.problem() == null).extracting(OpenApiDocument.Operation::operationId).containsExactly("getC");
        assertThat(ops).filteredOn(o -> "/a".equals(o.path())).singleElement()
                .satisfies(o -> assertThat(o.problem()).contains("Steuerzeichen"));
        assertThat(ops).filteredOn(o -> "/%2e%2e/admin".equals(o.path())).singleElement()
                .satisfies(o -> assertThat(o.problem()).contains("Pfad"));
        assertThat(OpenApiDocument.safeOperationId("a\nb")).isFalse();
        assertThat(OpenApiDocument.safeOperationId("list_items-v2.1")).isTrue();
    }
}
