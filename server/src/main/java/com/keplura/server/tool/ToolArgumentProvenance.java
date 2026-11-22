package com.keplura.server.tool;

import tools.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Literal vocabulary proof, never a model judgement. Internal results are never sources. */
public final class ToolArgumentProvenance {
    private static final Pattern TOKENS = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+|[^\\p{L}\\p{M}\\p{N}\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    private ToolArgumentProvenance() { }

    private static String normalized(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
    public static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = TOKENS.matcher(normalized(text));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
    public static boolean allowed(JsonNode args, JsonNode schema, Set<String> publicTokens) {
        if (args.isNull() || args.isBoolean()) return true;
        if (schema!=null && schema.isObject()) {
            if (schema.has("const") && same(args,schema.get("const"))) return true;
            if (schema.has("default") && same(args,schema.get("default"))) return true;
            if (schema.path("enum").isArray()) for (JsonNode value:schema.path("enum")) if(same(args,value)) return true;
        }
        if (args.isObject()) {
            for (var e:args.properties()) {
                JsonNode child = schema==null ? null : schema.path("properties").get(e.getKey());
                if (child==null) {
                    // Dynamic object keys can carry private data too.
                    if (!publicTokens.containsAll(tokens(e.getKey()))) return false;
                    child = schema==null ? null : schema.get("additionalProperties");
                }
                if (!allowed(e.getValue(), child, publicTokens)) return false;
            }
            return true;
        }
        if (args.isArray()) {
            for (JsonNode value:args) if(!allowed(value,schema==null ? null : schema.get("items"),publicTokens)) return false;
            return true;
        }
        String value=args.isString()?args.asString():args.toString();
        return publicTokens.containsAll(tokens(value));
    }
    private static boolean same(JsonNode a,JsonNode b) {
        if(a.isString() && b.isString()) return normalized(a.asString()).equals(normalized(b.asString()));
        return a.equals(b);
    }
}
