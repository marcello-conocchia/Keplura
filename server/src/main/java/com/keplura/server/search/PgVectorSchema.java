package com.keplura.server.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SERVER-PAKET (ADR-0010): makes the pgvector twin column dimension follow
 * the configured embedding model instead of the frozen {@code vector(64)}
 * of migration V5 — WITHOUT ever touching indexed data.
 *
 * <p>Rule (decided per boot, before the store probe in
 * {@link SearchStoreConfiguration}):
 * <ul>
 *   <li>column dimension == configured dimension → nothing to do;</li>
 *   <li>mismatch AND the index is completely empty ({@code kb_chunk} and
 *       {@code kb_chunk_embedding} both 0 rows) AND auto-adapt is on →
 *       {@code ALTER TABLE kb_chunk_embedding ALTER COLUMN embedding TYPE
 *       vector(N)} inside one transaction that first takes an ACCESS
 *       EXCLUSIVE lock and re-counts (no upload can slip in between);</li>
 *   <li>mismatch with ANY indexed chunk → no change. The store probe then
 *       refuses pgvector (auto ⇒ logged fallback, pinned ⇒ failed boot).
 *       Vectors of two models are not comparable; mixing them would make
 *       search silently wrong, so the operator must re-index first.</li>
 * </ul>
 *
 * <p>Why not a Flyway placeholder migration: a versioned migration runs once,
 * so a dimension chosen at first boot could never follow a later (still
 * empty) model switch, and a placeholder change would trip checksum
 * validation. The twin table is NOT a JPA entity, so {@code ddl-auto=validate}
 * is unaffected; V5 stays frozen (ADR-0006 discipline).
 */
public final class PgVectorSchema {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSchema.class);

    /**
     * Upper bound the portable {@code kb_chunk.embedding VARCHAR(65536)} can
     * hold: {@code Float.toString} needs at most 15 chars per component plus
     * one separator, plus the two brackets ⇒ floor((65536 - 2 + 1) / 16).
     */
    public static final int MAX_DIMENSION = 4095;

    /** What {@link #decide} concluded. */
    public enum Decision {
        /** table already carries the configured dimension */
        MATCH,
        /** empty index, auto-adapt on → retype the column */
        ADAPT,
        /** mismatch that must not be fixed automatically */
        KEEP_MISMATCH
    }

    private PgVectorSchema() {
    }

    /** Fails the boot on a dimension no store can hold — before any DB work. */
    public static void requireValidDimension(int dim) {
        if (dim < 1 || dim > MAX_DIMENSION) {
            throw new IllegalStateException("keplura.search.embedding-dim must be between 1 and "
                    + MAX_DIMENSION + ", got " + dim);
        }
    }

    /** Pure decision rule (unit-tested); see class doc. */
    static Decision decide(int tableDim, int expectedDim, long chunkRows, long twinRows, boolean autoAdapt) {
        if (tableDim == expectedDim) {
            return Decision.MATCH;
        }
        if (autoAdapt && chunkRows == 0 && twinRows == 0) {
            return Decision.ADAPT;
        }
        return Decision.KEEP_MISMATCH;
    }

    /**
     * Brings the twin column to {@code expectedDim} where {@link #decide}
     * allows it. Never throws on a missing extension/table — the probe that
     * follows reports that case; returns the decision for logging/tests, or
     * {@code null} when the twin table is not there at all.
     */
    public static Decision reconcile(JdbcTemplate jdbc, int expectedDim, boolean autoAdapt) {
        requireValidDimension(expectedDim);
        try {
            return jdbc.execute((Connection c) -> reconcile(c, expectedDim, autoAdapt));
        } catch (RuntimeException e) {
            log.info("pgvector schema check skipped ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private static Decision reconcile(Connection c, int expectedDim, boolean autoAdapt) throws SQLException {
        if (!twinTableExists(c)) {
            return null;
        }
        boolean previousAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            int tableDim = tableDimension(st);
            if (tableDim == expectedDim) {
                c.commit();
                return Decision.MATCH;
            }
            // Lock BOTH tables before counting: an upload commits chunk rows and
            // twin rows together, so with both locked the counts cannot change
            // between the check and the ALTER.
            st.execute("lock table kb_chunk, kb_chunk_embedding in access exclusive mode");
            long chunkRows = count(st, "select count(*) from kb_chunk");
            long twinRows = count(st, "select count(*) from kb_chunk_embedding");
            Decision d = decide(tableDim, expectedDim, chunkRows, twinRows, autoAdapt);
            if (d == Decision.ADAPT) {
                // expectedDim is a validated int — no string from outside reaches the DDL
                st.execute("alter table kb_chunk_embedding alter column embedding type vector("
                        + expectedDim + ")");
                c.commit();
                log.warn("pgvector: kb_chunk_embedding.embedding retyped vector({}) -> vector({}) "
                        + "(index was empty; follows keplura.search.embedding-dim)", tableDim, expectedDim);
            } else {
                c.rollback();
                log.warn("pgvector: column dimension {} != configured embedding dimension {} and the "
                                + "index is not empty ({} chunks) or auto-adapt is off — NOT changed. "
                                + "Re-index with the new model (or restore the old one) before switching.",
                        tableDim, expectedDim, chunkRows);
            }
            return d;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(previousAutoCommit);
        }
    }

    private static boolean twinTableExists(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select to_regclass('kb_chunk_embedding') is not null")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static int tableDimension(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("""
                select format_type(atttypid, atttypmod) from pg_attribute
                where attrelid = 'kb_chunk_embedding'::regclass and attname = 'embedding'
                """)) {
            return rs.next() ? parseDimension(rs.getString(1)) : -1;
        }
    }

    /** {@code "vector(1024)"} → 1024; anything else → -1. */
    static int parseDimension(String formatType) {
        if (formatType == null || !formatType.startsWith("vector")) {
            return -1;
        }
        int open = formatType.indexOf('(');
        int close = formatType.indexOf(')');
        if (open < 0 || close <= open) {
            return -1;
        }
        try {
            return Integer.parseInt(formatType.substring(open + 1, close).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
