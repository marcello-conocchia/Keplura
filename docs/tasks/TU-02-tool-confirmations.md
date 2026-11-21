# TU-02 — Aufrufkarten und konkrete Bestätigungen

Status: **implementiert im Draft-PR, nicht gemergt** (Branch `feat/tool-use-confirmations`); unabhängige Code-/Security-Prüfung offen. Nächste einzelne Implementierungsaufgabe nach TU-01 (PR #15, Merge `1296cdb`). PO-Auftrag und Reihenfolge: [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Werkzeugausführung, Freigaben und Nutzerablauf.

## Ziel

Werkzeugaufrufe im Chat nachvollziehbar anzeigen und kritische Aktionen erst nach einer konkreten Nutzerbestätigung ausführen. Der Server bleibt die Autorisierungsgrenze; alle Aufrufe laufen weiterhin durch ToolExecutionService.

## Scope

- Strukturierte Aufrufkarten mit Werkzeug/Quelle, Risikoklasse, verständlichen Eingaben, Status und Ergebnis oder generischem Fehler. Zustände mindestens ausstehend, läuft, erfolgreich, fehlgeschlagen, abgelehnt und abgelaufen/abgebrochen. Vorhandenen Designkatalog verwenden, Deutsch und Englisch.
- Für CRITICAL: erst ausdrückliche Adminfreigabe, dann konkrete Nutzerbestätigung vor jedem Aufruf. Erst wenn der Bestätigungsweg vollständig funktioniert, die bisherige pauschale CRITICAL-Sperre dafür ersetzen. READ bleibt nach Adminfreigabe automatisch nutzbar; WRITE bleibt standardmäßig aus und benötigt eigene Adminfreigabe wie TU-01. Keine zusätzliche globale WRITE-Regel erfinden.
- Ausstehender Aufruf liegt serverseitig und bindet Person, Organisation, Arbeitsbereich, Chat, Werkzeugdefinition/Risikoklasse, Ziel-/Token-Fingerprint und exakt geplante Argumente. Entscheidung referenziert diesen Aufruf; der Client kann keine anderen Argumente, Ziele oder Rechte einschleusen.
- Vor Ausführung Kontext, Mitgliedschaft, aktuelle Freigabe, Klasse/Definition, Ziel und Schema erneut prüfen; Pflicht-Audit vor externem Aufruf. Entzug oder Änderung macht die alte Bestätigung ungültig.
- Bestätigungen zeitlich begrenzen und atomar genau einmal verbrauchen. Doppelklick, parallele Entscheidungen, Reload, Verbindungsabbruch und Wiederholen einer HTTP-Anfrage dürfen keinen zweiten externen Aufruf auslösen. Bei unklarem Ausführungsstatus keine automatische Wiederholung.
- Ablehnen, Stoppen oder Ablauf beendet ausstehende Aktionen sicher. Andere Personen können weder lesen noch entscheiden. Entscheidungen benötigen Session-/CSRF-Schutz wie andere schreibende APIs.
- Status im Verlauf nachvollziehbar halten; keinen erfolgreichen Abschluss vortäuschen. Zugangsdaten ausschließlich beim Server/Adapter, nicht in Karten, Modellkontext oder Audit-Payloads. Anzeigen und Ergebnisse als Fremdinhalt behandeln.
- Eine einzige geplante Aufrufrunde und bestehende zentrale Limits bleiben erhalten; Bestätigung erzeugt keinen neuen unkontrollierten Turn oder Aufrufzähler. Kein automatischer Folgeauftrag aus Werkzeugergebnissen.

## Non-Goals

Keine mehrstufige Modellschleife (TU-03), eingebauten Suchwerkzeuge (TU-04), Tool-Suche/allgemeinen Rückfragedialoge (TU-05), neuen Adapter, OAuth-Anbindungen, Unteragenten, Releases oder Deployments. Keine pauschale Bestätigung aller künftigen Aktionen.

## Acceptance Criteria

1. Zugängliche Karten zeigen tatsächliche Zustände und Ergebnisse. Normale Chats und freigegebene READ-/WRITE-Werkzeuge funktionieren weiter.
2. Kein CRITICAL-Aufruf vor Adminfreigabe und gültiger Bestätigung des genau angezeigten Aufrufs. Ablehnung/Ablauf erzeugen keinen externen Aufruf.
3. Manipulierte Argumente/IDs, fremde Kontexte, entzogene Mitgliedschaft/Freigabe, geänderte Definition/Klasse/Ziel/Token und Audit-Ausfall verhindern die Ausführung.
4. Gleichzeitige Entscheidungen, Doppelklick, Replay und Wiederaufnahme erzeugen höchstens einen externen Aufruf; unklarer Status wird nicht erneut ausgeführt.
5. Keine Geheimnisse in Modellkontext, API-Karten oder Protokoll; Inhalte nicht ungeprüft als HTML/Anweisung ausführen. Bestätigung ist keine allgemeine Rechteerweiterung.
6. Eine Aufrufrunde, bestehende Limits und keine automatischen Wiederholungen bleiben erhalten.
7. Gezielte Tests, einmal lokale Linux-CI, unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Keine Browser-/Feldtests ohne PO-Auftrag; weitere Prüftiefe nach AGENTS.md.
8. Nach Merge PROJECT_STATE.md mit Stand/Belegen und TU-03 als nächstem Schritt aktualisieren.

## Tests

Gezielte Server-Tests des Zustands-/Entscheidungswegs einschließlich paralleler Bestätigungen und Null-Aufruf-Nachweis in Sperrfällen. Lokaler Fake-MCP-Server mit Aufrufzähler; geskriptetes Gateway zulässig und kennzeichnen. Frontend-Tests für Karten/Fehler/Entscheidungen im vorhandenen Testansatz. Keine neue Vollkampagne; volle Linux-CI einmal am Ende als Nicht-root mit acl.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-confirmations` ab aktuellem main. Vor Start aktive Aufgaben im autoritativen Kanban prüfen; genau eine Implementierung. Entwickler öffnet Draft-PR, Codex prüft unabhängig. Kein Merge durch den Entwickler. Provider-/Budgetregeln aus AGENTS.md gelten.

## Bestehende Folgepunkte

Die MCP-Werkzeug-Häkchen tragen laut TU-01-QA im Accessibility-Baum den Namen „on“. Bei Arbeit an derselben Oberfläche im nächsten regulären UI-PR beheben oder als Folgepunkt beibehalten; keine separate Test-/PR-Kampagne. Nicht unterstützte Schemas (`pattern`, `$ref`) bleiben gesperrt; 120 s bleiben ein zwischen Aufrufen geprüftes Budget, kein harter Abbruch eines laufenden Aufrufs.

## Umsetzung (Draft-PR, 21.11.2026)

- **Karten:** Tabelle `tool_call` (Migration V26), je geplantem Aufruf eine Zeile an der Frage des Turns; `tool_call_log.tool_call_id` verknüpft das Audit. Zustände PENDING, RUNNING, SUCCEEDED, FAILED, REJECTED, BLOCKED (Server hat nicht ausgeführt), EXPIRED, CANCELLED, UNKNOWN (hängendes RUNNING > 5 min, nur Anzeige). Karten kommen mit dem Transkript (`toolCalls`), nur für die Person des Chats; ohne Fingerprint, Hash oder interne IDs (öffentliche ID = zufällige UUID).
- **CRITICAL:** nach Adminfreigabe anbietbar; im Turn entsteht nur eine PENDING-Karte (Frist 10 min) mit Definitions-Hash (ID, Name, Quelle, Beschreibung, Schema, Klasse, Freigabe, Ziel-/Token-Fingerprint) und den exakt geplanten Argumenten. Das Modell erfährt nur „noch nicht ausgeführt“. Kein neuer Modell-Turn nach der Bestätigung.
- **Entscheidung:** `POST /api/v1/orgs/{org}/chat/conversations/{id}/tool-calls/{callId}/decision` mit `{"decision":"confirm"|"reject"}` (Session + CSRF). Nur der Wert `decision` wird gelesen. Atomarer Verbrauch per bedingtem UPDATE (PENDING → RUNNING/REJECTED, nur vor Ablauf); danach Kontext/Mitgliedschaft/Bereich, aktuelle Definition (Hash), Klasse und Argumente gegen das aktuelle Schema erneut prüfen, Audit vor dem Aufruf, genau ein Adapteraufruf. Verloren/entschieden/abgelaufen → 409 mit aktuellem Stand; fremd/unbekannt → 404.
- **Ende ohne Aufruf:** Ablehnen, Fristablauf, neue Nachricht im Chat (PENDING → CANCELLED), Löschen des Chats.
- **READ/WRITE:** unverändert direkt nach Adminfreigabe, jetzt mit Karte. Limits unverändert (eine Runde, 8 Versuche inkl. geplanter, Größen, Zeitbudget).
- **Admin:** CRITICAL ist freigebbar; Hinweis in der Oberfläche. Häkchen tragen jetzt den Namen „<Werkzeug> freigeben“ (Folgepunkt aus TU-01-QA behoben).
- **Desktop:** `ToolCallCard.vue` unter der Antwort, Deutsch/Englisch, Ergebnis nur als Text, Bestätigen/Ablehnen mit Busy-Sperre, bei unklarer Antwort kein Wiederholen, sondern Neuladen des Stands. Logik in `tool-cards.ts`, getestet mit `e2e/tool_cards.test.cjs`.
