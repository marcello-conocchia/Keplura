# ADR-0006: Flyway baseline schema for PostgreSQL; H2 stays dev/test

Date: 2026-09-04
Status: Accepted (M3a implementation decision, within PO-approved infrastructure scope)

## Context

M3 (Knowledge) is the first milestone whose data cannot live in a throwaway
database: the roadmap calls for PostgreSQL + pgvector. Until now the schema was
implicit — `ddl-auto=update` let Hibernate mutate whatever database it booted
against. That is fine for the dev H2 file and for the ephemeral H2 the tests
use, but unacceptable in prod: an accidental entity change would silently ALTER
the live schema, and there is no migration history to evolve pgvector
structures (M3b) from.

## Decision

1. **Flyway owns the prod schema.** `spring-boot-starter-flyway` +
   `flyway-database-postgresql` + the PostgreSQL JDBC driver join the server.
   The `prod` profile enables Flyway and sets Hibernate to
   `ddl-auto=validate`: drift between entities and schema fails the boot
   loudly instead of mutating data structures. The `dev` profile keeps
   `spring.flyway.enabled=false` and `ddl-auto=update` — developer databases
   and the 77+ H2 tests are untouched.
2. **V1__baseline.sql is the single source of truth.** Derived from the
   Hibernate-generated schema of the current entity set (User, Organization,
   Membership — verified against a real PostgreSQL 16 + pgvector instance),
   written in **portable SQL only** (identity columns, `timestamp(6) with time
   zone`, named constraints) so the very same file executes against
   PostgreSQL *and* H2's PostgreSQL mode. That hermetic replay is the CI leg;
   the real-Postgres boot is documented manual evidence per release.
   No Testcontainers dependency: the host's 3.8 GB RAM and CI simplicity rule
   it out, and H2-PG-mode replay + live-PG manual proof together cover parse,
   semantics and Hibernate-validate compatibility.
3. **No silent baselining.** `baseline-on-migrate=false`: a non-empty database
   without `flyway_schema_history` is unknown territory and must fail the boot,
   not get quietly adopted.
4. **pgvector is NOT in the baseline.** No `CREATE EXTENSION vector`, no
   embedding columns — that is M3b's migration (V2), authored when the
   Knowledge entities exist. The compose image is
   `pgvector/pgvector:pg16` (upstream-maintained) so the extension is merely
   *available*.
5. **Local infra via `deploy/docker-compose.yml`** with credentials only from
   a git-ignored `.env` (template: `.env.example`, fail-fast `:?` syntax — no
   committed defaults). The prod-profile DB target stays `KEPLURA_DB_URL`.

## Consequences

- Every schema change from now on is a numbered migration; entity edits
  without one fail prod boots at startup (this is the point).
- The prod H2 in-memory smoke fallback runs the same portable V1 — keeping
  `java -jar` smoke tests of the bare artifact possible without Postgres.
- CI stays H2-based (documented): the Flyway leg it runs is the PostgreSQL-mode
  replay plus the prod-config assertions.
