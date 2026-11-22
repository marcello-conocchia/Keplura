# TU-04a — Interne Wissenssuche als Werkzeug

Status: **geplant, nicht gestartet.** Erste Aufgabe von TU-04 nach Abschluss von TU-03 (PRs #19/#21/#23, letzter Merge `37362d7`), gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). **Vor dem Start PO-Entscheidung nötig** (siehe unten). Klasse A: Datenquellen und Dokumentrechte, Modell-/Werkzeugschleife.

## Ziel

Heute bekommt das Modell nur die Treffer einer einzigen Suche mit der Originalfrage. Neu soll es in den Wissensbasen des Chats gezielt selbst nachsuchen können, z. B. mit anderen Begriffen oder nach einem Werkzeugergebnis. Jede Suche prüft die Rechte der Person neu, und die Antwort zitiert die Fundstellen wie bisher.

## Scope

- Eingebauter Adapter (kein MCP) mit genau einem Werkzeug `knowledge_search` über `ToolExecutionService` und die bestehenden Karten, Limits, Audit und Kontextprüfungen. Risikoklasse **READ fest durch den Server**. Standardmäßig **aus**; nur ein Org-Admin kann es je Organisation einschalten (bestehende Einstellungsablage oder additive Migration). Unklassifiziert oder aus bedeutet: wird nicht angeboten und nicht ausgeführt.
- Eingaben vom Server geprüft: `query` (Text, nicht leer, höchstens die bestehende Länge von `SearchService`) und optional `topK` (1–8). Gesucht wird **nur in den Wissensbasen, die dem Chat zugeordnet sind** (`groundingKbIds`), jeweils über `SearchService.search` mit Person, Org und Bereich. Damit gelten bei jedem Aufruf Mandantentrennung, Bereichsmitgliedschaft und Dokument-ACLs. Keine Suche in anderen Wissensbasen, auch nicht auf Wunsch des Modells.
- Ergebnis begrenzt: höchstens 8 Treffer, Auszüge auf die bestehende Chunk-Länge gekürzt, gesamt innerhalb der 8 000 Zeichen je Ergebnis. Jeder Treffer mit Fundstelle (Datei, Abschnitt/Seite). Auszüge sind als Fremdinhalt markiert und keine Anweisungen.
- Zitate: Die Fundstellen der Werkzeugtreffer fließen in die bestehenden Zitate der Antwort ein (gleiche Anzeige wie RAG-Treffer). Keine Zitate auf Treffer, die die Person nicht mehr sehen darf.
- **Keine dauerhafte Kopie privater Auszüge:** Karten, `tool_question`-Dialog und spätere „früher bestätigte Ergebnisse“ speichern für dieses Werkzeug nur Fundstellen (Dokument, Abschnitt, Seite, Score), keinen Chunk-Text. Wird der Text später gebraucht (Karte anzeigen, Fortsetzung aus TU-03b, Abschlussanfrage), wird er mit **aktuellen** Rechten neu aufgelöst oder weggelassen.
- **Vorbestehender Befund aus TU-02 (minor, Security low, Review zu PR #23):** Nicht endliche Zahlen (`1e400`, `-1e400`, NaN-Varianten) werden heute vor der Prüfung kanonisiert und dabei zum Text `"Infinity"`, sodass `finiteAndBounded` sie nicht sieht. Den Validator auf die **ungewandelten** Argumente anwenden, bevor kanonisiert wird, oder die Kanonisierung bei nicht endlichen Zahlen scheitern lassen. Gilt für alle Werkzeuge, nicht nur für dieses.
- Desktop: bestehende Karte und Zitatanzeige. Ein Schalter in der bestehenden Admin-Werkzeugverwaltung (Deutsch/Englisch). Keine neue UI-Richtung.

## PO-Entscheidung vor dem Start

Bisher sehen werkzeugfähige Modellanfragen keine privaten Inhalte außer eigenen Werkzeugergebnissen. Mit diesem Werkzeug werden private Dokumentauszüge selbst zu Werkzeugergebnissen. Stehen in derselben Frage externe Werkzeuge (MCP) zur Verfügung, könnte ein eingeschleuster Text im Dokument das Modell dazu bringen, Inhalte als Argument eines externen READ-Aufrufs nach außen zu senden (siehe major-Befund zu PR #19).

- **A (empfohlen):** Sobald eine Wissenssuche in einer Frage Treffer geliefert hat, bietet der Server in dieser Frage keine externen READ-Werkzeuge mehr an und führt sie nicht mehr aus. Vorher sind externe Werkzeuge möglich. Bestätigungspflichtige Karten (CRITICAL, Folgeaktionen) bleiben möglich, weil die Person die exakten Eingaben sieht. Nachteil: kein „intern suchen → extern nachschlagen“ in einer Frage, dafür reicht eine neue Frage.
- **B:** Ist die Wissenssuche in einer Frage angeboten, werden externe Werkzeuge in dieser Frage gar nicht angeboten. Am einfachsten und strengsten, aber schlechter nutzbar.
- **C:** Keine Einschränkung; das Restrisiko wird nur dokumentiert. Am schnellsten, aber genau der Abflussweg, den TU-03a geschlossen hat.

Bis zur Entscheidung nicht starten. Die Acceptance Criteria unten gehen von **A** aus.

## Non-Goals

Kein Lesen ganzer Dokumentstellen (TU-04b), keine Anhang-Werkzeuge (TU-04c), keine Websuche und kein Webseitenabruf als Werkzeug (spätere TU-04-Teile), keine Suche außerhalb der Chat-Wissensbasen, kein neuer Suchindex, kein Reranker-/Embedding-Wechsel, keine Schreibwerkzeuge. Kein Release/Deployment.

## Acceptance Criteria

1. Geskriptetes Gateway: Frage → `knowledge_search` mit eigenem Suchbegriff → Treffer aus einer Chat-Wissensbasis → Abschlussantwort mit korrekten Zitaten. Karte zeigt Suchbegriff und Fundstellen.
2. Ausgeschaltet, nicht Admin oder fremde Org: Das Werkzeug wird nicht angeboten, und ein vom Modell trotzdem gelieferter Aufruf wird gesperrt. Ein Nicht-Admin kann den Schalter nicht setzen (403).
3. Treffer aus Dokumenten ohne Leserecht, aus fremden Bereichen/Orgs oder aus nicht zugeordneten Wissensbasen erscheinen nie, auch nicht mit vom Modell vorgegebenen IDs oder Parametern.
4. Nach Entzug eines Dokumentrechts erscheint dessen Text weder in der Karte noch in einer TU-03b-Fortsetzung noch als früherer Beleg einer neuen Frage.
5. Variante A: Nach einer Wissenssuche mit Treffern wird kein externes READ-Werkzeug mehr angeboten oder ausgeführt (Adapterzähler); vorher bleibt es möglich. Kein privater Marker gelangt in Argumente eines externen Werkzeugs.
6. `1e400`, `-1e400` und NaN-Varianten in Werkzeugargumenten werden mit verständlichem Hinweis gesperrt, für alle Werkzeuge; der Adapterzähler bleibt bei 0.
7. Bestehende Limits (5 Modellanfragen, 8 Versuche, 120 s, 16 000/8 000 Zeichen), Stop/neue Frage/Rechteentzug sowie TU-02/TU-03-Abläufe und normale RAG-Chats funktionieren unverändert.
8. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl; unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Bei einer Migration: additiv, unter H2 und PostgreSQL geprüft. Keine Browser-/Feldtests ohne PO-Auftrag.
9. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md mit tatsächlichem Teilumfang und dem nächsten kleinsten TU-04-Schritt aktualisieren.

## Tests

Geskriptetes Gateway vor dem echten zentralen Dienst mit H2, echter `SearchService` mit Test-Embeddings bzw. Fake-Store über ACL-Fixtures (Muster `ChatToolLoopTest` sowie die bestehenden Such- und ACL-Tests): Treffer und Zitate, ausgeschaltet/fremde Org, ACL-Entzug vor Anzeige, Fortsetzung und neuer Frage, nicht zugeordnete Wissensbasis, Variante A mit gezähltem externem Fake-Adapter, nicht endliche Zahlen. HTTP-Regression für den Admin-Schalter inkl. CSRF und Nicht-Admin. Volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-knowledge-search` ab aktuellem main. Implementierung und unabhängige Prüfung nach PO-Zuweisung (bei TU-03: Codex implementiert, Claude prüft). Genau eine Implementierungsaufgabe. Draft-PR, kein Merge durch den Entwickler; Provider-/Budgetregeln aus AGENTS.md gelten.
