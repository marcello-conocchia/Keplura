# TU-03b — Begrenzte Fortsetzung nach konkreter Bestätigung

Status: **geplant, nicht gestartet.** Nächste einzelne Aufgabe nach TU-03a (PR #19, Merge `5c25bfa`). Zweiter Umsetzungsschritt von TU-03 gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Modell-/Werkzeugschleife, Bestätigungen, externe Aufrufe und Rechte.

## Ziel

Nach einer ausdrücklichen Entscheidung zu einer CRITICAL-Karte (Ausführen oder Ablehnen) kann dieselbe Nutzerfrage einmal weiterlaufen: Das Modell sieht das bestätigte Ergebnis oder die Ablehnung, darf mit freigegebenen READ-Werkzeugen nachsehen und gibt eine Abschlussantwort. Heute endet der Turn mit der wartenden Karte, und das Ergebnis fließt erst bei einer neuen Frage ein.

## Scope

- Fortsetzung nur durch die Person selbst: ausgelöst unmittelbar nach ihrer Entscheidung in der Karte, nie durch Reload, Replay einer Entscheidung, einen zweiten Tab, ein Modell- oder Werkzeugergebnis. Höchstens **eine** Fortsetzung je Frage, atomar verbraucht (bedingtes UPDATE wie `claimPending`); eine zweite Anfrage bekommt 409 ohne Modellanfrage.
- Fortsetzung nur, solange die Frage aktuell ist: keine neuere Nutzerfrage, kein Stopp, Kontext, Mitgliedschaft, Freigabe, Definitions-Hash und Ziel unverändert. Sonst keine Modellanfrage und ein verständlicher Hinweis; Karte und Ergebnis bleiben unverändert.
- Dasselbe Budget wie die ursprüngliche Frage, ohne Zurücksetzen: verbleibende Modellanfragen von fünf, verbleibende Versuche von acht, gemeinsame 120 s Werkzeugzeit, 16 000/8 000 Zeichen. Ist das Budget erschöpft, direkt die werkzeuglose Abschlussantwort oder ein klarer Hinweis, keine ungezählte Anfrage. Dafür den Verbrauch der ursprünglichen Schleife serverseitig je Frage festhalten, nicht im Client.
- In der Fortsetzung ausschließlich freigegebene READ-Folgeaufrufe; WRITE/CRITICAL serverseitig gesperrt, auch wenn das Modell sie liefert. Die bestätigte Aktion wird nie erneut geplant oder ausgeführt (Schutz vor doppelten Schreibaktionen); eine neue gleichartige Aktion braucht eine neue Nutzerfrage.
- Kontexttrennung aus TU-03a unverändert: Werkzeugfähige Anfragen sehen nur aktuelle Frage, eigene Schleifenergebnisse und das Ergebnis der entschiedenen Karte. Wissensbasis, Anhänge, Websuche, Verlauf und frühere bestätigte Ergebnisse nur in der werkzeuglosen Abschlussanfrage.
- READ-Versiegelung in den Dienst verlegen (Info-Folgepunkt aus TU-03a): eine Runden-API in `ToolExecutionService`/`ToolTurn`, die ab der ersten Folgerunde und in jeder Fortsetzung automatisch versiegelt, statt vom Aufrufer abzuhängen. `ChatToolLoop` nutzt sie für beide Wege.
- Folgepunkt aus TU-02 mitbeheben: Gleichzeitige Bestätigungen derselben Frage dürfen das gemeinsame Zeitbudget nicht zusammen überschreiten (Zeit vor dem Aufruf atomar reservieren oder serialisieren).
- Antwort der Fortsetzung als eigene Assistentennachricht der Frage speichern; Stopp während der Fortsetzung wie in TU-03a als abgebrochen. Vorhandene Karten, Chatanzeige und deutsch-/englischsprachige Texte weiterverwenden; keine neue UI-Richtung, keine Anzeige interner Modellüberlegungen.

## Non-Goals

Keine davon abhängigen Schreibketten (eine weitere WRITE/CRITICAL-Aktion auf Grundlage eines bestätigten Ergebnisses); das bleibt der letzte offene TU-03-Teil. Keine neuen eingebauten Werkzeuge (TU-04), Tool-Suche/Rückfragen (TU-05), neuen Adapter, Hintergrundausführung oder Fortsetzung ohne die Person. Kein Release/Deployment.

## Acceptance Criteria

1. Geskriptetes Gateway: Frage → CRITICAL-Karte → Bestätigen → genau ein externer Aufruf → Fortsetzung mit einem READ-Folgeaufruf auf Basis des Ergebnisses → Abschlussantwort. Karten und Antwort sind der richtigen Frage zugeordnet.
2. Ablehnen → Fortsetzung ohne Aufruf; die Antwort sagt ehrlich, dass nichts ausgeführt wurde.
3. Zweite Fortsetzungsanfrage, Reload, Replay der Entscheidung und zweiter Tab erzeugen weder eine Modellanfrage noch einen Aufruf (409). Der bestätigte Aufruf läuft insgesamt genau einmal.
4. Neue Frage, Stopp, entzogene Rechte/Freigabe oder geänderte Definition/Ziel zwischen Entscheidung und Fortsetzung verhindern die Fortsetzung; während der Fortsetzung verhindern sie weitere Runden und Aufrufe.
5. Ein manipuliertes Ergebnis der bestätigten Aktion, das WRITE/CRITICAL oder eine Wiederholung verlangt, erzeugt keinen solchen Aufruf; die Sperre wirkt im Dienst, ohne Mitwirkung des Aufrufers.
6. Ursprüngliche Schleife plus Fortsetzung überschreiten weder fünf Modellanfragen noch acht Versuche noch das gemeinsame Zeitbudget, auch bei gleichzeitigen Bestätigungen.
7. Kein privater Belegmarker (Wissensbasis, Anhang, Verlauf, früheres Ergebnis) in einem Request mit `tools`, wohl aber in der Abschlussanfrage.
8. Normale Chats, Modelle ohne Tool Calling und der bestehende TU-02-Bestätigungsweg funktionieren unverändert.
9. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl; unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Keine Browser-/Feldtests ohne PO-Auftrag.
10. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md mit tatsächlichem Teilumfang, Nachweisen und letztem offenen TU-03-Schritt aktualisieren. TU-03 nicht allein wegen TU-03b vollständig markieren.

## Tests

Geskriptetes Gateway und Fake-Adapter mit Aufrufzähler über den echten zentralen Dienst, Karten und Audit (Muster `ChatToolLoopTest`): Bestätigen/Ablehnen mit Fortsetzung, doppelte und parallele Fortsetzung, Replay, Stopp/neue Frage/Rechteentzug zwischen Entscheidung und Fortsetzung, Budget über beide Wege, manipuliertes Ergebnis, Kontexttrennung, gleichzeitige Bestätigungen. HTTP-Regression für Fortsetzungs-Endpunkt inkl. CSRF und fremder Person/Org. Bestehende TU-02/TU-03a-Regressionen erhalten. Volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-confirmed-continuation` ab aktuellem main. Implementierung und unabhängige Prüfung nach PO-Zuweisung (bei TU-03a: Codex implementiert, Claude prüft). Genau eine Implementierungsaufgabe. Draft-PR, kein Merge durch den Entwickler; Provider-/Budgetregeln aus AGENTS.md gelten.
