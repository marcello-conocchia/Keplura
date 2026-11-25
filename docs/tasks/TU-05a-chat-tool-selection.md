# TU-05a — Tool-Auswahl je Chat

Status: **abgeschlossen mit PR #36 (25.11.2026).** Unmittelbar nächste einzelne Aufgabe nach TU-04 (PR #34, Merge 36014ca), gemäß [Tool-Use-Plan](../TOOL_USE_PLAN.md). Klasse A: autorisierter Werkzeugkatalog, Chat-Kontext und externe Aufrufe.

## Ziel

Eine Person kann die Werkzeuge ihres eigenen Chats einschränken. Die Auswahl ist eine zusätzliche Einschränkung der aktuellen serverseitigen Freigaben und erweitert keine Rechte.

## Scope

- Auswahl aus den aktuell im Chat zulässigen Werkzeugen; stabile Werkzeugidentitäten statt Modell-Alias als gespeicherte Schlüssel.
- Chat-Auswahl serverseitig im vorhandenen Besitz-/Org-/Bereichskontext lesen und speichern. Fremde oder geratene Chat-/Werkzeugidentitäten werden sicher abgelehnt.
- Standard ohne explizite Auswahl erhält das bisherige Verhalten. Eine explizit leere Auswahl schaltet Werkzeugangebote für neue Fragen aus.
- Zentrale Angebotsmenge ist die Schnittmenge aus Auswahl, aktueller Freigabe und vorhandenen Bedingungen (z. B. Org- und Frage-Schalter bei Web). Jede Ausführung prüft Rechte weiter neu.
- Jede Frage bleibt an ihren ursprünglichen Katalog gebunden. Änderungen der Auswahl dürfen eine laufende Frage weder erweitern noch alte Karten oder Schreibfreigaben neu nutzbar machen.
- Kompakte Auswahl im bestehenden Chat-Kontext mit vorhandenen UI-Komponenten und Lade-/Fehler-/Speicherzuständen; deutsche und englische Texte. Keine neue Admin-Oberfläche.

## Non-Goals

Keine Modellvorgaben, Tool-Suche, strukturierten Rückfragen, neuen Werkzeuge oder Adapter, OAuth, neuen Dienste, Releases oder Deployments. Keine Änderung von Risikoklassen, Bestätigungs- oder Herkunftsregeln.

## Acceptance Criteria

1. Auswahl und Wiederherstellung funktionieren ausschließlich für den eigenen Chat; fremde Nutzer/Organisationen/Bereiche bleiben gesperrt.
2. Ohne Auswahl bleibt das bisherige Verhalten erhalten; explizit leer bietet kein Werkzeug an; ein Teilkatalog bietet exakt seine weiterhin erlaubten Werkzeuge an.
3. Ein manipuliertes Auswahl-Request aktiviert weder unbekannte/gesperrte Werkzeuge noch umgeht es Web-Frageschalter, Org-Freigaben oder Dokumentrechte. In gesperrten Fällen kein externer Aufruf.
4. Auswahlwechsel während Planung/Bestätigung erweitert den ursprünglichen Fragekatalog nicht. Entzogene Freigaben sperren die Ausführung weiterhin.
5. Vorhandene Karten, Fortsetzungen, Abbruch, Limits, Audit und Schutz vor doppelten Schreibaktionen bleiben erhalten.
6. Gezielte Tests und einmal lokale Linux-CI auf dem finalen Produktstand als Nicht-root mit acl; unabhängiges Code-/Security-Review. Keine Browser-/Feldtests ohne PO-Auftrag.
7. Nach Merge PROJECT_STATE.md und TOOL_USE_PLAN.md mit tatsächlichem Stand und dem nächsten kleinsten TU-05-Schritt aktualisieren.

## Tests

Serverseitige Katalog-/Chat-/Autorisierungstests mit gezählten externen Aufrufen; Default, leere Auswahl, Teilmenge, fremder Kontext, unbekannte Identität, entzogene Freigabe und Auswahlwechsel während einer Frage. Gezielt UI-Zustand/Typprüfung und Übersetzungsprüfung im vorhandenen Build. Vollständiges ci-local.sh einmal am Ende.

## Workspace

Neuer eigener Worktree auf Branch feat/tool-use-chat-selection ab aktuellem main. Vor Beginn laufende Implementierungen prüfen; genau eine aktive Aufgabe. Implementierung und unabhängige Prüfung nach PO-Zuweisung. Draft-PR, kein Selbst-Approve. Diese Spec startet keine Implementierung.
