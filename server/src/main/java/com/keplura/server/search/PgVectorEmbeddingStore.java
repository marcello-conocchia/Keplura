package com.keplura.server.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Native pgvector KNN store (M3d) — the PostgreSQL/prod path. Vectors live in
 * the extension-only twin table {@code kb_chunk_embedding} (created by
 * db/migration-postgresql/V5__pgvector.sql, mirrored from the portable
 * kb_chunk rows by {@link #afterChunkPersisted} inside the SAME indexing
 * transaction). Cosine distance ({@code <=>}) is evaluated by the database.
 *
 * <p><b>The tenant gate is inside the statement:</b> the ANN candidate set is
 * restricted by kb pin + kb→space pin + space→org pin + membership EXISTS
 * <i>before</i> the ORDER BY/LIMIT — the same four pins the memory path's JPQL
 * enforces — so a foreign caller's query never even ranks a chunk it
 * cannot see: the same query-level isolation rule as every M3c read, just
 * spoken in SQL instead of JPQL (documented in docs/adr/0007).
 * {@link SearchStoreSelectionTest} asserts this gate shape (and the exact
 * parameter count against the binding) because pgvector cannot execute in CI.
 *
 * <p>Score convention: {@code 1 - cosine_distance} — equal to within float32
 * representation noise to what {@link MemoryEmbeddingStore} computes in the
 * JVM (M3d evidence: identical ranking, Δ &lt; 2e-8), the cross-check the PG
 * evidence run performs.
 *
 * <p>Raw JDBC (not Hibernate): a {@code vector} parameter has no portable
 * type, so it cannot go through the JPQL entities — the whole class is one
 * narrow JDBC seam used only after {@link #probe} verified extension + table
 * + dimension at startup.
 */
public class PgVectorEmbeddingStore implements SearchStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorEmbeddingStore.class);

    private static final String UPSERT_SQL = """
            insert into kb_chunk_embedding (document_id, chunk_idx, embedding)
            values (?, ?, ?::vector)
            on conflict (document_id, chunk_idx) do update set embedding = excluded.embedding
            """;

    static final String SEARCH_SQL = """
            select e.document_id, c.chunk_idx, c.chunk_text, d.filename,
                   c.page_number, c.section_title, c.start_offset, c.end_offset,
                   coalesce((select max(sf.modified_at) from data_source_file sf where sf.document_id=d.id), d.created_at) as source_updated_at,
                   1 - (e.embedding <=> ?::vector) as score
            from kb_chunk_embedding e
            join kb_chunk c on c.document_id = e.document_id and c.chunk_idx = e.chunk_idx
            join kb_document d on d.id = e.document_id
            where c.knowledge_base_id = ?
              and exists (select 1 from knowledge_base k
                          where k.id = ? and k.space_id = ?)
              and exists (select 1 from space s
                          where s.id = ? and s.org_id = ?)
              and exists (select 1 from membership m
                          where m.org_id = ? and m.user_id = ?)
              and """ + com.keplura.server.access.AccessFilter.sql("e.document_id") + """
            order by e.embedding <=> ?::vector
            limit ?
            """;

    private final JdbcTemplate jdbc;

    // Both rankings see only the same SQL-authorized scope. Reciprocal rank
    // fusion avoids adding incomparable full-text and cosine score scales.
    static final String HYBRID_SEARCH_SQL = """
            with scoped as materialized (
              select c.document_id, c.chunk_idx, c.chunk_text, d.filename,
                     c.page_number, c.section_title, c.start_offset, c.end_offset,
                   coalesce((select max(sf.modified_at) from data_source_file sf where sf.document_id=d.id), d.created_at) as source_updated_at,
                     1 - (e.embedding <=> ?::vector) as score,
                     ts_rank_cd(to_tsvector('german', c.chunk_text),
                                websearch_to_tsquery('german', ?)) as lexical
              from kb_chunk c
              join kb_chunk_embedding e on e.document_id=c.document_id and e.chunk_idx=c.chunk_idx
              join kb_document d on d.id=c.document_id
              where c.knowledge_base_id=? and d.index_status is null
                and exists (select 1 from knowledge_base k where k.id=? and k.space_id=?)
                and exists (select 1 from space s where s.id=? and s.org_id=?)
                and exists (select 1 from membership m where m.org_id=? and m.user_id=?)
                and """ + com.keplura.server.access.AccessFilter.sql("c.document_id") + """
            ), dense as (
              select document_id, chunk_idx,
                     row_number() over (order by score desc, document_id, chunk_idx) as rank
              from scoped order by score desc, document_id, chunk_idx limit ?
            ), lexical as (
              select document_id, chunk_idx,
                     row_number() over (order by lexical desc, document_id, chunk_idx) as rank
              from scoped where lexical>0 order by lexical desc, document_id, chunk_idx limit ?
            ), fused as (
              select document_id, chunk_idx, sum(weight) as rank
              from (
                select document_id, chunk_idx, 1.0/(60+rank) as weight from dense
                union all
                select document_id, chunk_idx, 1.0/(60+rank) as weight from lexical
              ) ranks group by document_id, chunk_idx
            )
            select s.* from scoped s join fused f using (document_id, chunk_idx)
            order by f.rank desc, s.document_id, s.chunk_idx limit ?
            """;

    PgVectorEmbeddingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Startup capability probe: the extension must be installed, the twin
     * table must exist and its {@code embedding} column must carry EXACTLY
     * the configured model dimension. Any miss → not usable (caller decides
     * fallback vs. fail-fast), because writing wrong-dimension vectors into a
     * vector(N) column would fail per insert, and a stale table at another N
     * is worse than an honest fallback.
     */
    static boolean probe(JdbcTemplate jdbc, int expectedDim) {
        try {
            Integer ext = jdbc.queryForObject(
                    "select count(*) from pg_extension where extname = 'vector'", Integer.class);
            if (ext == null || ext == 0) {
                log.info("pgvector probe: extension 'vector' not installed");
                return false;
            }
            String type = jdbc.queryForObject("""
                    select format_type(atttypid, atttypmod) from pg_attribute
                    where attrelid = 'kb_chunk_embedding'::regclass and attname = 'embedding'
                    """, String.class);
            if (type == null || !type.startsWith("vector")) {
                log.info("pgvector probe: kb_chunk_embedding.embedding missing or not a vector");
                return false;
            }
            int dim = PgVectorSchema.parseDimension(type);
            if (dim != expectedDim) {
                log.warn("pgvector probe: table dimension {} != configured embedding dimension {}", dim, expectedDim);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.info("pgvector probe failed ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void afterChunkPersisted(Chunk chunk, float[] embedding) {
        // The chunk mirror rides the SAME transaction/connection as the JPA
        // rows (DataSourceUtils ties this to the active transaction), so a
        // rolled-back upload rolls back its vectors too — no orphan native
        // rows on the pgvector side, same all-or-nothing rule as the
        // portable half.
        jdbc.update(UPSERT_SQL, chunk.getDocumentId(), chunk.getChunkIdx(),
                EmbeddingCodec.encode(embedding));
    }

    @Override
    public List<SearchHit> search(float[] queryEmbedding, Long kbId, Long spaceId,
                                  Long orgId, Long userId, int topK) {
        String vector = EmbeddingCodec.encode(queryEmbedding);
        return jdbc.query(SEARCH_SQL,
                (rs, n) -> new SearchHit(rs.getLong("document_id"), rs.getString("filename"),
                        rs.getInt("chunk_idx"), rs.getString("chunk_text"), rs.getDouble("score"),
                        rs.getObject("page_number", Integer.class), rs.getString("section_title"),
                        rs.getObject("start_offset", Integer.class), rs.getObject("end_offset", Integer.class),
                        rs.getTimestamp("source_updated_at").toInstant()),
                // positional binding, in ?-appearance order: score vector, kb pin,
                // kb→space pin, space→org pin, membership pin, order vector, limit
                vector, kbId, kbId, spaceId, spaceId, orgId, orgId, userId,
                userId, userId, // ADR-0012 access filter: allow branch, deny branch
                vector, topK);
    }

    @Override
    public List<SearchHit> search(String query, float[] queryEmbedding, Long kbId, Long spaceId,
                                  Long orgId, Long userId, int topK) {
        return jdbc.query(HYBRID_SEARCH_SQL,
                (rs, n) -> new SearchHit(rs.getLong("document_id"), rs.getString("filename"),
                        rs.getInt("chunk_idx"), rs.getString("chunk_text"), rs.getDouble("score"),
                        rs.getObject("page_number", Integer.class), rs.getString("section_title"),
                        rs.getObject("start_offset", Integer.class), rs.getObject("end_offset", Integer.class),
                        rs.getTimestamp("source_updated_at").toInstant()),
                EmbeddingCodec.encode(queryEmbedding), query,
                kbId, kbId, spaceId, spaceId, orgId, orgId, userId,
                userId, userId, // ADR-0012 access filter: allow branch, deny branch
                topK, topK, topK);
    }

    @Override
    public void afterDocumentDeleted(Long documentId) {
        // same transaction as the portable delete (JdbcTemplate joins it)
        jdbc.update("delete from kb_chunk_embedding where document_id = ?", documentId);
    }

    @Override
    public String backendName() {
        return "pgvector";
    }
}
