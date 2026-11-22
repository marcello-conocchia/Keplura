ALTER TABLE tool_call ADD COLUMN parent_call_id bigint;
ALTER TABLE tool_question ADD COLUMN followup_used boolean NOT NULL DEFAULT false;
