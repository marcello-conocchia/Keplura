package com.keplura.server.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M3d: the store-selection rules (auto/pgvector/memory) without a live
 * database — the probe result is the boolean input. Plus the hermetic
 * gate-shape assertions on {@link PgVectorEmbeddingStore#SEARCH_SQL}:
 * pgvector cannot execute in CI, so the tenant pins and the exact
 * placeholder↔binding parity are asserted on the statement itself.
 */
class SearchStoreSelectionTest {

    private final MemoryEmbeddingStore memory = mock(MemoryEmbeddingStore.class);

    @BeforeEach
    void stubBackendName() {
        // mock returns null by default — backendName() must answer for assertions
        when(memory.backendName()).thenReturn("memory");
    }
    private final org.slf4j.Logger log = LoggerFactory.getLogger(SearchStoreSelectionTest.class);

    private SearchStore chosen(String mode, boolean nativeCapable) {
        return SearchStoreConfiguration.choose(mode, nativeCapable, memory,
                () -> new PgVectorEmbeddingStore(mock(org.springframework.jdbc.core.JdbcTemplate.class)),
                64, log);
    }

    @Test
    void autoPicksNativeWhenCapableAndMemoryOtherwise() {
        assertThat(chosen("auto", true).backendName()).isEqualTo("pgvector");
        assertThat(chosen("auto", false).backendName()).isEqualTo("memory");
    }

    @Test
    void pinnedPgvectorFailsTheBootWithoutTheCapability() {
        assertThatThrownBy(() -> chosen("pgvector", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a PostgreSQL");
    }

    @Test
    void pinnedMemoryNeverGoesNative() {
        assertThat(chosen("memory", true).backendName()).isEqualTo("memory");
    }

    @Test
    void unknownModeIsAConfigError() {
        assertThatThrownBy(() -> chosen("elastic", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auto|memory|pgvector");
    }

    // ---- pgvector gate-shape: assert on the statement + binding what CI cannot execute ----

    /** Records the exact SQL + positional args the store hands to JDBC; returns no rows. */
    private static final class RecordingJdbc extends JdbcTemplate {
        String sql;
        List<Object> args;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.args = new ArrayList<>(List.of(args));
            return List.of();
        }
    }

    @Test
    void pgvectorSearchSqlCarriesAllFourTenantPinsBeforeRanking() {
        String sql = PgVectorEmbeddingStore.SEARCH_SQL.replaceAll("\\s+", " ").toLowerCase();
        // the same four gates ChunkRepository.findCandidateChunks enforces in JPQL
        assertThat(sql).contains("c.knowledge_base_id = ?");                        // kb pin
        assertThat(sql).contains("k.id = ? and k.space_id = ?");                    // kb→space pin
        assertThat(sql).contains("s.id = ? and s.org_id = ?");                      // space→org pin
        assertThat(sql).contains("m.org_id = ? and m.user_id = ?");                 // membership pin
        // every gate sits in the WHERE — before ORDER BY/LIMIT, never post-filtered
        int where = sql.indexOf("where");
        int order = sql.indexOf("order by");
        assertThat(where).isPositive();
        assertThat(order).isGreaterThan(where);
        assertThat(sql.indexOf("limit")).isGreaterThan(order);
        assertThat(sql.lastIndexOf("exists (select 1 from space")).isGreaterThan(where);
        assertThat(sql.lastIndexOf("exists (select 1 from membership")).isGreaterThan(where);
    }

    @Test
    void pgvectorBindingMatchesPlaceholdersExactlyAndInOrder() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new PgVectorEmbeddingStore(jdbc).search(
                new float[]{1f, 0f}, 11L, 22L, 33L, 44L, 5);

        assertThat(jdbc.sql).isEqualTo(PgVectorEmbeddingStore.SEARCH_SQL);
        long placeholders = PgVectorEmbeddingStore.SEARCH_SQL.chars()
                .filter(c -> c == '?').count();
        assertThat(jdbc.args).hasSize((int) placeholders);
        // positional order mirrors the pin order in the WHERE clause:
        // score vector, kb pin, kb→space pin, space→org pin, membership pin,
        // ACL allow/deny user pins, order vector, limit
        assertThat(jdbc.args).containsExactly(
                "[1.0,0.0]", 11L, 11L, 22L, 22L, 33L, 33L, 44L, 44L, 44L, "[1.0,0.0]", 5);
    }
}
