# PILOT-05 — Schritt 4e: Erst-Admin-Onboarding, Konten, Datensicherung, Bewertung

Branch `feat/pilot-admin-ops` von `main@43302d3`; übernimmt `feat/pilot-interface` (4d,
inkl. 4b/4c) per Merge-Commit als Voraussetzung. Kein Merge nach main.
Die Installer-Änderungen aus `feat/pilot-installation` (4a) sind weiterhin NICHT enthalten.

## Umgesetzt (14.11.2026)

| Funktion | Server | Oberfläche |
|---|---|---|
| Daumen hoch/runter | V14 `chat_message.feedback`; `PUT /api/v1/orgs/{orgId}/chat/conversations/{cid}/messages/{mid}/feedback {rating}` nur eigene Unterhaltung, nur Antworten | zwei Daumen unter vollständigen Antworten, zweiter Klick nimmt zurück |
| Mitglied direkt hinzufügen | `POST /api/v1/orgs/{id}/members {email, displayName}` nur OWNER: bestehendes Konto → Mitglied (200), sonst neues Konto mit Startpasswort (201, no-store, einmalig) | Team → „Mitglied hinzufügen“, Zugangsdaten einmalig anzeigen/kopieren |
| Pflicht-Passwortwechsel | V15 `app_user.must_change_password`; Login/`/me` melden es; Session-Merker sperrt alle schreibenden Anfragen (403); `POST /api/v1/auth/password` mit eigener CSRF-Prüfung und Rate-Limiter | Ansicht „Eigenes Passwort festlegen“ statt der App |
| Erste Schritte | — | statt leerem Chat, solange Organisation/Arbeitsbereich/Wissensbasis fehlen; danach weiter zu „Wissen“ |
| Datensicherung | `backup.sh` schreibt Statusdatei nach verifizierter Sicherung; `GET /api/v1/admin/backup` liest sie | Administration → „Datensicherung“: Status, Anleitung, Cron, Wiederherstellen |

Geprüft in der Cloud: `mvn compile`, `mvn test-compile`, `vue-tsc --noEmit`, `bash -n scripts/backup.sh`.
**Nicht** geprüft: Server-Tests, Build, laufende App, Browser, echtes Backup.

## Abnahme-Checkliste (lokal)

1. Server-Tests komplett (`FlywayBaselineMigrationTest` kennt V14/V15). Neue Tests ergänzen:
   Bewertung (fremder Chat → 404, Benutzer-Nachricht → 404, ungültiger Wert → 400, Zurücknehmen),
   Mitglied hinzufügen (MEMBER → 403, bestehendes Konto unverändert, doppelt → 409, neues Konto
   kann sich mit Startpasswort anmelden), Passwortwechsel (ohne CSRF → 403, falsches Startpasswort
   → 400 + Rate-Limit, schreibende Anfragen vor dem Wechsel → 403, danach erlaubt), Backup-Status
   (keine Datei, kaputte Datei, zu große Datei, überfällig).
2. Browser: Erste Schritte mit frischem Konto bis „Wissen“; Mitglied anlegen → zweites Browserprofil
   meldet sich mit Startpasswort an → Pflichtwechsel → normale App; Daumen setzen/zurücknehmen,
   nach Neuladen erhalten; Administration → Datensicherung vor und nach `scripts/backup.sh`.
3. e2e: Start ohne Organisation zeigt jetzt `onboarding-view` statt `chat-view`; Suiten ggf. anpassen.
4. Echtes `scripts/backup.sh` gegen die Pilotinstanz (Volumes nicht löschen!), Status in der UI prüfen.
5. Screenshots unter `ci/evidence/PILOT-05-<datum>/`.

## Für das Security-Gate

- Verwalter:innen erkennen, ob eine E-Mail bereits ein Konto hat (bewusst akzeptiert, Firmeninstallation).
- Startpasswort ~93 Bit, nur als Argon2-Hash gespeichert, nie geloggt; Antwort `no-store`.
- Startpasswort-Konten dürfen lesen (Session-Restore), aber nichts schreiben, bis das Passwort gewechselt ist.
- Neue Abhängigkeiten: keine.
