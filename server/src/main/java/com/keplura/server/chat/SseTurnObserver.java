package com.keplura.server.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Line-level SSE observer for one chat stream (M4b). It does NOT re-frame or
 * rewrite anything — the relay forwards upstream bytes verbatim; this class
 * only watches the byte flow to accumulate what the turn produced:
 *
 * <ul>
 *   <li>{@code data:} JSON payloads → append {@code choices[0].delta.content},
 *       record the {@code model} tag if present;</li>
 *   <li>{@code data: [DONE]} → the upstream's own finish signal;</li>
 *   <li>anything unparseable is IGNORED for accumulation (a foreign event,
 *       a comment, a malformed frame) — the client still receives it verbatim
 *       and the answer text simply does not grow from it.</li>
 * </ul>
 *
 * <p>Line splitting is byte-driven with UTF-8 decode per COMPLETE line: an
 * upstream may split a multi-byte character across TCP reads, and decoding
 * partial buffers per read would corrupt it. Decoding only at {@code \n}
 * boundaries is always whole-character safe for the OpenAI SSE shape (one
 * JSON event per line).
 *
 * <p>Accumulation is capped (DB bound of chat_message.content, V6): a stream
 * that rambles past the cap still relays to the client but no longer grows
 * the persisted copy — persistence truncates anyway, and the cap stops
 * unbounded heap per concurrent turn.
 */
final class SseTurnObserver {

    private static final Logger log = LoggerFactory.getLogger(SseTurnObserver.class);
    /** chat_message.content bound (V6) — heap guard, not a correctness value. */
    static final int MAX_ACCUMULATED_CHARS = ChatMessage.MAX_CONTENT_LENGTH;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
    private final StringBuilder text = new StringBuilder();
    private volatile String model = "";
    private boolean sawDone;
    private boolean overflowed;

    /** Feed relayed bytes; complete lines are parsed, the rest is buffered. */
    void feed(byte[] buf, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            byte b = buf[i];
            if (b == '\n') {
                handleLine(new String(line.toByteArray(), StandardCharsets.UTF_8));
                line.reset();
            } else if (b != '\r') {
                line.write(b);
            }
        }
    }

    /** Flush a final unterminated line at stream end (upstreams omit the trailing \n). */
    void finish() {
        if (line.size() > 0) {
            handleLine(new String(line.toByteArray(), StandardCharsets.UTF_8));
            line.reset();
        }
    }

    private void handleLine(String raw) {
        String s = raw.strip();
        if (!s.startsWith("data:")) {
            return; // event:/id:/retry:/comment — no payload to learn from
        }
        String payload = s.substring("data:".length()).strip();
        if (payload.isEmpty()) {
            return;
        }
        if ("[DONE]".equals(payload)) {
            sawDone = true;
            return;
        }
        JsonNode chunk;
        try {
            chunk = mapper.readTree(payload);
        } catch (RuntimeException e) {
            // malformed frame (or a frame Jackson 3 rejects): ignore for the
            // transcript, the relay already forwarded it verbatim.
            return;
        }
        if (chunk == null || !chunk.isObject()) {
            return;
        }
        JsonNode modelNode = chunk.path("model");
        if (modelNode.isString() && model.isEmpty()) {
            model = modelNode.stringValue();
        }
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode content = choices.get(0).path("delta").path("content");
        if (!content.isString()) {
            return;
        }
        if (text.length() < MAX_ACCUMULATED_CHARS) {
            text.append(content.stringValue());
        } else {
            overflowed = true;
        }
    }

    String answerText() {
        return text.toString();
    }

    String modelTag() {
        return model;
    }

    boolean sawDone() {
        return sawDone;
    }

    /** True when the cap was hit and the persisted text is a truncation. */
    boolean overflowed() {
        if (overflowed) {
            log.debug("chat stream accumulation exceeded persist cap");
        }
        return overflowed;
    }
}
