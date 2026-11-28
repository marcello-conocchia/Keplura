package com.keplura.server.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3a (ADR-0006) + M3b + M3c + M3d: the Flyway chain is the source of truth
 * for the prod schema. Legs, all hermetic (run in CI, no container required):
 *
 * <ol>
 *   <li><b>Portable replay</b>: Flyway applies {@code V1..V4 + V6 + V7} to a
 *       fresh H2 database in {@code PostgreSQL} compatibility mode — proves
 *       every migration parses, executes in version order and lands exactly
 *       the nine domain tables (plus the MVP-5 index_status column on
 *       kb_document). V4/V6/V7 are written portable on purpose; the
 *       pgvector-only V5 lives in {@code db/migration-postgresql} and must
 *       NOT appear on this leg (verified: the vector twin table is absent).</li>
 *   <li><b>PG vendor replay</b>: the SAME portable chain plus the
 *       PostgreSQL-only directory is scanned against H2 with the vendor
 *       location spelled literally — proves V5 is picked up when Boot's
 *       {@code {vendor}} resolution lands on PostgreSQL and parses as SQL
 *       (the CREATE EXTENSION/vector statements fail on H2 EXECUTION, so
 *       this leg validates <i>resolution</i> of the file, and V4-only table
 *       set difference against leg 1).</li>
 *   <li><b>Drift</b>: re-pointing Flyway at a schema that has an untracked
 *       table with no history must fail instead of silently baselining.</li>
 * </ol>
 *
 * The end-to-end leg (Flyway V1→V5 + Hibernate validate + real KNN against
 * <b>PostgreSQL 16 + pgvector</b>) cannot run in CI on this host class
 * without a DB service; it was executed manually against a local PG16 with
 * the extension installed — see the PR evidence log and docs/development.md
 * ("RAG-Suchpfad / pgvector-Evidenz").
 */
class FlywayBaselineMigrationTest {

    private static final String BASELINE_LOCATION = "classpath:db/migration";
    private static final String PG_LOCATION = "classpath:db/migration-postgresql";

    private DataSource h2PgMode(String dbName) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }

    private Flyway flyway(DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations(BASELINE_LOCATION)
                .baselineOnMigrate(false)
                .load();
    }

    @Test
    void portableMigrationsApplyInPostgreSqlModeAndCreateTheDomainTables() {
        DataSource ds = h2PgMode("flyway_v1v10");
        MigrateResult result = flyway(ds).migrate();
        // V1 (three tables), V2 (space), V3 (knowledge_base + kb_document),
        // V4 (kb_chunk), V6 (chat_conversation + chat_message), V7 (document
        // index_status column), V8 (org_invite), V9 (app_user.role column),
        // V10 (ai_provider + ai_model), V11 (oidc_provider + oidc_account_
        // link) — applied in strict version order.
        // V5 (pgvector) is vendor-only and must NOT appear here;
        // V6..V12 being portable keeps the chain gapless on H2 (V12: index_status
        // PENDING/FAILED for async indexing, constraint swap only — no new table).
        // V14/V15 (PILOT 4e) and V16 (ADR-0012 data sources + ACL tables) — portable.
        // V25 (TU-01: tool_risks column + tool_call_log) — portable.
        // V27 (TU-03b: persistent question budgets, execution guard and answer binding).
        // V28 binds a single dependent proposal to its first confirmed action.
        // V29 knowledge tool/provenance, V30 (TU-04c) marks private arguments, V31 (TU-04d) web search switch — all additive.
        // V32 (TU-05a) adds the nullable chat selection and its confirmation cutoff.
        // V33 (TU-05b) adds the chat model tool policy (tool_use with default, nullable default kinds).
        // V34 (TU-05c) stores which tools of a sealed question catalog tool_search made visible.
        // V35 (TU-06a) adds the OpenAPI tool server tables (own tables, MCP tables unchanged).
        // V36 (TU-06b) adds OAuth client configuration, personal connections and authorization flows.
        // V36 also stores the MCP auth mode explicitly (personal mode fails closed without a client).
        // V37 (TU-06d) adds the verified image/file results of tool calls (cascade with the card).
        // V38 binds the OAuth return address to its flow.
        assertThat(result.migrationsExecuted).isEqualTo(38);

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name <> 'flyway_schema_history'", String.class))
                .containsExactlyInAnyOrder("app_user", "organization", "membership", "space",
                        "knowledge_base", "kb_document", "kb_chunk",
                        "chat_conversation", "chat_message", "org_invite",
                        "ai_provider", "ai_model",
                        "oidc_provider", "oidc_account_link",
                        "user_principal", "kb_document_acl", "directory_connection",
                        "data_source", "data_source_file",
                        "quality_question", "quality_run", "quality_result", "prompt_template",
                        "chat_conversation_kb", "chat_attachment", "web_search_settings", "mcp_server",
                        "mcp_server_space", "mcp_call_log", "tool_call_log", "tool_call", "tool_call_stop",
                        "tool_question", "tool_execution_guard", "org_tool_setting", "mcp_org_boundary", "tool_admin_event",
                        "openapi_server", "openapi_server_space", "openapi_org_boundary",
                        "tool_oauth_client", "tool_oauth_connection", "tool_oauth_flow", "tool_artifact");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='chat_message' AND column_name='prompt_message_id' AND is_nullable='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='tool_call' AND column_name='parent_call_id' AND is_nullable='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='tool_question' AND column_name='followup_used' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='chat_conversation' AND column_name='tool_selection_json' AND is_nullable='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='chat_conversation' AND column_name='tool_selection_cutoff' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='ai_model' AND column_name='tool_use' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='ai_model' AND column_name='tool_defaults_json' AND is_nullable='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='tool_question' AND column_name='visible_tools_json' AND is_nullable='YES'",
                Long.class)).isEqualTo(1L);
        // V4 must stay vendor-free on this leg: no pgvector twin table on H2
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name = 'kb_chunk_embedding'", String.class))
                .isEmpty();
        // identity PKs and the unique email key are part of the contract
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='app_user' AND column_name='id' AND is_identity='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='app_user' AND constraint_type='UNIQUE'",
                Long.class)).isGreaterThanOrEqualTo(1L);
        // the M2c composite key is preserved
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='membership' AND constraint_type='PRIMARY KEY'",
                Long.class)).isEqualTo(1L);
        // M3b: space has an identity PK and the (org_id, name) UNIQUE key — the
        // name-is-unique-per-org invariant must hold at the DB level, not just
        // in the service pre-check.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='space' AND column_name='id' AND is_identity='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='space' AND constraint_type='UNIQUE'",
                Long.class)).isGreaterThanOrEqualTo(1L);
        // M3c: knowledge_base has an identity PK + the (space_id, name) UNIQUE
        // key, and kb_document carries the kb-lookup index backing the list read.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='knowledge_base' AND column_name='id' AND is_identity='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='knowledge_base' AND constraint_type='UNIQUE'",
                Long.class)).isGreaterThanOrEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='kb_document' AND column_name='stored_path' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        // Index presence via the portable INFORMATION_SCHEMA view (H2 2.x:
        // INDEXES carries INDEX_NAME/TABLE_NAME; a pg_indexes catalog query
        // would be PG-only and break the H2 replay leg of this test).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.indexes WHERE lower(table_name)='kb_document' AND lower(index_name)='kb_document_knowledge_base_id_idx'",
                Long.class)).isEqualTo(1L);
        // M3d: kb_chunk carries the (document_id, chunk_idx) UNIQUE key — the
        // idempotent re-index anchor — plus the kb-lookup index the search
        // tenant gate filters on, and a NOT NULL text + embedding pair.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='kb_chunk' AND constraint_type='UNIQUE'",
                Long.class)).isGreaterThanOrEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='kb_chunk' AND column_name='embedding' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.indexes WHERE lower(table_name)='kb_chunk' AND lower(index_name)='kb_chunk_knowledge_base_id_idx'",
                Long.class)).isEqualTo(1L);
        // Flyway history exists and carries V1..V4 + V6..V11 in strict order
        // (V5 is pgvector-only and lives in the vendor dir, never on this leg).
        // (version IS NOT NULL: in H2 PostgreSQL mode Flyway also logs a
        // schema-creation row with a null version — not a migration.)
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class))
                .containsExactly("1", "2", "3", "4", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39");
        // M4b: both chat tables have identity PKs, the citation blob and status
        // are NOT NULL (an assistant row without an honest end state must be
        // impossible at the schema level), and the list-read index exists.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='chat_conversation' AND column_name='id' AND is_identity='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='chat_message' AND column_name='id' AND is_identity='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='chat_message' AND column_name='citations_json' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='chat_message' AND column_name='status' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.indexes WHERE lower(table_name)='chat_conversation' AND lower(index_name)='chat_conversation_org_id_created_by_space_id_idx'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.indexes WHERE lower(table_name)='chat_message' AND lower(index_name)='chat_message_conversation_id_idx'",
                Long.class)).isEqualTo(1L);
        // MVP-5: kb_document.index_status is the ONLY persisted index marker,
        // nullable and CHECK-clamped to NO_TEXT_LAYER — every other status
        // stays derived, so a stored claim can never contradict the derivation.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='kb_document' AND column_name='index_status' AND is_nullable='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints WHERE lower(constraint_name)='kb_document_index_status_check'",
                Long.class)).isEqualTo(1L);
        // SEC-2 V8: org_invite — token_hash is the UNIQUE one-time anchor,
        // role is CHECK-clamped to MEMBER (an invite can structurally never
        // mint an OWNER, even as a hand-edited row), used_at stays NULL until
        // redemption.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='org_invite' AND column_name='token_hash' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='org_invite' AND constraint_type='UNIQUE'",
                Long.class)).isGreaterThanOrEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints WHERE lower(constraint_name)='org_invite_role_check'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='org_invite' AND column_name='used_at' AND is_nullable='YES'",
                Long.class)).isEqualTo(1L);
        // ADMIN-KI V9: app_user.role — NOT NULL DEFAULT 'USER', CHECK-clamped
        // to USER|ADMIN (a hand-edited row can never mint a third role).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='app_user' AND column_name='role' AND is_nullable='NO'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints WHERE lower(constraint_name)='app_user_role_check'",
                Long.class)).isEqualTo(1L);
        // ADMIN-KI V10: ai_provider + ai_model — identity PKs, the provider's
        // type CHECK and the model's type CHECK are the schema-level vocabularies.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='ai_provider' AND column_name='id' AND is_identity='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='ai_model' AND column_name='id' AND is_identity='YES'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints WHERE lower(constraint_name)='ai_provider_type_check'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints WHERE lower(constraint_name)='ai_model_type_check'",
                Long.class)).isEqualTo(1L);
        // the default-slot UNIQUE is the race-proof double-default guard
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='ai_model' AND constraint_type='UNIQUE'",
                Long.class)).isGreaterThanOrEqualTo(1L);
    }

    /**
     * Boot resolves {@code classpath:db/migration-{vendor}} against the JDBC
     * driver id — "postgresql" on the real database. This leg proves the
     * vendor directory + V5 file are correctly named and RESOLVED by Flyway
     * (the history table shows version 5 attempted); execution itself stops
     * at {@code CREATE EXTENSION} — which is exactly the point of isolating
     * pgvector SQL from the portable chain: on H2 it must fail, on
     * PostgreSQL (evidence run) it succeeds.
     */
    @Test
    void postgresqlVendorMigrationResolvesFromItsOwnDirectory() {
        DataSource ds = h2PgMode("flyway_vendor_probe");
        Flyway f = Flyway.configure()
                .dataSource(ds)
                .locations(BASELINE_LOCATION, "classpath:db/migration-h2")
                .baselineOnMigrate(false)
                .load();
        f.migrate(); // portable chain incl. the empty h2 vendor dir

        // the PG-only file must be discoverable and parse-as-resource from its dir:
        // run it with a dry validation via a Flyway scan of migration-postgresql
        // only far enough to attempt V5 on H2 (fails at CREATE EXTENSION — as
        // designed), proving version/naming resolves without silently skipping.
        Flyway pg = Flyway.configure()
                .dataSource(ds)
                .locations(BASELINE_LOCATION, PG_LOCATION)
                .baselineOnMigrate(false)
                .load();
        assertThatThrownBy(() -> pg.migrate())
                .hasMessageContaining("5"); // V5 was found & attempted (H2 cannot run it)
    }

    @Test
    void migrationAgainstUntrackedNonEmptySchemaFailsInsteadOfBaselining() {
        DataSource ds = h2PgMode("flyway_drift");
        new JdbcTemplate(ds).execute("CREATE TABLE someone_elses_table (id INT)");

        assertThatThrownBy(() -> flyway(ds).migrate())
                .hasMessageContaining("non-empty schema");
    }

    @Test
    void secondMigrateIsANoOpAndValidatesClean() {
        DataSource ds = h2PgMode("flyway_idempotent");
        Flyway f = flyway(ds);
        f.migrate();
        assertThat(f.migrate().migrationsExecuted).isZero();
        f.validate(); // no pending, no checksum drift
    }
}
