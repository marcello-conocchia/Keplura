-- PILOT 4e: Verwalter:innen legen Mitarbeiterkonten mit einmaligem
-- Startpasswort an; das Konto muss es beim ersten Login ersetzen.
-- Portable SQL (PostgreSQL + H2).
ALTER TABLE app_user
    ADD COLUMN must_change_password boolean DEFAULT FALSE NOT NULL;
