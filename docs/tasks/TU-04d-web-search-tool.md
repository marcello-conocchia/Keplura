# TU-04d — Websuche als Werkzeug

Status: **abgeschlossen** — PR #32, Squash-Merge `ddc05a9` (24.11.2026); Claude implementiert, Codex-Freigabe Code/Security auf `2fffa59`. Zusätzlich aus der Prüfung: Ausführung an den geprüften Anbieter-Snapshot gebunden, Anfragen über 400 UTF-16-Einheiten abgelehnt. Vierte Aufgabe von TU-04 nach TU-04c (PR #30, Merge `35fa2d2`), gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Anfragen an externe Hosts (Suchanbieter), Modell-/Werkzeugschleife. Der Webseitenabruf mit SSRF-Schutz folgt als eigener letzter Schritt (TU-04e), nicht hier.

## Ziel

Heute sucht „Im Web suchen“ genau einmal vor der Antwort mit dem wörtlichen Prompt (`ChatService.beginTurn` → `WebSearchService.search`). Das Modell kann nicht selbst nachsuchen, eine Suchanfrage umformulieren oder nach einem ersten Treffer gezielt weitersuchen. Neu soll das Modell innerhalb der bestehenden Werkzeugschleife selbst Websuchen ausführen können, mit Zitaten der Web-Treffer.

## Scope

- Eingebauter Adapter `web` mit einem Werkzeug `web_search(query)` über `ToolExecutionService` (Karten, Limits, Audit, Guard, Recheck). Risikoklasse **READ fest durch den Server**, Ziel **extern**. Er nutzt den bestehenden `WebSearchService` mit Anbieter, gesperrten Domains, Ergebniszahl, Zeitlimit und `SsrfGuard` aus der bestehenden Admin-Einstellung. Kein neuer Anbieter und kein neuer Netzwerkpfad.
- **Freigabe:** neuer Org-Schalter „Websuche als Werkzeug“ (OWNER, Revision und Audit wie der Wissensschalter aus TU-04a), **standardmäßig aus**. Additive Migration (z. B. `org_tool_setting.web_search_enabled`). Angeboten wird das Werkzeug nur, wenn zugleich
  1. ein Suchanbieter konfiguriert ist,
  2. der Org-Schalter an ist und
  3. die Person für diese Frage „Im Web suchen“ eingeschaltet hat.

  Ohne den Schalter in der Frage geht keine Anfrage nach außen.
- `query` 1–400 Zeichen (Grenze von `WebSearchService`), serverseitig geprüft. Höchstens die eingestellte Trefferzahl (≤ 10), Titel/URL/Auszug gekürzt. Gesamtergebnis ≤ 8 000 Zeichen und Werkzeugbudgets wie bisher.
- **Herkunft:** Web-Treffer sind externe Ergebnisse. Ihr kanonischer Text darf in Folgeaufrufen automatisch verwendet werden (TU-04a). `web_search` ist ein externer READ-Aufruf. Nach internen Inhalten (Wissenssuche, Dokumentstellen, Anhänge) läuft eine Suchanfrage ohne wörtliche Herkunft aus Frage oder externem Ergebnis nie automatisch, sondern wird zur Bestätigungskarte. Dafür gelten die vorhandene Prüfung und die privaten Argumente aus TU-04c (`private_arguments`, atomares Löschen, Bereinigung).
- **Zitate:** Web-Treffer erscheinen als Web-Quellen in den Zitaten der Antwort, im selben Format wie die bestehende Vorabsuche. Treffer sind nicht vertrauenswürdig: Sie erweitern weder Rechte noch Werkzeuge noch Freigaben.
- **Vorabsuche:** Die bisherige einmalige Vorabsuche bei „Im Web suchen“ bleibt unverändert. Das Werkzeug ergänzt sie; Doppelungen werden nicht entfernt.
- Fehler des Anbieters (Zeitlimit, HTTP-Fehler, Anbieter aus) enden als fehlgeschlagene Karte mit festem Text, ohne Anbieter-URL, Schlüssel oder Antwortkörper.
- Desktop: Schalter im bestehenden Org-Werkzeugbereich und bestehende Karten, deutsch/englisch; keine neue UI-Richtung.

## Non-Goals

Kein Webseitenabruf (Laden einer URL; folgt als TU-04e mit eigenem SSRF-Konzept für Weiterleitungen, DNS, Größe und Inhaltstyp). Kein neuer Suchanbieter, keine kostenpflichtigen Dienste ohne PO-Freigabe, keine Freigabe je Arbeitsbereich, keine Änderung an der Vorabsuche oder an der Admin-Einstellung der Websuche. Keine Schreibwerkzeuge, keine Aufbewahrungsrichtlinie für `tool_question`. Kein Release/Deployment.

## Acceptance Criteria

1. Geskriptetes Gateway mit Testanbieter: Frage mit „Im Web suchen“ → `web_search` mit umformulierter Anfrage → zweite `web_search` mit einem Begriff aus dem ersten Treffer → Abschlussantwort zitiert Web-Treffer. Die Karten zeigen die Suchanfragen.
2. Ohne Anbieter, mit ausgeschaltetem Org-Schalter, ohne „Im Web suchen“ in der Frage oder in fremder Org wird `web_search` weder angeboten noch ausgeführt. Es geht keine Anfrage nach außen (Anbieteraufrufe gezählt).
3. Nach einem internen Ergebnis wird eine Suchanfrage mit internem Text zur Karte und läuft erst nach Bestätigung, einmalig. Ihre Argumente werden nach Ende der ausführbaren Lebensdauer gelöscht (wie TU-04c). Eine Anfrage aus Fragetext oder externem Treffertext läuft automatisch.
4. Gesperrte Domains, Trefferzahl, Längen und `query`-Grenzen (inkl. leer, zu lang, nicht endliche Zahlen) werden serverseitig durchgesetzt. Die SSRF-Prüfung des konfigurierten Anbieters gilt unverändert.
5. Anbieterfehler und Zeitüberschreitung ergeben eine fehlgeschlagene Karte mit festem Text. Weder Karte, Dialog, Audit noch Log enthalten Anbieter-URL mit Schlüssel, API-Schlüssel oder Antwortkörper.
6. Org-Schalter: nur OWNER, Revision, Audit, standardmäßig aus. Die Migration ist additiv und unter H2 und PostgreSQL 16 geprüft.
7. Budgets (5 Modellanfragen, 8 Versuche, 120 s, 16 000/8 000 Zeichen), Stop/neue Frage/Rechteentzug, TU-02/TU-03/TU-04a–c-Abläufe, die Vorabsuche und normale Chats funktionieren unverändert.
8. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl (`id`/`setfacl` im Nachweis); unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Keine Browser-/Feldtests ohne PO-Auftrag.
9. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md mit tatsächlichem Teilumfang und dem letzten TU-04-Schritt (Webseitenabruf, TU-04e) aktualisieren.

## Tests

Geskriptetes Gateway vor dem echten zentralen Dienst mit H2 und einem lokalen Testanbieter (gezählte Aufrufe, Muster der TU-04a–c-Tests):

- Nachsuche
- jede Angebotsbedingung einzeln aus
- Herkunftsprüfung nach Wissens-/Anhangsergebnis mit Karte und gespeicherten Spalten
- gesperrte Domain
- Anbieterfehler und Zeitlimit ohne Datenreste
- Org-Schalter mit Rollen

PostgreSQL-Probe für die Migration. Volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-web-search` ab aktuellem main. Implementierung und unabhängige Prüfung nach PO-Zuweisung (TU-04c: Claude implementiert, Codex prüft). Genau eine Implementierungsaufgabe. Draft-PR, kein Merge durch den Entwickler; Provider-/Budgetregeln aus AGENTS.md gelten.
