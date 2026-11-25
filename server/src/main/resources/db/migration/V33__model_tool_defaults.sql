-- TU-05b: per chat model tool defaults. AUTO keeps the previous behaviour; OFF never offers tools.
-- tool_defaults_json NULL = every approved tool kind, otherwise a JSON array of tool kinds.
-- No CHECK constraint on purpose: the admin service validates, and the tool service treats any value
-- other than AUTO (or unreadable defaults) as OFF — fail closed on every database.
ALTER TABLE ai_model ADD COLUMN tool_use character varying(10) NOT NULL DEFAULT 'AUTO';
ALTER TABLE ai_model ADD COLUMN tool_defaults_json text;
