-- ADMIN-KI SCOPE A (docs/adr/0008 §1/§9): the global user role.
-- V1–V8 stay untouched (Flyway checksums must never change; schema evolves by
-- NEW migrations only, ADR-0006).
--
-- app_user.role is the GLOBAL deployment role (USER | ADMIN) and is
-- complementary to the org-scoped membership.role (V1): tenant authorization
-- keeps reading the membership table fresh per request (ADR-0004), the admin
-- question is a user-table read fresh per request on /api/v1/admin/** only.
--
-- Portability rules unchanged (PostgreSQL prod + H2 in PostgreSQL mode +
-- Hibernate ddl-auto=validate): plain ALTER, CHECK constraint with no
-- NOT-NULL inference issues, DEFAULT keeps every existing row a USER.
-- Hibernate maps the column as a plain String so `ddl-auto=validate` (which
-- only checks presence/width/nullability, never CHECK bodies) stays green on
-- both the migrated PG schema and the dev H2 (ddl-auto=update) path.

ALTER TABLE app_user
    ADD COLUMN role character varying(20) NOT NULL DEFAULT 'USER';

ALTER TABLE app_user
    ADD CONSTRAINT app_user_role_check
    CHECK (role IN ('USER', 'ADMIN'));
