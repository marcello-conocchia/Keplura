# MCP-01 — MCP-Server anbinden

PO-Freigabe 14.11.2026: Auf die Frage nach dem nächsten Feature (Optionen u. a. „Werkzeuge/Tool-Calling … braucht
deine ausdrückliche Freigabe") antwortete der PO wörtlich: „mcp". Damit ist MCP als Produktumfang freigegeben
(Ausnahme zu „Agents/MCP" in AGENTS.md / M5–M9 der ROADMAP). Leitplanken aus ROADMAP gelten: MCP ist Adapter,
nie Kern-Abhängigkeit; ein MCP-Server entscheidet nie selbst über Rechte; jeder Aufruf auditierbar.

Branch `feat/chat-productivity`, Migration **V22** `mcp_server`. Nur kompiliert/typgeprüft.

## Stufe 1 (umgesetzt)

- `mcp/McpClient` — Streamable HTTP, JSON-RPC: initialize → notifications/initialized → tools/list (und
  `callTool` für Stufe 2). JSON- und SSE-Antworten, keine Redirects, 2 MB, 20 s. Kein stdio (kein Prozessstart).
- `mcp/McpAdminController` — `/api/v1/admin/mcp-servers`: GET, POST (verbindet sofort), PATCH (Name, URL, Token,
  aktiv, `enabledTools`), DELETE, POST `/{id}/refresh`. Token write-only, `SecretCipher`. Werkzeuge
  standardmäßig gesperrt; Freigaben verschwundener Werkzeuge entfallen beim Neu-Laden.
- Oberfläche: Administration → „MCP-Server": eintragen, Status/Fehler, Werkzeuge einzeln anhaken.

Prüfen : Beispiel-MCP-Server (z. B. `@modelcontextprotocol/server-everything` im Streamable-HTTP-Modus)
starten, eintragen → Werkzeugliste erscheint; falsches Token → „access denied"; Server aus → Fehlermeldung;
Nicht-Admin → 403; Token in keiner Antwort/keinem Log; `FlywayBaselineMigrationTest` erwartet 21 Migrationen bis V22.

## Stufe 2 (umgesetzt, PO 14.11.2026: „mach stufe 2 auch")

Migration **V23**: `mcp_server.all_spaces`, `mcp_server_space`, `mcp_call_log` (ohne Inhalte).

- Freigabe je Arbeitsbereich in Administration → MCP-Server („alle" oder einzelne Bereiche; Standard: keiner).
- `mcp/McpToolService` + `ChatService.toolTurn`: Sind im Bereich der Unterhaltung Werkzeuge freigegeben, läuft der
  Turn nicht-streamend mit `tools` (OpenAI-Format, Name `s{serverId}__{tool}`). `tool_calls` des Modells werden über
  `McpClient.callTool` ausgeführt, max. 3 Runden, dann `tool_choice: none`. Nur freigegebene Werkzeuge sind
  aufrufbar (unbekannter Name → Ablehnung als Text). Ergebnisse als Fremdinhalt markiert, 8 000 Zeichen.
  Die fertige Antwort geht als ein SSE-Frame durch den normalen Relay; oben steht „_Werkzeuge verwendet: …_".
- `ChatRequestValidator` unverändert: Clients dürfen weiterhin KEINE `tools` schicken — nur der Server baut sie.
- Protokoll: `GET /api/v1/admin/mcp-servers/calls` (letzte 100), in der Oberfläche unter „Letzte Werkzeug-Aufrufe".

Bekannte Grenzen: Antworten mit Werkzeugen kommen am Stück statt gestreamt; Werkzeug-Rechte sind je Bereich, nicht je
Benutzer (der MCP-Server sieht das Dienst-Token, nicht den Benutzer); Schema-Validierung der Argumente macht der MCP-Server.

Prüfen : Modell mit Tool-Calling (z. B. Ollama `qwen2.5`/`llama3.1`, vLLM mit `--enable-auto-tool-choice`);
Werkzeug freigeben + Bereich freigeben → Frage, die das Werkzeug braucht → Hinweis „Werkzeuge verwendet" und Eintrag
im Protokoll; anderer Bereich → kein Werkzeug; Werkzeug wieder sperren → wird nicht mehr angeboten; Modell ohne
Tool-Calling → Fehlermeldung des Gateways prüfen und ggf. abfangen; `FlywayBaselineMigrationTest`: 22 Migrationen bis V23.
Security-Gate vor Merge: Token, SSRF durch Admin-URL, Prompt-Injection über Werkzeug-Ergebnisse.

## Abnahme-Zwischenstand — Pause 14.11.2026

Stufe-1- und Stufe-2-Prüfpunkte **noch nicht geprüft**. Offizieller Beispielserver
`@modelcontextprotocol/server-everything` 1.0.0 im Streamable-HTTP-Modus gestartet;
Test-Authentifizierungsproxy vorbereitet. Noch keine Keplura-Anbindung oder
Werkzeugausführung als bestanden gemeldet. Ollama qwen2.5:7b ist lokal vorhanden.

Rückfall ohne Tool-Calling, SSE/Session-/Notification-Verhalten, ISO-Zeitstempel,
Token-Echo in Fehlern und tatsächliche Begrenzung vor vollständigem Lesen sind
offen. Die aktuelle Standard-CI auf `9a28e01` ersetzt diese Abnahme nicht.
Fortsetzung nach DS-01 und CHAT-01, siehe zentrale Übergabe.

### Abnahme-Fortsetzung 15.11.2026

| Prüfpunkt | Ergebnis |
|---|---|
| Offizieller Beispielserver | Bestanden: `@modelcontextprotocol/server-everything` 1.0.0, Streamable HTTP, echtes JSON/SSE und Sitzungen. Token über lokalen Test-Authentifizierungsproxy. |
| Verbindung / Token / Rollen | Bestanden: Werkzeugliste erscheint zunächst gesperrt, Token bleibt write-only, falsches Token abgewiesen, Nicht-Admin 403. Nicht erreichbares Ziel liefert verständliche Fehlermeldung. |
| Lokales Tool-Calling | Bestanden: qwen2.5:7b ruft `echo` tatsächlich auf, Antwort enthält Ergebnis/Hinweis, Protokoll enthält erfolgreichen Aufruf mit ISO-Zeitstempel. |
| Arbeitsbereich / Werkzeug sperren | Bestanden: anderer Bereich und gesperrtes Werkzeug führen keinen Aufruf aus. Ziel-/Tokenwechsel verwirft alte Werkzeugfreigaben. |
| Ohne Tool-Calling | Bestanden mit echtem Ollama `tinyllama:latest`: normaler Chat funktioniert bei angebotenen Werkzeugen, kein MCP-Aufruf. Ergänzende HTTP-Integrationstests: Serverfehler nicht verschlucken, nach schon ausgeführtem Werkzeug nie den Turn wiederholen. |
| SSE / Größen / Fehlertexte | Gezielte Tests bestanden: mehrzeilige SSE-Events, passende Antwort-ID, `202` auf Notification, Versionsheader auch ohne Session, Rückkehr vor Stream-EOF, Größen-/Zeitbegrenzung beim Lesen, keine Token-Echos in Fehlern/Tooltext. |
| Freigaben / Audit / Parallelität | Gezielte Tests bestanden: eigene generierte IDs bei paralleler Anlage; laufendes Refresh kann entzogene Freigaben oder alte Ziel-Metadaten nicht wiederherstellen. Freigabe vor Aufruf neu geprüft; Journal vor Ausführung, ohne Journal kein Aufruf. Namen bleiben nach Kürzen/Normalisierung unterscheidbar. |
| SSRF | Bestehende Betrieb-Freigabeliste integriert; interne Ziele standardmäßig gesperrt. Metadatenziel und eingebettete URL-Zugangsdaten abgewiesen; keine Redirects. DNS-/Socket-TOCTOU-Grenze bleibt wie im vorhandenen `SsrfGuard` dokumentiert. |
| Browser | Vier Schritte grün: verbinden, Werkzeug/Bereich freigeben und Auswahl erhalten, gültige Protokollzeiten, Websuche-Administration speichern/testen. |

Evidence: `ci/evidence/MCP-01-20261115/`. Anleitung:
`docs/howto/mcp.md`. Alle bestehenden E2E-Suiten sind nach Anpassung einzeln
bestanden. Den verbindlichen vollständigen Schlusslauf auf dem endgültigen
Commit (genaue SHA, Matrix und Protokoll) führt
[Draft-PR #53](https://github.com/marcello-conocchia/Keplura/pull/53).
Unabhängige Security-/QA-/Reviewer-Freigaben fehlen weiterhin.
