package com.keplura.server.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PostgreSQL acceptance against the example data from quality-check.cjs.
 * Run in the build container on the Compose network; credentials are ENV-only.
 * Arguments: organisation id, space id, knowledge-base id. Writes no data. */
public class HybridSearchProbe {
    public static void main(String[] args) {
        Long org = Long.valueOf(args[0]), space = Long.valueOf(args[1]), kb = Long.valueOf(args[2]);
        var env = System.getenv();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:postgresql://" + env.getOrDefault("PILOT_DB_HOST", "db") + ":5432/" + env.get("POSTGRES_DB"),
                env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD")));
        Long user = jdbc.queryForObject("select id from app_user where email=?", Long.class,
                env.get("KEPLURA_ADMIN_BOOTSTRAP_EMAIL"));
        String irrelevantVector = jdbc.queryForObject("""
                select embedding from kb_chunk where knowledge_base_id=? and section_title='Urlaub'
                order by id limit 1
                """, String.class, kb);
        float[] vector = EmbeddingCodec.decode(irrelevantVector);
        var store = new PgVectorEmbeddingStore(jdbc);
        var dense = store.search(vector, kb, space, org, user, 1);
        var hybrid = store.search("Rauchwarnschwellen", vector, kb, space, org, user, 1);
        require(!dense.isEmpty() && "Urlaub".equals(dense.getFirst().sectionTitle()), "dense fixture");
        require(!hybrid.isEmpty() && "Leitwarte".equals(hybrid.getFirst().sectionTitle()), "hybrid retrieval");
        require(Boolean.TRUE.equals(jdbc.queryForObject("""
                select to_tsvector('german', 'Rauchwarnschwelle')
                    @@ websearch_to_tsquery('german', 'Rauchwarnschwellen')
                """, Boolean.class)), "German word forms");
        require(store.search("Rauchwarnschwellen", vector, kb, space, org, -1L, 20).isEmpty(), "membership pin");
        require(store.search("Rauchwarnschwellen", vector, kb, space, -1L, user, 20).isEmpty(), "organisation pin");
        require(store.search("Rauchwarnschwellen", vector, kb, -1L, org, user, 20).isEmpty(), "space pin");
        require(store.search("Rauchwarnschwellen", vector, -1L, space, org, user, 20).isEmpty(), "knowledge-base pin");
        System.out.println("{\"hybridBeatsMisleadingVector\":true,\"germanWordForms\":true,\"allTenantPins\":true}");
    }
    private static void require(boolean condition, String check) {
        if (!condition) throw new AssertionError("Hybrid search probe failed: " + check);
    }
}
