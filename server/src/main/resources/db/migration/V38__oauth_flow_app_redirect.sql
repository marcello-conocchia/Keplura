-- Bind the callback handoff to the app selected when the OAuth flow started.
ALTER TABLE tool_oauth_flow ADD COLUMN app_redirect_uri character varying(600) DEFAULT '/' NOT NULL;
