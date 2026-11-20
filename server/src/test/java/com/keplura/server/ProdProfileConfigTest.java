package com.keplura.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config profiles (M1c): with the <b>prod</b> profile active — as it would be via
 * {@code SPRING_PROFILES_ACTIVE=prod} in a deployment — the production overrides
 * apply: INFO logging for {@code com.keplura} and the default (env-overridable)
 * port 8080.
 */
@SpringBootTest
@ActiveProfiles("prod")
class ProdProfileConfigTest {

    @Autowired
    private Environment env;

    @Test
    void prodProfileReplacesTheDefaultProfile() {
        assertThat(env.getActiveProfiles()).containsExactly("prod");
    }

    @Test
    void prodProfileUsesInfoLoggingAndDefaultPort() {
        assertThat(env.getProperty("server.port")).isEqualTo("8080");
        Logger logger = (Logger) LoggerFactory.getLogger("com.keplura");
        assertThat(logger.getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void prodProfileMarksTheSessionCookieSecure() {
        // M2b/ADR-0003: production runs behind TLS (ADR-0001) — the login
        // session cookie must never be sent over plain HTTP.
        assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(env.getProperty("server.servlet.session.cookie.name")).isEqualTo("KEPLURASESSION");
    }

    @Test
    void prodProfileOwnsSchemaViaFlywayAndOnlyValidatesIt() {
        // M3a/ADR-0006: in prod the schema belongs to Flyway — Hibernate must
        // validate, never mutate. A prod boot whose schema drifted from the
        // entities fails loudly here (see the same boot against a real
        // PostgreSQL 16 + pgvector in the M3a PR evidence).
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
    }
}
