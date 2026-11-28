# RISK-01 — Oversharing-Check vor der KI-Freigabe

PO-Freigabe 15.11.2026 („was sind die nächsten schritte du kannst dran arbeiten …“) auf Basis der
Produktstrategie: erstes Alleinstellungsmerkmal. Branch `feat/oversharing-check` von `origin/main`.

## Ziel

Bevor eine Datenquelle aktiviert wird, sieht der Admin, welche Dateien für praktisch alle Mitarbeitenden
lesbar sind und welche davon nach Ordner/Dateiname sensibel wirken, und kann sie mit einem Klick von der
Übernahme ausschließen. Hintergrund: Rechte-treue KI macht vorhandenes Oversharing sofort abfragbar; das ist
der belegte Hauptgrund für verschobene KI-Einführungen.

## Scope (umgesetzt)

- `ExposureRules`: „breite“ Prinzipale (everyone inkl. Jeder/Authentifizierte Benutzer, Domänen-Benutzer
  RID 513, Domänen-Gäste 514, lokale Benutzer/Gäste, Anonym, ganze M365-Organisation, Linux-Gruppe `users`)
  und fünf Kategorien sensibler Namen (Personal/Gehalt, Gesundheit, Zugangsdaten, Vertraulich, Bank/Steuern);
  bekannte Industrie-Fehltreffer (Prüfzeugnis, Risikobeurteilung, Fettgehalt, Steuerung) ausgenommen.
- `ExposureScanService`: Hintergrundprüfung je Quelle, liest NUR Dateiliste + Rechte über denselben Konnektor
  und dieselben Filter wie der Abgleich, schreibt nichts; Ergebnis im Speicher (letzter Bericht je Quelle).
  Verweigern hat Vorrang wie im `AccessFilter`; unlesbare Rechte zählen separat (beim Abgleich fail-closed).
- API (Admin, Session + CSRF + ADMIN): `POST/GET /api/v1/admin/datasources/{id}/exposure`,
  `POST …/{id}/exclude {paths}` — hängt exakt maskierte Muster an die Ausschlussliste (max. 200 Pfade je
  Aufruf, Traversal/absolute Pfade abgelehnt). Der nächste Abgleich übernimmt die Dateien nicht bzw.
  entfernt bereits übernommene Fassungen. Die Ablage wird nie verändert.
- Migration **V24**: `data_source.exclude_patterns` auf 20 000 Zeichen verbreitert.
- Oberfläche: Dialog „Oversharing-Check“ (Fortschritt, Kennzahlen, breite Gruppen, Ordnerliste,
  sensibel wirkende Dateien mit Auswahl, „Liste für Fileserver-Admin kopieren“), erreichbar im Assistenten
  (Schritt „Rechte“, vor dem Aktivieren) und im Zeilenmenü jeder Quelle („Oversharing prüfen“).

## Non-Goals (bewusst später)

- Keine Inhaltsprüfung (IBAN, Steuer-ID, Passwort-Zeilen, lokales Modell) — Stufe 2, baut auf OCR-01/Textextraktion auf.
- Keine Gruppengrößen-Auswertung beliebiger Gruppen (nur bekannte breite Prinzipale); keine persistente Historie.
- Keine Änderungen an der Ablage, kein Quarantäne-Modus für bereits übernommene Dateien.
- Erweiterte POSIX-/NFSv4-ACLs werden wie im Abgleich nicht ausgewertet.

## Acceptance Criteria

1. Prüfung liest nie Dateiinhalte (Test: `open()` wirft) und ändert keine Daten außer per „Ausschließen“.
2. Breite Lesbarkeit stimmt mit der Abgleichslogik überein (Deny-Vorrang, fail-closed, Elternordner unter Linux).
3. Ausgeschlossene Datei taucht in der nächsten Prüfung nicht mehr auf und wird beim Abgleich nicht übernommen.
4. Nicht-Admins: 403, ohne Session: 401, ohne CSRF: 403; unbekannte Quelle: 404.
5. Oberfläche erklärt, dass „sensibel“ ein Hinweis aus dem Namen ist, keine Inhaltsprüfung.

## Prüfstand (ehrlich)

- Unit: `ExposureRulesTest` 7, `ExposureScanServiceTest` 6 (davon 1 nur unter Linux mit echten POSIX-Rechten),
  Wire: `ExposureAdminIntegrationTest` 6, `FlywayBaselineMigrationTest` angepasst (23 Migrationen bis V24).
- Frontend: `vue-tsc` und `npm run build` grün.
- `ci-local.sh` (Standardstufen, ohne E2E) auf dem Dateibaum von `5929497` in frischem Container:
  Exit 0, 468 Server-Tests / 69 Klassen, 0 übersprungen (inkl. Linux-POSIX-Test), Desktop-Build und
  `cargo check` grün. Evidence `ci/evidence/RISK-01-20261115-193029/`.
- Browser (15.11.2026, isolierter Demo-Container, Linux-Ordner mit 11 Testdateien und echten POSIX-Rechten,
  Web-Bundle same-origin): Check aus dem Zeilenmenü → 11 geprüft, 8 breit lesbar, 3 sensibel wirkend
  (Gehaltsliste, Passwörter, Bankverbindungen); 640/600-Dateien und Dateien im 700-Ordner korrekt NICHT breit;
  „Materialprüfzeugnis“ korrekt nicht sensibel. Alle drei ausgeschlossen → erneute Prüfung: 8 geprüft,
  5 breit, 0 sensibel.
- OFFEN: echter SMB/Samba-AD-Lauf in der QA-Umgebung, Abgleich nach Ausschluss (Entfernen bereits
  übernommener Fassungen) im Browser, Assistenten-Einstieg im Browser, E2E-Suiten, unabhängige
  Reviewer-/Security-/QA-Gates.
