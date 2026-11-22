package com.keplura.server.tool;

import tools.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Literal provenance proof, never a model judgement. Internal results are never sources.
 *
 * <p>Every string or number value must appear as ONE contiguous token sequence in the person's question or in
 * one external result. A bag of individually public tokens is not enough, otherwise "9 1 0 0" could spell a
 * private 9100 from public digits. Very short values may only come from the question itself, so data cannot
 * leave one character per element or per call. The number of free values per call is capped, and any
 * non-canonical spelling (Unicode whitespace other than single U+0020, leading/trailing space, non-NFC text)
 * is refused because it could encode data while the tokens stay equal.
 */
public final class ToolArgumentProvenance {
    /** More free values than this in one call need a confirmation card. */
    public static final int MAX_AUTOMATIC_VALUES = 8;
    /** Values with fewer characters (whitespace ignored) are only accepted from the question itself. */
    public static final int MIN_SOURCE_VALUE_CHARS = 3;
    /**
     * ONE whitespace definition for the tokenizer AND the canonical-text check: {@code \s} under
     * UNICODE_CHARACTER_CLASS = Unicode White_Space (U+00A0, U+2000–U+200A, U+202F, U+3000, …). If the two
     * disagreed, characters the tokenizer drops could still reach the external tool and carry data.
     */
    private static final String SPACE = "\\s";
    private static final Pattern TOKENS = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+|[^\\p{L}\\p{M}\\p{N}" + SPACE + "]",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WHITESPACE_RUN = Pattern.compile(SPACE + "+", Pattern.UNICODE_CHARACTER_CLASS);
    private ToolArgumentProvenance() { }

    /**
     * Only one exact spelling of a value is automatic: already NFC (NFC/NFD would be a free bit per character)
     * and whitespace only as single U+0020 between tokens, nothing leading or trailing.
     */
    static boolean canonicalText(String value) {
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) return false;
        if (!WHITESPACE_RUN.matcher(value).replaceAll(" ").equals(value)) return false;
        return !(value.startsWith(" ") || value.endsWith(" "));
    }

    /** Token sequences of the question and of every public source (the question included). */
    public record Sources(List<String> question, List<List<String>> all) {
        public static Sources of(String question, List<String> publicTexts) {
            List<String> q = tokens(question == null ? "" : question);
            List<List<String>> all = new ArrayList<>();
            all.add(q);
            for (String text : publicTexts) if (text != null) all.add(tokens(text));
            return new Sources(q, all);
        }
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    /** Ordered tokens (duplicates kept): letter/digit runs and single other non-space characters. */
    public static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        var matcher = TOKENS.matcher(normalized(text));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    public static boolean allowed(JsonNode args, JsonNode schema, Sources sources) {
        return allowed(args, schema, sources, new int[]{0});
    }

    private static boolean allowed(JsonNode args, JsonNode schema, Sources sources, int[] values) {
        if (args.isNull() || args.isBoolean()) return true;
        if (schema != null && schema.isObject()) {
            if (schema.has("const") && same(args, schema.get("const"))) return true;
            if (schema.has("default") && same(args, schema.get("default"))) return true;
            if (schema.path("enum").isArray()) for (JsonNode value : schema.path("enum")) if (same(args, value)) return true;
        }
        if (args.isObject()) {
            for (var e : args.properties()) {
                JsonNode child = schema == null ? null : schema.path("properties").get(e.getKey());
                if (child == null) {
                    // Dynamic object keys can carry private data too.
                    if (!literal(e.getKey(), sources, values)) return false;
                    child = schema == null ? null : schema.get("additionalProperties");
                }
                if (!allowed(e.getValue(), child, sources, values)) return false;
            }
            return true;
        }
        if (args.isArray()) {
            for (JsonNode value : args) if (!allowed(value, schema == null ? null : schema.get("items"), sources, values)) return false;
            return true;
        }
        return literal(args.isString() ? args.asString() : args.toString(), sources, values);
    }

    private static boolean literal(String value, Sources sources, int[] values) {
        if (++values[0] > MAX_AUTOMATIC_VALUES) return false;
        if (!canonicalText(value)) return false;
        List<String> needle = tokens(value);
        if (needle.isEmpty()) return value.isEmpty();
        int chars = 0;
        for (String token : needle) chars += token.length();
        if (chars < MIN_SOURCE_VALUE_CHARS) return Collections.indexOfSubList(sources.question(), needle) >= 0;
        for (List<String> source : sources.all()) if (Collections.indexOfSubList(source, needle) >= 0) return true;
        return false;
    }

    private static boolean same(JsonNode a, JsonNode b) {
        // Schema values too: the raw spelling must be canonical, otherwise whitespace/NFD would carry data.
        // Structured enum/const/default values (objects, lists) are checked recursively, keys included.
        if (!allCanonical(a)) return false;
        if (a.isString() && b.isString()) return normalized(a.asString()).equals(normalized(b.asString()));
        return a.equals(b);
    }

    private static boolean allCanonical(JsonNode value) {
        if (value.isString()) return canonicalText(value.asString());
        if (value.isObject()) {
            for (var e : value.properties()) if (!canonicalText(e.getKey()) || !allCanonical(e.getValue())) return false;
            return true;
        }
        if (value.isArray()) {
            for (JsonNode item : value) if (!allCanonical(item)) return false;
        }
        return true;
    }
}
