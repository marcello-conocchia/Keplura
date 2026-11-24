-- TU-04d: web search as a tool, own org switch with its own revision (toggling it never changes the knowledge tools).
ALTER TABLE org_tool_setting ADD COLUMN web_search_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE org_tool_setting ADD COLUMN web_revision bigint NOT NULL DEFAULT 0;
