-- MVP-5: parser integration (pdf/docx via Apache Tika). One extra column on
-- kb_document: the persisted NO_TEXT_LAYER marker. V1–V6 stay untouched
-- (their Flyway checksums must never change; schema evolves by NEW
-- migrations only, ADR-0006).
--
-- Portability rules unchanged (PostgreSQL prod + H2 in PostgreSQL mode +
-- Hibernate ddl-auto=validate): plain ALTER, no vendor syntax.
--
-- index_status is NULL for every ordinary row — searchability stays DERIVED
-- (type policy + chunk presence, DocumentIndexingService.statusFor). The
-- only persisted value is NO_TEXT_LAYER: a fact knowable only at parse time
-- (a scanned PDF has no chunk rows to inspect; absence of rows is
-- indistinguishable from a historical not-yet-parsed row). The CHECK keeps
-- the column from ever claiming a status the derivation does not agree
-- with; PARSER_PENDING never enters the schema because the marker's whole
-- meaning — "we know this type is not parsed yet" — is retired by this task.

ALTER TABLE kb_document
    ADD COLUMN index_status character varying(20);

ALTER TABLE kb_document
    ADD CONSTRAINT kb_document_index_status_check
    CHECK (index_status IS NULL OR index_status = 'NO_TEXT_LAYER');
