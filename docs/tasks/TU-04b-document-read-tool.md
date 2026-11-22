# TU-04b — Dokumentstellen lesen

Status: **geplant, nicht gestartet.** Zweite Aufgabe von TU-04 nach TU-04a (PR #26, Merge `841ae00`), gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Dokumentrechte, Datenquellen, Modell-/Werkzeugschleife.

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
