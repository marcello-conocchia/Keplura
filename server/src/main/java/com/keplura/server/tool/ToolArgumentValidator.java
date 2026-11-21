package com.keplura.server.tool;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Prüft Werkzeugargumente gegen eine bewusst kleine, vollständig unterstützte Teilmenge von JSON
 * Schema. Enthält ein Schema ein nicht unterstütztes Schlüsselwort (z. B. {@code $ref},
 * {@code pattern}, {@code if}/{@code then}, Tupel-{@code items}), gilt das ganze Werkzeug als nicht
 * prüfbar und wird gesperrt — es wird nie ungeprüft ausgeführt.
 *
 * <p>Unterstützt: {@code type} (auch als Liste), {@code properties}, {@code required},
 * {@code additionalProperties} (boolesch oder Schema), {@code minProperties}/{@code maxProperties},
 * {@code enum}, {@code const}, {@code items} (ein Schema), {@code minItems}/{@code maxItems},
 * {@code minLength}/{@code maxLength}, {@code minimum}/{@code maximum},
 * {@code exclusiveMinimum}/{@code exclusiveMaximum} (Zahl), {@code anyOf}/{@code oneOf}/{@code allOf}
 * und boolesche Schemas. Reine Anmerkungen ({@code description}, {@code title}, {@code default},
 * {@code format}, …) werden ignoriert.
 */
public final class ToolArgumentValidator {

    static final int MAX_DEPTH = 16;

    private static final Set<String> ANNOTATIONS = Set.of("title", "description", "default", "examples", "$schema",
            "$id", "$comment", "format", "readOnly", "writeOnly", "deprecated", "contentMediaType", "contentEncoding");
    private static final Set<String> TYPES = Set.of("object", "string", "number", "integer", "boolean", "array", "null");
    private static final Set<String> COUNTS = Set.of("minItems", "maxItems", "minLength", "maxLength",
            "minProperties", "maxProperties");
    private static final Set<String> BOUNDS = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");

    private ToolArgumentValidator() {
    }

    /**
     * {@code null}, wenn das Eingabeschema vollständig unterstützt wird, sonst ein kurzer Grund.
     * Ein fehlendes Schema bedeutet „keine Argumente“ (leeres Objekt) und ist unterstützt.
     */
    public static String unsupportedReason(JsonNode schema) {
        if (absent(schema)) return null;
        if (!schema.isObject() || !"object".equals(schema.path("type").asString(""))) {
            return "Das Eingabeschema muss ein Objekt beschreiben.";
        }
        return check(schema, 0);
    }

    /** {@code null}, wenn die Argumente zum (unterstützten) Schema passen, sonst ein generischer Grund. */
    public static String validate(JsonNode schema, JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) return "Die Werkzeugargumente sind kein JSON-Objekt.";
        if (absent(schema)) {
            return arguments.isEmpty() ? null : "Dieses Werkzeug erwartet keine Argumente.";
        }
        String unsupported = unsupportedReason(schema);
        if (unsupported != null) return unsupported;
        String problem = errorAt(schema, arguments, "", 0);
        return problem == null ? null : "Die Werkzeugargumente passen nicht zum Eingabeschema" + problem + ".";
    }

    private static boolean absent(JsonNode schema) {
        return schema == null || schema.isMissingNode() || schema.isNull();
    }

    private static String check(JsonNode schema, int depth) {
        if (depth > MAX_DEPTH) return "Das Eingabeschema ist zu tief verschachtelt.";
        if (schema.isBoolean()) return null;
        if (!schema.isObject()) return "Das Eingabeschema enthält einen ungültigen Teil.";
        for (Map.Entry<String, JsonNode> e : schema.properties()) {
            String key = e.getKey();
            JsonNode v = e.getValue();
            if (ANNOTATIONS.contains(key)) continue;
            String problem = switch (key) {
                case "type" -> validType(v) ? null : "ungültiger Typ";
                case "properties" -> {
                    if (!v.isObject()) yield "ungültige Eigenschaften";
                    for (Map.Entry<String, JsonNode> p : v.properties()) {
                        String sub = check(p.getValue(), depth + 1);
                        if (sub != null) yield sub;
                    }
                    yield null;
                }
                case "required" -> {
                    if (!v.isArray()) yield "ungültige Pflichtfelder";
                    for (JsonNode n : v) if (!n.isString()) yield "ungültige Pflichtfelder";
                    yield null;
                }
                case "additionalProperties", "items" -> v.isArray() ? "Tupel-Schemas werden nicht unterstützt"
                        : check(v, depth + 1);
                case "enum" -> v.isArray() && !v.isEmpty() ? null : "ungültige Werteliste";
                case "const" -> null;
                case "anyOf", "oneOf", "allOf" -> {
                    if (!v.isArray() || v.isEmpty()) yield "ungültige Kombination";
                    for (JsonNode n : v) {
                        String sub = check(n, depth + 1);
                        if (sub != null) yield sub;
                    }
                    yield null;
                }
                default -> {
                    if (COUNTS.contains(key)) yield v.isIntegralNumber() && v.asLong() >= 0 ? null : "ungültige Grenze";
                    if (BOUNDS.contains(key)) yield v.isNumber() ? null : "ungültige Grenze";
                    yield "nicht unterstütztes Schlüsselwort";
                }
            };
            if (problem != null) {
                return problem.startsWith("Das Eingabeschema") ? problem
                        : "Das Eingabeschema wird nicht unterstützt (" + problem + ": " + shorten(key) + ").";
            }
        }
        return null;
    }

    private static boolean validType(JsonNode v) {
        if (v.isString()) return TYPES.contains(v.asString());
        if (!v.isArray() || v.isEmpty()) return false;
        for (JsonNode n : v) if (!n.isString() || !TYPES.contains(n.asString())) return false;
        return true;
    }

    /** {@code null} = gültig; sonst Pfadhinweis wie {@code " (Feld: a.b)"}. */
    private static String errorAt(JsonNode schema, JsonNode value, String path, int depth) {
        if (depth > MAX_DEPTH) return at(path);
        if (schema.isBoolean()) return schema.asBoolean() ? null : at(path);
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) return at(path);
        JsonNode en = schema.get("enum");
        if (en != null) {
            boolean found = false;
            for (JsonNode option : en) if (option.equals(value)) { found = true; break; }
            if (!found) return at(path);
        }
        if (schema.has("const") && !schema.get("const").equals(value)) return at(path);
        if (value.isString()) {
            int len = value.asString().codePointCount(0, value.asString().length());
            if (tooSmall(schema, "minLength", len) || tooLarge(schema, "maxLength", len)) return at(path);
        }
        if (value.isNumber()) {
            BigDecimal n = value.decimalValue();
            if (schema.has("minimum") && n.compareTo(schema.get("minimum").decimalValue()) < 0) return at(path);
            if (schema.has("maximum") && n.compareTo(schema.get("maximum").decimalValue()) > 0) return at(path);
            if (schema.has("exclusiveMinimum") && n.compareTo(schema.get("exclusiveMinimum").decimalValue()) <= 0) return at(path);
            if (schema.has("exclusiveMaximum") && n.compareTo(schema.get("exclusiveMaximum").decimalValue()) >= 0) return at(path);
        }
        if (value.isArray()) {
            if (tooSmall(schema, "minItems", value.size()) || tooLarge(schema, "maxItems", value.size())) return at(path);
            JsonNode items = schema.get("items");
            if (items != null) {
                int i = 0;
                for (JsonNode item : value) {
                    String sub = errorAt(items, item, path + "[" + i++ + "]", depth + 1);
                    if (sub != null) return sub;
                }
            }
        }
        if (value.isObject()) {
            if (tooSmall(schema, "minProperties", value.size()) || tooLarge(schema, "maxProperties", value.size())) return at(path);
            JsonNode props = schema.path("properties");
            for (JsonNode req : schema.path("required")) {
                if (!value.has(req.asString())) return at(join(path, req.asString()));
            }
            JsonNode additional = schema.get("additionalProperties");
            for (Map.Entry<String, JsonNode> e : value.properties()) {
                JsonNode sub = props.isObject() ? props.get(e.getKey()) : null;
                if (sub == null) sub = additional;
                if (sub == null) continue; // JSON Schema default: additional properties allowed
                String problem = errorAt(sub, e.getValue(), join(path, e.getKey()), depth + 1);
                if (problem != null) return problem;
            }
        }
        JsonNode all = schema.get("allOf");
        if (all != null) for (JsonNode s : all) {
            String sub = errorAt(s, value, path, depth + 1);
            if (sub != null) return sub;
        }
        JsonNode any = schema.get("anyOf");
        if (any != null) {
            boolean ok = false;
            for (JsonNode s : any) if (errorAt(s, value, path, depth + 1) == null) { ok = true; break; }
            if (!ok) return at(path);
        }
        JsonNode one = schema.get("oneOf");
        if (one != null) {
            int matches = 0;
            for (JsonNode s : one) if (errorAt(s, value, path, depth + 1) == null) matches++;
            if (matches != 1) return at(path);
        }
        return null;
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isString()) return typeMatches(type.asString(), value);
        Set<String> options = new HashSet<>();
        for (JsonNode t : type) options.add(t.asString());
        for (String t : options) if (typeMatches(t, value)) return true;
        return false;
    }

    private static boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber()
                    || (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0);
            default -> false;
        };
    }

    private static boolean tooSmall(JsonNode schema, String key, long actual) {
        return schema.has(key) && actual < schema.get(key).asLong();
    }

    private static boolean tooLarge(JsonNode schema, String key, long actual) {
        return schema.has(key) && actual > schema.get(key).asLong();
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String at(String path) {
        return path.isEmpty() ? "" : " (Feld: " + shorten(path) + ")";
    }

    private static String shorten(String s) {
        String clean = s.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() > 80 ? clean.substring(0, 80) + "…" : clean;
    }
}
