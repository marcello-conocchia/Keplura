# TU-01 — Gemeinsame Tool-Ausführung

Status: **implementiert im Draft-PR #15, nicht gemergt** (Branch `feat/tool-use-foundation`). Code-/Security-Freigabe (Codex) auf `71efc94`, lokale Linux-CI grün auf Produktstand `7c33de9` (`ci/evidence/20261121-104549/`), gezielte Admin-QA bestanden (siehe unten). Merge bleibt Orchestrator-/PO-Schritt. PO-Auftrag vom 21.11.2026, [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: externe Aufrufe und Berechtigungen.

## Ziel

Ein kontrollierter serverseitiger Ausführungsweg für bestehende MCP-Werkzeuge und später eingebaute Tools/OpenAPI. Der Chat-Kern hängt von der allgemeinen Schnittstelle ab, nicht vom MCP-Transport.

## Scope

- Tool-Definition und Adaptervertrag mit stabiler Identität, Eingabeschema und serverseitiger Risikoklasse.
- Bestehenden MCP-Pfad hinter diesen Vertrag legen; Chat nutzt den allgemeinen Ausführungsdienst.
- Argumente vor Ausführung gegen das unterstützte Schema prüfen; nicht unterstützte Varianten sicher ablehnen statt ungeprüft ausführen.
- Benutzer-/Organisations-/Arbeitsbereich-/Chat-Kontext, aktuelle Werkzeugfreigabe, Zeit-/Größen-/Aufruflimits und Pflicht-Audit zentral prüfen.
- Unklassifizierte Tools sperren; READ nur nach Adminfreigabe, WRITE standardmäßig aus. CRITICAL bleibt bis TU-02 mangels konkreter Bestätigungsfunktion gesperrt. Bestehende Freigaben nicht automatisch als READ übernehmen.
- MCP-Anleitung korrigieren: aktuell eine Aufrufrunde, höchstens acht Aufrufe. Änderungen an bestehenden Freigaben verständlich dokumentieren.

## Non-Goals

Keine zusätzlichen Aufrufrunden, neuen eingebauten Werkzeuge, Freigabedialoge, OAuth-/OpenAPI-Anbindungen, Prozessstarts, neuen Dienste, Releases oder Deployments. Bestehende Rechte- und SSRF-Prüfungen nicht abschwächen.

## Acceptance Criteria

1. Chat nutzt die allgemeine Schnittstelle; MCP bleibt ein Adapter.
2. Aktivierte und explizit klassifizierte MCP-Tools bleiben in zulässigen Arbeitsbereichen nutzbar. Unklassifizierte oder nicht freigegebene Tools werden nicht ausgeführt.
3. Ungültige Argumente, fehlende Rechte, entzogene Freigaben und fehlendes Pflicht-Audit verhindern den externen Aufruf.
4. Limits, generische Fehlertexte, Secret-Schutz und Schutz vor automatischer Wiederholung bleiben erhalten.
5. Eine Aufrufrunde und maximal acht Aufrufe bleiben bestehen; CRITICAL kann ohne Bestätigungsnachweis nicht laufen.
6. Unabhängiges Code-/Security-Review ohne offene Befunde; gezielte QA bei geändertem Nutzerablauf; lokale CI nach AGENTS.md.
7. PR referenziert diese Spec und den PO-Auftrag. Nach Merge PROJECT_STATE.md mit tatsächlichem Stand und nächstem Schritt aktualisieren.

## Tests

Gezielt bestehende MCP-/Chat-Tests sowie Negativfälle für Schemafehler, fremde Kontexte, entzogene Freigaben, Risikoklassen und Audit-Ausfall. Nachweisen, dass in gesperrten Fällen kein externer Aufruf erfolgt. Volle lokale Linux-CI einmal am Ende als Nicht-root mit acl; Windows/macOS bleiben Release-Gate.

## Workspace

Neuer eigener Worktree auf Branch `feat/tool-use-foundation` (PO 21.11.2026: Branch nicht „codex“ nennen) ab dem dann aktuellen `main`. Vor Start laufende Implementierungen im autoritativen Kanban prüfen; maximal eine aktive Aufgabe. Dieser Dokumentationsbranch ist kein Implementierungsworkspace. Modell-/Provider-Vorgaben aus AGENTS.md beachten.

## Umsetzung (Draft-PR, 21.11.2026)

- Paket `server/.../tool`: `ToolAdapter` (Vertrag), `ToolDefinition` (stabile ID `adapter:quelle:name`, Schema, Admin-Risikoklasse, Freigabe, Ziel-Fingerprint), `ToolRisk` (READ/WRITE/CRITICAL, `null` = unklassifiziert), `ToolArgumentValidator` (geprüfte JSON-Schema-Teilmenge; unbekannte Schlüsselwörter sperren das Werkzeug), `ToolAuditLog` (`tool_call_log`, Beginn vor Ausführung), `ToolExecutionService` (Kontext, Freigabe/Klasse, Schema, Limits, Audit; ein Turn = `ToolTurn`).
- MCP: `McpToolAdapter` ersetzt `McpToolService`; `ChatService` kennt nur noch `ToolExecutionService`.
- Migration V25: `mcp_server.tool_risks`, Tabelle `tool_call_log`, Übernahme der bisherigen `mcp_call_log`-Zeilen (Kopie, Altbestand bleibt).
- Admin: Risikoklasse je Werkzeug (API `toolRisks`, Oberfläche Auswahlfeld); Freigabe nur für klassifizierte, nicht kritische Werkzeuge mit prüfbarem Schema; Klassenwechsel, geänderte Werkzeugdefinition beim Neu-Laden und Ziel-/Tokenwechsel heben Freigaben auf.
- Limits zentral: 8 Aufrufe je Antwort (auch abgelehnte Versuche), 16 000 Zeichen Argumente, 8 000 Zeichen Ergebnis, 120 s Werkzeugzeit je Antwort (Prüfung vor jedem Aufruf), höchstens 32 angebotene Werkzeuge. Eine Aufrufrunde unverändert.
- Anleitung `docs/howto/mcp.md` korrigiert (eine Runde, acht Aufrufe, Risikoklassen, Umstellung bestehender Freigaben).

Auslegung „WRITE standardmäßig aus“: WRITE-Werkzeuge sind wie alle Werkzeuge erst nach eigener ausdrücklicher Adminfreigabe ausführbar; es gibt keinen zusätzlichen globalen Schalter. Bei abweichender PO-Absicht (z. B. globaler Betriebsschalter) nachziehen.

## Korrekturen nach Review-Runde 1 (Codex, PR #15)

- **[major / Security medium] Ungültige Schemas:** `inputSchema` wird nicht mehr zu „kein Schema“ normalisiert. MCP-Client, Refresh, Adminliste und Adapter übernehmen jeden gesendeten Wert; nur ein fehlender Schlüssel heißt „keine Argumente“ (nur `{}` erlaubt). `false`, `true`, JSON-`null`, Liste, Text oder `{}` ohne `"type":"object"` sperren das Werkzeug.
- **[major / Security medium] Große Zahlen:** Grenzen werden ohne Konvertierungs-Exception geprüft (ganzzahlig im `long`-Bereich bzw. endliche Dezimalzahl); sonst gesperrt. Validator-Methoden werfen nie (fail closed). Adminliste und Refresh bleiben bei solchen Schemas nutzbar. Argumente mit nicht endlichen Zahlen (z. B. `1e400`) oder Verschachtelung über 64 Ebenen werden abgewiesen.
- **[minor / info] enum/const:** Vergleich nach JSON-Schema-Wertgleichheit (`1` = `1.0`, rekursiv in Listen/Objekten) — mitbehoben.
- Regressionen: `McpSchemaFailClosedTest` (Fake-MCP-Server → Refresh → Liste → Klassifizieren/Freigeben → zentraler Dienst; kein `tools/call`, kein Audit-Eintrag; fehlendes Schema separat), zusätzliche Fälle in `ToolArgumentValidatorTest`.

## Gezielte QA Admin-Ablauf (21.11.2026)

Echter Keplura-Server aus dem PR-Stand (Dev-Profil, eigene H2), echte Desktop-Oberfläche im Browser, lokaler MCP-Beispielserver (Streamable HTTP, drei Werkzeuge: lesen/schreiben/kritisch, zählt `tools/call`), geskriptetes OpenAI-kompatibles Gateway statt Modell.

| Prüfung | Ergebnis |
|---|---|
| Verbinden → alle Werkzeuge „nicht klassifiziert“, Häkchen gesperrt | bestanden |
| `lookup_ticket` → Lesen → Häkchen aktiv → freigeben + Arbeitsbereich → Chatfrage | bestanden: nur `lookup_ticket` angeboten, genau 1 `tools/call`, Antwort „Werkzeuge verwendet: lookup_ticket“, Protokoll mit Klasse „Lesen“, erfolgreich |
| Klassenwechsel Lesen → Schreiben | bestanden: Freigabe aufgehoben (0 von 3), nächste Chatantwort ohne angebotenes Werkzeug, kein weiterer Aufruf |
| `delete_all_tickets` → Kritisch | bestanden: Häkchen gesperrt mit Hinweis; direkte Freigabe per Admin-API (`PATCH enabledTools`) bleibt wirkungslos, kein Aufruf |

Grenzen: kein echtes Modell (Gateway geskriptet), nicht der offizielle MCP-Beispielserver (kein Download), nur deutsche Oberfläche, kein Dunkelmodus, H2 statt PostgreSQL (V25 auf PostgreSQL hat Codex separat geprüft).
