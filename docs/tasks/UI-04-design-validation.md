# UI-04 – Phase 4: Designsystem im realen Ablauf validieren

Auftrag 15.11.2026: „Jo starte mit Phase 4“. Status: Phase 4 validiert und vom Inhaber abgenommen („Ja, abnehmen“, 15.11.2026; Fortsetzung nach [Übergabe](../HANDOFF-UI-PHASE4-2026-11-15.md)); Ergebnisse unten, Belege in [ui-validation-2026-11-15](../design/ui-validation-2026-11-15/README.md). Keine Merge-Freigabe, unabhängige Gates offen, P05 nicht begonnen.

## Ziel / Scope

System 1.0 an einer echten Chat-Unterhaltung und am komplexen Datenquellen-Assistenten umsetzen und beurteilen. Tokens, gemeinsam benötigte Buttons/Dialogs, Antwort-/Composer-/Quellenlayout, Datenzeile und Secret-Feld sind Teil dieser begrenzten Validierung. Zusammengehörige CSS-Rezepte ersetzen statt weitere Overrides anhängen. Fachliche Handler, Rechte, APIs, Testids und vorhandene Benutzerwege erhalten.

Nicht-Ziele: vollständige Migration aller Fachseiten, Frameworkwechsel, neue Backend-/Router-/Persistenzfunktionen, Merge/Release/Deployment. Gemeinsame Basisänderungen können bisherige Ansichten optisch beeinflussen; diese erhalten Regressionstests, keine pauschale neue Designabnahme.

Workspace `.worktrees/ui-validation`, Branch `feat/ui-validation`, von main angelegt und per Fast-forward bis `feat/ui-system@7a9f104` fortgeführt. Reviewbase `feat/ui-system`, bisherige Abhängigkeiten bleiben ungemergt. Kleine Commits unter Marcello Conocchia, Push ohne Force. Bestehende Pilotdaten/-instanz erhalten; Tests ausschließlich an eigenen QA-Daten.

## Abnahmekriterien / Prüfliste

Alle Punkte beginnen offen. Pro Ergebnis Commit/Beleg und Einschränkung dokumentieren.

| ID | Anforderung | Ergebnis |
|---|---|---|
| V01 | Autoritative Tokens in Laufzeit, keine widersprüchlichen Workspace-Tokenblöcke; Hell/Dunkel | bestanden – Tokencheck im Build, keine weiteren --ic-Definitionen, Hell/Dunkel gemessen – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V02 | Chat mit realer langer Antwort, Markdown/Tabelle/Code, Kontext/Quellen und Anhang; lesbare Hierarchie | bestanden – echte qwen-Antwort mit Tabelle/Code/4 Quellen, Anhang genutzt – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V03 | Chat Senden/Streaming/Stopp/Retry, Feedback/Export/Vorlagen und Tool-Rückfall bleiben funktionsfähig | teilweise – Senden/Stopp/Retry/Feedback/Export/Vorlagen bestanden; MCP-Tool-Rückfall nicht erneut geprüft – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V04 | Hauptbasiswechsel erhält Prompt und startet gemäß bestehendem Vertrag neuen Chat; Zusatzbasen getrennt | bestanden – Entwurf bleibt, neuer Chat an Basis gebunden, Zusatzbasis getrennt – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V05 | Quelle zitiert an passender Stelle, Seite soweit vorhanden; Download, Lade-/Fehler-/Rechtezustände | bestanden mit Einschränkung – Stelle/Download/Modalität echt; Laden/404/403 simuliert; keine PDF-Seiten geprüft – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V06 | Chat/Quelle bei390/768/1440 sowie320px und900×640/480×400; Fokus, kein Root-Überlauf | bestanden – 7 Größen hell/dunkel, kein Root-/Innenüberlauf, Drehen geprüft – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V07 | Assistent mit eigenem großen Rahmen, sechs verständlichen Schritten, gut erreichbaren Aktionen | bestanden – 1040-px-Rahmen, sechs Schritte, erreichbarer Fuß – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V08 | LOCAL und SMB: Verbindung/Probelauf/Rechte/Zeitplan/Aktivierung mit echten QA-Quellen; SharePoint Formular und explizite Integrationsgrenze | LOCAL/SMB bestanden (echt bis „Aktuell“); SharePoint nur Formular, Integration offen – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V09 | ACL-Warnungen sichtbar, keine stillen Rechteänderungen; Verbindung-/Save-/Aktivierungsfehler wahrheitsgemäß | bestanden mit Befund S-1 (Server-CORS ohne DELETE für Admin, Browser-Dev-Modus) – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V10 | Secret beim Bearbeiten beibehalten/ersetzen gemäß vorhandener API, kein Klartext in Belegen | bestanden – Secret geleert/beibehalten/ersetzt, kein Klartext in Belegen – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V11 | Dialog Initialfokus, Tab/Shift+Tab/Escape, Rückkehr, Dirty-Abbruch, Busy-Sperre, Fehler mit Eingabeerhalt | bestanden – inkl. Fokusfalle und Doppel-Escape (vorher rot, behoben) – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V12 | Assistent/Verbindung/ACL auf schmaler/niedriger Ansicht und Dunkel; großes Modal gegenüber eigener Arbeitsansicht begründet | bestanden – Entscheidung großes Modal, begründet in Belegen – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V13 | Tatsächliche Renderkontraste/Zielgrößen, Zoom/reduced-motion und Tastatur geprüft; keine ungemessene A11y-Behauptung | bestanden mit Grenzen – Kontrast/Ziele/Reflow/reduced-motion/Tastatur gemessen; kein Screenreader, Zoom als CSS-Äquivalent – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V14 | App gebaut/gestartet; vollständiges lokales CI auf finalem Produktcommit, relevante Regressionen/E2E | bestanden – ci-local.sh --with-e2e Exit 0 auf 7bd2304 (449 Tests/66 Klassen, Build, cargo, 9 E2E-Suiten), ci/evidence/UI-04-20261115-144820 – [Belege](../design/ui-validation-2026-11-15/README.md) |
| V15 | Screenshots, Befunde/Entscheidungen und Systemkorrekturen nachvollziehbar; Tracker nur mit tatsächlich geprüften Fällen | bestanden – Belege und Systemkorrekturen nachgeführt – [Belege](../design/ui-validation-2026-11-15/README.md) |

## Tests / Nachweise

Reproduzierbare Tokenprüfung; Frontend-Typecheck/Build, reale QA-App mit vorhandener PostgreSQL-/Modell-/Verzeichnis-/SMB-Umgebung; vorhandene E2Es plus gezielte neue Fokus-/Fehler-/Abbruchfälle. Vollständiger lokaler CI-Lauf nach letztem Produktfix. Keine vorhandene JAR in laufendem Betrieb überschreiben. Ursprüngliche Phase-3-Prüfung bleibt historischer Nachweis und muss nach Produktänderungen nicht unveränderte Audit-Hashes behaupten.

Externe Integrationen ohne Testumgebung (insbesondere SharePoint/Entra) bleiben mit Grund offen. Unabhängige Reviewer-/QA-/gegebenenfalls Security-Freigaben sind durch eigene Tests nicht ersetzt. P05 beginnt erst nach dokumentierter Phase-4-Entscheidung.
