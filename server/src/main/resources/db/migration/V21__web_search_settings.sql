-- Websuche-Einstellungen aus der Administration (CHAT-02). Eine Zeile (id = 1); fehlt sie,
-- gilt die Server-Konfiguration (INTRACORE_WEBSEARCH_*). api_key_enc: SecretCipher-Umschlag.
CREATE TABLE web_search_settings (
    id bigint NOT NULL,
    provider character varying(20) NOT NULL,
    url character varying(500),
    api_key_enc character varying(4000),
    results integer NOT NULL,
    blocked_domains character varying(2000),
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT web_search_settings_pkey PRIMARY KEY (id)
);
