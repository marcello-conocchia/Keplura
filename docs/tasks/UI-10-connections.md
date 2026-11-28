# Verbindungen – Websuche, MCP und Verzeichnis

Expliziter PO-Auftrag 15.11.2026: alle drei Ansichten im selben Stil wie Wissen/Datenquellen gestalten. Aktiver Branch `feat/ui-connections`, Reviewbase `feat/ui-datasources@875c83d`, bestehender UI-Worktree. Vorige Pakete sind gesichert; unabhängige Gates weiterhin offen.

## Ziel / Scope / Grenzen

Gemeinsames Seiten-/Form-/Listenrezept aus System 1.0, ruhige Flächen ohne Kartenstapel, klare Status- und Fehleranzeigen. Websuche in verständliche Einstellungsgruppen gliedern. MCP-Verbindungen und Werkzeug-/Bereichsfreigaben übersichtlich darstellen, Neuanlage in einem fokussierten Dialog. Verzeichnisliste und Anlegen/Bearbeiten/Nachschlagen vereinheitlichen. Alle bestehenden Anbieter, Optionen, Rechte und APIs erhalten; gespeicherte Schlüssel/Passwörter bleiben write-only.

Keine neuen Connectoren, Tools, Rechte, Backendfunktionen oder Abhängigkeiten. Kein echter Verbindungs-/Modelllauf und keine schreibenden Änderungen an vorhandenen Verbindungen für Layouttests. Kein Merge/Release/Deployment.

| ID | Abnahme | Stand |
|---|---|---|
| V01 | Einheitliche Titel, Flächen, Typografie, 20-px-Formabstände und 40/44-px-Controls | umgesetzt, gezielt geprüft; echte Browserkontrolle + Größenbilder |
| V02 | Websuche: alle Anbieter/Parameter, Schlüssel beibehalten/entfernen, gespeichert vs. ungespeichert und Testzustände verständlich | gezielt bestanden (simulierte Antworten); echte Anbieterprüfung offen |
| V03 | MCP: Anlage, Status, Aktivierung, Werkzeuge, Arbeitsbereiche, letzte Aufrufe und Entfernen erhalten | umgesetzt; UI-Abläufe simuliert geprüft, echte MCP-/Löschprüfung offen |
| V04 | Verzeichnis: AD/LDAP, Verbindungs-/Anmeldeoptionen, Test, Nachschlagen, Bearbeiten und Entfernen erhalten | umgesetzt; Passwort-Erhalt/Abfrage/Test simuliert geprüft, reale AD-/LDAP-Gesamtabnahme offen |
| V05 | Loading, Empty und Fehler getrennt; Busy/Retry und Dialogfokus zuverlässig, keine Secrets in Nachweisen | gezielt bestanden: Loading/Empty/Fehler/Retry, Busy/Dirty, Menüfokus; Secrets nicht ausgegeben |
| V06 | Hell/Dunkel und 390/768/1440, Tastatur und Reflow; tatsächliche Screenshots | gezielt bestanden: Hell/Dunkel 390/768/1440, Dialoge und Reflow; keine vollständige A11y-Abnahme |
| V07 | Build, gezielte Browserregressionen, Dokumentation, kleine Commits/Push und Entwurfs-PR | Build grün, 34/34 Verbindungspunkte + 17/17 Basisregression; Produktcommit d8cc94d gepusht, Evidenz dokumentiert; vollständige CI/Reviews offen |

Tests: echte QA-Leseansichten, ansonsten ausdrücklich simulierte HTTP-Antworten für Schreib-/Fehlerfälle. Bestehende Systeme unverändert erhalten. Vollständige lokale CI und unabhängige Reviews bleiben vor einem Merge erforderlich; schwere Läufe während des Spielens zurückgestellt.

Nachweise und genaue Grenzen: `docs/design/ui-connections-2026-11-15/README.md`. Die Aufgabe ist noch nicht durch sämtliche Merge-Gates abgenommen.
