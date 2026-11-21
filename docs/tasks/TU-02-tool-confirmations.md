# TU-02 — Aufrufkarten und konkrete Bestätigungen

Status: **auf main übernommen**, PR #17, Squash-Merge `8f6852a40029c6c0e3ae8fa06b6edc437f24cb6b`, am 21.11.2026 nach Fetch und vollständigem Baumvergleich gegen den freigegebenen Head `01bf6f3` verifiziert. Unabhängige Code-/Security-Freigabe ausdrücklich auf `01bf6f3`; lokale Linux-CI Exit 0 auf Produktcommit `5b31a1a`, Evidence `ci/evidence/20261121-175832/`; danach nur Nachweise/Dokumentation. Gezielte Browser-QA laut PO-Bericht bestanden, mit den unten dokumentierten Grenzen. Kleinere Restgrenzen zu Zeitbudget/Stop bleiben dokumentierte Folgepunkte; alle Review-Threads geschlossen. Kein Release/Deployment. Nächste einzelne Aufgabe: [TU-03a](TU-03a-read-loop.md). PO-Auftrag und Reihenfolge: [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Werkzeugausführung, Freigaben und Nutzerablauf.

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

## Korrekturen nach Review-Runde 1 (Codex, PR #17)

- **[major / Security medium] Vollständige Eingaben vor der Bestätigung:** Karten liefern `argumentsText` (exakt der gespeicherte JSON-Text, der bei Bestätigung ausgeführt wird) und `argumentFields` (je Feld verlustfrei als Text vom Server; kein `JSON.parse` im Client, also keine Rundung über 2^53). Bei offenen Karten ist der vollständige Text immer sichtbar („genau so wird ausgeführt“); die Übersicht kennzeichnet Kürzungen und weitere Felder.
- **[major / Security medium] Kein stiller Ersatz durch `{}`:** Die kanonische Form wird vor dem Speichern geprüft; zu groß → gesperrt (Karte ohne gespeicherte Eingaben, `arguments_json` NULL). Ausgeführt wird immer der gespeicherte kanonische Text, auch bei READ/WRITE (Karte = Adapter-Eingabe).
- **[major / Security medium] Turn-Generation:** Eine Karte gehört zur Frage `prompt_message_id`. Sie ist nur gültig, solange keine neuere Frage im Chat existiert und kein Stopp sie einschließt (`tool_call_stop`). Geprüft beim Anlegen (überholt → sofort CANCELLED, Modell erfährt „nicht vorgelegt“) und atomar im bedingten UPDATE der Bestätigung. Ein langsam planender alter Turn kann so nach einer neuen Nachricht keine bestätigbare Karte mehr erzeugen.
- **[minor / low] Zeitbudget:** Bestätigte Aufrufe teilen die 120 s mit allen Aufrufen derselben Frage (Summe aus `started_at`/`finished_at`); ist es aufgebraucht, endet die Bestätigung als BLOCKED ohne Aufruf. Grenze: zwei exakt gleichzeitige Bestätigungen derselben Frage können das Budget gemeinsam überschreiten (je Aufruf weiter durch Adapter-Zeitlimits begrenzt).
- **[minor / low] Stopp:** Der vorhandene Stopp-Knopf meldet den Abbruch jetzt an `POST …/tool-calls/stop`; offene Karten bis zur neuesten Frage werden CANCELLED, und der gestoppte Turn kann keine bestätigbare Karte mehr anlegen. Grenze: Wird gestoppt, bevor der Server die neue Frage gespeichert hat, deckt die Markierung nur die vorherigen Fragen ab; die neue Frage selbst bleibt dann bis zur nächsten Nachricht/Frist bestätigbar.
- Regressionen: `ToolConfirmationTest` +7 (inflationierende Argumente → BLOCKED ohne `{}`, Karte = Adapter-Eingabe inkl. 9007199254740993, 22 Felder und Suffix nach 450 Zeichen im exakten Text, langsamer Plan nach neuer Frage, neuere Frage ohne cancelPending, Stopp vor/nach Plan, geteiltes Budget), `ChatApiIntegrationTest` +1 (Stopp-Endpunkt, nur Eigentümer:in), `tool_cards.test.cjs` (verlustfreie Übersicht, Kürzungskennzeichnung).

## Gezielte Browser-QA (PO-Auftrag) (Produktstand `5b31a1a`, 21.11.2026)

Echter Keplura-Server aus diesem Branch (Dev-Profil, eigene H2-Datei, SSRF-Freigabe nur `127.0.0.1/32`), echte Desktop-Oberfläche im Browser (Deutsch, Systemdarstellung dunkel), lokaler MCP-Beispielserver mit einem Werkzeug `wipe_tickets` (vom Admin als **Kritisch** klassifiziert und freigegeben; zählt jeden `tools/call` und speichert den **Rohtext** der empfangenen Argumente), **geskriptetes** OpenAI-kompatibles Gateway statt Modell. Ersteinrichtung und MCP-Freigabe per Admin-API im angemeldeten Browser (nicht Prüfgegenstand), alle Chat-Schritte in der Oberfläche.

| Prüfung | Ergebnis |
|---|---|
| **1 Vollständige Eingaben** — Plan mit 23 Feldern (`k0`…`k19`, `cmd` = 450 × „x“ + `; delete_all`, `id` = `9007199254740993`, `delete_all: true`) | ✅ Karte „Kritisch / Wartet auf Bestätigung“; Übersicht zeigt 20 Felder + „3 weitere Eingaben nicht in der Übersicht“ + „Die Übersicht ist gekürzt. Maßgeblich sind die vollständigen Eingaben.“; darunter **immer sichtbar** „Vollständige Eingaben – genau so wird ausgeführt“ mit dem kompletten Text inkl. `; delete_all`, `"id":9007199254740993` (exakt) und `"delete_all":true`. MCP-Zähler 0. |
| **2 Bestätigen** — Doppelklick auf „Ausführen“ | ✅ Genau **1** `tools/call`; Karte „Erfolgreich“ mit Ergebnis als Text. Angezeigter und beim MCP-Server angekommener Argumenttext **byte-gleich** (SHA-256 `d2dda9a3…ab823`, je 672 Zeichen, `9007199254740993` exakt). |
| **2 Wiederholung** — dieselbe Entscheidung per API erneut: bestätigen, bestätigen, ablehnen | ✅ dreimal **409** (Stand SUCCEEDED), Zähler bleibt **1**. |
| **3a Ablehnen** — neue Frage T-2 → „Ablehnen“ | ✅ Karte „Abgelehnt“, ohne Knöpfe; nachträgliche Bestätigung per API → **409 REJECTED**; kein Aufruf. |
| **3b Neue Frage** — offene Karte T-3, dann neue Frage („doch lieber T-5“) | ✅ T-3 sofort „Abgebrochen“, ohne Knöpfe; nachträgliche Bestätigung → **409 CANCELLED**; nur die neue T-5-Karte ist offen. Kein Aufruf. |
| **3c Stoppen während des Planens** — Frage „QA-SLOW“ (Gateway plant 12 s), nach 2 s „Antwort stoppen“ | ✅ Offene T-5-Karte sofort „Abgebrochen“; der verspätete Plan (`T-SLOW`) kam nach 12 s an und wurde **direkt als „Abgebrochen“** angelegt, nicht als offen. Bestätigung von T-3/T-5/T-SLOW → jeweils **409 CANCELLED**. |
| Endstand nach Neuladen | ✅ 5 Karten: Erfolgreich, Abgelehnt, 3 × Abgebrochen — keine mit Knöpfen. **MCP-Zähler gesamt: 1.** |

Keine Codeänderung nötig → kein neuer CI-Lauf.

**Grenzen:** kein echtes Modell (Gateway geskriptet; es ignoriert die Rückmeldung „nicht vorgelegt“), eigener minimaler MCP-Beispielserver, H2 statt PostgreSQL, nur deutsche Oberfläche im Dunkelmodus, keine Handy-Breite, eine einzelne Browser-Sitzung (parallele Entscheidungen sind durch `ToolConfirmationTest` abgedeckt, nicht durch diese QA). Wiederholungen über die API im angemeldeten Browser, nicht über einen zweiten Client.

**Beobachtung (Folgepunkt, nicht durch TU-02 entstanden):** Nach „Antwort stoppen“ während eines Werkzeug-Turns wird die Modellantwort dieses Turns trotzdem als vollständige Antwort gespeichert (kein Hinweis „gestoppt“), weil der Werkzeugweg nicht gestreamt wird und erst am Ende schreibt. Ausgeführt wurde nichts; die Karte ist korrekt abgebrochen. Empfehlung: im nächsten regulären Chat-PR den Stopp-Status auch für Werkzeug-Turns übernehmen.
