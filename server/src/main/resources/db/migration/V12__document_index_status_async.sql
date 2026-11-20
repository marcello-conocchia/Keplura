-- Asynchronous indexing + re-index (Roadmap Punkt 3): two more persisted
-- states on kb_document.index_status — PENDING (stored, queued for the
-- background indexer) and FAILED (indexer gave up; re-index possible).
-- Portable SQL (PostgreSQL + H2).
ALTER TABLE kb_document
    DROP CONSTRAINT kb_document_index_status_check;

ALTER TABLE kb_document
    ADD CONSTRAINT kb_document_index_status_check
    CHECK (index_status IS NULL OR index_status IN ('NO_TEXT_LAYER', 'PENDING', 'FAILED'));
