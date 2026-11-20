package com.keplura.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

class LegacyEnvironmentPostProcessorTest {

    private static StandardEnvironment envWith(Map<String, Object> vars) {
        return new StandardEnvironment() {
            @Override
            public Map<String, Object> getSystemEnvironment() {
                return vars;
            }
        };
    }

    @Test
    void legacyVariablesBindUnderTheNewNames() {
        StandardEnvironment env = envWith(Map.of(
                "INTRACORE_GATEWAY_BASE_URL", "http://legacy:11434/v1",
                "INTRACORE_SECRETS_MASTER_KEY", "legacy-key"));

        new LegacyEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        // placeholder style (application*.properties) and relaxed binding
        assertThat(env.getProperty("KEPLURA_SECRETS_MASTER_KEY")).isEqualTo("legacy-key");
        assertThat(Binder.get(env).bind("keplura.gateway.base-url", String.class).get())
                .isEqualTo("http://legacy:11434/v1");
    }

    @Test
    void unmigratedComposeEnvAbortsTheStart() {
        // compose.prod.yml with an old .env.production: new names empty/defaulted,
        // detector passes the legacy master key through
        StandardEnvironment env = envWith(Map.of(
                "KEPLURA_SECRETS_MASTER_KEY", "",
                "INTRACORE_SECRETS_MASTER_KEY", "secret-value",
                "KEPLURA_SERVER_HOST_PORT", "",
                "INTRACORE_SERVER_HOST_PORT", "18080"));

        assertThatThrownBy(() -> new LegacyEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTRACORE_SECRETS_MASTER_KEY")
                .hasMessageContaining("INTRACORE_SERVER_HOST_PORT")
                .hasMessageContaining("install.sh")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-value"));
    }

    @Test
    void migratedComposeEnvStartsNormally() {
        // after install.sh the detectors are empty; the new names carry the values
        StandardEnvironment env = envWith(Map.of(
                "KEPLURA_SECRETS_MASTER_KEY", "k",
                "INTRACORE_SECRETS_MASTER_KEY", "",
                "INTRACORE_SERVER_HOST_PORT", ""));

        new LegacyEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("KEPLURA_SECRETS_MASTER_KEY")).isEqualTo("k");
        assertThat(env.getPropertySources().contains(LegacyEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
    }

    @Test
    void identicalValuesUnderBothNamesAreNoConflict() {
        StandardEnvironment env = envWith(Map.of("INTRACORE_CHAT_MODEL", "m", "KEPLURA_CHAT_MODEL", "m"));

        new LegacyEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertThat(Binder.get(env).bind("keplura.chat.model", String.class).get()).isEqualTo("m");
    }

    @Test
    void isRegisteredForSpringBoot() throws Exception {
        // guards the META-INF/spring.factories key (Boot 4 interface name)
        boolean registered = false;
        var urls = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (urls.hasMoreElements()) {
            var props = new java.util.Properties();
            try (var in = urls.nextElement().openStream()) {
                props.load(in);
            }
            String value = props.getProperty(org.springframework.boot.EnvironmentPostProcessor.class.getName(), "");
            registered |= java.util.Arrays.stream(value.split(","))
                    .map(String::trim)
                    .anyMatch(LegacyEnvironmentPostProcessor.class.getName()::equals);
        }
        assertThat(registered).isTrue();
    }

    @Test
    void noLegacyVariablesAddsNoSource() {
        StandardEnvironment env = envWith(Map.of("KEPLURA_CHAT_MODEL", "new", "PATH", "/usr/bin"));

        new LegacyEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getPropertySources().contains(LegacyEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
    }
}
