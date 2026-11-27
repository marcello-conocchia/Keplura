# TU-06e — Anmeldung für persönliche Verbindungen im Systembrowser öffnen (Desktop)

Status: **spezifiziert, nicht gestartet.** Folgeaufgabe zu [TU-06](TU-06-adapters-oauth-stdio-media.md) (TU-06b). Start erst, wenn TU-06 (PR #39) auf main ist (genau eine aktive Implementierung). PO-Entscheidung 27.11.2026 im Chat: „Eigener PR nach #39“ (statt eines fünften Commits in #39 oder des dauerhaften Verzichts).

Klasse A: neue Laufzeitabhängigkeit, und die App öffnet eine vom Server gelieferte Adresse außerhalb der App.

## Ziel

In der Desktop-App öffnet „Verbinden“ die OAuth-Anmeldung direkt im Systembrowser, wie es TU-06b vorsieht. Bisher zeigt die App nur den Link zum Öffnen oder Kopieren, weil Tauri ohne Zusatz-Plugin keinen Systembrowser erreicht.

## Scope

- Neue Abhängigkeit **`tauri-plugin-opener`** (Rust-Crate) und **`@tauri-apps/plugin-opener`** (npm), beide Version 2 aus dem offiziellen Tauri-Plugin-Workspace, also aus derselben Quelle wie das vorhandene `tauri-plugin-http`. Lockfiles (`Cargo.lock`, `package-lock.json`) werden aktualisiert, Lizenzinventar `docs/legal/THIRD_PARTY_LICENSES.md` neu erzeugt.
- **Capability so eng wie möglich** in `desktop/src-tauri/capabilities/default.json`: nur `opener:allow-open-url`, Scope ausschließlich `https://*`. Kein `allow-open-path`, kein `allow-reveal-item-in-dir`, keine anderen Schemata (`file:`, `http:`, eigene Schemata).
- `ConnectionsView.vue`: In Tauri wird `authorizationUrl` mit `openUrl` geöffnet.
  - Vorher prüft die App die Adresse selbst: Sie muss sich parsen lassen, das Schema `https:` haben und darf keine Zugangsdaten enthalten (`user:pass@`). Sonst öffnet die App nichts und zeigt einen festen Fehlertext.
  - Neben dem Hinweis „Melde dich im Browser an“ zeigt die App den **Ziel-Host**, damit die Person sieht, wo sie sich anmeldet.
  - Schlägt `openUrl` fehl, bleibt der bisherige Weg mit Link öffnen oder kopieren als Rückfall.
  - Das Web-Verhalten (`window.open` in einem neuen Tab) bleibt unverändert.
- Der Statusabruf (Polling) bleibt unverändert.

## Non-Goals

- kein Deep Link und kein eigenes URL-Schema zurück in die App, kein lokaler Loopback-Callback; der Callback bleibt fest auf dem Keplura-Ursprung (TU-06b);
- keine Nutzung des Openers an anderen Stellen (Quellen-Links, Hilfe usw.) in diesem PR;
- keine Änderung am Server, am OAuth-Ablauf oder an der Bindung an Sitzung, Person, Org und Server;
- `http:` für lokale Test-Autorisierungsserver nicht im Scope der ausgelieferten App (Tests laufen ohne Opener);
- kein Release/Deployment.

## Acceptance Criteria

1. In der Desktop-App öffnet „Verbinden“ bei einer `https:`-Adresse den Systembrowser. Danach fragt die App den Status wie bisher ab.
2. `http:`-, `file:`-, `javascript:`- und eigene Schema-Adressen sowie Adressen mit Zugangsdaten werden weder von der App-Prüfung noch vom Capability-Scope geöffnet. Die Prüfung ist eine reine Funktion, die Capability-Datei wird im Review geprüft.
3. Schlägt das Öffnen fehl, erscheint der bisherige Link zum Öffnen oder Kopieren.
4. Im Web ändert sich nichts.
5. Die Capability gewährt nur `opener:allow-open-url` mit `https://*`. Es gibt keine weiteren Opener-Rechte.
6. Lizenzinventar und Lockfiles sind aktuell, `cargo check` und der Desktop-Build sind grün.
7. Einmal lokale Linux-CI am Ende als Nicht-root mit acl. Unabhängiges Code- und Security-Review gemäß AGENTS.md.
8. Nach dem Merge werden PROJECT_STATE.md und TOOL_USE_PLAN.md aktualisiert: TU-06e abgeschlossen, nächster Schritt TU-07.

## Tests

- Der Desktop hat keinen Unit-Test-Runner, und ein neuer wäre eine weitere Abhängigkeit. Die URL-Prüfung wird deshalb als reine Funktion geschrieben, über die Typprüfung im Build abgesichert und im Review mit den Fällen aus Kriterium 2 durchgegangen.
- Desktop-Build, Übersetzungsprüfung (de/en für neue Texte), `cargo check`.
- Keine Browser- oder Feldtests ohne PO-Auftrag.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-tu06e` ab main **nach** dem Merge von TU-06. Ein Commit, Draft-PR, kein Merge durch den Entwickler.
