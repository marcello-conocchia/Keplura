# Gemeinsame Basis – tatsächliche Nachweise

Referenz `reference-dark.png`: der vom Inhaber angehängte eigene Designkatalog. Produktrezepte stehen zentral in `desktop/src/styles/controls.css`, Werte weiterhin in `system-v1/tokens.json`.

- `18e9fd1`: Button-/Feld-/Menü-/Statusrezepte, sichere Bestätigungen für MCP und Embedding-Standardwechsel. Build erfolgreich. `base/results.json`: 17/17; Bausteine in sechs Theme-/Breitenkombinationen, echte Team-/Wissens-/Adminansichten. Synthetische MCP-/Modellzeilen und simulierte HTTP-Fehler sind je Punkt markiert. Keine produktiven Konfigurationen gelöscht oder geändert.
- `7e29b00`: zusammengesetzte Suchfelder auf Input umgestellt (doppelten Rahmen entfernt), Dokumentaktionen auf Touch sichtbar; gebaut. Die vorherige 17/17-Evidence ersetzt keine Prüfung dieser späteren Änderung.
- `wizard-results.json`: Phase-4-Assistent 34/34 gegen QA, inklusive echtem LOCAL/SMB. `a11y-results.json`: 12/12 für Kontrast, Ziele, Reflow, Fokus, reduzierte Bewegung. Beide vor dem separaten Chat-/Navigationsumbau; keine Behauptung, sie seien auf dessen finalem SHA gelaufen.
- `chat-interrupted-results.json`: echter Modellversuch rot, bevor der Lauf unterbrochen wurde: erwartete Frist „37 Tage“ fehlte. Danach geschlossener Browser, Folgetests nicht auswertbar. Dieser Lauf ist ausdrücklich kein bestandener Chat-Regressionsnachweis.
- Voll-CI auf `18e9fd1`: Anläufe `20261115-153932` und `20261115-154340` unterbrochen, um den beim Spielen belasteten Rechner zu entlasten. Kein Exit 0, kein vollständiger Nachweis. Testcontainer gestoppt; Modelle entladen. Vor einem Merge bleibt ein vollständiger Lauf auf dem endgültigen PR-SHA erforderlich.

Bewusste Ausnahmen von normalen Buttons/Feldern: Navigation und aufklappbare Dokumentgruppen, zusammengesetzte Chat-Eingabe, Quellenpanel, Suchpalette, native Dateiauswahl, Checkbox-/Radio-Gruppen. Diese haben eigene Interaktionsverträge. Normale Aktionsbuttons und Textfelder verwenden die Basisrezepte. Die Seitenlayouts werden in den nachfolgenden Paketen einzeln umgebaut.

Offen: bekannter S-1 (Server-CORS ohne DELETE im Browser-Dev-Modus), vollständige CI, finale Regressionen und unabhängige Reviews. Keine Merge-Freigabe. Die neuen Bestätigungen zeigen Fehler weiter im Dialog; ein simulierter Erfolg ist kein Beweis für einen echten Browser-DELETE.
