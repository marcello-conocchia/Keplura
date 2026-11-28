# Übergabe — 14.11.2026 (Abend)

**Branch:** `feat/chat-productivity` (von `main`, enthält `feat/ds-datasources` = Schritt 5).
**Ursprünglicher Stand:** nur `mvn compile test-compile` + `vue-tsc --noEmit` geprüft.
**Aktueller Stand:** `docs/HANDOFF-2026-11-15.md` und Draft-PR #53.
Der Pausenstand unten ist historisch; der Inhaber hat am 15.11. ausdrücklich fortgesetzt.
Migrationen neu: V16–V23 (Datenquellen, Qualität, SharePoint, Vorlagen, Chat-Kontext, Websuche, MCP).

## Reihenfolge

1. `bash scripts/ci-local.sh` vollständig; rote Tests zuerst beheben (Migrationstest erwartet 22 Migrationen bis V23).
2. Prüflisten in dieser Reihenfolge abarbeiten:
   - `docs/tasks/DS-01-datasources.md` (Verzeichnis, SMB, Linux, SharePoint, Qualität)
   - `docs/tasks/CHAT-01-productivity.md` (Export, Vorlagen, Strg+K, mehrere Wissensbasen, Dateien, Websuche)
   - `docs/tasks/MCP-01-mcp-server.md` (Stufe 1 + 2)
3. e2e-Suiten an neue Oberflächen anpassen (neue Testids stehen in den Prüflisten; `sidebar-search` öffnet jetzt
   die Befehlspalette).
4. Ergebnisse (bestanden / rot / nicht prüfbar + Grund) je Prüfliste in die jeweilige Datei schreiben.

## Stellen, die ich  selbst für riskant halte

- `ChatService.toolTurn`: Modelle ohne Tool-Calling → Gateway-Fehler; sauber auf normalen Stream zurückfallen.
  Werkzeuge greifen nicht, wenn eine Wissensbasis gewählt ist und keine Treffer hat (NO_EVIDENCE kommt vorher).
- `McpClient`: SSE-Antworten verschiedener MCP-Server (Session-Header, 202 auf Notifications) gegen echten Server prüfen.
- `McpAdminController.calls` / `queryForList`: Zeitstempel-Format im JSON prüfen (Oberfläche nutzt `new Date(...)`).
- `ChatContextService`: Anhang-ID per `max(id)`; Gateway-Grenze 65 536 Zeichen je Nachricht mit großem Anhang + RAG.
- `WebSearchService`: SearXNG braucht `search.formats: [html, json]`; gesperrte Domains (Komma/Leerzeichen).
- `TikaDocumentTextExtractor.extract(String, Path)`: `runBounded(null, …)` — prüfen, dass `null` dort nur geloggt wird.
- Antwort-Export PDF: Umlaute (WinAnsi), lange Tabellen, Seitenumbruch.

## Regeln (unverändert)

Kein Merge nach `main`, kein Force-Push, keine Veröffentlichung. Nur tatsächlich durchgeführte Prüfungen als
bestanden melden. Bestehende Funktionen und Daten erhalten, keine Secrets committen. Kleine Commits unter der
Git-Identität Marcello Conocchia. Sichtbare Texte auf Deutsch.

## Prompt (zum Kopieren)

```
Arbeite lokal im IntraCore-Repo auf Branch feat/chat-productivity (git fetch; git checkout feat/chat-productivity;
git pull). Lies zuerst docs/HANDOFF-2026-11-14-pilot.md und arbeite die Reihenfolge dort ab: ci-local vollständig,
dann die Prüflisten DS-01, CHAT-01 und MCP-01 im laufenden Programm (lokaler Server + Browser; für Websuche einen
SearXNG-Container, für MCP einen Beispiel-MCP-Server im Streamable-HTTP-Modus, für Tool-Calling ein passendes
lokales Modell). Behebe gefundene Fehler mit kleinen, sinnvollen Commits unter Marcello Conocchia und trage die
Ergebnisse in die Prüflisten ein. Kein Merge nach main, kein Force-Push, keine Veröffentlichung. Nur tatsächlich
durchgeführte Prüfungen als bestanden melden. Keine Secrets committen. Alle sichtbaren Texte auf Deutsch.
```

## Nächste 10 Schritte (Vorschlag, mit PO abzustimmen)

1. **Abnahme dieses Branches durch die unabhängige Prüfung** (oben) — Fehler beheben, Prüflisten grün.
2. **Security-Review + Merge nach main**: Upload, Export, Websuche, MCP, Datenquellen; danach PROJECT_STATE.
3. **Pilot beim ersten Kunden**: Installation (4a), echtes AD/LDAP, Fileserver/SharePoint, Testfragen-Set.
4. **MCP robuster**: Rückfall ohne Tool-Calling, Streaming der Werkzeug-Antworten, Werkzeug-Anzeige als Chip.
5. **Modell-Auswahl pro Chat** (vom Admin freigegebene Modelle, z. B. „schnell" / „gründlich").
6. **Websuche ausbauen**: Freigabe je Arbeitsbereich, Web-Quellen als Quellen-Chips, Seitenabruf mit SSRF-Schutz.
7. **Chats organisieren**: umbenennen, anheften, archivieren, im Team teilen.
8. **Nutzung & Qualität für Admins**: Dashboard (aktive Nutzer, Fragen, Feedback, fehlende Antworten, Werkzeuge).
9. **Sprache**: Spracheingabe und Vorlesen lokal (Whisper/Piper), ohne Cloud.
10. **Updates beim Kunden**: Update mit einem Befehl, Wiederherstellung aus Backup getestet, Beta-Gate Windows/macOS.

## Zwischenstand und Pause — 14.11.2026

Der Inhaber hat ausdrücklich um Pause bis morgen gebeten. Nicht selbstständig
weiterarbeiten oder eine geplante automatische Fortsetzung annehmen.
Branch bleibt `feat/chat-productivity`, Haupt-Checkout ist das ursprüngliche
lokale IntraCore-Repository. Keine Änderung an main, kein Merge/Force-Push.

### Gesichert und geprüft

1. `186dfc2`: Chat-Integrationstests verwenden echte Flyway-Migrationen statt
   eines unvollständigen Hibernate-Testschemas; Login-Vertrag enthält
   `mustChangePassword`. Die ersten acht roten Tests damit behoben.
2. `9a28e01`: veraltete Parameter-Erwartung der pgvector-Prüfung um die beiden
   ACL-Parameter ergänzt. Voller Standardlauf `bash scripts/ci-local.sh` danach
   grün: 416 Tests, 57 Klassen genau einmal, Frontend und Linux-Tauri.
   `ci/evidence/20261114-221256/`, eingecheckt mit `0a02d5f`.
   Das ist KEIN `--with-e2e`-Lauf und kein Nachweis für den späteren Branch-Head.
3. `2f8c4f4`: lokale Rechte-/Pfadgrenzen und AD-Referrals korrigiert, kaputte
   SIDs verworfen; unbekannte ACL schreibt nur eine deny-everyone-Zeile.
   Danach zusätzlicher SMB-Fix: fehlender Deskriptor sperrt statt Freigabe.
   Insgesamt zehn gezielte neue Tests grün (Local 3, LDAP 4, Sync 1, SMB 2).
4. DS-01: echte Container mit OpenLDAP, Samba AD/SMB und PostgreSQL/pgvector.
   LDAPS mit eigener Test-CA UND Hostnamenprüfung funktioniert. Falscher
   Hostname wird abgewiesen. Gruppenauflösung, Negativkonto, Liste/Download/
   Vorschau/Suche, Rechteentzug nach Abgleich und Qualitätsfragen geprüft.
5. Neuer Browserlauf `desktop/e2e/datasources_flow_e2e.cjs` besteht in der
   QA-Umgebung: Anmeldung, Verzeichnis testen/nachschlagen und Assistent bis
   Aktivierung. Seine letzte Status-Wartebedingung noch auf die NEU angelegte
   Zeile einschränken (aktuell kann ein schon fertiger anderer Lauf genügen).
   Directory-Anlegen über Formular ist noch kein abgenommener Browserfall.

### Laufzeit und lokale Hilfsmittel (keine Secrets übernehmen)

- Originalpilot `http://localhost:18080` bleibt PILOT-03. Alte Entwicklungs-
  instanz auf 8080 ist separat. Nichts an deren Daten/Volumes löschen.
- QA: Container `intracore-ci`, `intracore-qa-pg`, `intracore-qa-directory`,
  `intracore-qa-ad`, `intracore-qa-searxng`, Netzwerk `intracore-qa`.
  Diese werden zur Pause gestoppt, nicht gelöscht. Linux-Arbeitskopie im
  Container `/workspace` ist eine PRÜFKOPIE; maßgeblich ist das Windows-Repo.
- Benannte QA-Volumes `intracore-ci-work` und `intracore-qa-pgdata` erhalten.
  Modelle im ursprünglichen Ollama-Container bleiben vorhanden.
- Browser-QA: Host `http://localhost:1420`, Server-API Hostport 18180 →
  Containerport 8080. Interner Node-Proxy `/qa/port-proxy.cjs` stellt 18180
  auch für Chromium im Container bereit.
- Ignorierte lokale Dateien `.devenv/qa-login.json`, `.devenv/qa-secrets.env`
  und Container `/qa/secrets.env` enthalten die zufälligen TEST-Zugangsdaten.
  Nur lokal verwenden, nie ausgeben/committen. Die ursprünglichen Pilot-
  Zugangsdaten wurden nicht verwendet oder geändert.
- `.devenv/Dockerfile.ci` und `Dockerfile.directory-fixtures` sowie Setup-/
  Prüfskripte sind lokal erhalten. CI-Image `intracore-ci:local` mit JDK21,
  Maven, Node22, Rust, GTK/WebKit, Xvfb/AT-SPI; Playwright unter `/qa/node_modules`.
- `/qa/run-server.sh` startet den QA-Server mit eigener PostgreSQL-Datenbank,
  qwen2.5:7b, bge-m3 (1024 Dimensionen), lokalen Testdateien und SearXNG.
  Vor Wiederaufnahme neues JAR bauen: bisherige Laufzeit enthielt die Local-/
  LDAP-Fixes, die letzten Sync-/SMB-Fixes sind bisher nur gezielt getestet.
- Nach `docker start` müssen die zuvor mit `docker exec` gestarteten Prozesse
  erneut gestartet werden: LDAP (`slapd`), Samba mit `/qa/ad4/etc/smb.conf`,
  Java über `/qa/run-server.sh`, Vite (Host 0.0.0.0, VITE_SERVER_URL auf
  http://localhost:18180), Node-Portproxy, MCP-Beispielserver und Auth-Proxy.
  Service-Namen und Details stehen in den lokalen `.devenv`-Skripten.
- Offizieller MCP-Beispielserver: Paket `@modelcontextprotocol/server-everything`
  1.0.0 unter `/qa/node_modules`, `dist/index.js streamableHttp`, Port 3001;
  Test-Authentifizierungsproxy `/qa/mcp-auth-proxy.cjs` auf 3002.
- SearXNG JSON-Format aktiv. Echte externe Suchtreffer erhalten; IntraCore-
  Websuche selbst noch NICHT abgenommen.
- SMB-Testdatei hat am Pausenstand zusätzlich ein DENY für `ad-allowed`
  (Rechteentzugstest). Zum Wiederherstellen `/tmp/set-smb-acl.sh` im AD-
  Container verwenden und danach Datenquelle neu abgleichen.
- QA-IDs nur lokal in `/qa/fixture-ids.json`. Keine Produktions-IDs verwenden.

### Genau hier fortsetzen

1. Arbeitsbaum/Branch prüfen. Im Linux-Prüfklon auf den echten aktuellen
   Branch-Commit synchronisieren, keine künstlichen Snapshot-Commits als
   verbindliche CI-Evidence ausgeben. Aktuelle neue Testdateien mitnehmen.
2. DS-Restpunkte: unabhängiger Security-Review, Directory-Anlegen im Browser,
   Gruppenauffrischung ≤15 min und deaktiviertes Konto mit bestehender Sitzung,
   Lauf-Abbruch/Neustart, leere/unlesbare ACL tatsächlich im laufenden System,
   SharePoint mit echtem Tenant. Nicht prüfbare Fälle begründet offen halten.
   Vorhandene lokale Prüfskripte `ds-api.cjs`, `ds-ad.cjs`, `ds-more.cjs`,
   `ds-revocation.cjs` benutzen; sie sind kein vollständiger E2E-Gate-Ersatz.
3. Danach CHAT-01 in der vorgegebenen Reihenfolge abnehmen, einschließlich
   echter Datei-/Mehrfach-KB-Antwort, Export und IntraCore-Websuche.
4. Danach MCP-01 gegen offiziellen Streamable-HTTP-Server und Ollama prüfen.
   Noch UNBEHOBEN: Rückfall ohne Tool-Calling; `McpClient` liest unbeschränkt
   vor seiner 2-MB-Prüfung und übernimmt rohe MCP-Fehlermeldungen in Status/
   Tool-Protokoll (mögliches Token-Echo). SSE-Events, IDs und Sessions absichern.
   Gateway-Fehler nicht pauschal verschlucken und nie nach ausgeführten
   Werkzeugen den Turn erneut starten (Doppelausführung vermeiden).
5. Weitere bekannte, noch UNBEHOBENE Risiken: `max(id)` bei Attachment und
   MCP-Anlage; parallele Größen-/Anzahllimits; `NO_EVIDENCE` blockiert aktuell
   auch bei Anhängen/Web/Werkzeugen; Export bei langen ungetrennten Wörtern;
   Websuche-Antwort ist ebenfalls erst nach komplettem Lesen größenbegrenzt.
   Gateway-Nachrichtengrenze mit großem Anhang + RAG gezielt messen.
6. Bestehende E2E-Suiten an neue Navigation anpassen. Dev-H2 mit
   `ddl-auto=update` erzeugt JDBC-only Tabellen nicht; frische E2E-Datenbanken
   über Flyway migrieren, keine bestehenden H2-Dateien blind baselinen.
7. Abschließend vollständige CI einschließlich E2E auf echtem aktuellem
   Commit, Prüflisten/PROJECT_STATE aktualisieren, pushen. Noch KEINE
   Merge-Freigabe behaupten. Reviews separat ausweisen.
