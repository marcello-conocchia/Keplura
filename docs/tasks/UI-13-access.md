# UI-13 — Zugang und Ersteinrichtung im Katalogstil

PO-Auftrag 19.11.2026 („Ja, weitermachen“ nach UI-12): letztes offenes UI-Paket des Inventars (A01–A06 in `docs/design/UI-INVENTORY-2026-11-15.md`). Branch `claude/ui-access` von `main@7c7eff4`.

## Ziel

Anmeldung, Pflicht-Passwortwechsel und Sitzungsprüfung teilen einen ruhigen gemeinsamen Rahmen; die Ersteinrichtung führt Schritt für Schritt mit sichtbaren Feldbeschriftungen und Fehlern am richtigen Ort.

## Scope

- Neu `components/layout/AccessShell.vue`: zentrierte Karte mit Marke, Titel, Einleitung, optionalem Fuß.
- `LoginView.vue`: auf AccessShell; Meldungen als Hinweisfläche mit `role="alert"`; nicht erreichbarer Server als eigener Hinweis oben mit „Erneut versuchen“ (`login-offline`, `connection-retry`); Firmenkonto-Fehler nennt den lokalen Alternativweg; Verbindung + Darstellung/Vollbild im Fuß.
- `ChangePasswordView.vue`: auf AccessShell, gemeinsame Eingabefelder, Regeln mit Zustand auch für Screenreader, Felder während des Speicherns gesperrt, Passwörter beim Verlassen geleert, `expired` beendet die Sitzung.
- `App.vue`: Sitzungsprüfung („Sitzung wird geprüft …“) im selben Rahmen.
- `OnboardingView.vue`: Fortschritt „x von 3“, sichtbare Feldbeschriftungen, Fehler am aktiven Schritt, Fokus springt nach einem erledigten Schritt in das nächste Feld, Folgeschritte als „Danach“.
- `styles/workspace.css`: veraltete globale Überschreibungen für Login-, Ersteinrichtungs- und alte Team-Klassen entfernt (sie verbogen auch die neue Team-Seite aus UI-12: eingerückte Zeilen, eckige Avatare).
- `styles/administration.css`: Hinweisvariante `connection-note.warn`.

## Non-Goals

Keine Änderung an Server, API, Anmelde-/OIDC-Ablauf, Passwortregeln, Sitzungs- oder CSRF-Logik. Keine neue Funktion (z. B. kein „Passwort anzeigen“, kein Passwort-vergessen). Externe IdP-Seiten bleiben unberührt.

## Unverändert (sicherheitsrelevant)

- Grobe Fehlermeldungen bei der Anmeldung (keine Unterscheidung Konto/Passwort, ADR-0003).
- OIDC-Knopf nur bei aktivem Firmen-IdP (schließt bei Fehler).
- Passwortregeln und `changePassword`-Aufruf identisch; Pflichtwechsel bleibt vor der App.
- `autocomplete`-Angaben (username/current-password/new-password) bleiben.

## Acceptance Criteria

| ID | Abnahme |
|---|---|
| Z-A1 | Anmeldung, Passwortwechsel, Sitzungsprüfung im gemeinsamen Rahmen |
| Z-A2 | Falsche Daten, Sperre, offline, Firmenkonto-Fehler jeweils sichtbar und angesagt |
| Z-A3 | Passwortwechsel: Regeln, Fehler, Busy, Abmelden funktionieren wie bisher |
| Z-A4 | Ersteinrichtung: drei Schritte nacheinander, Fehler am Schritt, Fokus wandert |
| Z-A5 | Test-IDs (`login-*`, `cp-*`, `onboarding-*`, `auth-restoring`, `connection-*`, `theme-toggle`, `fullscreen-toggle`) bleiben |
| Z-A6 | Typecheck/Build grün; Screenshots; ci-local einmal am Ende |

## Klasse

A — Anmeldung und Passwortwechsel (Authentifizierung). Reine Oberflächenänderung, aber unabhängiger Reviewer + Security vor Merge.
