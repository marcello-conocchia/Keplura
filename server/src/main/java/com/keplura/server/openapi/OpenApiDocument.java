package com.keplura.server.openapi;

import com.keplura.server.tool.ToolArgumentValidator;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TU-06a: turns an OpenAPI 3.0/3.1 JSON document into a snapshot of operations. Only operations with an
 * {@code operationId} become tools; path and query parameters plus a JSON body are merged into ONE input schema
 * (body under the property {@code body}). Everything else (header/cookie/form parameters, file uploads, external
 * {@code $ref}, schemas outside the {@link ToolArgumentValidator} subset) blocks the single operation with a
 * visible reason. The document's {@code servers} list is never read: the admin's fixed base URL is the only target.
 * Pure function, never dials anything (external references are refused, not fetched).
 */
public final class OpenApiDocument {

    public static final int MAX_DOCUMENT_BYTES = 1_000_000;
    public static final int MAX_OPERATIONS = 128;
    static final int MAX_EXPANDED_NODES = 20_000;
    static final int MAX_DEPTH = 16;
    static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static final Set<String> ALL_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    /** OpenAPI-only annotations without any validation meaning; dropped from the tool schema. */
    private static final Set<String> OPENAPI_ANNOTATIONS = Set.of("example", "xml", "externalDocs");

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** One parameter that is filled from the merged arguments. {@code in} is {@code path} or {@code query}. */
    public record Param(String name, String in, boolean array) { }

    /**
     * One operation of the snapshot. {@code problem == null} = usable; otherwise the operation stays blocked and
     * the admin view shows the reason.
     */
    public record Operation(String operationId, String method, String path, String description, JsonNode inputSchema,
                            List<Param> params, boolean hasBody, String problem) { }

    public static class InvalidDocument extends RuntimeException {
        public InvalidDocument(String message) { super(message); }
    }

    private OpenApiDocument() { }

    /** Parses a JSON document. Throws {@link InvalidDocument} with a fixed message for the admin. */
    public static List<Operation> parse(byte[] raw) {
        if (raw == null || raw.length == 0) throw new InvalidDocument("Das OpenAPI-Dokument ist leer.");
        if (raw.length > MAX_DOCUMENT_BYTES) throw new InvalidDocument("Das OpenAPI-Dokument ist größer als 1 MB.");
        JsonNode doc;
        try {
            doc = MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            throw new InvalidDocument("Nur OpenAPI-Dokumente im JSON-Format werden unterstützt (kein YAML).");
        }
        if (doc == null || !doc.isObject()) {
            throw new InvalidDocument("Nur OpenAPI-Dokumente im JSON-Format werden unterstützt (kein YAML).");
        }
        String version = doc.path("openapi").asString("");
        if (!(version.startsWith("3.0.") || version.startsWith("3.1."))) {
            throw new InvalidDocument("Nur OpenAPI 3.0 und 3.1 werden unterstützt.");
        }
        boolean v30 = version.startsWith("3.0.");
        JsonNode paths = doc.path("paths");
        List<Operation> out = new ArrayList<>();
        if (!paths.isObject()) return out;
        Set<String> seenIds = new HashSet<>();
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            String path = pathEntry.getKey();
            JsonNode item = pathEntry.getValue();
            if (item == null || !item.isObject()) continue;
            for (Map.Entry<String, JsonNode> opEntry : item.properties()) {
                String method = opEntry.getKey().toLowerCase(Locale.ROOT);
                if (!ALL_METHODS.contains(method) || !opEntry.getValue().isObject()) continue;
                JsonNode op = opEntry.getValue();
                String operationId = op.path("operationId").isString() ? op.path("operationId").asString().strip() : "";
                String description = describe(op);
                if (operationId.isEmpty()) {
                    out.add(new Operation(null, method.toUpperCase(Locale.ROOT), path, description, null, List.of(), false,
                            "Die Operation hat keine operationId und ist nicht nutzbar."));
                } else if (operationId.length() > 128 || !seenIds.add(operationId)) {
                    out.add(new Operation(null, method.toUpperCase(Locale.ROOT), path, description, null, List.of(), false,
                            "Die operationId ist zu lang oder nicht eindeutig. Die Operation ist nicht nutzbar."));
                } else {
                    out.add(operation(doc, v30, path, method, item, op, operationId, description));
                }
                if (out.size() > MAX_OPERATIONS) {
                    throw new InvalidDocument("Das OpenAPI-Dokument enthält mehr als " + MAX_OPERATIONS + " Operationen.");
                }
            }
        }
        return out;
    }

    private static String describe(JsonNode op) {
        String summary = op.path("summary").isString() ? op.path("summary").asString().strip() : "";
        String text = op.path("description").isString() ? op.path("description").asString().strip() : "";
        String joined = summary.isEmpty() ? text : text.isEmpty() || text.equals(summary) ? summary : summary + " – " + text;
        return joined.length() > 1000 ? joined.substring(0, 1000) : joined;
    }

    private static Operation operation(JsonNode doc, boolean v30, String path, String method, JsonNode item,
                                       JsonNode op, String operationId, String description) {
        String upper = method.toUpperCase(Locale.ROOT);
        try {
            if (!METHODS.contains(method)) return blocked(operationId, upper, path, description, "Die HTTP-Methode wird nicht unterstützt.");
            if (!path.startsWith("/") || path.contains("..") || path.contains("?") || path.contains("#") || path.contains("\\")) {
                return blocked(operationId, upper, path, description, "Der Pfad der Operation ist ungültig.");
            }
            Set<String> templated = templateNames(path);
            if (templated == null) return blocked(operationId, upper, path, description, "Der Pfad der Operation ist ungültig.");
            Expander expander = new Expander(doc, v30);
            // Path-level parameters first, operation parameters override by (name, in).
            Map<String, JsonNode> params = new LinkedHashMap<>();
            for (JsonNode source : List.of(item.path("parameters"), op.path("parameters"))) {
                if (source.isMissingNode() || source.isNull()) continue;
                if (!source.isArray()) return blocked(operationId, upper, path, description, "Die Parameter sind ungültig.");
                for (JsonNode raw : source) {
                    JsonNode p = expander.resolveObject(raw);
                    String name = p.path("name").asString("");
                    String in = p.path("in").asString("");
                    if (name.isEmpty() || in.isEmpty()) return blocked(operationId, upper, path, description, "Die Parameter sind ungültig.");
                    params.put(in + "\u0000" + name, p);
                }
            }
            ObjectNode schema = MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = MAPPER.createArrayNode();
            List<Param> list = new ArrayList<>();
            Set<String> pathParams = new HashSet<>();
            for (JsonNode p : params.values()) {
                String name = p.path("name").asString();
                String in = p.path("in").asString();
                if (in.equals("header") || in.equals("cookie")) {
                    return blocked(operationId, upper, path, description,
                            "Header- und Cookie-Parameter werden nicht unterstützt. Die Operation bleibt gesperrt.");
                }
                if (!in.equals("path") && !in.equals("query")) return blocked(operationId, upper, path, description, "Die Parameter sind ungültig.");
                if (name.equals("body") || properties.has(name) || name.length() > 64 || !name.matches("[A-Za-z0-9_.-]+")) {
                    return blocked(operationId, upper, path, description, "Die Parameternamen sind nicht eindeutig oder nicht unterstützt.");
                }
                if (p.has("content") || !p.has("schema")) {
                    return blocked(operationId, upper, path, description, "Parameter ohne einfaches Schema werden nicht unterstützt.");
                }
                if (p.has("style") || p.path("explode").isBoolean() && !p.path("explode").asBoolean()
                        || p.path("allowReserved").asBoolean(false)) {
                    return blocked(operationId, upper, path, description, "Abweichende Parameterkodierungen werden nicht unterstützt.");
                }
                JsonNode ps = expander.schema(p.get("schema"), 0);
                if (!ps.isObject()) return blocked(operationId, upper, path, description, "Parameter ohne einfaches Schema werden nicht unterstützt.");
                String type = ps.path("type").isString() ? ps.path("type").asString() : "";
                boolean array = type.equals("array");
                boolean primitive = Set.of("string", "integer", "number", "boolean").contains(type);
                if (in.equals("path") && !primitive) {
                    return blocked(operationId, upper, path, description, "Pfadparameter müssen einfache Werte sein.");
                }
                if (in.equals("query") && !primitive && !(array && primitiveItems(ps))) {
                    return blocked(operationId, upper, path, description, "Query-Parameter müssen einfache Werte oder Listen davon sein.");
                }
                boolean isRequired = in.equals("path") || p.path("required").asBoolean(false);
                if (in.equals("path")) {
                    if (!templated.contains(name)) return blocked(operationId, upper, path, description, "Ein Pfadparameter fehlt im Pfad.");
                    pathParams.add(name);
                }
                ObjectNode copy = (ObjectNode) ps.deepCopy();
                if (p.path("description").isString() && !copy.has("description")) copy.put("description", p.path("description").asString());
                properties.set(name, copy);
                if (isRequired) required.add(name);
                list.add(new Param(name, in, array));
            }
            if (!pathParams.equals(templated)) return blocked(operationId, upper, path, description, "Ein Pfadparameter ist nicht beschrieben.");
            boolean hasBody = false;
            if (op.has("requestBody")) {
                JsonNode body = expander.resolveObject(op.get("requestBody"));
                JsonNode content = body.path("content");
                JsonNode json = content.path("application/json");
                if (!content.isObject() || !json.isObject()) {
                    return blocked(operationId, upper, path, description,
                            "Formular- und Datei-Uploads werden nicht unterstützt. Nur JSON-Körper sind möglich.");
                }
                JsonNode bodySchema = json.has("schema") ? expander.schema(json.get("schema"), 0) : MAPPER.createObjectNode();
                properties.set("body", bodySchema);
                if (body.path("required").asBoolean(false)) required.add("body");
                hasBody = true;
            }
            if (!required.isEmpty()) schema.set("required", required);
            String problem = ToolArgumentValidator.unsupportedReason(schema);
            if (problem != null) return blocked(operationId, upper, path, description, problem);
            return new Operation(operationId, upper, path, description, schema, List.copyOf(list), hasBody, null);
        } catch (Unsupported e) {
            return blocked(operationId, upper, path, description, e.getMessage());
        } catch (RuntimeException e) {
            return blocked(operationId, upper, path, description, "Die Operation kann nicht sicher gelesen werden.");
        }
    }

    private static boolean primitiveItems(JsonNode arraySchema) {
        String t = arraySchema.path("items").path("type").asString("");
        return Set.of("string", "integer", "number", "boolean").contains(t);
    }

    /** Names in {@code {...}} segments; {@code null} for malformed templates. */
    static Set<String> templateNames(String path) {
        Set<String> names = new LinkedHashSet<>();
        int i = 0;
        while (i < path.length()) {
            int open = path.indexOf('{', i);
            int close = path.indexOf('}', i);
            if (open < 0) return close < 0 ? names : null;
            if (close < open) return null;
            String name = path.substring(open + 1, close);
            if (name.isEmpty() || name.contains("{") || !names.add(name)) return null;
            i = close + 1;
        }
        return names;
    }

    private static Operation blocked(String id, String method, String path, String description, String problem) {
        return new Operation(id, method, path, description, null, List.of(), false, problem);
    }

    private static final class Unsupported extends RuntimeException {
        Unsupported(String message) { super(message, null, false, false); }
    }

    /** Inlines local {@code #/components/...} references with depth, cycle and size limits. */
    private static final class Expander {
        private final JsonNode doc;
        private final boolean v30;
        private int expanded;
        private final java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();

        Expander(JsonNode doc, boolean v30) { this.doc = doc; this.v30 = v30; }

        JsonNode target(String ref) {
            if (!ref.startsWith("#/components/")) {
                throw new Unsupported("Externe Referenzen werden nicht unterstützt. Die Operation bleibt gesperrt.");
            }
            JsonNode found;
            try {
                found = doc.at(JsonPointer.compile(java.net.URLDecoder.decode(ref.substring(1), java.nio.charset.StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                throw new Unsupported("Eine Referenz ist ungültig.");
            }
            if (found == null || found.isMissingNode()) throw new Unsupported("Eine Referenz verweist ins Leere.");
            return found;
        }

        /** Parameter / requestBody objects: follow $ref chains (no siblings), return the object. */
        JsonNode resolveObject(JsonNode node) {
            int hops = 0;
            while (node != null && node.isObject() && node.has("$ref")) {
                if (++hops > MAX_DEPTH) throw new Unsupported("Eine Referenz ist zu tief verschachtelt.");
                node = target(node.path("$ref").asString(""));
            }
            if (node == null || !node.isObject()) throw new Unsupported("Die Parameter sind ungültig.");
            return node;
        }

        JsonNode schema(JsonNode node, int depth) {
            if (depth > MAX_DEPTH) throw new Unsupported("Das Schema ist zu tief verschachtelt.");
            if (++expanded > MAX_EXPANDED_NODES) throw new Unsupported("Das Schema ist zu groß.");
            if (node == null || node.isBoolean()) return node == null ? MAPPER.createObjectNode() : node;
            if (node.isArray()) {
                ArrayNode copy = MAPPER.createArrayNode();
                for (JsonNode child : node) copy.add(schema(child, depth + 1));
                return copy;
            }
            if (!node.isObject()) return node;
            if (node.has("$ref")) {
                for (String key : node.propertyNames()) {
                    if (!key.equals("$ref") && !key.equals("description") && !key.equals("summary")) {
                        throw new Unsupported("Referenzen mit zusätzlichen Schlüsselwörtern werden nicht unterstützt.");
                    }
                }
                String ref = node.path("$ref").asString("");
                if (stack.contains(ref)) throw new Unsupported("Rekursive Schemas werden nicht unterstützt.");
                stack.push(ref);
                try { return schema(target(ref), depth + 1); }
                finally { stack.pop(); }
            }
            ObjectNode copy = MAPPER.createObjectNode();
            boolean nullable = v30 && node.path("nullable").asBoolean(false);
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String key = e.getKey();
                if (OPENAPI_ANNOTATIONS.contains(key) || key.startsWith("x-")) continue;
                if (v30 && key.equals("nullable")) continue;
                JsonNode value = e.getValue();
                switch (key) {
                    case "properties" -> {
                        if (!value.isObject()) throw new Unsupported("Das Schema ist ungültig.");
                        ObjectNode props = copy.putObject("properties");
                        for (Map.Entry<String, JsonNode> p : value.properties()) props.set(p.getKey(), schema(p.getValue(), depth + 1));
                    }
                    case "items", "additionalProperties", "anyOf", "oneOf", "allOf", "not" -> copy.set(key, schema(value, depth + 1));
                    default -> copy.set(key, value.deepCopy());
                }
            }
            if (nullable) {
                JsonNode type = copy.get("type");
                if (type != null && type.isString()) {
                    copy.putArray("type").add(type.asString()).add("null");
                } else if (copy.has("enum") || type == null) {
                    throw new Unsupported("Dieses Schema mit nullable wird nicht unterstützt.");
                }
            }
            return copy;
        }
    }

    /** Snapshot JSON as stored in {@code openapi_server.operations_json}. */
    public static String toJson(List<Operation> operations) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Operation o : operations) {
            ObjectNode n = arr.addObject();
            if (o.operationId() != null) n.put("operationId", o.operationId());
            n.put("method", o.method()).put("path", o.path()).put("description", o.description());
            if (o.inputSchema() != null) n.set("inputSchema", o.inputSchema());
            ArrayNode ps = n.putArray("params");
            for (Param p : o.params()) ps.addObject().put("name", p.name()).put("in", p.in()).put("array", p.array());
            n.put("hasBody", o.hasBody());
            if (o.problem() != null) n.put("problem", o.problem());
        }
        return arr.toString();
    }

    /** Reads the stored snapshot back. Unreadable entries are skipped (and therefore never offered). */
    public static List<Operation> fromJson(String json) {
        List<Operation> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        JsonNode arr = MAPPER.readTree(json);
        if (!arr.isArray()) return out;
        for (JsonNode n : arr) {
            List<Param> ps = new ArrayList<>();
            for (JsonNode p : n.path("params")) ps.add(new Param(p.path("name").asString(""), p.path("in").asString(""), p.path("array").asBoolean(false)));
            out.add(new Operation(n.path("operationId").isString() ? n.path("operationId").asString() : null,
                    n.path("method").asString(""), n.path("path").asString(""), n.path("description").asString(""),
                    n.has("inputSchema") ? n.get("inputSchema") : null, List.copyOf(ps), n.path("hasBody").asBoolean(false),
                    n.path("problem").isString() ? n.path("problem").asString() : null));
        }
        return out;
    }

    /** Canonical JSON of one stored operation; an approval only survives a refresh if this stays identical. */
    public static String canonical(Operation o) {
        return toJson(List.of(o));
    }
}
