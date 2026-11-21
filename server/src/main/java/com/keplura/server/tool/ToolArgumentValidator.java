package com.keplura.server.tool;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Prüft Werkzeugargumente gegen eine bewusst kleine, vollständig unterstützte Teilmenge von JSON
 * Schema. Enthält ein Schema ein nicht unterstütztes Schlüsselwort (z. B. {@code $ref},
 * {@code pattern}, {@code if}/{@code then}, Tupel-{@code items}) oder einen nicht sicher
 * darstellbaren Wert (z. B. eine Grenze jenseits von {@code long} oder {@code 1e400}), gilt das
 * ganze Werkzeug als nicht prüfbar und wird gesperrt — es wird nie ungeprüft ausgeführt.
 *
 * <p>Schema-Eingabe: Java-{@code null} bzw. ein {@link JsonNode#isMissingNode() fehlender} Knoten
 * heißt „kein Schema angegeben“ und erlaubt nur das leere Objekt. JEDER andere Wert, der kein Objekt
 * mit {@code "type":"object"} ist (auch {@code false}, {@code true}, JSON-{@code null}, Liste, Text),
 * ist ungültig und sperrt das Werkzeug.
 *
 * <p>Unterstützt: {@code type} (auch als Liste), {@code properties}, {@code required},
 * {@code additionalProperties} (boolesch oder Schema), {@code minProperties}/{@code maxProperties},
 * {@code enum}, {@code const}, {@code items} (ein Schema), {@code minItems}/{@code maxItems},
 * {@code minLength}/{@code maxLength}, {@code minimum}/{@code maximum},
 * {@code exclusiveMinimum}/{@code exclusiveMaximum} (Zahl), {@code anyOf}/{@code oneOf}/{@code allOf}
 * und boolesche Teilschemas. Reine Anmerkungen ({@code description}, {@code title}, {@code default},
 * {@code format}, …) werden ignoriert. {@code enum}/{@code const} vergleichen nach
 * JSON-Schema-Wertgleichheit (z. B. {@code 1} = {@code 1.0}, auch in Listen und Objekten).
 *
 * <p>Beide öffentlichen Methoden werfen nie: jeder unerwartete Fehler wird zu einem Sperr- bzw.
 * Ablehnungsgrund (fail closed).
 */
public final class ToolArgumentValidator {

    static final int MAX_DEPTH = 16;

    private static final String UNCHECKABLE = "Das Eingabeschema kann nicht sicher geprüft werden.";
    private static final Set<String> ANNOTATIONS = Set.of("title", "description", "default", "examples", "$schema",
            "$id", "$comment", "format", "readOnly", "writeOnly", "deprecated", "contentMediaType", "contentEncoding");
    private static final Set<String> TYPES = Set.of("object", "string", "number", "integer", "boolean", "array", "null");
    private static final Set<String> COUNTS = Set.of("minItems", "maxItems", "minLength", "maxLength",
            "minProperties", "maxProperties");
    private static final Set<String> BOUNDS = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");

    private ToolArgumentValidator() {
    }

    /** Kein Schema angegeben: nur Java-{@code null} oder fehlender Knoten — NICHT JSON-{@code null}. */
    public static boolean absent(JsonNode schema) {
        return schema == null || schema.isMissingNode();
    }

    /**
     * {@code null}, wenn das Eingabeschema vollständig unterstützt wird, sonst ein kurzer Grund.
     * Ein fehlendes Schema bedeutet „keine Argumente“ (leeres Objekt) und ist unterstützt. Wirft nie.
     */
    public static String unsupportedReason(JsonNode schema) {
        try {
            if (absent(schema)) return null;
            if (!schema.isObject() || !"object".equals(schema.path("type").asString(""))) {
                return "Das Eingabeschema muss ein Objekt beschreiben.";
            }
            return check(schema, 0);
        } catch (RuntimeException e) {
            return UNCHECKABLE;
        }
    }

    /** {@code null}, wenn die Argumente zum (unterstützten) Schema passen, sonst ein generischer Grund. Wirft nie. */
    public static String validate(JsonNode schema, JsonNode arguments) {
        try {
            if (arguments == null || !arguments.isObject()) return "Die Werkzeugargumente sind kein JSON-Objekt.";
            // Everywhere, also in free additional properties: only finite numbers, bounded nesting.
            if (!finiteAndBounded(arguments, 0)) return "Die Werkzeugargumente enthalten ungültige Zahlen oder sind zu tief verschachtelt.";
            if (absent(schema)) {
                return arguments.isEmpty() ? null : "Dieses Werkzeug erwartet keine Argumente.";
            }
            String unsupported = unsupportedReason(schema);
            if (unsupported != null) return unsupported;
            String problem = errorAt(schema, arguments, "", 0);
            return problem == null ? null : "Die Werkzeugargumente passen nicht zum Eingabeschema" + problem + ".";
        } catch (RuntimeException e) {
            return "Die Werkzeugargumente konnten nicht sicher geprüft werden.";
        }
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
                case "enum" -> v.isArray() && !v.isEmpty() && representable(v, depth) ? null : "ungültige Werteliste";
                case "const" -> representable(v, depth) ? null : "ungültige Werteliste";
                case "anyOf", "oneOf", "allOf" -> {
                    if (!v.isArray() || v.isEmpty()) yield "ungültige Kombination";
                    for (JsonNode n : v) {
                        String sub = check(n, depth + 1);
                        if (sub != null) yield sub;
                    }
                    yield null;
                }
                default -> {
                    if (COUNTS.contains(key)) yield count(v) != null ? null : "ungültige Grenze";
                    if (BOUNDS.contains(key)) yield decimal(v) != null ? null : "ungültige Grenze";
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

    /** Nicht-negative ganze Zahl im long-Bereich, sonst {@code null}. Wirft nie. */
    private static Long count(JsonNode v) {
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) return null;
        long n = v.longValue();
        return n >= 0 ? n : null;
    }

    /** Endliche Zahl als BigDecimal, sonst {@code null} (z. B. 1e400 → Infinity, NaN). Wirft nie. */
    private static BigDecimal decimal(JsonNode v) {
        if (v == null || !v.isNumber()) return null;
        if (v.isFloatingPointNumber() && !v.isBigDecimal() && !Double.isFinite(v.doubleValue())) return null;
        try {
            return v.decimalValue();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Alle Zahlen in enum/const sind als endliche Dezimalzahl darstellbar (begrenzte Tiefe). */
    private static boolean representable(JsonNode v, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (v.isNumber()) return decimal(v) != null;
        for (JsonNode child : v) if (!representable(child, depth + 1)) return false;
        return true;
    }

    static final int MAX_ARGUMENT_DEPTH = 64;

    private static boolean finiteAndBounded(JsonNode v, int depth) {
        if (depth > MAX_ARGUMENT_DEPTH) return false;
        if (v.isNumber()) return decimal(v) != null;
        for (JsonNode child : v) if (!finiteAndBounded(child, depth + 1)) return false;
        return true;
    }

    private static boolean validType(JsonNode v) {
        if (v.isString()) return TYPES.contains(v.asString());
        if (!v.isArray() || v.isEmpty()) return false;
        for (JsonNode n : v) if (!n.isString() || !TYPES.contains(n.asString())) return false;
        return true;
    }

    /** {@code null} = gültig; sonst Pfadhinweis wie {@code " (Feld: a.b)"}. Nur für geprüfte Schemas. */
    private static String errorAt(JsonNode schema, JsonNode value, String path, int depth) {
        if (depth > MAX_DEPTH) return at(path);
        if (schema.isBoolean()) return schema.asBoolean() ? null : at(path);
        if (value.isNumber() && decimal(value) == null) return at(path); // e.g. 1e400 from the model
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) return at(path);
        JsonNode en = schema.get("enum");
        if (en != null) {
            boolean found = false;
            for (JsonNode option : en) if (sameValue(option, value, 0)) { found = true; break; }
            if (!found) return at(path);
        }
        if (schema.has("const") && !sameValue(schema.get("const"), value, 0)) return at(path);
        if (value.isString()) {
            long len = value.asString().codePointCount(0, value.asString().length());
            if (below(schema, "minLength", len) || above(schema, "maxLength", len)) return at(path);
        }
        if (value.isNumber()) {
            BigDecimal n = decimal(value);
            Integer min = cmp(n, schema.get("minimum"));
            Integer max = cmp(n, schema.get("maximum"));
            Integer xmin = cmp(n, schema.get("exclusiveMinimum"));
            Integer xmax = cmp(n, schema.get("exclusiveMaximum"));
            if ((min != null && min < 0) || (max != null && max > 0)
                    || (xmin != null && xmin <= 0) || (xmax != null && xmax >= 0)) return at(path);
        }
        if (value.isArray()) {
            if (below(schema, "minItems", value.size()) || above(schema, "maxItems", value.size())) return at(path);
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
            if (below(schema, "minProperties", value.size()) || above(schema, "maxProperties", value.size())) return at(path);
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

    /** value.compareTo(bound), {@code null} wenn keine Grenze gesetzt. Ungeprüfte Grenze → Ausnahme (fail closed). */
    private static Integer cmp(BigDecimal value, JsonNode boundNode) {
        if (boundNode == null) return null;
        BigDecimal bound = decimal(boundNode);
        if (bound == null) throw new IllegalStateException("unchecked numeric bound");
        return value.compareTo(bound);
    }

    private static boolean below(JsonNode schema, String key, long actual) {
        Long bound = bound(schema, key);
        return bound != null && actual < bound;
    }

    private static boolean above(JsonNode schema, String key, long actual) {
        Long bound = bound(schema, key);
        return bound != null && actual > bound;
    }

    private static Long bound(JsonNode schema, String key) {
        JsonNode v = schema.get(key);
        if (v == null) return null;
        Long n = count(v);
        if (n == null) throw new IllegalStateException("unchecked count bound");
        return n;
    }

    /** JSON-Schema-Wertgleichheit: Zahlen nach Wert, Listen elementweise, Objekte nach Schlüsseln. */
    static boolean sameValue(JsonNode a, JsonNode b, int depth) {
        if (depth > MAX_DEPTH) return false;
        if (a.isNumber() && b.isNumber()) {
            BigDecimal x = decimal(a);
            BigDecimal y = decimal(b);
            return x != null && y != null && x.compareTo(y) == 0;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            Iterator<JsonNode> ia = a.iterator();
            Iterator<JsonNode> ib = b.iterator();
            while (ia.hasNext()) if (!sameValue(ia.next(), ib.next(), depth + 1)) return false;
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            for (Map.Entry<String, JsonNode> e : a.properties()) {
                JsonNode other = b.get(e.getKey());
                if (other == null || !sameValue(e.getValue(), other, depth + 1)) return false;
            }
            return true;
        }
        return a.equals(b);
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
            case "number" -> decimal(value) != null;
            case "integer" -> {
                BigDecimal d = decimal(value);
                yield d != null && (d.signum() == 0 || d.stripTrailingZeros().scale() <= 0);
            }
            default -> false;
        };
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
