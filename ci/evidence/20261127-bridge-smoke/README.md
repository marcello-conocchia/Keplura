# TU-06c — Bridge-Probe mit echtem stdio-MCP-Server (27.11.2026)

Manueller, einmaliger Nachweis in der Cloud-Umgebung, ergänzend zu `McpStdioBridgeTest`. Kein Compose-Start
(kein Docker-Daemon verfügbar), keine Container-Härtung geprüft.

- Jar: `server/target/keplura-server-0.1.0.jar`, gebaut vom CI-Lauf `ci/evidence/20261126-205059/` auf 37ea701.
- Start als Nicht-root-Benutzer `ci` (uid 1500) mit leerer Umgebung bis auf `PATH`, `HOME`, `LANG` und
  `KEPLURA_BRIDGE_TOKEN_FILE` (Token: `openssl rand -hex 32`, Wert hier nicht abgelegt):
  `java -jar keplura-server-0.1.0.jar --mcp-stdio-bridge --config=servers.json --bind=127.0.0.1:18090`
- Deklarierter Server: `@modelcontextprotocol/server-filesystem` (npm, SDK 1.32.2) mit
  `["/opt/node22/bin/node", ".../server-filesystem/dist/index.js", "/home/ci/bridge/files"]`, Arbeitsverzeichnis
  `/home/ci/bridge/files`, Zeitlimit 30 s.

| Prüfung | Ergebnis |
|---|---|
| `GET /health` ohne Token | 200 |
| `POST /mcp/files` ohne Token | 401, kein Prozess gestartet |
| `initialize` mit Token | 200, `protocolVersion` `2025-06-18` (= Version des Keplura-MCP-Clients), `Mcp-Session-Id` gesetzt, genau ein Kindprozess |
| `notifications/initialized` | 202 |
| `tools/list` | 14 Werkzeuge (`read_text_file`, `write_file`, `list_directory`, …) |
| `tools/call read_text_file` im freigegebenen Ordner | Inhalt der Testdatei |
| `tools/call read_text_file` auf die Token-Datei außerhalb | vom MCP-Server abgelehnt (`isError`) |
| Kindprozess | Benutzer `ci`, cwd = deklariertes Arbeitsverzeichnis, Umgebung nur `HOME LANG PATH`, Token 0-mal enthalten |
| `DELETE` der Sitzung | 204, danach kein Kindprozess mehr |
| Start als root | verweigert, Exit 2 |

Nicht geprüft: Compose-Dienst `mcp-bridge` mit `read_only`, `cap_drop`, Limits und internem Netz; Anbindung über
die Keplura-Administration mit echter Datenbank (dieser Weg ist in `McpStdioBridgeTest` über den echten
`McpClient` abgedeckt).
