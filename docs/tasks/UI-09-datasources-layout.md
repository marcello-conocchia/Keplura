# Datenquellen – Übersicht nach Designkatalog

PO-Auftrag 15.11.2026: nach gemeinsamer Browserbesichtigung die Datenquellenübersicht übersichtlich gestalten. Aktiv auf `codex/ui-datasources`, Reviewbase `codex/ui-knowledge@60eff80`, bestehender UI-Worktree. Gestapelter Featurebranch; kein Eingriff in main.

## Ziel / Scope

Quelle, Betriebsmodus und Ergebnis des letzten Abgleichs klar unterscheiden. Eine pausierte Quelle darf nicht als laufend/aktuell erscheinen, nur weil der letzte Abruf erfolgreich war. Ruhige Übersicht mit eindeutigen Spalten, kompakten Zeilenaktionen, Filter und erreichbarem Einrichtungsassistenten. Alle Werte und Basiskomponenten aus System 1.0. Lade-, Leer-, Fehler- und Dialogzustände einschließen.

Nicht-Ziele: neue Connectoren, Backend-/Rechteänderungen, allgemeiner Umbau sämtlicher Adminseiten, Frameworkwechsel, Datenbereinigung, Merge/Release/Deployment. Keine echten Abgleiche oder GPU-Modelle nur für Layouttests starten. Der bestehende Einrichtungsassistent und seine Dirty-/Busy-/Secret-Verträge bleiben erhalten.

## Abnahme und Prüfplan

| ID | Kriterium | Stand |
|---|---|---|
| D01 | Sichtbarer Titel, Quelle/Betrieb/letzter Abgleich/Aktionen klar strukturiert; Pfade umbrechen lokal | gezielt geprüft; 27/27 Browserpunkte, Grenzen im Beleg |
| D02 | Aktiv, pausiert, nicht aktiviert unabhängig vom letzten Ergebnis; Erfolg, Teilerfolg, Fehler und laufender Abgleich wahrheitsgemäß | gezielt geprüft; 27/27 Browserpunkte, Grenzen im Beleg |
| D03 | Suche und Betriebsfilter, Trefferzahl, leere Quelle vs. keine Treffer vs. Lade-/Serverfehler getrennt | gezielt geprüft; 27/27 Browserpunkte, Grenzen im Beleg |
| D04 | Abgleichen, Aktivieren/Pausieren, Einstellungen, Probleme und Entfernen erreichbar; laufende Anfragen nicht mehrfach auslösbar | gezielt geprüft; 27/27 Browserpunkte, Grenzen im Beleg |
| D05 | Problem-Dialog mit Loading/Fehler/Wiederholen; Entfernen mit sicherem Erstfokus, Busy und Fehlern im Dialog | gezielt geprüft; 27/27 Browserpunkte, Grenzen im Beleg |
| D06 | Bestehender Assistent erreichbar, Werte/Schritte/Dirty-Schutz, Dateirechte und Secret-Behandlung erhalten | gezielt geprüft; 27/27 Browserpunkte, Grenzen im Beleg |
| D07 | Hell/Dunkel, Desktop und Handy; Fokus/Tastatur, 44-px-Touchziele, Reflow, Katalogmaße und Screenshots | gezielt geprüft; 27/27 Browserpunkte, Grenzen im Beleg |
| D08 | Build, gezielte Regressionen, nachvollziehbare Ergebnisse, Projektstand, Commit/Push und Entwurfs-PR | gezielt geprüft; 27/27 Browserpunkte, Grenzen im Beleg |

Browsertests gegen den QA-Stack mit synthetischen Statuszeilen und ausdrücklich simulierten schreibenden Antworten; vorhandene Quellen unverändert erhalten. Endgültiges Entfernen, echte Abgleiche und echte Connector-Anmeldungen nur mit gesondert dokumentiertem Nachweis. Vollständige lokale CI und unabhängige Reviews bleiben vor Merge offen, während des Spielens keine schweren Gesamtläufe.

Zusätzliche PO-Rückmeldung: weniger Trennlinien im Assistenten. Wizardflächen angepasst, mobile Admin-Bereichsauswahl vereinheitlicht. Belege `docs/design/ui-datasources-2026-11-15/README.md`. Voll-CI und unabhängige Gates offen.
