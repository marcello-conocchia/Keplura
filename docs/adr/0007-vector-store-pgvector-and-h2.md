# ADR-0007: Dual-shape vector store — portable chunk rows, native pgvector when present, in-JVM cosine fallback

Date: 2026-09-05
Status: Accepted (M3d implementation decision, within PO-approved RAG scope)

## Context

M3d adds the retrieval path: uploaded documents are chunked, embedded and
searchable per knowledge base with source citations. The prod database is
PostgreSQL 16 + pgvector (ADR-0006 established the engine, the compose stack
and the Flyway-owned schema). But the build/test reality has not changed:

- the CI matrices (Linux/Windows/macOS) run **hermetic `mvn verify` against
  H2** — no database service, no network, no model;
- the prod profile keeps an H2 smoke fallback for bare-artifact runs;
- `ddl-auto=validate` means the Flyway schema and the JPA entities must agree
  on EVERY database the app boots against.

A `vector(N)` column is PostgreSQL-only syntax and type. Putting it in the
portable migration chain (or in the JPA entity) would break the H2 replay,
the prod smoke fallback and the H2-backed CI tests simultaneously.

## Decision

1. **Portable truth, native mirror.** V4 (`db/migration`, portable SQL, runs
   on PG and H2) creates `kb_chunk`: document/kb pins, `chunk_idx`, the chunk
   `text` (the citation payload) and `embedding` as the canonical
   comma-separated pgvector text literal in `[...]` brackets
   (`EmbeddingCodec`). This table alone is enough for correct search on any
   database. V5 (`db/migration-postgresql`, PostgreSQL-only) creates the
   extension and `kb_chunk_embedding(document_id, chunk_idx, embedding
   vector(64))` — a derived mirror keyed identically to the portable rows.
2. **Vendor-split Flyway locations.** The prod profile sets
   `spring.flyway.locations=classpath:db/migration,classpath:db/migration-{vendor}`;
   Boot substitutes `{vendor}` with the JDBC driver id
   (`postgresql` / `h2`), so PG-only SQL is invisible to every H2 path by
   construction, not by convention. `db/migration-h2/` exists as an empty
   placeholder so the fallback resolves cleanly.
3. **Store selection at startup with a capability probe.**
   `keplura.search.store=auto|memory|pgvector` (`SearchStoreConfiguration`):
   `auto` picks the native pgvector store only when the DataSource is real
   PostgreSQL AND the probe passes (extension installed, twin table present,
   column dimension equals `keplura.search.embedding-dim`); otherwise it
   logs the exact miss and uses `MemoryEmbeddingStore` — the same cosine
   math, executed in the JVM over the caller-visible candidate chunks.
   A deployment that pins `pgvector` fails the boot when the probe misses:
   retrieval semantics must never downgrade silently.
4. **Tenant gating inside both query shapes.** The memory path ranks only
   rows returned by `ChunkRepository.findCandidateChunks` — FOUR pins in one
   JPQL statement: kb pin, kb→space pin, space→org pin, membership EXISTS.
   The pgvector path carries the same four pins into its SQL `WHERE` so the
   ANN candidate set is filtered BEFORE ranking — never post-filtered in
   Java. Uniform 404 from `SearchService` applies on top (chain proven before
   any embedding work), but neither statement may rely on that caller-side
   check: the gate lives in the query, per the M3b/M3c layering rule. Because
   pgvector cannot execute in CI, `SearchStoreSelectionTest` asserts the SQL
   gate shape and its parameter count against the binding hermetically.
5. **Same scores on both paths.** The store returns
   `1 - (embedding <=> q)` on pgvector and `Similarity.cosine` on the memory
   path — equal to within float32 representation noise (M3d evidence run:
   identical ranking, Δscore < 2e-8 on the same data), which is what makes
   the H2 CI leg a faithful stand-in.
6. **Embeddings are pluggable; CI never calls a model.** The local backend
   is a deterministic SHA-256 feature-hash (`LocalHashEmbeddingService`,
   explicitly tagged `local-hash-*` in every API response so no consumer can
   mistake it for a model). Production switches to the OpenAI-compatible
   gateway (`/v1/embeddings`, base URL + key + model + dimension via ENV) —
   same `EmbeddingService` seam. The pgvector column dimension is validated
   against the configured embedding dimension by the same probe, so a
   dimension drift fails fast: changing the served dimension is a NEW
   PG-only migration against the twin table, never an ENV-only edit.

## Consequences

- **For scale class.** MVP search is exact KNN: no ANN index is created on
  `kb_chunk_embedding` yet (a plain heap scan over per-kb chunks is correct
  and honest at intra-company scale); adding an HNSW/IVFFlat index later is
  a new PG-only migration, additive by ADR-0006 rules.
- **Derived-data consistency.** The mirror is written inside the SAME
  indexing transaction via `JdbcTemplate` (same connection as JPA), so
  rollback semantics match; a hand-edited/corrupted portable table is a
  documented operational concern, not an access-control one.
- **The `embedding` VARCHAR(65536) column bounds the portable encoding**;
  at the default 64 dimensions and ~12 bytes per component it has >40×
  headroom, and the gateway client hard-rejects off-dimension vectors per
  call before storage.
- Testing keeps the ADR-0006 shape: hermetic H2 legs prove the portable
  chain (V1–V4) and the whole RAG wire contract; the pgvector leg runs
  against a real PG16 during evidence (boot + Flyway V5 + insert + KNN
  cross-check logged, see docs/development.md).
- Re-indexing an existing document is NOT implemented (MVP): a document's
  chunks are written once at upload. The (document_id, chunk_idx) unique key
  already guarantees idempotency for the future backfill task.
