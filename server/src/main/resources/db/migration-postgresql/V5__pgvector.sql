-- M3d (part 2, PostgreSQL-only): native pgvector KNN store.
--
-- Lives in db/migration-postgresql (NOT the portable db/migration chain) —
-- Boot resolves spring.flyway.locations ...{vendor} to this dir only for a
-- real PostgreSQL JDBC url, so H2 (dev file db, prod smoke fallback, the
-- Flyway replay test) never parses pgvector syntax. This keeps the task's
-- dual requirement honest: real vector search on prod PG, in-JVM cosine on
-- H2, ONE source of truth (kb_chunk.embedding, V4) that the vector table
-- mirrors.
--
-- CREATE EXTENSION needs the server package to ship pgvector (the prod image
-- is pgvector/pgvector:pg16 — see deploy/docker-compose.yml). On PostgreSQL,
-- DDL is transactional: if either statement fails, NOTHING lands — the
-- store's startup capability probe (extension present + table present +
-- dimension match) then keeps the app on the in-JVM fallback instead of
-- half-wired native search.

CREATE EXTENSION IF NOT EXISTS vector;

-- dimension MUST equal intracore.search.embedding-dim (default 64): the
-- startup probe compares the column's vector dimension against the
-- configured embedding dimension and refuses the pgvector path on drift, so
-- a wrong-dimension model can never write garbage vectors. Changing the
-- model dimension = a NEW migration (V1.. stay frozen, ADR-0006 discipline).
CREATE TABLE kb_chunk_embedding (
    document_id bigint NOT NULL,
    chunk_idx integer NOT NULL,
    embedding vector(64) NOT NULL,
    CONSTRAINT kb_chunk_embedding_pkey PRIMARY KEY (document_id, chunk_idx)
);
