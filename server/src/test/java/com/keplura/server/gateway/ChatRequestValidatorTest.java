package com.keplura.server.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4a: the whitelist/canonicalisation layer in isolation — what MAY reach the
 * upstream, what MUST be rejected, and the proof that unseen fields are
 * DROPPED (canonical body re-serialisation, not pass-through).
 */
class ChatRequestValidatorTest {

    private static GatewayProperties props(Set<String> allowed, long maxRequestBytes) {
        return new GatewayProperties("http://upstream.invalid/v1", "sekret", allowed,
                5, 5, 10, maxRequestBytes, 1_000_000, 1000, 8);
    }

    private static ChatRequestValidator validator() {
        return new ChatRequestValidator(props(Set.of(), 1_048_576));
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void dropsUnknownFieldsAndKeepsWhitelistedOnes() {
        ChatRequestValidator.CanonicalRequest canonical = validator().validate(json("""
                {"model":"m1","messages":[{"role":"user","content":"hi","extra":"gone"}],
                 "temperature":0.5,"user_secret_stuff":{"a":1},
                 "vendor_param":"drop me","stream":true,"max_tokens":100}"""));

        String body = new String(canonical.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"temperature\":0.5").contains("\"max_tokens\":100")
                .contains("\"stream\":true");
        assertThat(body).doesNotContain("user_secret_stuff").doesNotContain("vendor_param")
                .doesNotContain("gone");
        assertThat(canonical.model()).isEqualTo("m1");
        assertThat(canonical.stream()).isTrue();
    }

    @Test
    void rejectsShapesThatWouldForwardUnboundedOrFakeContent() {
        // content must be a plain string (no multimodal arrays, no null)
        assertThatThrownBy(() -> validator().validate(json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\"}]}]}")))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> validator().validate(json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":null}]}")))
                .isInstanceOf(GatewayException.class);
        // tool/structured-output surfaces: rejected, NOT silently dropped
        assertThatThrownBy(() -> validator().validate(json(
                "{\"model\":\"m\",\"tools\":[],\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}")))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.getMessage()).contains("not available"));
        // bad role / empty messages / missing model / non-object / bad json
        assertThatThrownBy(() -> validator().validate(json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"wizard\",\"content\":\"x\"}]}")))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> validator().validate(json("{\"model\":\"m\",\"messages\":[]}")))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> validator().validate(json("{\"messages\":[]}")))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> validator().validate(json("[1,2]")))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> validator().validate(json("{oops")))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> validator().validate(new byte[0]))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void oversizedBodyIs413AndPerMessageCapIs400() {
        ChatRequestValidator small = new ChatRequestValidator(props(Set.of(), 1024));
        assertThatThrownBy(() -> small.validate(json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(2000) + "\"}]}")))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));

        // message chars cap = 1000 in props(): 1200 chars must 400, not pass through
        assertThatThrownBy(() -> validator().validate(json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "y".repeat(1200) + "\"}]}")))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void messageCountCapIsEnforced() {
        // maxMessages = 8
        StringBuilder sb = new StringBuilder("{\"model\":\"m\",\"messages\":[");
        for (int i = 0; i <= 8; i++) {
            sb.append("{\"role\":\"user\",\"content\":\"x\"}");
            if (i < 8) {
                sb.append(',');
            }
        }
        sb.append("]}");
        assertThatThrownBy(() -> validator().validate(json(sb.toString())))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.getMessage()).contains("too many"));
    }

    @Test
    void modelAllowListIsEnforcedAndModelNamesAreCharsetBounded() {
        ChatRequestValidator pinned = new ChatRequestValidator(props(Set.of("ok-model"), 1_048_576));
        assertThatThrownBy(() -> pinned.validate(json(
                "{\"model\":\"other\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}")))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.getMessage()).contains("not allowed"));
        assertThat(pinned.validate(json(
                "{\"model\":\"ok-model\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}")).model())
                .isEqualTo("ok-model");
        // header-injection-ish or path-ish garbage in model: rejected by charset
        assertThatThrownBy(() -> validator().validate(json(
                "{\"model\":\"m1\\r\\nX-Y: 1\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}")))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void numericFieldsAreRangeAndTypeGuarded() {
        // temperature as string / huge n / float max_tokens -> dropped or coerced, never forwarded raw
        ChatRequestValidator.CanonicalRequest canonical = validator().validate(json("""
                {"model":"m","messages":[{"role":"user","content":"x"}],
                 "temperature":"hot","n":1000000,"max_tokens":12.5,"seed":7}"""));
        String body = new String(canonical.body(), StandardCharsets.UTF_8);
        // string-typed / out-of-range / fractional-for-int values are DROPPED, never coerced through
        assertThat(body).doesNotContain("hot").doesNotContain("1000000").doesNotContain("12.5")
                .doesNotContain("max_tokens");
        assertThat(body).contains("\"n\":1024")   // range-clamped
                .contains("\"seed\":7.0");        // numeric scalar passes
    }

    @Test
    void streamFlagDrivesTheCanonicalStreamField() {
        assertThat(validator().validate(json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"stream\":\"yes\"}"))
                .stream()).isFalse(); // non-boolean stream value is dropped
        assertThat(validator().validate(json(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"stream\":true}"))
                .stream()).isTrue();
    }
}
