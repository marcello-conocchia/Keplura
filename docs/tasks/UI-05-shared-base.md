# UI-05 – Phase 5: Gemeinsame Basis migrieren

Auftrag 15.11.2026: „Fange mit Phase 5 an“ (nach PO-Abnahme Phase 4 „Ja, abnehmen“). Plan-ID P05 in
[`UI-REDESIGN-PLAN-2026-11-15.md`](../design/UI-REDESIGN-PLAN-2026-11-15.md), Migrationsregeln in
[`UI-MIGRATION-2026-11-15.md`](../design/UI-MIGRATION-2026-11-15.md), Verträge in
[`UI-COMPONENT-CONTRACTS-2026-11-15.md`](../design/UI-COMPONENT-CONTRACTS-2026-11-15.md) inkl. Phase-4-Korrekturen.

## Ziel / Scope

Die in Phase 4 validierten Basisbausteine werden zur einzigen Grundlage aller Ansichten: ein Button-, ein Feld-,
ein Dialog-/Bestätigungs- und ein Statusrezept. Doppelte Altkomponenten werden nach vollständiger Migration entfernt,
konkurrierende Altregeln gezielt ersetzt. Inventar-IDs: F02, F03, F04 (Basisgrößen), F05 (Busy/Fehler am Feld),
F06, F08, F09 (Rückmeldung in migrierten Stellen), F11 (Icongrößen), Z01.

Nicht-Ziele: Neugestaltung einzelner Fachseiten (P06–P12), Shell/Navigation, neue Funktionen, Backend-/API-/Rechte-
änderungen, Befund S-1 (Server-CORS), Merge/Release/Deployment. Handler, Testids, Rechte und Benutzerwege bleiben.

Workspace `.worktrees/ui-base`, Branch `codex/ui-base`, gestapelt auf `codex/ui-validation@c1d2332` (Reviewbase).

## Abnahmekriterien

| ID | Anforderung | Ergebnis |
|---|---|---|
| W01 | `AppButton` vollständig auf `Button` migriert und entfernt; Varianten/Größen/Submit/Disabled/Testids erhalten | umgesetzt; Basisregression 17/17 im Folgepaket UI-08 |
| W02 | `Input` erfüllt FO-Vertrag (hint, error mit `aria-describedby`/`aria-invalid`, required/readonly/disabled, kein `<p>`-Wrapper, 40/44 px, 16 px mobil); `TextField` migriert und entfernt | umgesetzt; Basisregression 17/17 im Folgepaket UI-08 |
| W03 | Ungenutzter `Label`-Wrapper entfernt, reka-Label in `Input` erhalten (AT-SPI-Name der Login-Felder unverändert) | umgesetzt; Basisregression 17/17 im Folgepaket UI-08 |
| W04 | Native `window.confirm` (MCP entfernen, Embedding-Standard wechseln) durch `ConfirmDialog` ersetzt, Fokus/Escape/Busy/Fehler wie AppDialog | umgesetzt; Basisregression 17/17 im Folgepaket UI-08 |
| W05 | Statusdarstellung: `StatusPill` mit Textlabel, kompatible Tones, keine Live-Region pro Zeile; geprüft in allen Verwendern | offen |
| W06 | Rohe Buttons/Felder in migrierten Ansichten nutzen die Basisrezepte oder sind begründet ausgenommen (Liste im Beleg) | umgesetzt; Basisregression 17/17 im Folgepaket UI-08 |
| W07 | Altregeln für Buttons/Felder in `workspace.css`/`tailwind.css` ersetzt statt überschrieben; keine neuen `!important` | umgesetzt; Basisregression 17/17 im Folgepaket UI-08 |
| W08 | Regressionsläufe: Login (Browser + nativ), Team/Mitglieder, Wissen, Admin inkl. MCP, Phase-4-Läufe; Hell/Dunkel, 390/768/1440 | offen |
| W09 | Vollständiges `ci-local.sh --with-e2e` auf finalem Produkt-SHA | offen; zwei Anläufe vorzeitig zur Rechnerentlastung beendet, kein grüner Gesamtnachweis |
| W10 | Belege/Screenshots, Systemkorrekturen zentral, PROJECT_STATE aktualisiert | offen |

## Tests / Nachweise

Build mit Tokencheck/vue-tsc, echte QA-App, vorhandene E2E (`invite`, `admin`, `knowledge`, `auth` nativ) plus
Phase-4-Läufe als Regression, gezielte Prüfung der neuen Bestätigungen und Feldfehler. Simulierte Fehler werden
gekennzeichnet. Unabhängige Reviewer-/QA-/Security-Gates sind durch eigene Tests nicht ersetzt.

## Übergabe 15.11.2026 (Claude Code → Codex)

Auf Wunsch des Inhabers gestoppt („stoppe“, dann „ich lass codex weiter machen, pushe alles“). Stand:

- Erledigt, nur gebaut (vue-tsc/Vite grün), **noch nicht im Browser oder per E2E geprüft**: W01 AppButton→Button entfernt; W02 Input nach FO-Vertrag neu, TextField migriert/entfernt; W03 Label-Wrapper entfernt. Commit `ad96f26`.
- Risiko für die Prüfung: Admin-Felder erhalten weiterhin Radius/Höhe aus `#app .admin-content input` in `workspace.css` (höhere Spezifität) – unter W07 auflösen. Compact-Buttons in Admin/Team/Mitglieder visuell gegen vorher prüfen.
- Als Nächstes: W04 `window.confirm` in `admin/McpPanel.vue` (`remove`) und `AdminPanel.vue` (`makeDefault`, Embedding) durch `ConfirmDialog` ersetzen; dann W05–W10.
- Lokale Rohfeld-Rezepte (DirectoryPanel, QualityPanel, McpPanel, WebSearchPanel, ChangePasswordView, OnboardingView, DocumentTable) noch nicht konsolidiert (W06/W07).
- Testumgebung wie Phase 4: Läufe `desktop/e2e/ui4_*.cjs` im Container `keplura-ci` (Frontend aus `/workspace-ui-validation/desktop` – für diesen Branch Quelldateien neu synchronisieren); CI im separaten Container `keplura-ui4-ci` mit `NODE_PATH=/opt/e2e/node_modules` und `E2E_CHROME=/root/.cache/ms-playwright/chromium_headless_shell-1255/chrome-headless-shell-linux64/chrome-headless-shell`.

Aktueller Prüfstand und Grenzen: `docs/design/ui-base-2026-11-15/README.md` und Folgebelege `docs/design/ui-knowledge-2026-11-15/README.md`. Keine vollständige Abnahme bzw. Merge-Freigabe.
