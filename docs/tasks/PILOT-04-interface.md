# PILOT-04 — Oberfläche für den Hand-Upload-Pilot fertigstellen

Branch `codex/pilot-interface` ab main; Antwortqualität und Vorschau als
Voraussetzungen integriert, ohne Merge nach main.

Umfang: Team-, Administration- und Login-Seite im bestehenden ruhigen
Listenstil; verständliche deutsche Begriffe und Statusmeldungen; Wissensbasis
direkt in der Chat-Eingabe wählen; mobile Sidebar über Menü einblenden.
Bestehende Rollenprüfungen, Formularaktionen und Testids bleiben erhalten.

Ein Wechsel der Wissensbasis darf einen bestehenden Chat nicht rückwirkend
verändern. Bei geöffnetem Chat beginnt er eine neue Unterhaltung und bewahrt
einen bereits getippten Entwurf. Ohne geöffneten Chat gilt die Auswahl für die
nächste Frage. Während einer Antwort ist der Wechsel gesperrt.

Prüfung: Typecheck/Build, laufende App, Browserflüsse mit echten Daten,
Kontextwechsel mit Entwurf, Team/Admin-Formulare, mobile Menübedienung und
Escape, Darstellung ohne horizontales Überlaufen bei 390 px, Screenshots.
Backend aus PILOT-03 unverändert; dessen 412 erfolgreiche Server-Tests bleiben
der Backend-Nachweis. Merge-Gates aus AGENTS.md sind weiterhin offen.


## Stand 14.11.2026 (Claude, Cloud) — Code fertig, Abnahme durch Codex lokal

Umgesetzt: Team-Seite im Listenstil (Organisationen mit „Auswählen“, Umbenennen, Löschen;
Mitglieder mit Avatar/Rolle/Entfernen; offene Einladungen mit Code-Anzeige und „Zurückziehen“;
Code einlösen), alle sichtbaren Texte Deutsch, bestehende testids erhalten (neu: `org-select-<id>`).
Composer-Auswahl, mobile Sidebar, Login und Admin stammen aus dem WIP-Commit e5d8c4e.

Geprüft in der Cloud: nur `vue-tsc --noEmit` (grün). **Nicht** geprüft: Build, laufende App, Browser.

Abnahme-Checkliste für Codex (lokal, Image `keplura-server:pilot-interface`):
1. Team: Org anlegen/auswählen/umbenennen/löschen, Einladung erstellen → Code kopieren →
   zweites Konto löst ein → Mitglied erscheint → entfernen; Zurückziehen; leere Zustände.
2. Chat-Eingabe: erste Frage ohne offenen Chat; „Ohne Wissensbasis“; Wechsel zwischen zwei
   Wissensbasen bei offenem Chat MIT Entwurf (neue Unterhaltung, Entwurf bleibt); Wechsel während
   laufender Antwort gesperrt; Fehlerpfade.
3. 390 px: Menü öffnen/schließen, Escape, Fokus, Org/Arbeitsbereich wechseln, Portal-Menüs und
   Dialoge über der Sidebar, kein horizontales Überlaufen auf Login/Chat/Wissen/Team/Admin.
4. e2e-Browser-Suiten (invite, knowledge, chat, dark, admin, oidc, mvp5) erneut laufen lassen.
5. Screenshots unter ci/evidence/PILOT-04-<datum>/ ablegen.
