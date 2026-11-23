package com.keplura.server.tool;

import com.keplura.server.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL integration probe (TU-04c, info follow-up from #28): reading a transcript cancels a pending
 * card whose attachment reference was deleted, and the UPDATE survives on a real PostgreSQL. H2 does not
 * enforce read-only transactions, so this cannot be proven there.
 *
 * <p>Opt-in: runs only with {@code KEPLURA_TEST_POSTGRES_URL} (plus {@code _USER}/{@code _PASSWORD}),
 * e.g. against {@code pgvector/pgvector:pg16}. The regular CI gate on H2 skips it.
 */
@EnabledIfEnvironmentVariable(named = "KEPLURA_TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AttachmentPostgresProbeTest {

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
    @Autowired private ToolExecutionService tools;
    @Autowired private ToolCallStore cards;
    @Autowired private AttachmentToolAdapter attachments;
    @Autowired private ChatService chat;

    private record Fixture(long org, long user, long chat, long prompt, long attachment) { }

    private Fixture fixture() {
        long base = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L) * 10;
        long org = base, space = base + 1, user = base + 2, conversation = base + 3, prompt = base + 4, attachment = base + 5;
        jdbc.update("insert into organization(id,name,created_at) values(?,?,current_timestamp)", org, "PG probe " + base);
        jdbc.update("insert into space(id,org_id,name,created_at) values(?,?,'Probe',current_timestamp)", space, org);
        jdbc.update("insert into membership(org_id,user_id,role,created_at) values(?,?,'OWNER',current_timestamp)", org, user);
        jdbc.update("insert into chat_conversation(id,org_id,space_id,created_by,title,created_at,updated_at) values(?,?,?,?,'Probe',current_timestamp,current_timestamp)",
                conversation, org, space, user);
        jdbc.update("insert into chat_message(id,conversation_id,role,content,model,citations_json,status,created_at) values(?,?,'user','stichwort','','[]','complete',current_timestamp)",
                prompt, conversation);
        jdbc.update("insert into org_tool_setting(org_id,knowledge_enabled) values(?,true)", org);
        jdbc.update("insert into chat_attachment(id,conversation_id,filename,text_content,char_count,truncated,created_by,created_at) values(?,?,'probe.txt','Stichwort PG_PROBE_TEXT',23,false,?,current_timestamp)",
                attachment, conversation, user);
        return new Fixture(org, user, conversation, prompt, attachment);
    }

    private long pendingAfterSearch(Fixture f) {
        var ctx = tools.resolveContext(f.user(), f.org(), f.chat());
        assertThat(ctx).isNotNull();
        var turn = tools.openTurn(ctx, f.prompt());
        turn.startQuestion("stichwort", "");
        assertThat(turn.call("attachment_search", "{\"query\":\"stichwort\"}").invoked()).isTrue();
        var read = attachments.definitionsFor(ctx).stream().filter(d -> d.toolName().equals("attachment_read")).findFirst().orElseThrow();
        return cards.create(ctx, f.prompt(), read, ToolExecutionService.definitionHash(read),
                "{\"attachmentId\":" + f.attachment() + ",\"offset\":0}", ToolCallStore.Status.PENDING, true, null, Instant.now())
                .orElseThrow().id();
    }

    @Test void transcriptReadCancelsTheCardOfADeletedAttachmentOnPostgres() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL");
        var f = fixture();
        long pending = pendingAfterSearch(f);
        var readable = chat.transcript(f.user(), f.org(), f.chat());
        assertThat(readable.toString()).contains("PG_PROBE_TEXT");
        assertThat(cards.find(pending).orElseThrow().status()).isEqualTo(ToolCallStore.Status.PENDING);

        jdbc.update("delete from chat_attachment where id=?", f.attachment());
        var after = chat.transcript(f.user(), f.org(), f.chat());
        assertThat(after.toString()).doesNotContain("PG_PROBE_TEXT");
        assertThat(cards.find(pending).orElseThrow().status()).isEqualTo(ToolCallStore.Status.CANCELLED);
    }
}
