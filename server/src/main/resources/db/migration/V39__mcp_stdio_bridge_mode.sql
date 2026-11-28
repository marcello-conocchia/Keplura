-- The bridge protocol is selected only by an administrator.
ALTER TABLE mcp_server ADD COLUMN bridge boolean DEFAULT false NOT NULL;
