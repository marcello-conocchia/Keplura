package com.keplura.server.gateway;

import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * The one place the upstream chat request is understood (M4a). Parses the raw
 * JSON body, enforces an explicit whitelist, and re-serialises a CANONICAL
 * body: unknown fields are dropped, nothing the validator did not look at can
 * reach the upstream. It also surfaces the (model, stream) pair the controller
 * needs BEFORE the client is invoked.
 *
 * <p>MVP contract (documented in contracts/openapi.yaml):
 * <ul>
 *   <li>message {@code content} must be a plain string — multimodal content
 *       parts are rejected 400, not silently forwarded;</li>
 *   <li>tool / structured-output surfaces ({@code tools}, {@code tool_choice},
 *       {@code response_format}, {@code functions}) are rejected 400 — free-form
 *       nested objects are an unbounded forwarding surface;</li>
 *   <li>roles are pinned to the OpenAI set; sampling scalars pass through with
 *       type+range checks; caps (body bytes, message count, per-message chars)
 *       come from {@link GatewayProperties}.</li>
 * </ul>
 */
class ChatRequestValidator {

    /** Validated, canonical request: send these bytes, nothing else. */
    record CanonicalRequest(byte[] body, String model, boolean stream) {
    }

    private static final Set<String> ALLOWED_ROLES =
            Set.of("system", "user", "assistant", "tool", "developer");

    /** Present-and-rejected (not silently dropped — the caller must know). */
    private static final Set<String> UNSUPPORTED_FIELDS =
            Set.of("tools", "tool_choice", "response_format", "functions", "function_call",
                    "audio", "modalities", "web_search_options");

    private static final Set<String> PASS_THROUGH_NUMBER =
            Set.of("temperature", "top_p", "presence_penalty", "frequency_penalty", "seed");

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final GatewayProperties props;

    ChatRequestValidator(GatewayProperties props) {
        this.props = props;
    }

    CanonicalRequest validate(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (rawBody.length > props.maxRequestBytes()) {
            throw new GatewayException(HttpStatus.PAYLOAD_TOO_LARGE, "request body too large");
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (RuntimeException e) {
            // Jackson messages can quote input content — swallow it, stay generic.
            throw new GatewayException(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "request body must be a JSON object");
        }
        for (String unsupported : UNSUPPORTED_FIELDS) {
            if (root.has(unsupported)) {
                throw new GatewayException(HttpStatus.BAD_REQUEST,
                        "this request field is not available in the current milestone");
            }
        }

        ObjectNode canonical = mapper.createObjectNode();

        // ---- model: required, conservative charset, optional allow-list (shared-key abuse guard) ----
        JsonNode model = root.path("model");
        if (!model.isString() || model.stringValue().isBlank()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "model is required");
        }
        String modelName = model.stringValue().trim();
        if (modelName.length() > 128 || !modelName.matches("[A-Za-z0-9._:/@+-]+")) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "model is invalid");
        }
        if (!props.allowedModels().isEmpty() && !props.allowedModels().contains(modelName)) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "model is not allowed");
        }
        canonical.put("model", modelName);

        // ---- messages: non-empty array of {role, content-as-string} within caps ----
        JsonNode messages = root.path("messages");
        if (!messages.isArray() || messages.size() == 0) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "messages must be a non-empty array");
        }
        if (messages.size() > props.maxMessages()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "too many messages");
        }
        ArrayNode canonicalMessages = canonical.putArray("messages");
        for (JsonNode message : messages) {
            if (!message.isObject()) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "message must be an object");
            }
            JsonNode role = message.path("role");
            if (!role.isString() || !ALLOWED_ROLES.contains(role.stringValue())) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "message role is invalid");
            }
            JsonNode content = message.path("content");
            if (!content.isString()) {
                throw new GatewayException(HttpStatus.BAD_REQUEST,
                        "message content must be a string in this milestone");
            }
            String text = content.stringValue();
            if (text.length() > props.maxMessageChars()) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "message content too large");
            }
            ObjectNode cm = canonicalMessages.addObject();
            cm.put("role", role.stringValue());
            cm.put("content", text);
            JsonNode name = message.path("name");
            if (name.isString() && name.stringValue().length() <= 128) {
                cm.put("name", name.stringValue());
            }
        }

        // ---- optional scalars: type+range checked, everything else dropped ----
        for (String field : PASS_THROUGH_NUMBER) {
            JsonNode v = root.path(field);
            if (v.isNumber()) {
                double d = v.doubleValue();
                if (d >= -100000 && d <= 100000) {
                    canonical.put(field, d);
                }
            }
        }
        for (String field : new String[] {"max_tokens", "max_completion_tokens", "n"}) {
            JsonNode v = root.path(field);
            if (v.isNumber()) {
                double d = v.doubleValue();
                if (Double.isFinite(d) && d == Math.rint(d)) {
                    int i = (int) Math.min(Math.max(d, 0), 1024);
                    if ("n".equals(field) && (i < 1)) {
                        continue;
                    }
                    canonical.put(field, i);
                }
            }
        }
        for (String field : new String[] {"stream", "do_sample", "logprobs"}) {
            JsonNode v = root.path(field);
            if (v.isBoolean()) {
                canonical.put(field, v.booleanValue());
            }
        }

        // stop: string or array of short strings; invalid shapes are dropped, not rejected
        JsonNode stop = root.path("stop");
        if (stop.isString() && stop.stringValue().length() <= 512) {
            canonical.put("stop", stop.stringValue());
        } else if (stop.isArray() && stop.size() <= 8) {
            boolean allShortStrings = true;
            for (JsonNode s : stop) {
                if (!s.isString() || s.stringValue().length() > 512) {
                    allShortStrings = false;
                    break;
                }
            }
            if (allShortStrings) {
                ArrayNode arr = canonical.putArray("stop");
                for (JsonNode s : stop) {
                    arr.add(s.stringValue());
                }
            }
        }

        boolean stream = canonical.path("stream").asBoolean(false);
        return new CanonicalRequest(mapper.writeValueAsBytes(canonical), modelName, stream);
    }
}
