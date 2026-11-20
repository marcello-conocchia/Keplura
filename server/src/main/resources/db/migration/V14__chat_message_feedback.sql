-- PILOT 4e: Daumen hoch/runter unter Antworten. Unterhaltungen sind
-- persönlich (eine Person je Chat), daher genügt die Bewertung an der
-- Antwortzeile selbst. NULL = keine Bewertung. Portable SQL (PostgreSQL + H2).
ALTER TABLE chat_message
    ADD COLUMN feedback character varying(8);

ALTER TABLE chat_message
    ADD COLUMN feedback_at timestamp with time zone;

ALTER TABLE chat_message
    ADD CONSTRAINT chat_message_feedback_check
    CHECK (feedback IS NULL OR feedback IN ('up', 'down'));
