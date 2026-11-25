# TU-04e — Webseitenabruf als Werkzeug

Status: **in Umsetzung (PO-Auftrag 25.11.2026), noch nicht gemergt.** Fünfte und letzte Aufgabe von TU-04 nach TU-04d (PR #32, Merge `ddc05a9`), gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Laden beliebiger Adressen aus dem Internet (SSRF-Fläche), Verarbeitung fremder Inhalte, Modell-/Werkzeugschleife.

## Ziel

Nach TU-04d sieht das Modell von einem Web-Treffer nur Titel, Link und einen kurzen Auszug. Neu soll es eine öffentliche Webseite laden und ihren Text lesen können, zum Beispiel einen Treffer aus `web_search` oder eine Adresse aus der Frage. Interne Netze und Dienste bleiben dabei immer unerreichbar.

## Scope

- Eingebauter Adapter `web` erhält ein zweites Werkzeug `web_fetch(url)` über `ToolExecutionService` (Karten, Limits, Audit, Guard, Recheck). Risikoklasse **READ fest durch den Server**, Ziel **extern**.
- **Freigabe** wie TU-04d: Angeboten wird das Werkzeug nur, wenn zugleich
  1. ein Suchanbieter eingerichtet ist,
  2. der Org-Schalter „Websuche als Werkzeug“ an ist und
  3. die Person für diese Frage „Im Web suchen“ eingeschaltet hat.

  Der Org-Schalter wird nur umbenannt, in „Websuche und Webseitenabruf als Werkzeuge“. Es gibt keine neue Einstellung. Die Bindung an den geprüften Snapshot aus TU-04d gilt auch hier.
- **Netzwerkschutz** in einem eigenen kleinen Abrufer, nicht im Prozess-HTTP-Client der Admin-Ziele:
  - nur `http`/`https`, Ports 80/443, keine Zugangsdaten in der URL;
  - DNS einmal auflösen, **jede** Adresse mit `SsrfGuard.routeAllowed` prüfen und genau zur geprüften IP verbinden (Schutz gegen DNS-Rebinding, TLS mit dem ursprünglichen Hostnamen);
  - Weiterleitungen manuell, höchstens 3, jede neu geprüft.
  - **Gesetzter Standard:** Die Admin-Freigabe `KEPLURA_ADMIN_SSRF_ALLOW_CIDRS` gilt für den Seitenabruf **nicht**. Er erreicht nie private, Loopback-, Link-local-, CGNAT- oder ULA-Adressen. Diese Freigabe bleibt den vom Admin eingerichteten Zielen (Modell, Suche, MCP) vorbehalten.
- **Grenzen:**
  - Zeitlimit 10 s gesamt;
  - höchstens 2 MB gelesen;
  - nur `text/html`, `text/plain` und `application/xhtml+xml`;
  - keine Cookies, kein `Authorization`, fester User-Agent;
  - gesperrte Domains der Websuche gelten auch hier.
- **Inhalt:** HTML mit der vorhandenen Bibliothek (jsoup/Tika, sofern bereits Abhängigkeit) zu Text. Skripte und Styles werden entfernt, Steuerzeichen normalisiert. Ergebnis mit Titel und End-URL, Text ≤ 8 000 Zeichen (`MAX_RESULT_CHARS`), Fremdinhalt-Hinweis. Keine neue Laufzeitabhängigkeit ohne Prüfung (Klasse A).
- **Herkunft:** Seiteninhalt ist externe Quelle wie Web-Treffer. Eine URL, die nach internen Inhalten keine wörtliche Herkunft aus Frage oder externem Ergebnis hat, wird zur Bestätigungskarte (TU-04a/c, private Argumente). Seiteninhalte erweitern keine Rechte.
- **Fehler** (gesperrt, Zeitlimit, zu groß, falscher Typ, HTTP-Fehler) enden als fehlgeschlagene Karte mit festem Text, ohne aufgelöste IP oder Antwortkörper.
- Desktop: nur der umbenannte Schaltertext (de/en).

## Non-Goals

- kein Rendern mit Browser/JavaScript;
- keine PDFs oder Bilder;
- keine Formulare, POST oder Anmeldungen, keine Cookies;
- kein Abruf interner Intranetseiten, auch nicht per Admin-CIDR (eigene PO-Entscheidung, falls gewünscht);
- kein Crawling über mehrere Seiten in einem Aufruf;
- keine neue Einstellung oder Freigabe je Arbeitsbereich;
- keine Schreibwerkzeuge;
- kein Release/Deployment.

## Acceptance Criteria

1. Geskriptetes Gateway: Frage mit „Im Web suchen“ → `web_search` → `web_fetch` auf einen Treffer (lokaler Testserver über den Testpfad) → Abschlussantwort nutzt den Seitentext. Die Karten zeigen die URL.
2. **SSRF:** Sie sind gesperrt, ohne dass eine Verbindung entsteht:
   - Loopback, private, Link-local, CGNAT, ULA, IPv4-mapped IPv6;
   - Dezimal-, Oktal- und Hex-Schreibweisen;
   - `0.0.0.0`, Metadaten-IP `169.254.169.254`;
   - DNS-Rebinding (erste Auflösung öffentlich, zweite intern);
   - Weiterleitung auf ein internes Ziel;
   - nicht erlaubter Port, Zugangsdaten in der URL, nicht erlaubtes Schema.

   Der Test zählt Verbindungen, und auch eine Admin-CIDR-Freigabe öffnet keinen dieser Wege.
3. Grenzen für Größe, Zeit, Inhaltstyp, Weiterleitungszahl und gesperrte Domains werden durchgesetzt. Ein Abbruch bei Überschreitung lädt nicht weiter.
4. Ohne eine der drei Angebotsbedingungen wird `web_fetch` weder angeboten noch ausgeführt (Verbindungen gezählt).
5. Nach internen Inhalten wird eine URL mit internem Text zur Karte. Erst nach Bestätigung wird sie genau einmal abgerufen, die Argumente danach gelöscht.
6. Fehlerkarten, Dialog, Audit und Log enthalten weder aufgelöste interne Adressen noch Antwortkörper oder Header.
7. Budgets und TU-02/TU-03/TU-04a–d-Abläufe, die Vorabsuche und normale Chats funktionieren unverändert.
8. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl; unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Keine Browser-/Feldtests ohne PO-Auftrag.
9. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md aktualisieren: TU-04 abgeschlossen, nächster Schritt TU-05.

## Tests

Lokaler HTTP-Testserver und ein einstellbarer DNS-Resolver im Abrufer, damit Rebinding und Weiterleitungen deterministisch nachgestellt werden können. Tests gegen den echten zentralen Dienst mit H2 (Muster der TU-04a–d-Tests). Volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-web-fetch` ab aktuellem main. Implementierung und unabhängige Prüfung nach PO-Zuweisung (TU-04c/d: Claude implementiert, Codex prüft). Genau eine Implementierungsaufgabe. Draft-PR, kein Merge durch den Entwickler.
