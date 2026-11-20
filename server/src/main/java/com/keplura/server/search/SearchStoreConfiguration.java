package com.keplura.server.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Chooses the {@link SearchStore} at startup (M3d, docs/adr/0007) from
 * {@code keplura.search.store} plus a live capability probe:
 *
 * <ul>
 *   <li>{@code auto} (default): if the DataSource is a real PostgreSQL AND
 *       the pgvector probe succeeds (extension installed, twin table
 *       present, dimension matches — an empty index is first retyped to the
 *       configured dimension by {@link PgVectorSchema}, ADR-0010), use native KNN; otherwise log the
 *       reason and fall back to {@link MemoryEmbeddingStore}. The H2 dev/CI
 *       path lands here every run.</li>
 *   <li>{@code pgvector}: same probe, but a miss FAILS THE BOOT — a
 *       deployment that pinned native search must never silently downgrade
 *       to a different retrieval backend.</li>
 *   <li>{@code memory}: pin the in-JVM path (used by tests and by anyone who
 *       wants byte-stable ranking without the extension).</li>
 * </ul>
 *
 * <p>Both paths return numerically identical scores for identical vectors
 * (cosine), so the fallback is a fidelity-preserving stand-in, not a
 * different product — proven against real PG16+pgvector in the M3d evidence
 * run (docs/development.md). The pure selection logic lives in
 * {@link #choose} and is unit-tested; the bean method wires the live
 * datasource probe into it.
 */
@Configuration
public class SearchStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SearchStoreConfiguration.class);

    /**
     * The ONE {@link SearchStore} injection point for the app: marked
     * {@code @Primary} because {@link MemoryEmbeddingStore} is also a
     * SearchStore-typed bean (it must stay injectable as the fallback below);
     * consumers get whichever backend {@link #choose} selected.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    SearchStore searchStore(DataSource dataSource,
                            JdbcTemplate jdbc,
                            MemoryEmbeddingStore memoryStore,
                            @Value("${keplura.search.store:auto}") String mode,
                            @Value("${keplura.search.embedding-dim:64}") int dim,
                            @Value("${keplura.search.auto-adapt-dimension:true}") boolean autoAdapt) {
        PgVectorSchema.requireValidDimension(dim);
        boolean postgres = isPostgres(dataSource);
        if (postgres) {
            // ADR-0010: an EMPTY index follows the configured model dimension
            // (fresh install with bge-m3 = 1024); a populated one is never touched.
            PgVectorSchema.reconcile(jdbc, dim, autoAdapt);
        }
        boolean nativeCapable = postgres && PgVectorEmbeddingStore.probe(jdbc, dim);
        return choose(mode, nativeCapable, memoryStore, () -> new PgVectorEmbeddingStore(jdbc), dim, log);
    }

    /** Pure, unit-testable selection (see class doc for the rules). */
    static SearchStore choose(String mode, boolean nativeCapable,
                              MemoryEmbeddingStore memoryStore,
                              Supplier<SearchStore> pgvectorFactory, int dim,
                              Logger log) {
        String normalized = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "memory" -> {
                log.info("search store: pinned 'memory' (in-JVM cosine over kb_chunk.embedding)");
                yield memoryStore;
            }
            case "pgvector" -> {
                if (!nativeCapable) {
                    throw new IllegalStateException(
                            "keplura.search.store=pgvector requires a PostgreSQL datasource "
                                    + "with the vector extension and kb_chunk_embedding at dimension "
                                    + dim + " — see startup probe log for the exact miss");
                }
                log.info("search store: pgvector (native KNN, dimension {})", dim);
                yield pgvectorFactory.get();
            }
            case "auto" -> {
                if (nativeCapable) {
                    log.info("search store: auto -> pgvector (native KNN, dimension {})", dim);
                    yield pgvectorFactory.get();
                }
                log.info("search store: auto -> memory (pgvector probe not satisfied)");
                yield memoryStore;
            }
            default -> throw new IllegalStateException(
                    "keplura.search.store must be one of auto|memory|pgvector, got: " + mode);
        };
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getURL().startsWith("jdbc:postgresql:");
        } catch (Exception e) {
            return false;
        }
    }
}
