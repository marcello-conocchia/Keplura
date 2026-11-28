# TU-06 — OpenAPI-Adapter, persönliche OAuth-Verbindungen, isolierter MCP-stdio-Adapter, Bild- und Dateiergebnisse

Status: **abgeschlossen auf main, PR #39, Squash-Merge 9364c77 (28.11.2026)**, ohne TU-06c (zurückgenommen, eigene Aufgabe [TU-06c](TU-06c-isolated-stdio-bridge.md)). Claude implementiert, Codex prüft unabhängig (Code + Security). Abweichungen, Review-Korrekturen und offene Punkte stehen unten unter „Umsetzung“. Ursprünglicher Status: spezifiziert, nicht gestartet. Nächster Schritt nach TU-05 (PR #37, Merge 88e3e60), gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). PO-Entscheidung vom 26.11.2026 im Chat: „ja spezifiziere TU-06 alles in einem PR“. Umsetzung als **ein PR mit einem Commit je Teilschritt** (Regel aus TU-05b–d): eine Spec, ein CI-Lauf am Ende, eine Review-Runde. Implementierung und unabhängige Prüfung nach PO-Zuweisung.

Klasse A in jedem Teilschritt: neue externe Ziele (SSRF-Fläche), Zugangsdaten und Tokens, Prozessisolation, Verarbeitung und Auslieferung fremder Binärdaten.

## Ziel

Neue Anbindungen laufen über den vorhandenen kontrollierten Weg von `ToolExecutionService`, ohne Sonderwege im Kern: Freigabe, Risikoklasse, Schema, Karten, Limits, Audit, Herkunftsprüfung, Chat-Auswahl und Modellvorgaben. Zugangsdaten gelangen nie in den Modellkontext, in Karten, Audit oder Logs. Bild- und Dateiergebnisse werden sicher gespeichert und angezeigt.

## Gemeinsame Regeln für alle Teilschritte

- Jedes neue Werkzeug ist ein `ToolDefinition` eines Adapters. Unklassifizierte oder nicht freigegebene Werkzeuge bleiben gesperrt. Risikoklassen setzt nur der Admin, nie die Gegenstelle (keine Ableitung aus HTTP-Methode, `readOnlyHint` o. ä.).
- Ziele sind Admin-Ziele: Prüfung mit `SsrfGuard.routeAllowed` (Admin-CIDR-Freigabe gilt wie bei Modell, Suche und MCP), keine Weiterleitungen, feste Zeit- und Größenlimits, keine Proxys aus der Umgebung.
- Fingerprint je Werkzeug über Ziel, Zugang und Definition. Ändert sich eines davon, gilt die Definition als neu und alte Karten werden nicht ausgeführt.
- Arbeitsbereichszuordnung und Org-Grenze (`internal`) wie bei MCP (`mcp_server_space`, `mcp_org_boundary`), damit Herkunftsprüfung und Offenlegungskarten unverändert greifen.
- Fehlertexte an Modell und Karten sind fest und enthalten weder Adressen noch Antwortkörper oder Header.
- Keine neue Laufzeitabhängigkeit ohne Prüfung. Bevorzugt werden vorhandene Bausteine (Jackson, Spring Security OAuth2/nimbus aus ADR-0009, `SecretCipher`, vorhandener HTTP-Client).

## Teilschritte

### TU-06a OpenAPI-Adapter

- Admin legt einen **OpenAPI-Werkzeugserver** an: Name, URL des OpenAPI-Dokuments, **feste Basis-URL** (die `servers`-Angabe des Dokuments wird ignoriert), Zugang `keiner` / `Bearer-Token (verschlüsselt)` / `persönlich (OAuth, TU-06b)`, Arbeitsbereiche, aktiv.
- Eigene Tabelle analog `mcp_server` (Dokument-Snapshot, freigegebene Operationen, Risikoklassen, Bereiche). Kein Umbau der MCP-Tabellen.
- Unterstützt: OpenAPI 3.0/3.1 als **JSON**, höchstens 1 MB, nur lokale `$ref` (`#/components/...`), keine externen Referenzen. YAML nur, wenn ein Parser schon Abhängigkeit ist. Sonst ist es ein Non-Goal, und die Oberfläche sagt das klar.
- Jede Operation mit `operationId` wird ein Werkzeug (ohne `operationId`: übersprungen und in der Admin-Ansicht als „nicht nutzbar“ gezeigt). Funktionsname eindeutig und gekürzt wie bei MCP (`o<id>__<operationId>`).
- Parameter: Pfad- und Query-Parameter sowie JSON-Body werden zu **einem** Eingabeschema zusammengeführt. Header-, Cookie- und Formularparameter sowie Datei-Uploads werden nicht unterstützt (Operation gesperrt, Grund sichtbar). Schemas außerhalb der Teilmenge von `ToolArgumentValidator` sperren die Operation.
- Aufruf: genau die Methode und der Pfad aus dem Snapshot, Pfadparameter URL-kodiert (kein `..`, kein `/`), Antwort ≤ 2 MB, Zeitlimit 20 s, `Accept: application/json, text/plain`. Ergebnis als Text ≤ `MAX_RESULT_CHARS`. Bild- und Dateiantworten folgen in TU-06d.
- „Aktualisieren“ lädt das Dokument neu. Geänderte Operationen verlieren ihre Freigabe (neue Prüfung durch den Admin), wie bei MCP.

### TU-06b Persönliche OAuth-Verbindungen

- Für MCP- (HTTP) und OpenAPI-Server kann der Admin den Zugang „persönlich“ wählen: OAuth 2.1 **Authorization Code + PKCE (S256)**. Der Admin trägt Autorisierungs- und Token-Endpunkt, Client-ID, optional Client-Secret (verschlüsselt) und Scopes ein. Beide Endpunkte sind Admin-Ziele (SSRF-Prüfung).
- Jede Person verbindet sich selbst unter „Verbindungen“. Der Server startet den Ablauf mit `state` und PKCE, gebunden an Sitzung, Person und Server. Der Callback landet fest auf dem Keplura-Ursprung (`/api/v1/oauth/connections/callback`). **Geändert 27.11.2026 (PO „Wie OIDC-Login“, Review-Befund 1):** Die Anmeldung läuft im App-Fenster selbst (Navigation wie beim OIDC-Login, ADR-0009). Der Callback leitet mit einem Einmal-Code im Fragment auf die feste App-Adresse zurück. Nur die startende Sitzung derselben Person löst ihn ein. Ein weitergegebener Anmeldelink bindet damit kein fremdes Konto.
- Tokens liegen **je Person** verschlüsselt (`SecretCipher`) in einer eigenen Tabelle, gebunden an Person, Server und Serverkonfiguration (Revision). Der Server erneuert Refresh-Tokens selbst. Nie im Modellkontext, in Karten, Audit, Logs oder API-Antworten. Es wird nie ein Token zwischen Personen geteilt.
- Ohne eigene gültige Verbindung wird das Werkzeug dieser Person **nicht angeboten**. Das Werkzeugmenü zeigt „Verbindung nötig“ mit Link zur Verbindungsseite. Ein Token, der während einer Frage abläuft und sich nicht erneuern lässt, beendet den Aufruf mit fester Fehlerkarte, ohne Wiederholung.
- „Trennen“ löscht die Tokens der Person sofort (und, wenn angeboten, ruft den Revocation-Endpunkt auf). Ändert der Admin Endpunkte, Client oder Scopes oder löscht den Server, werden alle Verbindungen dieses Servers ungültig. Wer die Organisation verlässt oder deaktiviert wird, verliert seine Verbindungen.
- Admin-Ansicht zeigt nur die Zahl verbundener Personen, nie Tokens oder Namen der Gegenstelle-Konten.

### TU-06c Isolierter MCP-stdio-Adapter

> **Aus TU-06 herausgenommen (PO 27.11.2026, Review-Befund 8).** Neubau mit stärkerer Isolation als eigene Aufgabe [TU-06c](TU-06c-isolated-stdio-bridge.md). Der folgende Abschnitt beschreibt die erste Fassung.

- stdio-MCP-Server laufen **nie im Keplura-Serverprozess und werden nie über die Web-Oberfläche als Befehl eingetragen**. Das wäre eine Befehlsausführung aus der Weboberfläche.
- **PO-Entscheidung 26.11.2026 (Chat: „Bridge im Container“):** eine eigene **Bridge** im Server-Jar (`--mcp-stdio-bridge`), die in einem **eigenen Container** des Deployments läuft:
  - Der Betreiber deklariert die stdio-Server ausschließlich in einer Konfigurationsdatei des Deployments (Befehl als Argumentliste, keine Shell, feste Umgebungsvariablen, Arbeitsverzeichnis).
  - Die Bridge startet je Server einen Prozess und stellt ihn als **Streamable HTTP** im internen Netz bereit.
  - Isolation: eigener Nicht-root-Benutzer, nur lesbares Dateisystem bis auf ein Arbeitsverzeichnis, kein ausgehendes Netz außer ausdrücklich freigegebenen Zielen, CPU-/Speicher-/Prozesslimits über den Container, Zeitlimit je Aufruf, begrenzte Ausgabe, Neustart nach Absturz, Umgebung ohne Keplura-Geheimnisse.
  - Die Bridge verlangt ein gemeinsames, zufälliges Token zwischen Keplura und Bridge (verschlüsselt wie MCP-Tokens).
- Keplura verbindet sich über den **vorhandenen MCP-HTTP-Adapter**. Der Admin trägt die Bridge-Adresse wie einen MCP-Server ein. Sie ist standardmäßig als internes Ziel markiert, mit allen bestehenden Freigaben und Risikoklassen je Werkzeug. Im Kern gibt es keinen neuen Aufrufweg.
- Deployment: zusätzlicher, **standardmäßig deaktivierter** Dienst in `deploy/docker-compose.yml` bzw. `compose.prod.yml` mit Beispielkonfiguration und Doku in `deploy/README.md`.

### TU-06d Bild- und Dateiergebnisse

- MCP-Inhalte `image` und eingebettete `resource`-Blobs sowie OpenAPI-Antworten mit Binärtyp werden **Artefakte** einer Aufrufkarte. Erlaubt sind nur `image/png`, `image/jpeg`, `image/webp`, `image/gif`, `application/pdf`, `text/csv`, `text/plain` und `application/json`. **SVG, HTML und alles Ausführbare sind ausgeschlossen.**
- Typ nach **Magic Bytes** geprüft (die angegebene Content-Type-Angabe reicht nicht). Höchstens 4 Artefakte je Aufruf, 5 MB je Artefakt, 10 MB je Frage, Bilder höchstens 40 Megapixel. Bei Überschreitung wird das Artefakt verworfen und auf der Karte vermerkt.
- Speicherung in einer eigenen Tabelle (Karte, Typ, Größe, SHA-256, Bytes). Löschen mit Chat und Karte. Artefakte von internen Zielen gelten als interne Inhalte.
- Auslieferung nur über einen authentifizierten Endpunkt mit Eigentümerprüfung (Person, Org, Bereich, Chat):
  - fester Content-Type aus der Prüfung, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox; default-src 'none'`, `Cache-Control: private, no-store`;
  - Nicht-Bilder immer als `Content-Disposition: attachment`.
- Desktop/Web: Bilder als Vorschau in der Karte, Dateien als Download. Kein Rendern von HTML.
- Das Modell bekommt **nur einen Text-Platzhalter** (Typ, Name, Größe), keine Binärdaten und keine Bildübergabe an das Modell.

## Non-Goals

- keine dynamische Client-Registrierung und keine automatische OAuth-Erkennung (RFC 8414/9728). Endpunkte trägt der Admin ein. Kein Weiterreichen des Keplura-Login-Tokens („System OAuth“).
- keine von Personen selbst eingetragenen Werkzeugserver; nur Admin-Server;
- keine stdio-Server-Eingabe über die Web-Oberfläche, keine Prozessstarts im Serverprozess;
- kein HTML-/SVG-Rendern, keine interaktiven Visualisierungen (TU-07), keine Bildübergabe an das Modell (Vision);
- kein Code Interpreter, keine Bildgenerierung (TU-07);
- keine Änderung an Risikoklassen-, Bestätigungs-, Herkunfts- oder Budgetregeln;
- kein Release/Deployment.

## Acceptance Criteria

1. **OpenAPI:** Ein Testserver mit OpenAPI-Dokument liefert Werkzeuge nur für Operationen mit `operationId`. Nicht unterstützte Parameterarten sperren die Operation sichtbar. Ein Aufruf geht genau an Basis-URL + Pfad aus dem Snapshot (gezählt), nie an `servers` aus dem Dokument. Pfad-Traversal über Parameter ist unmöglich. Ohne Admin-Freigabe und Risikoklasse wird nichts angeboten oder ausgeführt.
2. **OAuth:** Ablauf mit `state` und PKCE gegen einen lokalen Test-Autorisierungsserver. Falscher `state`, falscher Benutzer, wiederverwendeter Code und abgelaufene Sitzung werden abgelehnt. Die Tokens liegen nur verschlüsselt vor, Person A sieht und nutzt nie die Tokens von Person B. Ohne Verbindung wird nichts angeboten. Erneuern, Trennen und Admin-Änderung wirken sofort. Tokens tauchen weder in Modellanfragen noch in Karten, Audit, Logs oder API-Antworten auf (gezielt geprüft).
3. **stdio:** Die Bridge startet nur deklarierte Befehle ohne Shell, läuft als Nicht-root, ohne Keplura-Geheimnisse in der Umgebung, und beendet hängende Aufrufe nach dem Zeitlimit. Keplura erreicht sie nur mit dem gemeinsamen Token. Der Keplura-Serverprozess startet keinen Prozess dafür (geprüft).
4. **Artefakte:** Erlaubte Typen werden gespeichert und angezeigt. SVG/HTML, falscher Typ laut Magic Bytes und Größen- und Anzahlgrenzen werden verworfen. Eine fremde Person, Org oder ein fremder Chat bekommt 404. Die Antwort-Header sind wie oben gesetzt. Das Modell sieht nur Platzhalter.
5. Bestehende Abläufe (TU-01 bis TU-05: Karten, Bestätigungen, Fortsetzungen, Herkunft, Chat-Auswahl, Modellvorgaben, Tool-Suche, Rückfragen) bleiben unverändert grün.
6. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl. Unabhängiges Code- und Security-Review gemäß AGENTS.md.
7. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md aktualisieren: TU-06 abgeschlossen (ohne TU-06c), nächster Schritt [TU-06c](TU-06c-isolated-stdio-bridge.md) (Neubau der stdio-Bridge, PO 27.11.2026), danach TU-07 (erste einzelne Fähigkeit).

## Tests

- Lokale HTTP-Testserver für OpenAPI und den OAuth-Autorisierungsserver, mit Verbindungszählern.
- Tests gegen den echten zentralen Dienst mit H2 (Muster TU-04/TU-05), dazu ein PostgreSQL-Probe-Test für die Artefakt-Tabelle.
- Die Bridge wird mit einem kleinen deklarierten Testprozess getestet: Echo, Hänger, große Ausgabe.
- Desktop: Typprüfung und Übersetzungsprüfung im vorhandenen Build.
- Volle CI einmal am Ende. Keine Browser- oder Feldtests ohne PO-Auftrag.

## PO-Entscheidungen

- **26.11.2026, stdio-Isolation (TU-06c):** „Bridge im Container“. stdio-Server laufen in einer eigenen Bridge im separaten Container, deklariert vom Betreiber im Deployment und angebunden über den vorhandenen MCP-HTTP-Adapter. Verworfen wurde die Alternative, Prozesse aus dem Keplura-Server in einer Sandbox (z. B. bubblewrap) zu starten. Die Regel „keine Prozessstarts im Serverprozess“ bleibt damit unverändert.
- **27.11.2026, OAuth-Ablauf (TU-06b, Review-Befund 1, Blocker):** „Wie OIDC-Login“. Die Anmeldung läuft im App-Fenster, die Rückkehr erfolgt mit einem Einmal-Code, den nur die startende Sitzung einlöst. Verworfen: Systembrowser mit Loopback-Rückleitung (RFC 8252) und „nur im Web“. Folge: TU-06e entfällt. Bekannte Grenze: Anbieter, die eingebettete App-Fenster verweigern (z. B. Google).
- **27.11.2026, stdio-Bridge (TU-06c, Review-Befund 8, Major):** „Aus #39 herausnehmen“. TU-06c wird zurückgenommen und als eigene Aufgabe mit stärkerer Isolation und Nachweis im echten Container neu gebaut. Verworfen: Nachbessern in #39 und Annahme als Restrisiko.
- **27.11.2026, Systembrowser in der Desktop-App (TU-06b, überholt durch den Eintrag zum OAuth-Ablauf):** „Eigener PR nach #39“. TU-06 bleibt ohne neue Abhängigkeit: Die Desktop-App zeigt den Anmeldelink zum Öffnen oder Kopieren. Das Öffnen im Systembrowser über `tauri-plugin-opener` folgt als eigene Klasse-A-Aufgabe [TU-06e](TU-06e-desktop-system-browser.md) nach dem Merge von TU-06. Verworfen wurden ein fünfter Commit in #39 und der dauerhafte Verzicht.

## Umsetzung (26.11.2026, Prüfung offen)

- **TU-06a:** Tabellen `openapi_server`, `openapi_server_space`, `openapi_org_boundary` (V35). Parser nur JSON (kein YAML-Parser als Abhängigkeit vorhanden, daher Non-Goal, in der Oberfläche benannt). Fingerprint je Werkzeug enthält zusätzlich die genaue Operation (Methode, Pfad, Parameter, Schema), damit eine geänderte Methode nie unter alter Freigabe läuft. Antworten ≤ 2 MB Text bzw. ≤ 5 MB Binär (TU-06d).
- **TU-06b:** Tabellen `tool_oauth_client`, `tool_oauth_connection`, `tool_oauth_flow` (V36). Verbindungen sind an Person **und Organisation** gebunden. Der Callback nimmt nur den Code an; eingelöst wird er durch die startende Sitzung beim Statusabruf (Bindung an Sitzung, Person, Org, Server, Client-Revision). Ein Admin-Token eines persönlichen Servers dient nur dem Laden der Werkzeugliste bzw. des Dokuments. Keplura kennt keine Kontodeaktivierung; Austritt und Entfernen aus der Organisation löschen die Verbindungen, ohne Mitgliedschaft ist eine Verbindung sofort unbrauchbar. Desktop-App: Ohne zusätzliches Tauri-Plugin (keine neue Abhängigkeit) wird der Anmeldelink angezeigt und kann kopiert werden; im Web öffnet er sich in einem neuen Tab. Das Öffnen im Systembrowser folgt mit TU-06e (PO 27.11.2026). Restrisiko: Wer seinen Anmeldelink an eine andere Person weitergibt, kann deren Konto an sich binden, wenn deren Browser keine Keplura-Sitzung hat (mit fremder Keplura-Sitzung wird abgelehnt).
- **TU-06c:** Bridge-Modus `--mcp-stdio-bridge` ohne Spring, Konfiguration `deploy/mcp-bridge/servers.json`, Dienst `mcp-bridge` (Profil, standardmäßig aus) in `deploy/compose.prod.yml`, internes Netz ohne Ausgang, Doku `deploy/README.md` Abschnitt 10. Abweichung: statt eines Dauerprozesses je Server startet die Bridge **je MCP-Sitzung einen frischen Prozess** (keine geteilten Zustände zwischen Aufrufen oder Personen; „Neustart nach Absturz“ ergibt sich je Sitzung). MCP-Einträge mit „isolierte stdio-Bridge“ (V37) gelten standardmäßig als internes Ziel. Probe mit echtem stdio-Server (`@modelcontextprotocol/server-filesystem`) aus dem gebauten Jar als Nicht-root: `ci/evidence/20261127-bridge-smoke/` (ohne Compose-Start).
- **API-Vertrag:** `contracts/openapi.yaml` um die neuen Endpunkte und `ChatToolSelection.connectionsRequired` ergänzt (validiert als OpenAPI 3.1).
- **TU-06d:** Tabelle `tool_artifact` (V37, nach Rücknahme von TU-06c von V38 verschoben), Prüfung nach Magic Bytes, Download `GET /api/v1/orgs/{org}/chat/conversations/{chat}/tool-artifacts/{id}`. Die Antwortobergrenze für MCP-`tools/call` steigt dafür von 2 MB auf 16 MB.

## Review-Runde 1 (Codex, 27.11.2026: REQUEST CHANGES) und Korrekturen

| Befund | Schwere | Korrektur |
|---|---|---|
| 1 Weitergegebener OAuth-Link bindet fremdes Konto | Blocker | Anmeldung im App-Fenster wie OIDC-Login. Callback → 302 auf die feste App-Adresse mit `#oauth=<Einmal-Code>`. Einlösen nur durch die startende Person **und** Sitzung über `POST /api/v1/orgs/{org}/connections/complete`, genau einmal. Der Statusabruf tauscht nichts mehr. Regressionstest: Link in einem anderen, abgemeldeten Browser geöffnet → keine Verbindung, kein Tausch. |
| 2 Wettläufe bei Trennen, Konfigurationsänderung und Erneuerung | Major | Abschluss in einer Transaktion: Flow muss noch aktiv sein, Client-Zeile gesperrt, gleiche Generation. Trennen und Admin-Änderungen löschen Flows zuerst. Erneuerung liest Token und genau die zugehörige Client-Konfiguration in einer Abfrage. `revision` ist eine zufällige Generation, die Löschen und Neuanlage übersteht. Tests mit angehaltenem Tausch bzw. angehaltener Erneuerung. |
| 3 Entfernen des MCP-OAuth-Clients aktiviert den Admin-Schlüssel | Major | `mcp_server.auth_mode` (`shared`/`personal`) gespeichert (V36). „persönlich“ ohne Client schlägt geschlossen fehl. Zurück zu „shared“ nur über „Ausschalten“, das alle Freigaben und Risikoklassen des Servers löscht. |
| 4 OAuth-Zugangsdaten über HTTP | Major | `OAuthTransportPolicy`: Autorisierungs-, Token- und Widerrufs-Endpunkt sowie Serveradressen persönlicher Server nur `https://`, geprüft beim Speichern und vor jedem Versand. Ausnahme nur per Testeinstellung `keplura.tools.oauth.allow-insecure-loopback-http` und nur für Loopback-IP-Literale (Standard aus). |
| 5 Artefakte umgehen die Token-Schwärzung | Major | Dateinamen erzeugt Keplura selbst (`ergebnis-N.<typ>`), Ressourcen-URIs werden nie verwendet. Dateien, deren Bytes das gesendete Token enthalten, werden vor dem Speichern verworfen (MCP und OpenAPI). Leck-Tests für URIs und Dateiinhalte. |
| 6 OpenAPI-/OAuth-Verbindung nicht an geprüfte IP gebunden | Major | Neuer Baustein `PinnedHttp`: einmal auflösen, jede Antwort prüfen, genau zur geprüften Adresse verbinden, TLS mit dem ursprünglichen Hostnamen. Genutzt von `OpenApiClient` und `OAuthTokenClient`. Test mit wechselnder DNS-Antwort. |
| 7 OpenAPI-Pfad und `operationId` | Major | Pfadvorlagen nur aus einfachen Pfadzeichen, ohne `%`, `;` oder Punkt-Segmente. Die fertige URL muss nach Normalisierung und doppelter Dekodierung unter dem Basis-Pfad bleiben. `operationId` mit Steuerzeichen ist nicht nutzbar, Freigaben filtern solche Namen. |
| 8 Bridge-Isolation | Major | TU-06c aus #39 zurückgenommen (PO), eigene Aufgabe [TU-06c](TU-06c-isolated-stdio-bridge.md). |
| 9 Download ohne HTTP-Integrationstest | Minor | `ToolArtifactDownloadIntegrationTest` über echtes HTTP: anonym 401, fremde Person und fremde Organisation 404, Sicherheits-Header an der echten Antwort. |

Offen bleibt: Der MCP-HTTP-Client (seit TU-01 auf main) verbindet weiterhin ohne IP-Bindung (dokumentierter TOCTOU-Hinweis in `SsrfGuard`). Persönliche Tokens gehen dort nur noch über HTTPS. Die IP-Bindung für MCP ist eine Folgeaufgabe. Ein echter OAuth-Anbieter ist weiterhin nicht getestet.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-tu06` ab aktuellem main. Ein Commit je Teilschritt in der Reihenfolge TU-06a, TU-06b, TU-06c (zurückgenommen), TU-06d, danach Review-Korrekturen. Draft-PR, kein Merge durch den Entwickler.
