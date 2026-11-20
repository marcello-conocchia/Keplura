ALTER TABLE kb_chunk ADD COLUMN page_number INTEGER;
ALTER TABLE kb_chunk ADD COLUMN section_title VARCHAR(512);
ALTER TABLE kb_chunk ADD COLUMN start_offset INTEGER;
ALTER TABLE kb_chunk ADD COLUMN end_offset INTEGER;
ALTER TABLE kb_chunk ADD CONSTRAINT kb_chunk_location_check CHECK (
    (page_number IS NULL OR page_number > 0)
    AND (start_offset IS NULL OR start_offset >= 0)
    AND (end_offset IS NULL OR (start_offset IS NOT NULL AND end_offset > start_offset))
);
