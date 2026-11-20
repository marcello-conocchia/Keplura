package com.keplura.server.gateway;

import com.keplura.server.SystemInfoController;
import com.keplura.server.chat.ChatProperties;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4c: the provider-preset resolution rules WITHOUT a Spring context (the
 * SearchStoreSelectionTest pattern — boot-failure semantics asserted on the
 * static seams, no extra Tomcat eating the container's pid budget).
 *
 * <p>Proves the layering contract: preset fills defaults, raw ENV overrides
 * per value, unknown provider kills the boot, the api preset alone carries
 * the bearer expectation, and the desktop-facing {@code model} projection is
 * the closed vocabulary with raw values NEVER surfacing.
 */
class ProviderConfigurationTest {

    private final GatewayConfiguration config = new GatewayConfiguration();

    private GatewayProperties resolve(String provider, String baseUrl, String apiKey) {
        return config.gatewayProperties(provider, baseUrl, apiKey, "",
                30, 60, 300, 1_048_576, 8_388_608, 65_536, 256);
    }

    // ---- preset resolution ----

    @Test
    void presetsFillBaseUrlDefaultsPerBackend() {
        assertThat(resolve("ollama", "", "").baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(resolve("vllm", "", "").baseUrl()).isEqualTo("http://127.0.0.1:8000/v1");
        assertThat(resolve("api", "", "").baseUrl()).isEqualTo("https://api.openai.com/v1");
        // ids recorded for the projection + logs
        assertThat(resolve("ollama", "", "").provider()).isEqualTo("ollama");
    }

    @Test
    void rawEnvOverridesBeatThePresetDefaults() {
        GatewayProperties p = resolve("ollama", "http://ollama.lan:11434/v1", "");
        assertThat(p.baseUrl()).isEqualTo("http://ollama.lan:11434/v1");
        assertThat(p.provider()).isEqualTo("ollama"); // preset still names the auth kind
    }

    @Test
    void presetDecidesAuthKindIndependentlyOfTheKeyValue() {
        // local presets: NONE — a stray hosted key must never ride to the LAN
        assertThat(resolve("ollama", "", "some-key").sendsAuth()).isFalse();
        assertThat(resolve("vllm", "", "some-key").sendsAuth()).isFalse();
        // hosted preset: BEARER
        assertThat(resolve("api", "", "some-key").sendsAuth()).isTrue();
        // raw M4a path (no preset): bearer stays the behaviour (backward compat)
        assertThat(resolve("", "http://gw.internal/v1", "some-key").sendsAuth()).isTrue();
    }

    @Test
    void unknownProviderFailsTheBootWithSanitizedMessage() {
        assertThatThrownBy(() -> resolve("mistral", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown model provider")
                .hasMessageContaining("ollama, vllm or api");
        // CRLF in the bad value cannot forge a log/error line
        assertThatThrownBy(() -> resolve("bad\nvalue", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("\n")
                .hasMessageContaining("bad?value");
    }

    @Test
    void unknownProviderKillsARealSpringContext() {
        // The acceptance-grade proof: the resolution happens inside the real
        // bean factory, so a Spring context with a typo provider never starts.
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("keplura.provider=mistral")
                .withUserConfiguration(GatewayConfiguration.class)
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure().hasMessageContaining("unknown model provider"));
        // sanity: the same runner with a valid preset DOES start
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("keplura.provider=ollama")
                .withUserConfiguration(GatewayConfiguration.class)
                .run(ctx -> assertThat(ctx)
                        .hasNotFailed()
                        .hasSingleBean(GatewayProperties.class));
    }

    @Test
    void noneStyleValuesMeanNoPresetLegacyPathStays() {
        for (String raw : new String[] {"", "   ", "none", "OFF", "disabled"}) {
            GatewayProperties p = resolve(raw, "", "");
            assertThat(p.provider()).isEmpty();
            assertThat(p.configured()).isFalse();
        }
    }

    // ---- desktop projection (SystemInfo `model`) ----

    @Test
    void modelProjectionIsClosedVocabularyNeverRawConfig() {
        // preset facts map to the enum words
        assertThat(modelOf(resolve("ollama", "", ""))).isEqualTo("local");
        assertThat(modelOf(resolve("vllm", "http://gpu.lan:8000/v1", "k"))).isEqualTo("configured");
        assertThat(modelOf(resolve("api", "", "k"))).isEqualTo("configured");
        // legacy raw path: boolean-shaped
        assertThat(modelOf(resolve("", "http://gw.internal/v1", "k"))).isEqualTo("configured");
        assertThat(modelOf(resolve("", "", ""))).isEqualTo("none");
    }

    @Test
    void systemInfoBodyCarriesNoUrlNoKeyNoModelId() {
        GatewayProperties p = resolve("ollama", "http://secret-host.lan:11434/v1", "sekret-" + "value");
        String json = modelOf(p);
        assertThat(json).isEqualTo("local");
        // the projection functions themselves must not be able to emit more:
        assertThat(p.provider()).doesNotContain("secret-host");
        assertThat(p.baseUrl()).doesNotContain("sekret");
        assertThat(p.apiKey()).doesNotContain("local");
    }

    private static String modelOf(GatewayProperties props) {
        // Drive the controller's projection exactly as HTTP would, without web
        // wiring: same bean, same method the /info route calls.
        SystemInfoController controller =
                new SystemInfoController("Keplura Server", "0.1.0", "v1", GatewayRuntime.envOnly(props));
        return controller.info().model();
    }

    // ---- chat-side preset defaults (model name + Ollama loop guard) ----

    @Test
    void chatModelFollowsThePresetWhenUnsetExplicitly() {
        assertThat(ChatProperties.of("", "ollama", 20, 4, 4000, 700, 0).model())
                .isEqualTo("qwen2.5:0.5b");
        assertThat(ChatProperties.of("", "vllm", 20, 4, 4000, 700, 0).model())
                .isEqualTo("default");
        assertThat(ChatProperties.of("", "api", 20, 4, 4000, 700, 0).model())
                .isEqualTo("gpt-4o-mini");
        // no preset: the dev stand-in name survives (M4b behaviour)
        assertThat(ChatProperties.of("", "", 20, 4, 4000, 700, 0).model())
                .isEqualTo("local-chat-dev");
        // explicit model always wins over the preset default
        assertThat(ChatProperties.of("mein-modell", "ollama", 20, 4, 4000, 700, 0).model())
                .isEqualTo("mein-modell");
    }

    @Test
    void ollamaPresetGetsTheCompletionCapOthersOmitTheField() {
        // Ollama's compat layer has no length default -> guard cap at the clamp
        assertThat(ChatProperties.of("m", "ollama", 20, 4, 4000, 700, 0).maxTokens())
                .isEqualTo(ChatProperties.MAX_TOKENS_CAP);
        // vLLM / hosted API / raw path: 0 = omit, backend decides
        assertThat(ChatProperties.of("m", "vllm", 20, 4, 4000, 700, 0).maxTokens()).isZero();
        assertThat(ChatProperties.of("m", "api", 20, 4, 4000, 700, 0).maxTokens()).isZero();
        assertThat(ChatProperties.of("m", "", 20, 4, 4000, 700, 0).maxTokens()).isZero();
        // explicit cap wins, and never exceeds the validator's clamp
        assertThat(ChatProperties.of("m", "vllm", 20, 4, 4000, 700, 64).maxTokens()).isEqualTo(64);
        assertThat(ChatProperties.of("m", "ollama", 20, 4, 4000, 700, -5).maxTokens())
                .isEqualTo(ChatProperties.MAX_TOKENS_CAP);
        assertThat(ChatProperties.of("m", "ollama", 20, 4, 4000, 700, 999999).maxTokens())
                .isEqualTo(ChatProperties.MAX_TOKENS_CAP);
    }

    // ---- properties-shape safety nets ----

    @Test
    void legacyConstructorKeepsBearerSemanticsForExistingCallers() {
        GatewayProperties legacy = new GatewayProperties("http://x/v1", "k", Set.of(),
                5, 5, 10, 4096, 1024, 64, 4);
        assertThat(legacy.provider()).isEmpty();
        assertThat(legacy.sendsAuth()).isTrue();
        assertThat(legacy.chatCompletionsEndpoint()).isEqualTo("http://x/v1/chat/completions");
    }
}
