-- RISK-01 (Oversharing-Check): einzelne Dateien werden als exakte Muster an die
-- Ausschlussliste angehängt; 2000 Zeichen reichen dafür nicht. Portable Syntax
-- (PostgreSQL + H2). Nur Verbreiterung, keine Datenänderung.
ALTER TABLE data_source ALTER COLUMN exclude_patterns SET DATA TYPE character varying(20000);
