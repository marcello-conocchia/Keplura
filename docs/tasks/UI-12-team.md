# UI-12 — Team im Katalogstil

PO-Auftrag 19.11.2026 („Ja, Team als Nächstes“), Folgepaket zu UI-11. Branch `feat/ui-team` von `main@a9ace2c`. Inventar T01–T10 in `docs/design/UI-INVENTORY-2026-11-15.md`.

## Ziel

Die Team-Seite sieht aus und verhält sich wie Administration/Verbindungen: klar getrennte Abschnitte „Deine Organisationen“, „Mitglieder & Einladungen“ und „Einladungscode einlösen“, ruhige Zeilen, seltene Aktionen im „…“-Menü, echte Lade-/Fehlerzustände.

## Scope

- `TeamView.vue`: Organisationen als Katalogliste; „Auswählen“ sichtbar, Umbenennen/Löschen im Menü (nur Verwalter:innen); Anlegen/Umbenennen im Dialog mit Dirty-/Busy-Schutz.
- `MembersPanel.vue`: Mitgliederliste, offene Einladungen mit Restlaufzeit als Status („Noch 4 h“ als Warnung unter 24 h), Entfernen im Menü, Hinzufügen im Dialog mit Dirty-/Busy-Schutz.
- Einmal-Anzeigen (Startpasswort, Einladungscode) als markierte Box; misslungenes Kopieren wird sichtbar gemeldet, der Wert bleibt markierbar.
- Ladefehler mit „Erneut versuchen“ statt dauerhaftem „wird geladen“; Hinweise zum Einlösen erscheinen am Einlöseformular.
- Mitglieder (keine Verwalter:innen) sehen einen erklärenden Hinweis statt leerer Fläche.

## Non-Goals

Keine Server-, API-, Rechte- oder Abhängigkeitsänderung. Keine neuen Rollen, kein Rollenwechsel, kein Einladungs-Ablauf ändern. Zugang/Onboarding ist ein eigenes Folgepaket.

## Unverändert bzw. verschärft (sicherheitsrelevant)

- Startpasswort und Einladungscode werden nur im Speicher der Komponente bzw. im bestehenden Store-Feld gehalten und nie erneut geladen; der Server kennt nur den Hash.
- NEU: Beim Wechsel der Organisation verschwinden angezeigte Startzugangsdaten (nie Zugangsdaten einer anderen Organisation zeigen).
- NEU: Auch ein angezeigter Einladungscode wird beim Org-Wechsel verworfen, im Store (`auth-store.ts`, `selectOrg` und automatische Neuauswahl) — so auch bei einem Wechsel außerhalb der Team-Seite. Die Einladung selbst bleibt bestehen und ist in der Liste zurückziehbar.
- Startzugang, dessen Antwort erst nach einem Org-Wechsel eintrifft, nennt die Organisation, für die das Konto angelegt wurde (Passwort geht nicht verloren).
- NEU: `expired` (401) beim Umbenennen/Löschen/Entfernen/Hinzufügen beendet die Sitzung wie in den übrigen Bereichen.
- Mitglieder-/Einladungsflächen erscheinen nur für Verwalter:innen; Rechte entscheidet weiter allein der Server.

## Acceptance Criteria

| ID | Abnahme |
|---|---|
| T-A1 | Drei Abschnitte im Katalogstil, Verwalter:in- und Mitglied-Ansicht unterscheidbar |
| T-A2 | Alle Funktionen erhalten: Anlegen, Auswählen, Umbenennen, Löschen, Hinzufügen, Entfernen, Einladen, Zurückziehen, Einlösen |
| T-A3 | Einmal-Anzeigen markiert, Kopierfehler sichtbar, nach Org-Wechsel weg |
| T-A4 | Laden/Leer/Fehler getrennt, Fehler mit „Erneut versuchen“ |
| T-A5 | Alle `org-*`/`invite-*`/`member-*`-Test-IDs der E2E-Suite bleiben erhalten |
| T-A6 | Typecheck/Build grün; Screenshots für den PO; ci-local einmal am Ende |

## Klasse

A — zeigt Rollen und Mitgliedschaften und gibt einmalige Zugangsdaten (Startpasswort, Einladungscode) aus. Reine Oberflächenänderung, aber nach „im Zweifel A“: unabhängiger Reviewer + Security vor Merge.
