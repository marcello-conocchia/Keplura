-- NULL keeps the default catalog. An explicit empty JSON array disables tools for new questions.
ALTER TABLE chat_conversation ADD COLUMN tool_selection_json text;
ALTER TABLE chat_conversation ADD COLUMN tool_selection_cutoff bigint NOT NULL DEFAULT 0;
