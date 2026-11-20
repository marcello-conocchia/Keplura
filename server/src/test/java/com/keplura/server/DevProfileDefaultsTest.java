package com.keplura.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config profiles (M1c): with no profile set from the outside, the server must
 * come up in the <b>dev</b> profile (base default in application.properties) —
 * default port 8080 and DEBUG logging for {@code com.keplura}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Hermetic datasource (ADMIN-KI) — see ApiIntegrationTest for the why:
        // never boot onto the shared target/keplura-dev file DB.
        "spring.datasource.url=jdbc:h2:mem:dev-profile-defaults-test;DB_CLOSE_DELAY=-1"
})
class DevProfileDefaultsTest {

    @Autowired
    private Environment env;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void devProfileIsActiveByDefault() {
        assertThat(env.getActiveProfiles()).contains("dev");
        assertThat(env.getProperty("spring.profiles.active")).isEqualTo("dev");
    }

    @Test
    void devProfileKeepsDefaultPortAndDebugLogging() {
        assertThat(env.getProperty("server.port")).isEqualTo("8080");
        assertThat(env.getProperty("logging.level.com.keplura", (String) null)).isEqualTo("DEBUG");
        Logger logger = (Logger) LoggerFactory.getLogger("com.keplura");
        assertThat(logger.getLevel()).isEqualTo(Level.DEBUG);
    }

    @Test
    void devProfileAllowsSessionCookieOverPlainHttp() {
        // M2b: dev runs on http://localhost, so Secure must be off there — and
        // only there (ProdProfileConfigTest asserts the opposite).
        assertThat(env.getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
        assertThat(env.getProperty("server.servlet.session.cookie.name")).isEqualTo("KEPLURASESSION_DEV");
    }

    @Test
    void devAccountSeederIsAbsentWithoutSeedProperties() {
        // M2d: out of the box no known dev credential may exist — the seeder
        // is @ConditionalOnProperty and must not be instantiated here.
        assertThat(context.getBeanNamesForType(com.keplura.server.identity.DevAccountSeeder.class))
                .isEmpty();
    }

    @Test
    void uploadSizeLimitIsOneValueEverywhere() {
        // M3d hardening (b): the declared cap (policy), the measured copy cap
        // (storage) and the multipart container caps all resolve from ONE
        // property chain (KEPLURA_UPLOAD_MAX_BYTES) — with the environment
        // unset they must be byte-identical, so no framing trick can hit a
        // looser limit than the app policy.
        String policy = env.getProperty("keplura.upload.max-size-bytes");
        assertThat(policy).isEqualTo("20971520");
        assertThat(env.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo(policy);
        assertThat(env.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo(policy);
        assertThat(env.getProperty("keplura.search.max-doc-bytes")).isEqualTo(policy);
    }

    @Test
    void parserLimitsHaveExplicitDefaults() {
        // MVP-5: the extraction DoS gates must have a value WITHOUT any ENV —
        // an unset knob that silently means "unlimited" is exactly the failure
        // mode this test exists to prevent. Values match application.properties
        // and docs/development.md (changes must be reflected here too).
        assertThat(env.getProperty("keplura.parser.extracted-chars")).isEqualTo("2000000");
        assertThat(env.getProperty("keplura.parser.pdf-max-pages")).isEqualTo("500");
        assertThat(env.getProperty("keplura.parser.docx-max-pages")).isEqualTo("200");
        assertThat(env.getProperty("keplura.parser.max-entry-count")).isEqualTo("5000");
        assertThat(env.getProperty("keplura.parser.timeout-seconds")).isEqualTo("60");
        assertThat(env.getProperty("keplura.parser.max-concurrency")).isEqualTo("2");
    }
}
