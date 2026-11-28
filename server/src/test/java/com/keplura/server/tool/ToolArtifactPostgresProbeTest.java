package com.keplura.server.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TU-06d PostgreSQL probe: the {@code tool_artifact} table (bytea, FK cascade with the card) on a real PostgreSQL.
 * Opt-in like {@link AttachmentPostgresProbeTest}: runs only with {@code KEPLURA_TEST_POSTGRES_URL}; the H2 gate skips it.
 */
@EnabledIfEnvironmentVariable(named = "KEPLURA_TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ToolArtifactPostgresProbeTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("KEPLURA_TEST_POSTGRES_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("KEPLURA_TEST_POSTGRES_USER", "keplura"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("KEPLURA_TEST_POSTGRES_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-{vendor}");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("keplura.search.store", () -> "memory");
        registry.add("keplura.upload.dir", () -> "target/test-uploads-pg-probe");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ToolArtifactStore store;
    @Autowired private ToolCallStore cards;

    @Test void bytesRoundTripAndCascadeWithTheCard() {
        long base = 910_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L) * 10;
        long org = base, space = base + 1, user = base + 2, conversation = base + 3;
        jdbc.update("insert into organization(id,name,created_at) values(?,?,current_timestamp)", org, "Artifact probe " + base);
        jdbc.update("insert into space(id,org_id,name,created_at) values(?,?,'Probe',current_timestamp)", space, org);
        jdbc.update("insert into membership(org_id,user_id,role,created_at) values(?,?,'OWNER',current_timestamp)", org, user);
        jdbc.update("insert into chat_conversation(id,org_id,space_id,created_by,title,created_at,updated_at) values(?,?,?,?,'Probe',current_timestamp,current_timestamp)",
                conversation, org, space, user);
        var tool = new ToolDefinition("files:1:get", "files", "files_get", "get", 1L, "Dateien", "d", null, ToolRisk.READ, true, "fp");
        var card = cards.create(new ToolContext(user, org, space, conversation), null, tool, "h", "{}",
                ToolCallStore.Status.RUNNING, false, null, Instant.now()).orElseThrow();
        byte[] png = ToolArtifactTest.png(2, 2);
        String notes = store.store(card, List.of(new ToolAdapter.Artifact("p.png", "image/png", png)), false);
        assertThat(notes).contains("p.png (image/png");
        var view = store.forCard(card.id()).getFirst();
        assertThat(store.owned(view.id(), user, org, conversation).orElseThrow().data()).isEqualTo(png);
        cards.deleteConversation(conversation);
        assertThat(jdbc.queryForObject("select count(*) from tool_artifact where conversation_id=?", Integer.class, conversation)).isZero();
        jdbc.update("delete from chat_conversation where id=?", conversation);
        jdbc.update("delete from membership where org_id=?", org);
        jdbc.update("delete from space where id=?", space);
        jdbc.update("delete from organization where id=?", org);
    }
}
