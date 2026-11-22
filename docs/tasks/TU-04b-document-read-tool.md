# TU-04b — Dokumentstellen lesen

Status: **abgeschlossen, auf main** — PR #28, Squash-Merge `8e88e8d` (22.11.2026), Baum identisch mit freigegebenem Head `909ede8`. Linux-CI Exit 0 auf Produktcommit `8665d0d`, Evidence `ci/evidence/20261122-195911/`; unabhängige Code-/Security-Freigabe durch Claude auf `909ede8` (zwei info-Folgepunkte, siehe PROJECT_STATE). Zweite Aufgabe von TU-04 gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md); nächster Schritt [TU-04c](TU-04c-attachment-tools.md). Klasse A: Dokumentrechte, Datenquellen, Modell-/Werkzeugschleife.

## Ziel

Die Wissenssuche liefert einzelne Abschnitte. Oft steht die Antwort aber im Absatz davor oder danach, etwa bei einer Tabelle, einer Fortsetzung oder einer Ausnahme. Das Modell soll zu einem Treffer der Wissenssuche gezielt den umgebenden Text lesen können. Jeder Zugriff prüft die Rechte der Person neu, und die Antwort zitiert die Stellen wie bisher.

## Scope

- Zweites eingebautes Werkzeug `document_read` im bestehenden Wissens-Adapter (TU-04a), über `ToolExecutionService` mit Karten, Limits, Audit, Guard und Recheck. Risikoklasse **READ fest durch den Server**, Ziel **intern**. Ein- und ausgeschaltet über denselben Org-Schalter wie `knowledge_search` (OWNER, Revision). Keine neue Einstellung.
- Eingaben vom Server geprüft: `documentId` und `chunkIdx` eines **Treffers aus `knowledge_search` derselben Frage**, dazu optional `before`/`after` (je 0–3, Standard 1). Andere Dokumente oder Abschnitte, auch innerhalb der Chat-Wissensbasen, werden gesperrt und nicht nachgeschlagen, damit das Modell keine Dokument-IDs durchprobieren kann. Die Wissensbasis bestimmt der Server aus der gespeicherten Fundstelle, nie das Modell.
- Lesen über die bestehende Rechtekette: `requireKb`, `findByIdInKbForMember` und `findPreviewWindow` mit Person, Org, Bereich und Wissensbasis. Höchstens 7 Abschnitte je Aufruf, gesamt innerhalb der 8 000 Zeichen je Ergebnis; jeder Abschnitt mit Fundstelle (Datei, Abschnitt, Seite).
- Wie TU-04a: Karten, `tool_question`-Dialog und frühere Belege speichern **nur Fundstellen** (Wissensbasis, Dokument, Abschnittsbereich), keinen Text. Für Karte, Fortsetzung und Abschlussanfrage wird mit aktuellen Rechten neu aufgelöst. Bei Rechteentzug endet die Runde, offene Karten werden abgebrochen, und Zitate entfallen. Ergebnisse markieren die Frage als „mit internen Inhalten“ (Herkunftsprüfung aus TU-04a, PO-Entscheidung 22.11.2026) und zählen nie als öffentliche Quelle.
- Zitate: gelesene Stellen fließen wie Wissenssuchtreffer in die Antwortzitate ein, ohne Duplikate.
- **Folgepunkt aus #26 mitbeheben (info):** `view()` darf offene Karten nur bei **nachgewiesenem** Rechteentzug abbrechen, nicht bei vorübergehenden Lese- oder DB-Fehlern; dann bleibt die Karte offen und zeigt keine Eingaben bzw. Ergebnisse. Die Neuauflösung von Fundstellen wird je Anfrage zwischengespeichert, statt sie je Karte, Nachricht und `active()`-Prüfung zu wiederholen. Rechte werden dabei weiter pro Anfrage aktuell geprüft, nicht über Anfragen hinweg gecacht.
- Desktop: bestehende Karten und Zitate, deutsch/englisch; keine neue UI-Richtung.

## Non-Goals

Keine Volltextsuche oder Navigation über ganze Dokumente, kein Lesen von Dokumenten ohne vorherigen Treffer in derselben Frage, keine Anhang-Werkzeuge (nächster TU-04-Teil), keine Websuche oder Webseitenabruf als Werkzeug, kein neuer Index, keine Schreibwerkzeuge, keine Aufbewahrungsrichtlinie für `tool_question` (eigene Aufgabe). Kein Release/Deployment.

## Acceptance Criteria

1. Geskriptetes Gateway: Frage → `knowledge_search` → `document_read` zum Treffer mit `before`/`after` → Abschlussantwort, die Text aus dem Nachbarabschnitt nutzt und ihn korrekt zitiert. Die Karte zeigt Dokument und Abschnittsbereich.
2. `document_read` auf ein Dokument oder einen Abschnitt, der in dieser Frage nicht als Treffer vorkam, auf eine fremde Wissensbasis, einen fremden Bereich oder eine fremde Org oder mit eingeschleuster `kbId` wird gesperrt; kein Text gelangt ins Modell oder in die Karte.
3. Ausgeschaltet oder ohne Chat-Wissensbasis wird das Werkzeug weder angeboten noch ausgeführt. `before`/`after` außerhalb 0–3 und nicht endliche Zahlen werden gesperrt.
4. Nach Entzug eines Dokumentrechts erscheint der gelesene Text weder in Karte, Fortsetzung, Abschlussanfrage noch als früherer Beleg; eine laufende Runde endet.
5. Nach `document_read` läuft ein externer READ-Aufruf mit Text aus der gelesenen Stelle nie automatisch, sondern erzeugt eine Karte; kanonischer öffentlicher Text aus Frage oder externen Ergebnissen bleibt automatisch.
6. Ein vorübergehender Lesefehler bricht keine offene Karte ab; ein nachgewiesener Rechteentzug schon. Die Zahl der Neuauflösungen pro Transkriptanfrage ist begrenzt (Test zählt die Store-Zugriffe).
7. Budgets (5 Modellanfragen, 8 Versuche, 120 s, 16 000/8 000 Zeichen), Stop/neue Frage/Rechteentzug sowie TU-02/TU-03/TU-04a-Abläufe und normale RAG-Chats funktionieren unverändert.
8. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl (`id`/`setfacl`-Ausgabe im Nachweis sichern); unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Bei einer Migration: additiv, unter H2 und PostgreSQL geprüft. Keine Browser-/Feldtests ohne PO-Auftrag.
9. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md mit tatsächlichem Teilumfang und dem nächsten kleinsten TU-04-Schritt aktualisieren.

## Tests

Geskriptetes Gateway vor dem echten zentralen Dienst mit H2, echtem `SearchService` und Dokument-/Chunk-Fixtures mit ACLs (Muster der TU-04a-Tests in `ChatApiIntegrationTest`): Lesen um einen Treffer, Fensterrand am Dokumentanfang und -ende, gesperrte fremde oder nicht getroffene Dokumente, eingeschleuste IDs, Rechteentzug vor Anzeige, Fortsetzung und neuer Frage, Herkunftsprüfung mit gezähltem externem Adapter, vorübergehender Fehler in `view()`, Zählung der Neuauflösungen. Volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-document-read` ab aktuellem main. Implementierung und unabhängige Prüfung nach PO-Zuweisung (bisher: Codex implementiert, Claude prüft). Genau eine Implementierungsaufgabe. Draft-PR, kein Merge durch den Entwickler; Provider-/Budgetregeln aus AGENTS.md gelten.

## Implementierungsstand (22.11.2026, noch nicht freigegeben)

- `document_read(documentId, chunkIdx, before?, after?)` gehört zum bestehenden Wissensadapter und verwendet unverändert die Org-Freigabe und deren Revision. Der zusätzliche Ausführungskontext enthält die Frage-ID ausschließlich aus der serverseitigen Karte; das Modell kann sie nicht liefern.
- Ein Anker muss in einer eigenen erfolgreichen `knowledge_search`-Karte derselben Frage mit unveränderter Definition stehen. Ein früheres `document_read`, eine frühere Frage oder fremde Chats/Orgs/Bereiche schaffen keine neuen Anker. Unbekannte Ziel-IDs werden vor dem Dokumentzugriff abgewiesen. Rechteprüfungen bestehender eigener Belege finden weiter statt.
- `SearchService.resolveWindow` verwendet dieselbe KB-/Bereichs-/Dokument-ACL-Kette und `findPreviewWindow`. Der Bereich ist inklusiv und auf sieben Chunks begrenzt; am Dokumentanfang/-ende sind kleinere Fenster erlaubt. Gespeichert werden nur die tatsächlich vorhandenen einzelnen Fundstellen, kein Auszugstext. Die Karte zeigt Anker und Fensterargumente sowie aktuell lesbare Abschnitte. Kein neues DB-Schema, keine Migration und keine zusätzliche Einstellung/Abhängigkeit.
- Wissenssuche und Dokumentlesen markieren die Frage dauerhaft als intern. Externe Folgeaufrufe bleiben der Herkunftsprüfung und den bestehenden Bestätigungs-/Fortsetzungsregeln unterworfen. Gelesene Stellen werden in bestehende Zitate aufgenommen und anhand Dokument/Chunk dedupliziert; Antwortmetadaten bleiben innerhalb des bestehenden Zeichenbudgets.
- Fundstellenzugriff hat drei Ergebnisse: lesbar, nachgewiesen entzogen/geändert, vorübergehend nicht verfügbar. Nur nachgewiesene Entziehung/Änderung bricht offene Karten beim Lesen ab. Bei vorübergehendem Fehler bleiben Status und exakte gespeicherte Eingaben erhalten, werden aber im Client ausgeblendet; nach erneutem Laden mit erfolgreichen Prüfungen sind sie wieder sichtbar. Entscheidungen/Ausführung bleiben bei jedem Fehler gesperrt.
- Ein explizit begrenzter `ReferenceReadScope` teilt aktuelle Definitionen, Fundstellenauflösungen, Dokumentrechte und Chunk-Fenster innerhalb einer Transkriptanfrage bzw. einer einzelnen Prüfphase. Schlüssel enthalten die vollständige Identität und Fundstellenbindung. Fehler und negative Resultate werden ebenfalls nur dort zwischengespeichert. Der Scope endet in `finally`; vor/nach Modellanfragen und bei jedem späteren HTTP-Zugriff wird frisch geprüft. Kein Cache über Netzwerkaufrufe oder Ausführungen hinweg.
- Das Transkript hat keine gemeinsame äußere Read-only-Transaktion mehr: Sonst würde ein abgefangener Fehler einer Fundstellen-Lesetransaktion die gesamte Antwort mit `UnexpectedRollbackException` verwerfen; auch das dokumentierte Abbrechen bei bewiesenem Rechteentzug muss schreibbar sein. Die einzelnen Repository-/Fundstellenzugriffe behalten ihre Transaktionen und Rechteprüfungen.
- Gezielte Tests: geskriptetes Gateway über echten Chat-/Such-/ACL-Pfad, Nachbarabschnitte und Zitate, private/öffentliche externe Folgeeingaben, Rechteentzug und Wiederholung in anderer Frage, vorübergehende Auflösungs- und Kontextfehler, begrenzte Store-Zugriffe pro Transkript; zusätzlich zentraler Dienst für Ankerbindung, Fenstergrenzen, unbekannte/fremde IDs, Argumentgrenzen und nicht endliche Zahlen. Keine Browser-QA beauftragt, kein Nachweis mit echtem Modell.

Der Aufbewahrungs-Folgepunkt und die bekannten Abhängigkeitswarnungen bleiben eigene Aufgaben. Bereits verfasste Chatantworten werden durch späteren Rechteentzug nicht gelöscht; Fundstellen, Karten, Neuauflösung und folgende Belegverwendung verwenden aktuelle Rechte.

## Validierung und Übergabe

Verbindliche Linux-CI Exit 0 auf Produktcommit `8665d0d655740ecaca3840a912024c93ad35110f` (`uid=1500(ci)`, `setfacl 2.3.2` gesichert): 88 Klassen, 832 Tests, 0 Fehler, 1 übersprungen; Rechte-Helfer, Desktop-Build und cargo check PASS. Nachweise `ci/evidence/20261122-195911/` (`MATRIX.md`, `ci-local.log`, `environment.txt`, `runner.sh`). Danach nur Dokumentation/Nachweise. Unabhängiges Review durch Claude ausstehend; kein Merge, keine Browser-QA, kein Release/Deployment.

Gezielte Entwicklungstests: 197 Tests im Chat-/Tool-/Suchbereich grün, danach 6 gezielte HTTP-Fälle für den letzten Stand einschließlich Fortsetzung/Rechteentzug grün. Desktop-Build und Übersetzungsprüfung grün; 6 Kartentests und 4 Servertext-Übersetzungstests grün. Ein vorher begonnener CI-Lauf auf `b1b14e9` wurde vor Abschluss angehalten, um den neuen englischen Fehlertext aufzunehmen; maßgeblich ist ausschließlich der vollständige grüne Lauf auf `8665d0d`. Kein Testnachweis mit echtem Modell oder Browser.
