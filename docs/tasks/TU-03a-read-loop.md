# TU-03a — Begrenzte mehrstufige Lesezugriffe

Status: **implementiert im Draft-PR**, Branch `feat/tool-use-read-loop`, nicht gemergt. Linux-CI Exit 0 auf Produktcommit `35fa59f`, Evidence `ci/evidence/20261121-184918/`; unabhängiges Code-/Security-Review durch Claude offen. Nächste einzelne Aufgabe nach TU-02 (PR #17, Merge `8f6852a`). Erster Umsetzungsschritt von TU-03 gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Modell-/Werkzeugschleife, externe Aufrufe und Rechte.

## Ziel

Das Modell kann ein Werkzeugergebnis auswerten, gezielt mit freigegebenen Lese-Werkzeugen nachfragen und danach antworten: Suchen → Detail lesen → Nachsuchen → Antwort. Werkzeugergebnisse sind Daten und autorisieren keine neuen Schreibaktionen.

## Scope

- Begrenzte Modell-/Tool-Schleife statt der bisherigen einen Werkzeugrunde. Alle Aufrufe bleiben bei ToolExecutionService und erzeugen bestehende Aufrufkarten; Kontext, Mitgliedschaft, Freigabe, Schema und Ziel vor jedem Aufruf neu prüfen.
- Ein einziges zusammenhängendes Budget je ursprünglicher Nutzerfrage: höchstens fünf Modellanfragen einschließlich Abschlussantwort, acht Werkzeugversuche insgesamt (auch gesperrte), bestehende 120 s Werkzeugbudget und 16 000/8 000 Zeichen für Eingaben/Ergebnisse. Kein Zurücksetzen durch neue Runden, neue ToolTurn-Instanzen oder Bestätigungen. Beim Limit verständlich beenden; keine zusätzliche ungezählte Modellanfrage.
- Nach Ergebnissen darf der Server ausschließlich freigegebene READ-Werkzeuge für automatische Folgeaufrufe zulassen. WRITE/CRITICAL dürfen nicht aus Tool-Ergebnissen, Dokumenten oder altem Verlauf neu autorisiert werden: Folgeaufrufe serverseitig sperren, nicht nur im Prompt verbergen. Bestehende initiale WRITE-Freigaben und konkrete CRITICAL-Bestätigungen aus TU-02 nicht abschwächen oder umgehen.
- Der initiale Aktionsplan bleibt an den aktuellen Nutzerauftrag gebunden. Katalog, Schema, Risikoklasse, Ziel und Limits kommen vom Server, nicht vom Modell oder Tool-Ergebnis. Fremdinhalte bleiben entsprechend gekennzeichnet; keine Modell-Instruktionen aus Ergebnissen ausführen.
- Sobald ein Turn auf konkrete CRITICAL-Bestätigung wartet, keine automatische Fortsetzung nach dieser Entscheidung in diesem Schritt. Ergebnis bleibt in der Karte; abgeschlossene Ergebnisse dürfen bei einer nächsten expliziten Nutzerfrage als begrenzte, berechtigte Belegdaten berücksichtigt werden. Keine unbemerkte Wiederaufnahme einer alten Schleife.
- Stop, neue Frage und Rechteentzug verhindern weitere noch nicht gestartete Runden/Aufrufe. Bereits gestartete externe Aktionen nicht als rückgängig gemacht darstellen. Fehlgeschlagene oder unklare Aufrufe niemals automatisch wiederholen; normaler Fallback für Modelle ohne Tool Calling nur vor jeglicher Ausführung.
- Den vorbestehenden QA-Folgepunkt aus TU-02 im selben regulären Chat-PR beheben: Nach Stop wird auch die gepufferte Modellantwort eines Werkzeug-Turns als gestoppt/abgebrochen gespeichert und angezeigt, statt als vollständig. Kartenstatus und Antwortstatus nicht verwechseln.
- Vorhandene Karten und deutsch-/englischsprachige Fehler-/Abbruchtexte weiterverwenden; keine neue UI-Richtung oder Anzeige interner Modellüberlegungen.

## Non-Goals

Keine neuen eingebauten Suchwerkzeuge (TU-04), Tool-Suche/Rückfragen (TU-05), neuen Adapter, Skills, Unteragenten, parallele Hintergrundagenten oder beliebige Aktionsketten. Keine automatische Wiederaufnahme nach CRITICAL-Bestätigung; dafür bleibt ein weiterer begrenzter TU-03-Schritt offen. Kein Release/Deployment.

## Acceptance Criteria

1. Ein geskriptetes Gateway kann in derselben Nutzerfrage erst READ-Suche, dann READ-Detailabruf mit einer ID aus dem ersten Ergebnis anfordern und anschließend eine passende Antwort liefern. Karten und Ergebniszuordnung bleiben korrekt.
2. Ein manipuliertes Ergebnis, das WRITE/CRITICAL-Folgeaktionen verlangt, erzeugt keinen solchen externen Aufruf; die Sperre bleibt auch bei missachteter Prompt-Anweisung wirksam. Modell/Tool können keine Freigabe oder Risikoklasse erweitern.
3. Schleifen, große Ergebnisse und gesperrte Versuche überschreiten weder Rundenzahl noch Gesamtaufruflimit. Werkzeugzeit bleibt derselben Frage zugeordnet; bekannte weiche Grenze eines bereits laufenden Aufrufs transparent halten.
4. Neue Frage, Stop, entzogene Rechte/Freigaben und geänderte Definitionen/Ziele verhindern weitere betroffene Aufrufe. Gepufferte gestoppte Modellantwort wird als abgebrochen persistiert und angezeigt.
5. Keine Wiederholung nach möglicherweise erfolgter externer Wirkung; keine erneute Modellschleife durch Replay einer Entscheidung oder durch einen Reload. Bestehender einmaliger CRITICAL-Bestätigungsweg bleibt erhalten.
6. Geheimnisse bleiben beim Adapter; nur berechtigte und begrenzte Ergebnisdaten gelangen in Modellkontext/Karten. Normale Chats und Modelle ohne Tool Calling funktionieren weiterhin.
7. Gezielte Tests und einmal lokale Linux-CI am Ende als Nicht-root mit acl; unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Keine Browser-/Feldtests ohne PO-Auftrag.
8. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md mit tatsächlichem Teilumfang, Nachweisen und kleinstem verbleibendem TU-03-Schritt aktualisieren. TU-03 nicht allein wegen TU-03a vollständig markieren.

## Tests

Geskriptetes Gateway und lokaler Fake-Adapter/MCP-Server mit Aufrufzähler für mehrstufigen READ-Erfolg, verbotene Folgeaktionen aus Fremdinhalt, Runden-/Aufruf-/Zeitlimits, Stop/neue Frage während langsamer Runde und Fehler ohne Wiederholung. Bestehende Kontext-, CSRF-, Bestätigungs- und Kartenregressionen gezielt erhalten. Keine zusätzlichen Testsuiten oder Browserkampagne ohne Anlass; volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-read-loop` ab main `b1c4b08`. PO-Rollentausch vom 21.11.2026: Codex implementiert, Claude prüft unabhängig Code und Sicherheit. Kein bestehender TU-03a-Branch oder Implementierungs-PR beim Start gefunden. Autoritatives Hermes-Kanban von diesem Windows-Host nicht erreichbar (lokales Board `intracore` existiert nicht); kein Ersatzboard angelegt. Genau eine Implementierungsaufgabe. Draft-PR, kein Merge durch den Entwickler; Provider-/Budgetregeln aus AGENTS.md gelten.

## Umsetzung und Nachweise (21.11.2026)

- ChatToolLoop: maximal fünf Modellanfragen, eine gemeinsame ToolTurn-Instanz mit acht Versuchen; Abschluss ohne Werkzeuge. Erster Plan nur aus aktuellem Auftrag, Folgekontext mit Belegen und ausschließlich READ-Angebot. Irreversible READ-Sperre zusätzlich im zentralen Ausführungsdienst, auch gegen vom Modell trotzdem gelieferte WRITE-/CRITICAL-Aufrufe.
- Stop, neue Frage und Rechteentzug werden vor jeder Runde, nach Modellantwort und vor Werkzeugausführung geprüft. Gepufferte gestoppte Antworten werden als aborted persistiert. Kritische Bestätigungen und fehlgeschlagene Aufrufe schließen die automatische Schleife; keine automatische Wiederaufnahme nach Bestätigung.
- Höchstens acht frühere bestätigte Ergebnisse (insgesamt 16 000 Zeichen) bei neuer expliziter Frage als Belege, nur bei unverändertem Zugriff/Definition/Ziel. Nicht im ersten Aktionsplan.
- ChatToolLoopTest: 17 geskriptete Fälle mit echtem zentralem Dienst/Karten/Audit vor gezähltem Fake-Adapter. ChatApiIntegrationTest: HTTP-Regressionen plus Stop vor Relay. ToolConfirmationTest nutzt echte Frage-IDs statt einer ungespeicherten Fixture-ID.
- Gezielte Windows-Prüfungen grün: betroffene Chat-/Tool-Klassen, zusätzlich Desktop-Build und 13 Node-Tests. Verbindliche Linux-CI auf 35fa59f als ci (UID 1500), acl installiert, Linux-Dateisystem: 85 Klassen genau einmal, 673 Tests, 0 Failures/Errors, 1 Skipped; Rechte-Helfer, Desktop-Build und cargo check PASS. E2E nicht ausgewählt; kein echter Modell-/Browser-/Release-Nachweis.
- Keine neue Abhängigkeit, Migration oder Produktoberfläche. Weiter offen: unabhängige Prüfung durch Claude, gegebenenfalls vom PO beauftragte gezielte Browser-QA. Nach diesem grünen Produktstand nur Nachweise/Dokumentationsstatus ergänzt; kein erneuter CI-Lauf dafür.
