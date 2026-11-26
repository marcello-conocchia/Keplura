# TU-05b–d — Modellvorgaben, Tool-Suche und strukturierte Rückfragen

Status: **abgeschlossen mit PR #37 (26.11.2026).** Codex: APPROVED (Code + Security) auf 94ebf13 nach einer Prüfrunde mit zwei behobenen Major-Befunden. Restumfang von TU-05 nach TU-05a (PR #36, Merge 3019e81), gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). PO-Entscheidung vom 25.11.2026 im Chat: Option 1, alle drei Teile in **einem PR mit getrennten Commits**, ein CI-Lauf und eine Review-Runde am Ende. Claude implementiert, Codex prüft unabhängig (Code + Security).

Klasse A: Admin-Einstellung am Modell, autorisierter Werkzeugkatalog, Unterbrechung und Fortsetzung der Werkzeugschleife.

## Ziel

- **TU-05b Modellvorgaben:** Der Admin legt je Chatmodell fest, ob es überhaupt Werkzeuge bekommt und welche Werkzeugarten standardmäßig angeboten werden.
- **TU-05c Tool-Suche:** Bei großen Katalogen bekommt das Modell nicht alle Werkzeuge auf einmal, sondern sucht die passenden selbst.
- **TU-05d Strukturierte Rückfragen:** Das Modell kann der Person während einer Frage eine Rückfrage mit Antwortoptionen stellen. Nach der Antwort läuft der Ablauf weiter.

Keine Stufe erweitert Rechte. Reihenfolge der Einschränkungen: Serverfreigabe → Modellvorgabe → Chat-Auswahl (TU-05a) → Bedingungen je Frage (z. B. „Im Web suchen“).

## Scope

### TU-05b Modellvorgaben

- `ai_model` erhält zwei Felder (Migration, nur neue Spalten mit Standardwerten):
  - `tool_use`: `AUTO` (Standard, bisheriges Verhalten) oder `OFF`. `OFF` bietet für dieses Modell **nie** Werkzeuge an, auch nicht durch eine Chat-Auswahl.
  - `tool_defaults_json`: `NULL` = alle freigegebenen Werkzeugarten; sonst eine Liste von Werkzeugarten: `knowledge` (Wissenssuche und Dokumentstellen), `attachments` (Chat-Anhänge), `web` (Websuche und Webseitenabruf), `mcp` (MCP-Werkzeuge), `ask` (Rückfragen).
- Maßgeblich ist das aktive Standard-Chatmodell, also dasselbe Modell, das die Antwort erzeugt. Ohne DB-Modell (ENV-Konfiguration) gilt `AUTO` ohne Einschränkung.
- Ohne eigene Chat-Auswahl gilt die Modellvorgabe. Eine eigene Chat-Auswahl (TU-05a) ersetzt die Vorgabe der Werkzeugarten, nie aber `OFF` und nie die Serverfreigaben.
- Jede Ausführung prüft `OFF` und die Vorgabe erneut.
- Admin-API und Admin-Oberfläche: beide Felder lesen und ändern. Das Werkzeugmenü im Chat zeigt, wenn das Modell Werkzeuge ausschaltet, und welche Werkzeuge die Vorgabe vorauswählt.

### TU-05c Tool-Suche bei großen Katalogen

- Umfasst der zulässige Katalog einer Frage mehr als **12** Werkzeuge, bekommt das Modell zunächst nur das Werkzeug `tool_search(query)` (und, falls zulässig, `ask_user`).
- `tool_search` durchsucht **ausschließlich** den bereits zulässigen Katalog dieser Frage (Namen, Quelle, Beschreibung) und schaltet höchstens **8** Treffer je Suche für die weiteren Runden dieser Frage frei, insgesamt höchstens 32 gleichzeitig.
- Der Fragekatalog wird zu Beginn wie bisher versiegelt. Die Suche kann ihn nicht erweitern, nur Teile davon sichtbar machen. Freigeschaltete Werkzeuge werden mit der Frage gespeichert und gelten auch in Fortsetzungen.
- Ein Suchaufruf zählt wie ein Werkzeugaufruf zum Limit von acht Aufrufen je Frage. Er greift auf keine Daten zu und erzeugt deshalb keine Aufrufkarte.
- Kataloge bis 12 Werkzeuge verhalten sich unverändert. Die bisherige stille Kürzung auf 32 Werkzeuge entfällt bei großen Katalogen.

### TU-05d Strukturierte Rückfragen

- Eingebautes Werkzeug `ask_user(question, options, allowFreeText)`: Frage ≤ 500 Zeichen, 0–6 Optionen mit je ≤ 100 Zeichen, freie Antwort optional. Risikoklasse READ, intern, verlässt den Server nicht.
- Ein Aufruf legt eine offene Rückfragekarte an und unterbricht die Frage wie eine offene Bestätigung. Höchstens eine Rückfrage je Frage, nicht in Fortsetzungen, nicht zusammen mit einer anderen offenen Bestätigung.
- Die Person antwortet in der Karte mit einer Option oder freiem Text (≤ 1 000 Zeichen). Der Server prüft die Antwort gegen die gespeicherten Optionen. Ablehnen beendet die Rückfrage ohne Antwort.
- Nach der Antwort läuft die Frage über den bestehenden einmaligen Fortsetzungsweg weiter. Die Antwort zählt wie der Fragetext als Herkunft der Person.
- In der Fortsetzung sind Lesewerkzeuge erlaubt und **höchstens eine** Schreib- oder kritische Folgeaktion. Diese wird nur als Karte vorgelegt und braucht eine eigene konkrete Bestätigung, wie die Folgeaktion nach einer kritischen Bestätigung.
- Die Rückfrage ist eine Werkzeugart (`ask`). Chat-Auswahl und Modellvorgabe können sie abschalten.

## Umsetzungshinweise

- V33 hat bewusst keine CHECK-Regel für `tool_use`: H2 kann die vorhandenen IN-Listen-CHECKs auf dem migrierten Schema nicht auswerten. Die Admin-API prüft die Werte, und der Werkzeugdienst behandelt jeden Wert außer `AUTO` sowie unlesbare Vorgaben als `OFF`.
- `ask_user` wird nur angeboten, wenn mindestens ein anderes Werkzeug zulässig ist. Allein macht es aus einem normalen Chat keinen Werkzeug-Turn und erscheint auch nicht im Werkzeugmenü.
- Rückfragekarten haben keinen Audit-Eintrag, weil nichts ausgeführt wird und keine Daten den Server verlassen. Die Antwort steht als Ergebnis auf der Karte.
- Die Tool-Suche erzeugt keine Aufrufkarte. Sie zählt aber zum Limit von acht Aufrufen.

## Non-Goals

- keine Auswahl einzelner Werkzeuge am Modell (nur Werkzeugarten), keine Modellauswahl im Chat;
- keine semantische oder Embedding-Suche über Werkzeuge, nur Textabgleich;
- keine mehrstufigen Formulare, Dateiantworten oder mehrere Rückfragen in einer Frage;
- keine Änderung an Risikoklassen, Bestätigungs-, Herkunfts- oder Budgetregeln außer den oben genannten Folgeaktionen nach einer Rückfrage;
- keine neuen externen Dienste oder Laufzeitabhängigkeiten, kein Release/Deployment.

## Acceptance Criteria

1. **Modellvorgaben:** `OFF` bietet keine Werkzeuge an und blockiert auch bereits geplante Aufrufe (keine Verbindung). Eine Vorgabe von Werkzeugarten bietet ohne Chat-Auswahl genau diese Arten an. Eine Chat-Auswahl gilt stattdessen, kann aber `OFF` nicht aufheben. Ungültige Werte lehnt die Admin-API mit 400 ab. Nur Admins können ändern.
2. **Tool-Suche:** Bei mehr als 12 zulässigen Werkzeugen sieht das Modell zunächst nur `tool_search`. Die Suche liefert nur zulässige Werkzeuge der Frage, schaltet höchstens 8 je Suche frei und wirkt in Fortsetzungen weiter. Ein nicht freigeschaltetes Werkzeug wird nicht ausgeführt. Bis 12 Werkzeuge bleibt alles unverändert.
3. **Rückfragen:** Eine Rückfrage unterbricht die Frage und erzeugt eine Karte. Antwort mit gültiger Option oder freiem Text setzt den Ablauf genau einmal fort, und das Modell erhält die Antwort. Eine ungültige Option wird abgelehnt. Fremde Personen oder Chats können nicht antworten. Eine zweite Rückfrage und Rückfragen in Fortsetzungen werden blockiert.
4. Nach einer Antwort ist höchstens eine Schreib-/kritische Folgeaktion möglich, und nur nach eigener Bestätigung.
5. Budgets, Karten, Herkunftsprüfung, Abbruch, Chat-Auswahl und alle TU-02 bis TU-05a-Abläufe bleiben unverändert grün.
6. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl. Unabhängiges Code- und Security-Review durch Codex.
7. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md aktualisieren: TU-05 abgeschlossen, nächster Schritt TU-06.

## Tests

Serverseitig mit H2 und dem echten zentralen Dienst (Muster TU-04/TU-05a): Modell `OFF`/Arten/Chat-Auswahl, Admin-API-Validierung, großer Katalog mit Suche und Freischaltung, Fortsetzung mit gespeicherter Freischaltung, Rückfrage mit Option, freiem Text, ungültiger Option, fremdem Kontext, zweiter Rückfrage und Folgeaktion. Desktop: Typprüfung und Übersetzungsprüfung im vorhandenen Build. Volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-tu05` ab main (3019e81). Draft-PR, kein Merge durch den Entwickler.
