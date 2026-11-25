-- TU-05c: tools of the sealed question catalog made visible by tool_search.
-- NULL = the question is not in search mode (catalog of at most 12 tools, all visible).
ALTER TABLE tool_question ADD COLUMN visible_tools_json text;
