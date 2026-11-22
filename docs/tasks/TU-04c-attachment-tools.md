# TU-04c — Anhänge auflisten, durchsuchen und lesen

Status: **geplant, nicht gestartet.** Dritte Aufgabe von TU-04 nach TU-04b (PR #28, Merge `8e88e8d`), gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: private Chat-Inhalte, Modell-/Werkzeugschleife.

## Ziel

Heute gehen die Anhänge eines Chats (höchstens 5 Dateien, je bis 30 000 Zeichen) als ein Block von bis zu 40 000 Zeichen in die Abschlussantwort. Was darüber hinausgeht, sieht das Modell nicht, und es kann nicht gezielt nach einer Stelle suchen. Neu soll das Modell die Anhänge des eigenen Chats auflisten, darin wörtlich suchen und eine begrenzte Stelle lesen können.

## Scope

- Eingebauter Adapter `attachments` mit drei Werkzeugen über `ToolExecutionService` (Karten, Limits, Audit, Guard, Recheck). Risikoklasse **READ fest durch den Server**, Ziel **intern**. Ein- und ausgeschaltet über denselben Org-Schalter wie die Wissenswerkzeuge aus TU-04a/b (OWNER, Revision); keine neue Einstellung. Angeboten nur, wenn der Chat mindestens einen Anhang hat.
  - `attachment_list()`: ID, Dateiname, Zeichenzahl und „gekürzt“ der Anhänge **dieses Chats**, höchstens 5.
  - `attachment_search(query, attachmentId?)`: wörtliche Suche ohne Groß-/Kleinschreibung (NFC), höchstens 8 Treffer mit Position und kurzem Auszug (je ≤ 300 Zeichen), gesamt ≤ 8 000 Zeichen. `query` 1–200 Zeichen; keine regulären Ausdrücke und keine Modell-Interpretation.
  - `attachment_read(attachmentId, offset, length?)`: zusammenhängender Ausschnitt, `length` 1–4 000 (Standard 2 000), auf das Dateiende begrenzt.
- **Bindung:** Gelesen werden nur Anhänge des Chats aus dem serverseitigen Kontext (Person, Org, Bereich, Chat; `created_by` des Chats). Fremde oder geratene `attachmentId` werden ohne Inhaltszugriff gesperrt. Gelöschte Anhänge sind sofort nicht mehr lesbar.
- **Nur Fundstellen speichern:** Karten, `tool_question`-Dialog und frühere Belege speichern nur Anhang-ID, Position und Länge, keinen Text. Bei Bedarf wird der Text neu aus `chat_attachment` aufgelöst. Gelöscht oder nicht mehr zugänglich heißt: Text entfällt, die Runde endet, offene Karten werden wie in TU-04b nur bei nachgewiesenem Entzug abgebrochen.
- **Herkunft:** Ergebnisse markieren die Frage als „mit internen Inhalten“ und zählen nie als öffentliche Quelle (TU-04a, PO-Entscheidung 22.11.2026). Externe READ-Aufrufe mit Anhangtext werden zur Karte.
- **Abschlussantwort:** Der bisherige Anhangsblock in der werkzeuglosen Abschlussanfrage bleibt unverändert. Werkzeugergebnisse ergänzen ihn, Doppelungen werden nicht entfernt. Keine Änderung an Upload, Grenzen oder Löschung von Anhängen.
- Desktop: bestehende Karten, deutsch/englisch; keine neue UI-Richtung.
- **Info-Folgepunkt aus #28 mitnehmen:** eine PostgreSQL-Integrationsprobe für den Kartenabbruch beim Lesen (Transkript ohne gemeinsame read-only Transaktion), weil der neue Adapter denselben Pfad nutzt.

## Non-Goals

Kein Lesen von Anhängen anderer Chats oder Personen, keine Dateiformat-Verarbeitung (es wird der bereits extrahierte Text genutzt), kein OCR, keine Volltextindizierung oder Embeddings für Anhänge, keine Websuche oder Webseitenabruf (letzter TU-04-Teil), keine Schreibwerkzeuge, keine Aufbewahrungsrichtlinie für `tool_question`. Kein Release/Deployment.

## Acceptance Criteria

1. Geskriptetes Gateway: Frage → `attachment_search` findet eine Stelle **hinter** dem bisherigen 40 000-Zeichen-Budget → `attachment_read` an dieser Position → Abschlussantwort nutzt den gelesenen Text. Die Karten zeigen Suchbegriff bzw. Anhang und Position.
2. `attachment_list`, `attachment_search` und `attachment_read` liefern nur Anhänge des eigenen Chats. Eine fremde oder geratene `attachmentId`, ein anderer Chat, eine andere Person oder Org wird gesperrt, und es entsteht kein Inhaltszugriff (Zugriffe gezählt).
3. Ohne Anhänge, ausgeschaltet oder bei fremder Org werden die Werkzeuge weder angeboten noch ausgeführt. Grenzen (`query` 1–200, `length` 1–4 000, `offset` ≥ 0, nicht endliche Zahlen) werden serverseitig geprüft.
4. Nach Löschen eines Anhangs erscheint sein Text weder in Karte, Fortsetzung, Abschlussanfrage noch als früherer Beleg; eine laufende Runde endet. Ein vorübergehender DB-Fehler bricht keine offene Karte ab.
5. Nach einem Anhangsergebnis läuft ein externer READ-Aufruf mit Anhangtext nie automatisch, sondern wird zur Karte; kanonischer öffentlicher Text bleibt automatisch.
6. Karten und Dialog enthalten keinen Anhangtext, nur Fundstellen (Test prüft gespeicherte Spalten).
7. PostgreSQL-Integrationsprobe: Der Kartenabbruch beim Lesen eines Transkripts funktioniert auf PostgreSQL (Folgepunkt aus #28).
8. Budgets (5 Modellanfragen, 8 Versuche, 120 s, 16 000/8 000 Zeichen), Stop/neue Frage/Rechteentzug sowie TU-02/TU-03/TU-04a/b-Abläufe und normale Chats mit Anhängen funktionieren unverändert.
9. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl (`id`/`setfacl` im Nachweis); unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Bei einer Migration: additiv, unter H2 und PostgreSQL geprüft. Keine Browser-/Feldtests ohne PO-Auftrag.
10. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md mit tatsächlichem Teilumfang und dem letzten TU-04-Schritt (Websuche/Webseitenabruf) aktualisieren.

## Tests

Geskriptetes Gateway vor dem echten zentralen Dienst mit H2 und echten `chat_attachment`-Zeilen (Muster der TU-04a/b-Tests): Suche hinter dem Budget, Lesen an Dateigrenzen, fremde/geratene IDs mit gezählten Zugriffen, Löschen vor Anzeige, Fortsetzung und neuer Frage, vorübergehender Fehler, Herkunftsprüfung mit gezähltem externem Adapter, gespeicherte Spalten ohne Text. PostgreSQL-Probe für den Transkriptpfad. Volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-attachments` ab aktuellem main. Implementierung und unabhängige Prüfung nach PO-Zuweisung (bisher: Codex implementiert, Claude prüft). Genau eine Implementierungsaufgabe. Draft-PR, kein Merge durch den Entwickler; Provider-/Budgetregeln aus AGENTS.md gelten.
