-- TU-04c review: arguments that may carry values from internal tool results (attachments, knowledge,
-- internal MCP targets) are kept only while a card can still run. Additive; existing rows stay unmarked.
ALTER TABLE tool_call ADD COLUMN private_arguments boolean NOT NULL DEFAULT false;
