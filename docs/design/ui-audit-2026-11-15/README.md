# Audit-Artefakte – Phase 1

Produktbasis: `2de0a8a5203735507c00f56548daca2d2c2a05f9`, 15.11.2026.

| Datei | Zweck / Grenze |
|---|---|
| `source-index.json` | Vollständiger SFC-/Template-AST-Index der 36 Vue-Dateien: 527 relevante Elementstellen und 314 Bedingungen/Wiederholungen. Source-Hashes, Zeilen, Events, Props und Testids. Statische Erfassung, kein Funktions-/A11y-Test. |
| `COMPONENTS.md` | Alle 36 Dateien und ihre direkten Vue-Verwender. Das lokale Label-Wrapper-File ist vom verwendeten Reka-Label zu unterscheiden. |
| `coverage.json` | 109 fachliche Plan-Einträge mit Kriterien, gültigen Quellpfaden und Abhängigkeiten; alle Designstatus offen. Testfälle/Belege werden erst bei der jeweiligen späteren Umsetzung gefüllt. |
| `observations.json` | 32 live geöffnete Ansichten/Overlays, davon zwei bei 390×844, die übrigen bei 1440×1000. Keine vollständige Workflow-/Zustandsabnahme. |
| `screenshots/` | Zugehörige Aufnahmen aus der isolierten QA-App. Passwort-/Einladungscode-/Startpasswort-Elemente schon bei Aufnahme maskiert; der pinke Balken ist eine Maskierung, kein Designbefund. |
| `extract-source-index.cjs` | Lesender Quelltext-Extractor; schreibt nur die angegebene Indexdatei. Nutzt die bereits vorhandenen Vue-Compiler-Abhängigkeiten. |

## Statischen Index reproduzieren

Im Repository nach Installation der vorhandenen Desktop-Lockfile-Abhängigkeiten:

```bash
NODE_PATH=desktop/node_modules node docs/design/ui-audit-2026-11-15/extract-source-index.cjs . /tmp/keplura-ui-source-index.json
```

Das Ergebnis enthält den tatsächlichen aktuellen Git-Commit und Hashes der gelesenen Dateien. Bei einem späteren Stand ist es ein neuer Index; die hier gespeicherte Basis nicht kommentarlos überschreiben. Die erzeugte Git-Quelle kann nach einem reinen Dokumentationscommit eine andere SHA besitzen, obwohl die Produktdateien identisch sind; die SHA-256-Werte ermöglichen den exakten Produktvergleich.

Der Quelltextindex nimmt native Controls, Tags mit Rollen und Komponenteninstanzen auf. Deshalb zählen auch Icons und Portals, während rein dekorative HTML-Container nicht als eigene Oberfläche gezählt werden. Dynamische Code-Kopierbuttons und native Bestätigungen/OS-Dialoge werden im Fachinventar zusätzlich erklärt. `v-for` bezeichnet einen wiederverwendeten UI-Typ, keine fixe Anzahl kundenspezifischer Datensätze.

## Live-Erfassung

Lokale QA-App auf Port 1420 mit vorhandenem Testkonto, tatsächlicher PostgreSQL-Testdatenbank und konfigurierten Testquellen. Automatisiert über Chromium geöffnet: Zugang, Chat, Konto-/Org-Menü, globale Suche, Wissen und Anlage-/Umbenennungsdialoge, Team/Mitgliedanlage, alle zehn Administrationsbereiche, Verzeichnisanlage/Lookup, Assistent Schritt 1, Testfragenanlage/Import, Anbieter-/Modellanlagen sowie mobile Adminansicht/Navigation.

Nur die Anmeldung wurde abgeschickt; keine fachlichen Änderungsformulare gespeichert und keine destruktiven Vorgänge bestätigt. Listen reflektieren existierende QA-Daten. Insbesondere nicht allein wegen einer Aufnahme als geprüft melden: Anlage/Edit-Erfolg, Eingabevalidierung, Fehlerszenarien, Rechtewirkung, alle Assistentenschritte, Fokus-Traps, Kontraste oder Screenreader-Verhalten.

Besonders visuell begutachtete Dialogaufnahmen: `datenquelle-assistent-art.png`, `verzeichnis-anlegen.png`, `testfragen-import.png`, `mitglied-hinzufuegen.png`. Die übrigen Aufnahmen dokumentieren Erreichbarkeit/Bestand und sind keine Einzelabnahme. Frühere UI- und CI-Nachweise werden nicht auf den neuen Redesign-Auftrag übertragen.
