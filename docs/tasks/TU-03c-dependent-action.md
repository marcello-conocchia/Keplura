# TU-03c — Abhängige Folgeaktion mit konkreter Bestätigung

Status: **implementiert; Linux-CI und unabhängige Prüfung offen.** Branch `feat/tool-use-dependent-action` ab main `564a85b`. Codex implementiert, Claude prüft unabhängig Code und Sicherheit; bestehende Rollenteilung fortgeführt. Nicht gemergt. Nächste einzelne Aufgabe nach TU-03b (PR #21, Merge `b1b9e72`). Dritter und letzter Umsetzungsschritt von TU-03 gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: Schreibaktionen, Bestätigungen, externe Aufrufe und Rechte.

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

## Umsetzung und Nachweise (22.11.2026)

- V28 ergänzt nullable `tool_call.parent_call_id` und `tool_question.followup_used` (Default false); keine bestehenden Geschäftsdaten geändert. Alte Karten haben keine Herkunft und behalten ihr Verhalten.
- Nur die bereits einmalig beanspruchte Fortsetzung einer erfolgreich bestätigten Erstaktion erhält im Dienst die Möglichkeit, eine Folgeaktion vorzuschlagen. Originalkatalog und Kontext bleiben gebunden. Der persistente Vorschlagszähler wird atomar auf Frage und konkrete Erstentscheidung geprüft; weitere WRITE-/CRITICAL-Vorschläge werden gezählt und gesperrt.
- WRITE wie CRITICAL wird als Folgeaktion ausschließlich PENDING mit `needsConfirmation=true` gespeichert, mit exaktem Argumenttext und Herkunft. Im bestehenden Entscheidungsweg werden zusätzlich Herkunft, ursprünglicher Katalog, aktueller Kontext, Generation und Definitionen geprüft. Kein neuer Fortsetzungsnachweis nach einer Folgeentscheidung; keine dritte Kettenstufe.
- Duplikatsprüfung gegen Karten derselben Frage: gleiches Werkzeug und gleiche Argumente einschließlich verschachtelter Objekte, unabhängig von Feldreihenfolge und gleichwertiger JSON-Zahlenschreibweise. Auch laufende, ausstehende, abgelehnte und möglicherweise schon ausgeführte fehlgeschlagene Aufrufe bleiben geschützt.
- Karte zeigt „Folgeaktion zu …“ mit Link zur konkret bestätigten Erstkarte; ursprüngliche Risikoklasse bleibt sichtbar, vollständige Argumente bleiben Grundlage der Bestätigung. Deutsch-/englischsprachiger Hinweis und Sperrtexte.
- Gemeinsame Budgets, serialisierte Ausführung und Kontexttrennung unverändert. Auch Anfragen mit einem WRITE-/CRITICAL-Angebot erhalten keine privaten Belege; diese kommen erst in die werkzeuglose Abschlussanfrage. Kein neuer Adapter oder neue Abhängigkeit.
- Gezielte Tests grün: ChatToolLoopTest 61, ChatApiIntegrationTest 34, ToolConfirmationTest 28, ToolExecutionServiceTest 17, FlywayBaselineMigrationTest 4 (144 insgesamt); 14 Node-Karten-/Übersetzungstests und Desktop-Build mit Übersetzungsprüfung.
- PostgreSQL 16: portable Migrationen V1–V28 per psql mit ON_ERROR_STOP angewendet; zusätzlicher transaktionaler Upgrade-Probe prüft bestehende Daten, nullable Herkunft, Default false, falschen Elternaufruf, ersten/zweiten Vorschlags-Claim und exakte Argumente. H2 wird über die Flyway-Tests geprüft. Kein pgvector-/Release-Nachweis daraus abgeleitet.
- Lokale Linux-CI als Nicht-root mit acl am abschließenden Produktcommit und unabhängiges Code-/Security-Review durch Claude noch offen. Keine Browser-/Feldtests oder echtes Modell verwendet. Autoritatives Hermes-Kanban von diesem Windows-Host nicht erreichbar; kein Ersatzboard angelegt.

## Grenzen

- Ein ungültiger oder doppelter erster WRITE-/CRITICAL-Vorschlag verbraucht den einmaligen Vorschlag ebenfalls. Kein automatisches Neuplanen einer Ersatzaktion; eine neue explizite Frage bleibt möglich.
- READ-Aufrufe im selben bereits gelieferten Batch bleiben möglich; nach einer PENDING-Folgekarte erfolgt nur die werkzeuglose Abschlussantwort. Nach der Folgeentscheidung ist das Ergebnis nur auf der Karte und bei einer nächsten expliziten Frage verfügbar.
- Manipulierte eigene Werkzeugergebnisse können weiterhin READ-Argumente beeinflussen und einen falschen Folgeaktionsvorschlag anregen. Kein privater zusätzlicher Belegkontext in werkzeugfähigen Anfragen; keine Schreibausführung ohne die erneute konkrete Bestätigung.
- Bestehende TU-03b-Grenzen (maximal eine Fortsetzung, konservative Wartezeit, weiche Grenze bereits laufender externer Aufrufe, kein Übernehmen verwaister Guards) bleiben bestehen. Aufbewahrung von tool_question und Dependabot-Warnungen bleiben separate Folgeaufgaben; keine neue Löschrichtlinie oder Sicherheits-Sammeländerung in diesem PR.
- TU-03 erst nach erfolgreichem Gate-Abschluss und Merge als abgeschlossen markieren; danach TU-04. Kein Selbst-Merge, Release oder Deployment.
