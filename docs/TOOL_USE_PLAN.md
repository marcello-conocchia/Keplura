# Tool Use — verbindlicher Arbeitsplan

## Auftrag und Priorität

PO-Beschluss vom **21.11.2026 im Chat**: Keplura soll mindestens den Tool-Use-Umfang von Open WebUI erhalten. Der besprochene Plan wird als **Arbeitsziel für die nächsten Tage** verankert, bis der PO die Priorität ändert. Kein Versprechen, den gesamten Umfang innerhalb weniger Tage fertigzustellen.

Diese Reihenfolge ersetzt ältere allgemeine Angaben zur nächsten UI-/Roadmap-Arbeit. Offene Sicherheitsbefunde bleiben offen; notwendige Sicherheitskorrekturen dürfen vorgezogen werden. Ziel ist funktionale Gleichwertigkeit mit Kepluras serverseitiger Rechteprüfung und Mandantentrennung, keine Übernahme des Open-WebUI-Stacks. Vergleichsstand: 21.11.2026; spätere Funktionen des Vergleichsprodukts erweitern den Auftrag nicht automatisch.

## Verbindliche Reihenfolge

**TU-01, TU-02, TU-03a und TU-03b sind auf main (PRs #15/#17/#19/#21, Merges `1296cdb`/`8f6852a`/`5c25bfa`/`b1b9e72`, 21./22.11.2026). Nächste einzelne Aufgabe ist TU-03c: eine bestätigungspflichtige Folgeaktion aus einem bestätigten Ergebnis als letzter Teil von TU-03. TU-03–TU-07 sind noch nicht abgeschlossen.** Genau eine aktive Implementierungsaufgabe. Größere Schritte werden bei Bedarf in kleine PRs zerlegt; nur die unmittelbar nächste Implementierungsaufgabe wird konkret angelegt.

| Schritt | Umfang | Abnahmeziel |
| --- | --- | --- |
| TU-01 | Gemeinsame Tool-Schnittstelle; MCP zuerst als Adapter. Argumentprüfung, Rechteprüfung, Risikoklassen, Limits und Audit zentralisieren. | Bestehende MCP-Tools laufen über den gemeinsamen kontrollierten Weg. Unklassifizierte Tools bleiben gesperrt. |
| TU-02 | Aufrufkarten mit Status, Eingaben und Ergebnis; konkrete Aktionen erlauben/ablehnen. | Zustimmung bindet Nutzer, Werkzeug und Argumente; ohne nötige Bestätigung kein Aufruf. Sensible Inhalte bleiben geschützt. |
| TU-03 | Mehrstufige Ausführung, Gesamtlimits, Abbruch und Schutz vor doppelten Schreibaktionen. | Suchen → Lesen → Nachsuchen → Antwort funktioniert; Ergebnisse erweitern weder Rechte noch Freigaben. |
| TU-04 | Wissenssuche, Dokumentstellen lesen, Anhänge auflisten/durchsuchen/lesen, Websuche und Webseitenabruf als Tools. | Eigenständige Nachrecherche mit Zitaten; Rechteprüfung bei jedem Dokumentzugriff und SSRF-Schutz beim Webseitenabruf. |
| TU-05 | Tool-Auswahl je Chat, Modellvorgaben, Tool-Suche bei großen Katalogen und strukturierte Rückfragen. | Nur erlaubte Tools auswählbar; Auswahl erweitert keine Rechte. Rückfragen unterbrechen einen Ablauf und erlauben seine Fortsetzung. |
| TU-06 | OpenAPI-Adapter, persönliche OAuth-Verbindungen, isolierter MCP-stdio-Adapter sowie Bild- und Dateiergebnisse. | Weitere Anbindungen ohne individuelle Änderungen am Kern; Zugangsdaten außerhalb des Modellkontexts und sichere Ergebnisdarstellung. |
| TU-07 | Eingebaute Fähigkeiten einzeln ergänzen: Code Interpreter, Bildgenerierung/-bearbeitung, Gedächtnis, Chat-Suche, Notizen, Aufgaben, Kalender, Automationen/Timer, Benachrichtigungen, Skills, Unteragenten und interaktive Visualisierungen. Eigene Tools mit Verwaltung und Versionierung. | Jede Fähigkeit hat eine eigene kleine Aufgabe, Berechtigungen, passende Bestätigungen und überprüfbare Ergebnisse. |

TU-07 ist kein Sammelticket. Bei der Vorbereitung den verbleibenden Vergleichsumfang (z. B. Kanal-Suche, Terminalwerkzeuge und Tool-Erstellung aus dem Chat) ausdrücklich zuordnen oder als offen dokumentieren; nichts stillschweigend als erreicht markieren.

Der Auftrag genehmigt den abgegrenzten Tool-Use-Plan, nicht pauschal die gesamten bisherigen Meilensteine M5–M9. Zusätzliche Produktoberflächen, Stackwechsel, kostenpflichtige Dienste und Sicherheitsabwägungen mit Produktwirkung bleiben PO-Entscheidungen. Bestehende Provider-/Budgetregeln gelten weiter.

## Sicherheitsregeln

- Server entscheidet über Rechte, Risikoklasse und Ausführung: READ nur nach Adminfreigabe; WRITE standardmäßig aus; CRITICAL zusätzlich mit konkreter Nutzerbestätigung unmittelbar vor Ausführung.
- Modell, Dokument, Tool-Ergebnis und Connector dürfen keine Rechte erweitern, Werkzeuge aktivieren oder Risikoklassen absenken.
- Geheimnisse weder im Modellkontext noch in Aufrufkarten oder Audit-Payloads. Persönliche Zugänge nicht zwischen Nutzern teilen.
- Keine unkontrollierten Prozessstarts oder beliebige Codeausführung im Keplura-Serverprozess; keine automatische Wiederholung möglicherweise bereits ausgeführter Schreibaktionen.

## Arbeitsweise und Nachweis

Jede Implementierungsaufgabe erhält Ziel / Scope / Non-Goals / Acceptance Criteria / Tests / Workspace. Eigener Worktree und Branch ab main. Gezielte Tests während der Entwicklung, einmal lokale Linux-CI zum Abschluss gemäß AGENTS.md. Klasse A: unabhängiger Reviewer und Security-Prüfer, gezielte QA bei geänderten Nutzerabläufen. Keine zusätzlichen Browserkampagnen oder Demos ohne PO-Auftrag.

Nach jedem Merge PROJECT_STATE.md mit tatsächlichem Stand, Belegen und nächstem kleinsten Schritt aktualisieren. Geplant, implementiert, geprüft und gemergt getrennt ausweisen. Bestehende Merge-, Release- und Deployregeln bleiben verbindlich. Dieses Dokument startet keine Hintergrundausführung.

## Unmittelbar nächste Aufgabe

**TU-03c — abhängige Folgeaktion mit konkreter Bestätigung**, siehe [Spec](tasks/TU-03c-dependent-action.md). Implementiert auf feat/tool-use-dependent-action; lokale Linux-CI auf 7e05e92 grün (729 Tests, 1 übersprungen; Evidence ci/evidence/20261122-105648/), unabhängige Prüfung durch Claude offen, nicht gemergt. In der einmaligen Fortsetzung nach einem bestätigten Ergebnis darf das Modell höchstens eine weitere WRITE/CRITICAL-Aktion vorschlagen; sie wird nie automatisch ausgeführt, sondern immer als Karte mit konkreter Bestätigung vorgelegt, auch bei WRITE. Doppelte Aktionen derselben Frage sind gesperrt. Erledigt: [TU-03a](tasks/TU-03a-read-loop.md) — mehrstufige READ-Folgeaufrufe (PR #19); [TU-03b](tasks/TU-03b-confirmed-continuation.md) — einmalige Fortsetzung nach Bestätigung (PR #21). Mit TU-03c ist TU-03 inhaltlich vollständig; die Phase gilt erst nach dessen Merge als abgeschlossen.

## Vergleichsquellen (abgerufen am 21.11.2026)

- [Open WebUI: Tools](https://docs.openwebui.com/features/extensibility/plugin/tools/)
- [Open WebUI: Tool-Server, Auswahl und OAuth](https://docs.openwebui.com/features/extensibility/plugin/tools/openapi-servers/open-webui/)
- [Open WebUI: Erweiterbarkeit](https://docs.openwebui.com/features/extensibility/)
- [Open WebUI: Codeausführung](https://docs.openwebui.com/features/chat-conversations/chat-features/code-execution/)
