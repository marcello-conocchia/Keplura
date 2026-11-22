# TU-03c — Abhängige Folgeaktion mit konkreter Bestätigung

Status: **geplant, nicht gestartet.** Nächste einzelne Aufgabe nach TU-03b (PR #21, Merge `b1b9e72`). Dritter und letzter Umsetzungsschritt von TU-03 gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Schreibaktionen, Bestätigungen, externe Aufrufe und Rechte.

## Ziel

Eine Schreibaktion darf auf dem bestätigten Ergebnis einer vorherigen Aktion aufbauen, z. B. „Ticket anlegen“ → bestätigt → „Ticket-ID im Kalendereintrag verknüpfen“. Das Modell darf die Folgeaktion nur **vorschlagen**. Sie läuft erst nach einer konkreten Bestätigung der Person, auch wenn das Werkzeug als WRITE sonst ohne Rückfrage laufen dürfte. Werkzeugergebnisse autorisieren weiterhin nichts.

## Scope

- Nur in der einmaligen Fortsetzung aus TU-03b und nur nach einer **bestätigten und erfolgreichen** Aktion (SUCCEEDED), nicht nach Ablehnung, Fehler, Sperre oder unklarem Ausgang. Höchstens **eine** Folgeaktion je Frage; Kettentiefe damit höchstens zwei (Erstaktion → eine Folgeaktion).
- Angeboten werden nur freigegebene WRITE-/CRITICAL-Werkzeuge aus dem ursprünglichen Katalog der Frage (TU-03b-Bindung, unveränderter Definitions-Hash). Keine neuen Werkzeuge, keine neue Freigabe, keine Absenkung der Risikoklasse durch Modell oder Ergebnis.
- Jede vorgeschlagene WRITE- oder CRITICAL-Aktion wird als ausstehende Karte mit vollständigen Eingaben gespeichert und nie in der Runde ausgeführt. Es gilt der bestehende einmalige Bestätigungsweg aus TU-02 (Bindung an Person, Org, Bereich, Chat, Definitions-Hash inkl. Ziel und exakte Argumente; atomarer Verbrauch; Recheck vor Ausführung). Die Karte zeigt sichtbar, dass sie eine Folgeaktion ist und auf welchem bestätigten Aufruf sie beruht.
- Weitere Vorschläge in derselben Fortsetzung werden serverseitig gesperrt und gezählt. Daneben bleiben READ-Folgeaufrufe wie in TU-03b möglich.
- Schutz vor doppelten Schreibaktionen: Ein Vorschlag mit gleichem Werkzeug und gleichen kanonischen Argumenten wie ein in dieser Frage schon ausgeführter, laufender, abgelehnter oder ausstehender Aufruf wird gesperrt („bereits ausgeführt bzw. entschieden“). Keine automatische Wiederholung.
- Nach der Entscheidung über die Folgekarte gibt es keine weitere Fortsetzung. Das Ergebnis bleibt in der Karte und fließt wie bisher bei der nächsten expliziten Frage als bestätigter Beleg ein.
- Gemeinsame Budgets und Kontexttrennung aus TU-03a/b unverändert: dieselben 5 Modellanfragen, 8 Versuche und 120 s je Frage. Werkzeugfähige Anfragen, auch solche mit WRITE/CRITICAL-Angebot, sehen nur Frage, eigene Schleifenergebnisse und die Entscheidung. Private Belege gibt es nur in der werkzeuglosen Abschlussanfrage.
- Vorhandene Karten, Chatanzeige und deutsch-/englischsprachige Texte weiterverwenden; nur ein kurzer Hinweis „Folgeaktion zu …“ auf der Karte. Keine neue UI-Richtung.

## Non-Goals

Keine längeren Ketten, keine Fortsetzung nach der Folgeentscheidung, keine Ausführung ohne Bestätigung, keine neuen eingebauten Werkzeuge (TU-04), Tool-Suche/Rückfragen (TU-05), neuen Adapter oder Hintergrundausführung. Keine Aufbewahrungs- bzw. Löschrichtlinie für `tool_question` (eigene spätere Aufgabe). Kein Release/Deployment.

## Acceptance Criteria

1. Geskriptetes Gateway: Frage → CRITICAL-Karte → Bestätigen → genau ein Aufruf → Fortsetzung schlägt eine WRITE-Folgeaktion mit einer ID aus dem Ergebnis vor → ausstehende Karte, **kein** externer Aufruf → Bestätigen → genau ein Aufruf mit exakt den angezeigten Argumenten. Keine weitere Fortsetzung.
2. Ein freigegebenes WRITE-Werkzeug, das im Erstplan ohne Rückfrage laufen dürfte, wird als Folgeaktion nie ohne Bestätigung ausgeführt.
3. Nach Ablehnung, Fehler, Sperre oder unklarem Ausgang der Erstaktion gibt es weder ein Angebot noch eine Karte für eine Folgeaktion. Ein zweiter Vorschlag in derselben Fortsetzung wird gesperrt.
4. Ein Vorschlag, der eine bereits ausgeführte, laufende, abgelehnte oder ausstehende Aktion derselben Frage wiederholt, wird gesperrt; der Adapterzähler bleibt unverändert.
5. Neue Frage, Stopp, entzogene Rechte/Freigabe sowie geänderte Definition, Ziel oder Schema verhindern Vorschlag und Ausführung; Replay, zweiter Tab und Reload führen nichts doppelt aus (409).
6. Ein manipuliertes Ergebnis kann weder ein Werkzeug außerhalb des ursprünglichen Katalogs noch eine niedrigere Risikoklasse noch eine zweite Folgeaktion erzwingen.
7. Kein privater Belegmarker (Wissensbasis, Anhang, Verlauf, früheres Ergebnis) in einem Request mit `tools`; Budgets über Erstschleife, Fortsetzung und beide Bestätigungen werden nicht überschritten.
8. Normale Chats, Modelle ohne Tool Calling sowie TU-02/TU-03a/TU-03b-Abläufe funktionieren unverändert.
9. Gezielte Tests während der Arbeit, einmal lokale Linux-CI am Ende als Nicht-root mit acl; unabhängige Code-/Security-Freigabe gemäß AGENTS.md. Keine Browser-/Feldtests ohne PO-Auftrag.
10. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md aktualisieren und TU-03 mit tatsächlichem Umfang und Grenzen als abgeschlossen markieren; nächster Schritt TU-04.

## Tests

Geskriptetes Gateway und Fake-Adapter mit Aufrufzähler über den echten zentralen Dienst, Karten und Audit (Muster `ChatToolLoopTest`/`ToolConfirmationTest`): Folgeaktion WRITE und CRITICAL, kein Angebot nach Ablehnung/Fehler, zweiter Vorschlag, Duplikat, Katalog-/Risikomanipulation, Stopp/neue Frage/Rechteentzug, Replay und parallele Bestätigung, Budgets, Kontexttrennung. HTTP-Regression inkl. CSRF und fremder Person/Org. Falls eine Migration für die Herkunft der Karte nötig ist: additiv und unter H2 und PostgreSQL geprüft. Volle CI einmal am Ende.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-dependent-action` ab aktuellem main. Implementierung und unabhängige Prüfung nach PO-Zuweisung (bei TU-03a/b: Codex implementiert, Claude prüft). Genau eine Implementierungsaufgabe. Draft-PR, kein Merge durch den Entwickler; Provider-/Budgetregeln aus AGENTS.md gelten.
