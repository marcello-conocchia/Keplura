# Wissen – Layout nach Browser-Rückmeldung

Beauftragt 15.11.2026: gedrängte Wissensansicht übersichtlich strukturieren und den neuen Designkatalog anwenden. Branch `codex/ui-knowledge`, gestapelt auf `codex/ui-chat-layout@df8e8ab`. Genau diese Oberfläche ist die aktive Implementierung.

## Scope

Wissensauswahl, Inhaltskopf, Upload, Suchfeld, Dokumentliste und deren Zustände. Desktop: ruhige vertikale Basisliste. Schmalere Fenster: eine kompakte Auswahl statt horizontaler Reiter. Klarer Dokumentbereich mit Titel, Anzahl, Suche und Tabelle; Aktionen dauerhaft erreichbar. Werte aus System 1.0, kein neuer visueller Stil. APIs, Rechte, Daten und bestehende Such-/Upload-/Verwaltungsfunktionen erhalten.

## Abnahme

| ID | Kriterium | Stand |
|---|---|---|
| K01 | Kein horizontaler Reiter-Scrollbereich; Basiswahl bei 390/768/938/1440 erreichbar | gezielt geprüft; siehe Belege |
| K02 | Sichtbarer Titel, Dokumentzahl, eindeutiger Upload, ausreichend breites Suchfeld | gezielt geprüft; siehe Belege |
| K03 | Klare Dokumentzeilen mit Dateiname, Größe/Datum je nach Platz und sichtbaren Aktionen | gezielt geprüft; siehe Belege |
| K04 | KB-Anlegen/Umbenennen/Löschen, Dokumentdownload/Reindex/Löschen weiterhin erreichbar; Abbrechen ohne Datenverlust | gezielt geprüft; siehe Belege |
| K05 | Filter, leere Liste, keine Treffer, Lade-/Fehlerzustände nachvollziehbar | gezielt geprüft; siehe Belege |
| K06 | Hell/Dunkel, mobile Ziele, Tastaturfokus, kein Root-Überlauf; tatsächliche Screenshots | gezielt geprüft; siehe Belege |
| K07 | Build und gezielte Regressionen; Grenzen, Commit und Projektstand dokumentiert | gezielt geprüft; siehe Belege |

Nicht-Ziele: Backendänderungen, Datenbereinigung, neue Importfunktionen, Ausführen von GPU-Modellen während des Spielens. Simulierte Suchantworten und synthetische Listen im Test werden ausdrücklich markiert. Voll-CI und unabhängige Reviews bleiben Merge-Gates; kein Merge/Deployment.

Belege: `docs/design/ui-knowledge-2026-11-15/README.md`. Erreichbarkeit/Abbrechen und echter Download geprüft; echter Upload, Reindex und endgültiges Löschen wurden in diesem Layoutlauf nicht ausgeführt. Vollständige Funktions-/native Abnahme bleibt offen.
