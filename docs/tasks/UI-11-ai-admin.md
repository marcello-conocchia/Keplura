# UI-11 — KI-Verwaltung und Systemstatus im Katalogstil

PO-Auftrag 19.11.2026 („fang mit 3 an“ → „Mach was am besten ist“): die restlichen UI-Bereiche schrittweise in den Stil von Wissen/Datenquellen/Verbindungen bringen. Erstes Paket: die vier Bereiche, die noch direkt in `AdminPanel.vue` im alten Kartenstil stehen. Branch `feat/ui-ai-admin` von `main@ea45a79`.

## Ziel

KI-Anbieter, Modelle, Suche & Wissen und Systemstatus sehen aus und verhalten sich wie Verzeichnis/MCP: eigene Ansicht je Bereich, ruhige Liste statt Kartenstapel, Anlegen/Bearbeiten im Dialog, Zeilenaktionen im „…“-Menü, Laden/Leer/Fehler getrennt mit „Erneut versuchen“.

## Scope

- Neue Dateien `components/admin/ProvidersPanel.vue`, `ModelsPanel.vue`, `SearchSettingsPanel.vue`, `SystemStatusPanel.vue` nach dem Rezept von `DirectoryPanel.vue` (gemeinsame `connection-*`-Klassen aus `styles/administration.css`).
- `AdminPanel.vue` wird zum reinen Rahmen (Navigation + Auswahl der Ansicht).
- Anbieter: „Verbindung testen“ bleibt sichtbar an der Zeile, Bearbeiten/Aktivieren/Deaktivieren im Menü; Installationsvorgaben-Übernahme bleibt.
- Modelle: Gruppen „Antworten“ und „Dokumentsuche“, „Als Standard“ bleibt sichtbar, Bearbeiten/Aktivieren/Deaktivieren im Menü; Bestätigung beim Wechsel des Such-Standardmodells bleibt.
- Dialoge mit Dirty-/Busy-Schutz (AppDialog).

## Non-Goals

Keine Server-, API-, Rechte- oder Abhängigkeitsänderung. Keine neuen Funktionen. Team und Zugang/Onboarding sind eigene Folgepakete.

## Unverändert (sicherheitsrelevant)

- Zugangsschlüssel bleiben write-only: Feld startet leer, leeres Feld sendet `secret: null` (alter Wert bleibt), nur getippter Wert ersetzt; Feld wird beim Schließen geleert.
- SSRF-Ablehnung des Verbindungstests wird weiter als „Ziel blockiert“ gezeigt, ohne Detail.
- Alle Autorisierung bleibt serverseitig; 403 zeigt den bisherigen Hinweis `admin-forbidden`.

## Acceptance Criteria

| ID | Abnahme |
|---|---|
| K01 | Vier Ansichten im Katalogstil, Titel/Beschreibung/Aktualisieren wie Verzeichnis |
| K02 | Alle bisherigen Funktionen erhalten (Anlegen, Bearbeiten, Testen, Aktivieren, Standard, Übernahme, Anzeige) |
| K03 | Laden/Leer/Fehler getrennt, Fehler mit „Erneut versuchen“ |
| K04 | Schlüsselregel wie oben unverändert |
| K05 | Bestehende `admin-*`-Test-IDs der E2E-Suite bleiben erhalten |
| K06 | Typecheck/Build grün; ein Screenshot für den PO; ci-local einmal am Ende |

## Klasse

A — das Anbieter-Formular verarbeitet Zugangsschlüssel und Adressen externer Server (SSRF-Fläche). Reine Oberflächenänderung, aber nach „im Zweifel A“: unabhängiger Reviewer + Security vor Merge.

## Tests

Typecheck und Vite-Build; gezielte Browserprüfung gegen den Dev-Server mit simulierten Admin-Antworten, wo kein Server läuft. `scripts/ci-local.sh` einmal auf dem finalen Produktcommit.
