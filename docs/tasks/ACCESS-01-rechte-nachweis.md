# ACCESS-01 — Rechte-Nachweis („Warum sieht Anna diese Datei?“)

PO 16.11.2026 („ja mach weiter“) auf Basis der Produktstrategie: zweites Alleinstellungsmerkmal.
Branch `claude/access-explain` von `origin/main`. **Klasse A** (Admin-Sicht auf Dokumentrechte).

## Ziel

Admins, Datenschutz und Betriebsrat können für jede Person und jedes Dokument nachvollziehen, ob die Person
es über Suche/Chat findet und warum, und sehen, was eine Person insgesamt findet.

## Scope

- `GET /api/v1/admin/access/users?q=`, `/documents?q=` (Suche, je max. 20), `/explain?userId&documentId`,
  `/overview?userId` — nur Admin (Session + ADMIN-Rolle), nur lesend.
- **Urteil** ausschließlich aus `DocumentRepository.findByIdInKbForMember` (dieselbe Abfrage wie Suche/Liste/Vorschau:
  Mandanten-Pins + `AccessFilter`). Übersicht zählt mit `countAllInKbForMember` (identischer Filter).
- Begründung in Schritten: Mitgliedschaft → Herkunft (Hand-Upload / Datenquelle mit Pfad) → passende Erlaubnis →
  ausdrückliches Verbot (hat Vorrang) → unlesbare Rechte (fail-closed) → Ergebnis. Weicht die abgeleitete Begründung
  vom Urteil ab, wird das als Hinweis angezeigt (`consistent=false`), nie überdeckt.
- Rechte-Tabelle der Datei mit „betrifft die Person“, Gruppen der Person, Zeitpunkt des letzten Gruppenabgleichs;
  Namen über das Verzeichnis, sonst lesbare Ersatznamen.
- Oberfläche: Administration → Verbindungen → „Zugriff prüfen“.

## Non-Goals

- Keine Darstellung der Gruppen-Verschachtelungskette (gespeichert sind nur transitive Prinzipale).
- Kein „Simulieren“ einer Suche mit Text, keine Änderung von Rechten, kein Export.
- Kein Audit-Log der Admin-Abfragen (später, zusammen mit dem Betriebsratsmodus).

## Acceptance Criteria

1. Für Hand-Upload, Gruppenfreigabe, fremde Gruppe, ausdrückliches Verbot, unlesbare Rechte und Nicht-Mitglied
   stimmen Urteil und Begründung überein und nennen die richtige Ursache.
2. Übersicht zählt pro Wissensbasis exakt das, was die Person sieht.
3. Nicht-Admin 403, ohne Sitzung 401, unbekannte IDs 404, überlange Suche 400.

## Tests

`AccessExplainIntegrationTest` (4 Tests, echte Filterabfrage über HTTP), `ci-local.sh` auf dem finalen Commit.

## Prüfstand (ehrlich)

- `AccessExplainIntegrationTest` 4/4 (Hand-Upload, Gruppenfreigabe, fremde Gruppe, Verbot, unlesbare Rechte,
  Nicht-Mitglied; Übersicht; Suche; Admin-Schutz) — Begründung = Filter in allen Fällen.
- `ci-local.sh` als Nicht-root im frischen Container: Exit 0, 524 Server-Tests / 74 Klassen, 1 übersprungen
  (Echt-Tesseract), Rust-Helfer, Desktop-Build, `cargo check` grün. Evidence `ci/evidence/ACCESS-01-*/`.
- Browser (16.11.2026, isolierter Demo-Container, Linux-Ordner mit echten POSIX-Rechten, Rust-Leser,
  Gruppen von „Anna Berger“ per SQL gesetzt, da kein Verzeichnis in der Demo): „Projekt intern.md“ (604, Gruppe
  Vertrieb ohne Recht) → nicht sichtbar, „verweigert über GID 2001, Verbot hat Vorrang“; „Angebot Müller.md“ (640,
  Gruppe Vertrieb) → sichtbar, „erlaubt über GID 2001“; Übersicht 2 von 4.
- Nebenbefund (bestehend, nicht Teil dieses PRs): Umlaute in Dateinamen werden beim Übernehmen entfernt
  („Angebot Mller.md“), Ursache `DocumentUploadPolicy.sanitizeFilename`.
- OFFEN: Namensauflösung über echtes AD/LDAP, unabhängige Code-/Security-Prüfung (Klasse A).

