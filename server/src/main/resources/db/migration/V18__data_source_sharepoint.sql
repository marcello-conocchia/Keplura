-- DS-4 (ADR-0012, PO 14.11.2026): SharePoint Online als Datenquelle (App-Registrierung
-- mit Sites.Selected). Portable SQL (PostgreSQL + H2).
ALTER TABLE data_source DROP CONSTRAINT data_source_kind_check;
ALTER TABLE data_source ADD CONSTRAINT data_source_kind_check CHECK (kind IN ('LOCAL', 'SMB', 'SHAREPOINT'));
ALTER TABLE data_source ADD COLUMN tenant_id character varying(100);
ALTER TABLE data_source ADD COLUMN client_id character varying(100);
