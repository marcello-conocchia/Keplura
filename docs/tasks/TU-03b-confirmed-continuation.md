# TU-03b — Begrenzte Fortsetzung nach konkreter Bestätigung

Status: **abgeschlossen, auf main** — PR #21, Squash-Merge `b1b9e72` (22.11.2026), Baum identisch mit Head `fe7a4d6`. Linux-CI Exit 0 auf Produktcommit `88c8669`, Evidence `ci/evidence/20261121-205038/`; unabhängige Code-/Security-Freigabe durch Claude auf `31c1849`; minor danach in `fe7a4d6` mit gezielten Tests korrigiert (Review-rounds-Regel). Zweiter Umsetzungsschritt von TU-03 gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md); nächster Schritt [TU-03c](TU-03c-dependent-action.md). Klasse A: Modell-/Werkzeugschleife, Bestätigungen, externe Aufrufe und Rechte.

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

## Umsetzung und Nachweise

- V27 ergänzt serverseitige Fragenbudgets und eigenen Schleifendialog, Katalog-Hashes, einmalige Fortsetzung sowie einen Ausführungs-Guard. Neue Antworten erhalten die ID ihrer Frage; späte Antworten werden im Verlauf ihrer ursprünglichen Frage zugeordnet. Bestehende Nachrichten bleiben unverändert.
- Nur die erfolgreiche erste Entscheidung zu einer passenden Karte erhält einen zufälligen Fortsetzungsnachweis. Server speichert ausschließlich dessen SHA-256; weder Verlauf, Modell, Audit noch Replay enthalten den Nachweis. Der Client sendet ihn unmittelbar einmal an den geschützten Fortsetzungs-Endpunkt, ohne automatische Wiederholung oder Wiederherstellung nach Reload. Dieselbe Frage kann höchstens einmal fortgesetzt werden.
- Nachweis gilt 60 s ab Abschluss der Entscheidung; die ursprüngliche Bestätigungsfrist wird nur für die Entscheidung selbst geprüft, nicht nachträglich für einen bereits ausgeführten Aufruf. Fortsetzung setzt SUCCEEDED oder REJECTED voraus, aktuellen Kontext und unveränderten ursprünglichen Katalog; bei fehlgeschlagenem, blockiertem oder unklarem Aufruf keine automatische Fortsetzung. Bestehender TU-02-Entscheidungsweg bleibt nutzbar.
- Dienst-Runden-API zählt Modellanfragen und versiegelt nach der ersten Runde automatisch auf READ. Fortsetzung beginnt bereits versiegelt. Versuche und Modellanfragen werden vor Verwendung atomar aus demselben Fragenbudget verbraucht; keine Erhöhung durch einen zweiten Client oder Wiederholung.
- Werkzeugfähige Anfragen sehen ausschließlich Frage, eigene Schleifenergebnisse und die Entscheidung. Private Quellen werden für die Abschlussantwort erneut über aktuelle Rechte geladen; kein gespeicherter privater Retrieval-Snapshot. Ursprüngliche öffentliche Webtreffer bleiben ausschließlich Abschlusskontext.
- Ausführungs-Guard serialisiert externe Aufrufe derselben Frage prozessübergreifend, einschließlich Bestätigungen. RUNNING-Karte und Pflicht-Audit sind vor dem externen Aufruf dauerhaft gespeichert; keine DB-Transaktion oder Pool-Verbindung über den Netzwerkaufruf offen. Budget, Generation und Definition werden nach dem Warten erneut geprüft.
- Gezielte Prüfung: ChatToolLoopTest 39, ChatApiIntegrationTest 32, ToolConfirmationTest 24 und ToolExecutionServiceTest 17 sowie FlywayBaselineMigrationTest 4 grün (116 Tests). Desktop-Build mit Übersetzungsprüfung grün. Kein echtes Modell, keine Browser-/Feldtests.
- Verbindliche lokale Linux-CI auf 88c8669 Exit 0: Nicht-root ci (UID 1500) mit acl, Linux-Dateisystem; 85 Klassen genau einmal, 701 Tests, 0 Fehler, 1 übersprungen. Rechte-Helfer, Desktop-Build und cargo check PASS. Evidence ci/evidence/20261121-205038/. Vorher ein abgebrochener CI-Versuch für Randfallkorrekturen sowie ein roter Lauf wegen veralteter Erwartungen im Migrationstest (Anzahl/Tabellen/Versionen); Test angepasst und gezielt grün. Nach diesem grünen Stand nur Nachweise/Dokumentation. Unabhängige Code-/Security-Freigabe durch Claude auf 31c1849 liegt vor; technische Review-Art COMMENT wegen identischem GitHub-Konto. Minor nach Freigabe gezielt korrigiert, ohne erneute volle CI oder Prüfrunde gemäß AGENTS.md. Autoritatives Hermes-Kanban von diesem Windows-Host nicht erreichbar; kein Ersatzboard angelegt. Genau eine Implementierungsaufgabe.

## Grenzen

- Ein manipuliertes eigenes Werkzeugergebnis kann weiterhin Argumente erlaubter READ-Folgeaufrufe beeinflussen. Zusätzlicher privater Kontext bleibt von allen werkzeugfähigen Anfragen getrennt.
- Bei mehreren offenen kritischen Karten löst höchstens eine Entscheidung eine Fortsetzung aus. Karten von vor V27 und Entscheidungen vor Abschluss der ursprünglichen Planung behalten ihren bestehenden Entscheidungsweg ohne automatische Fortsetzung. Weitere bereits geplante Karten können weiterhin einzeln entschieden werden; deren spätere Ergebnisse erfordern gegebenenfalls eine neue Frage.
- Wartezeit von bereits als RUNNING beanspruchten Bestätigungen zählt konservativ zum Zeitbudget. Die bestehende weiche Grenze eines bereits laufenden externen Aufrufs bleibt: Die Serialisierung verhindert paralleles Überziehen, kann aber einen bereits gestarteten Adapteraufruf nicht rückgängig machen.
- Ein Prozessabbruch mit belegtem Ausführungs-Guard schließt weitere Ausführungen dieser Frage sicher. Kein automatischer Neustart und keine Wiederholung einer unklaren Wirkung; die Person kann eine neue Frage stellen. Warten auf einen Guard ist auf 120 s begrenzt.
- Abhängige Schreibketten bleiben offen; TU-03 ist damit nicht vollständig erledigt. Kein Release/Deployment oder Selbst-Merge.

## Review-Abschluss und Minor-Korrektur (22.11.2026)

- [Unabhängiges Review](https://github.com/marcello-conocchia/Keplura/pull/21#pullrequestreview-6008738703) durch Claude: Freigabe auf 31c1849, kein blocker/major. Zusätzlich PostgreSQL 16 einschließlich V27 und neuer Speicherabfragen von Claude geprüft; keine eigene Wiederholung behauptet.
- Minor Guard-Timeout/Interrupt: Noch nicht gestartete Aufrufe werden als BLOCKED mit verständlichem Hinweis abgeschlossen, auch bei Bestätigungen. Kein Fehler 500 aus diesem Wartepfad, kein späteres UNKNOWN für diesen Fall; kein Aufruf/Audit gestartet, fremde Lease unverändert, keine Übernahme verwaister Guards. Die Warteobergrenze von 120 s bleibt bestehen.
- Vier deterministische Regressionen (Timeout/Interrupt, jeweils Bestätigung und READ) ohne echte 120-s-Wartezeit. Gezielte 84 Server-Tests sowie neun Übersetzungstests und Übersetzungsprüfung grün. Vorhandene volle Linux-CI auf 88c8669 und Freigabe bleiben gemäß Review-rounds-Regel gültig.
- Info/Folgepunkt Aufbewahrung: tool_question enthält Fragetext, dialogue_json mit eigenen Ergebnissen und web_context bis zur Chatlöschung. In einer späteren Aufbewahrungsaufgabe Löschung/Leerung nach Fortsetzungsfenster oder Verbrauch prüfen; keine Löschrichtlinie in diesem PR eingeführt.
- Keine Browser-QA beauftragt, kein Selbst-Merge. Gate-Abschluss und Merge durch PO/Orchestrator.
