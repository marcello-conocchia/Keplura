package com.keplura.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dev profile (what {@code java -jar keplura-server.jar} runs without any
 * profile) must build its schema from the SAME Flyway migrations as prod.
 *
 * <p>Regression: dev used Hibernate {@code ddl-auto=update} with Flyway off, so
 * tables that exist only in migrations (no JPA entity, used via JdbcTemplate —
 * chat knowledge bases/attachments, MCP, web search settings) were never
 * created. Attaching a knowledge base to a chat answered HTTP 500
 * ({@code BadSqlGrammarException}) for anyone trying Keplura locally.
 *
 * <p>The test reads the REAL {@code application-dev.properties} from the main
 * resources (the test-only override in {@code config/application-dev.properties}
 * keeps the other tests on their previous schema path) and boots with exactly
 * those settings; only the H2 file is swapped for an in-memory database with the
 * same URL options.
 */
@SpringBootTest
class DevProfileSchemaTest {

    private static final List<String> MIGRATION_ONLY_TABLES = List.of(
            "chat_conversation_kb", "chat_attachment",
            "mcp_server", "mcp_server_space", "mcp_call_log",
            "web_search_settings");

    @Autowired
    private JdbcTemplate jdbc;

    static Properties mainDevProperties() throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-dev.properties"));
    }

    /** Default value of a {@code ${ENV:default}} placeholder, or the raw value. */
    static String defaultOf(String raw) {
        if (raw != null && raw.startsWith("${") && raw.endsWith("}") && raw.contains(":")) {
            return raw.substring(raw.indexOf(':') + 1, raw.length() - 1);
        }
        return raw;
    }

    @DynamicPropertySource
    static void useMainDevSchemaSettings(DynamicPropertyRegistry registry) throws IOException {
        Properties dev = mainDevProperties();
        String fileUrl = defaultOf(dev.getProperty("spring.datasource.url"));
        String options = fileUrl.substring(fileUrl.indexOf(';'));
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:dev-profile-schema-test" + options
                .replace(";AUTO_SERVER=TRUE", "") + ";DB_CLOSE_DELAY=-1");
        for (String key : List.of("spring.flyway.enabled", "spring.flyway.locations",
                "spring.jpa.hibernate.ddl-auto")) {
            registry.add(key, () -> defaultOf(dev.getProperty(key)));
        }
    }

    @Test
    void devProfileOwnsSchemaViaFlywayLikeProd() throws IOException {
        Properties dev = mainDevProperties();
        assertThat(defaultOf(dev.getProperty("spring.flyway.enabled"))).isEqualTo("true");
        assertThat(defaultOf(dev.getProperty("spring.jpa.hibernate.ddl-auto"))).isEqualTo("validate");
        assertThat(defaultOf(dev.getProperty("spring.datasource.url")))
                .startsWith("jdbc:h2:file:")
                .contains(";MODE=PostgreSQL")
                .contains(";DATABASE_TO_LOWER=TRUE");
    }

    @Test
    void migrationOnlyTablesExistInDevSchema() {
        for (String table : MIGRATION_ONLY_TABLES) {
            Integer found = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_name = ?",
                    Integer.class, table);
            assertThat(found).as("table %s", table).isEqualTo(1);
        }
    }
}
