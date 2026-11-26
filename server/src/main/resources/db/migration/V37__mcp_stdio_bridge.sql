-- TU-06c: an MCP server entry that points to the isolated stdio bridge container. Such a server is an internal
-- target by default (the org owner can still switch it off per organization via mcp_org_boundary).
ALTER TABLE mcp_server ADD COLUMN bridge boolean DEFAULT false NOT NULL;
