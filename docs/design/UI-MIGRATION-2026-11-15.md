# Migration des vollständigen UI-Bestands auf System 1.0

Stand 15.11.2026. **Migrationsspezifikation, noch keine Produktmigration.** Grundlage: [36-Dateien-Register](ui-audit-2026-11-15/COMPONENTS.md), [109-ID-Tracker](ui-audit-2026-11-15/coverage.json), [Systemdefinition](UI-DESIGN-SYSTEM-2026-11-15.md), [Verträge](UI-COMPONENT-CONTRACTS-2026-11-15.md).

## Arbeitsauftrag und Grenzen

Ziel von P03: ein eindeutiges Soll für Farben/Maße, Varianten, Zustände, Interaktionen und Altbestand festlegen. Workspace `.worktrees/ui-system`, Branch `codex/ui-system`, von main angelegt und per Fast-forward bis zur noch ungemergten Recherche `3a8f398` fortgeführt. Der eigene Review-Diff gegen `codex/ui-research` umfasst nur Dokumentation und einen isolierten Referenzkatalog. Kein Produktimport, Serverzugriff, neues Feature, Merge, Release oder Deployment. Produktbasis bleibt `2de0a8a`.

Abnahme dieser Spezifikation: gleiche Hell-/Dunkelrollen, berechnete erlaubte Kontrastpaare, vollständige Zuweisung der 36 Vue-Dateien und 109 IDs, konkrete State-/API-/Responsiveverträge, reproduzierbarer Katalog und überprüfbare nächste Arbeit. Reale Produkt-/Accessibility-Abnahmen sowie unabhängige Reviews bleiben späteren Gates vorbehalten. Nachweise: [Prüfprotokoll](system-v1/README.md).

## CSS- und Tokenkonsolidierung

| Bestand | Ziel / konkreter Migrationsschritt | Erhaltungsbedingung |
|---|---|---|
| theme.css: Tokenwerte plus globale Rezepte | Runtime-Tokenblock aus system-v1/tokens.json erzeugen; Basistypografie/Fokus separat klar begrenzen | Bestehendes html[data-ic-theme] und Vorab-Theme-Skript behalten; keine externen Fonts |
| tailwind.css: unlayered Utilities, enges @source und eigene Rezepte | Nur Tokenbridge/Utilities, einheitliche Werte statt eigener Button-/Cardrezepte | Bestehenden Kaskadenmodus während Migration erhalten; kein globales Preflight einschalten |
| workspace.css: breite Overrides und !important | Jede Regel einer Shell-/Komponente zuordnen und dort ersetzen; nach Entfernung aller Verbraucher Datei entfernen | Nicht einfach noch höher spezifizieren; keine vorzeitige Löschung aktiver Stilregeln |
| Scoped CSS in Fachkomponenten | Nur fachliches Layout auf gemeinsamen Tokens; allgemeine Button-/Input-/Menürezepte entfernen | Aussehen/Verhalten auch in Body-/Dialogportals prüfen |
| AppButton vs Button | Ein Rezept in Button; AppButton als vorübergehender Adapter, danach ausbauen | AppButton compact→Button compact; alle Events und nativen Types erhalten |
| TextField vs Input | Ein Feldvertrag in Input; TextField als Adapter, später ausbauen | focus(), String-v-model, Inputattribute/IDs/Testids erhalten |
| --ic-primary vs --ic-accent | primary/on-primary/primary-hover als Aliasse auf dieselben Akzentrollen | Keine unterschiedlichen Primäraktionen pro Importpfad |
| --ic-danger mit rotem Rohwert-Fallback in MCP/Websuche | Kompatibilitätsalias danger→status-error; Verbraucher gezielt auf Semantik migrieren | Dark-Fehlertext nicht mehr vom ungeprüften Light-Fallback abhängig |
| --ic-radius-pill / shadow-raised / shadow-hairline | Übergangsaliase; kleine Tags 4 px, raised=none, hairline nur dekorative Kontur | Pflicht-Controlgrenze verwendet input-edge, keine schwache Hairline |
| --ic-focus-ring / --ic-transition | Kompatibilität definieren; neuer sichtbarer Outlinevertrag plus Reduced-Motion-Regel | Outline in Forced-colors erhalten, keine globale outline:none-Regel |
| Historische Designtexte / Kommentare | Historisch kennzeichnen, aktuelle Dokumente verlinken; veraltete Größen-/Font-/Freeze-Kommentare gezielt korrigieren | Keine parallele aktuelle Quelle; keine historische CI-Evidence überschreiben |

Kein sofortiger Wechsel des gesamten CSS auf neue Layers: unlayered Legacy-Regeln könnten neue Layer unbeabsichtigt schlagen. P04 prüft die Basis in begrenztem Umfang; P05 ersetzt vollständige Rezepte mit klarer Zuständigkeit. Neue Klassen dürfen keine unveränderten Fachbereiche beiläufig umgestalten. Aliasse sind benannte Kompatibilität, keine zweite Wertetabelle. Ihre Entfernung setzt nachgewiesene Verbraucherfreiheit voraus.

## Zuordnung jeder bestehenden Vue-Datei

Alle Dateiangaben relativ zu `desktop/src/`, außer App.vue ebenfalls dort. DS=Grundlage, BT=Aktion, FO=Feld/Form, OV=Overlay, DA=Daten/Feedback, SH=Shell/Kontext, CH=Lesen/Schreiben, MG=Migration. Zielnamen sind Verantwortung, keine Pflicht, alle Dateien umzubenennen oder zusätzliche Abstraktionen zu erzeugen.

| Datei | Ziel / Änderung | Paket / besondere Abnahme |
|---|---|---|
| App.vue | SH/DS: Shell, Guard, Geräte/Drawer, Sitzung | P06; Login-/Sitzungs-/Onboardingpfade, Fokus und geringer Viewport |
| components/AppSidebar.vue | SH: beschriftete Org-/Bereichsauswahl, Ziele, kontextueller Verlauf, Konto | P06; alle Ziele/Kontofunktionen, kein doppelter vollständiger Wissensbaum |
| components/LoginView.vue | FO/SH: ruhige Zugangsmasken mit lokal/Firmenkonto | P06; native Anmeldung, Fehlermeldung, Transport-/Taurioptionen |
| components/ChangePasswordView.vue | FO/SH: Feld-/Fehler-/Busyvertrag | P06; Pflichtwechsel und Rückweg bleiben korrekt |
| components/OnboardingView.vue | FO/SH: bestehende Einrichtungsfolge | P06; fehlende Org/Bereich/Basis, Folgeaktionen, Rollengrenzen |
| components/ChatView.vue | CH/SH: Lesebereich, Composer, Basisvertrag, Zustand/Antwortaktionen | P07; Streaming, Tool-Rückfall, lange Inhalte, Kontextwechsel, kein verlorener Prompt |
| components/CommandPalette.vue | OV/DA: echter Suchdialog, Fehler getrennt von leer | P07; Strg/⌘+K, Keyboard, aktive Ergebniszeile, Scope |
| components/SourcePreview.vue | OV/CH: nichtmodal breit, modal schmal, Textstelle | P07; Resize-/Fokuswechsel, Seite/Abschnitt, Download/Fehler/Rechte |
| components/KnowledgeView.vue | SH: Explorer-Seitengerüst | P08; Header und Fachkontext bei allen Größen |
| components/KnowledgePanel.vue | SH/FO/OV/DA: Auswahl, Suche, Upload und Fachdialoge | P08; bestehende Aktionen/Storeteardown, alle Dialogzustände |
| components/knowledge/KbList.vue | SH/DA: Basisnavigation, Aktionen | P08; Keyboard, lange Namen, Anlage/Löschung, mobil |
| components/knowledge/DocumentTable.vue | DA: scannbare Dokumentzeilen, Status/Aktionen | P08; echte Liste/Tabelle bewusst wählen, Filter/Download/Reindex/Delete |
| components/knowledge/StatusRing.vue | DA: gleicher Statuswortschatz, nur echter Fortschritt | P08; unbekannter Fortschritt und Fehler |
| components/TeamView.vue | SH/FO/OV: Organisationen und Dialoge | P09; OWNER/MEMBER getrennt von globalem ADMIN |
| components/MembersPanel.vue | SH/DA/FO: Mitglieder, Einladungen, einmalige Zugänge | P09; Codebereinigung, Entfernen/Zurückziehen/Einlösen, Kopierfehler |
| components/AdminPanel.vue | SH/FO/DA: eine Unternavigation, Anbieter/Modelle/RAG/Status | P10; alle Inlinevarianten, Defaults, Secrets, globale Rechte |
| components/admin/DataSourcesPanel.vue | SH/OV/FO/DA: eigener Assistentenrahmen, Quellenstatus | P11; alle sechs Schritte/Quellenarten, ACL, Probelauf, Busy/Abbruch |
| components/admin/DirectoryPanel.vue | SH/FO/OV: gegliederte Langform, Test/Lookup | P11; AD/LDAP, TLS/Provisionierung, lange DNs/Fehler |
| components/admin/QualityPanel.vue | SH/DA/FO/OV: Qualitätsdaten, Fragen und Import | P12; Kennzahlen, Importfehler/Teilstand, Anlage/Löschung |
| components/admin/WebSearchPanel.vue | SH/FO/DA: vier Anbietervarianten und Test | P12; Secret beibehalten/ersetzen/entfernen, Grenzen/Domains |
| components/admin/McpPanel.vue | SH/DA/FO/OV: kompakte Serverzeilen, Freigaben/Journal | P12; Werkzeug-/Bereichsrechte, Fehler, native Confirmablösung |
| components/admin/BackupPanel.vue | SH/DA: Status und geordnete Betriebshilfe | P12; Backup-/Zeitplan-/Restore-CLI verständlich, kein erfundener UI-Run |
| components/chat/ChatContextBar.vue | CH/FO/OV: Kontextchips und Checkbox-Menü | P07; echte Grenzen, Anhänge, Mutationserfolg/-fehler, Theme/Portal |
| components/chat/TemplateMenu.vue | CH/OV/FO: Menü, Speichern/Verwalten | P07; alle Vorlagenwege, lange Inhalte und Fehler |
| components/ui/MarkdownAnswer.vue | CH: gemeinsame Lesetypografie | P07; Sanitizing, Links, Codekopie, Tabelle/Überlauf |
| components/layout/PageContainer.vue | SH/DS: drei fachliche Layouttypen, flexible Grenzen | P05/P06; Scrollbesitz und Breiten |
| components/layout/PageHeader.vue | SH/DS: klarer Titel/Kontext/Aktionen | P05/P06; deckender Hintergrund, Umbruch/Zoom, keine feste 48 px-Klemme |
| components/ui/Button.vue | BT: autoritativer Buttonvertrag | P04/P05; Varianten, compact/default, Busy/disabled, Submit/Link |
| components/ui/AppButton.vue | BT/MG: Adapter, danach entfernen | P05; drei Verwenderfamilien und compact korrekt übertragen |
| components/ui/Input.vue | FO: autoritativer Textfeldvertrag | P04/P05; Attribute, Label/Hint/Error, focus(), v-model |
| components/ui/TextField.vue | FO/MG: Adapter, danach entfernen | P05; alle bestehenden Feldattribute und Events |
| components/ui/Label.vue | FO/MG: ungenutzten Wrapper prüfen/abbauen | P05; verwendetes Reka-Label nicht versehentlich entfernen |
| components/ui/AppDialog.vue | OV: Varianten und zentraler Schließguard | P04/P05; native Top Layer, Dirty/Busy, Fehler/Fokus/Portals |
| components/ui/ConfirmDialog.vue | OV/BT: gleiche risikobasierte Bestätigung | P05; alle Löschwege, native Confirm in Admin/MCP später migrieren |
| components/ui/AppIcon.vue | DS: bestehende Familie konsolidieren | P05; alle Namen erhalten, Stroke/Sizes, Accessible Names |
| components/ui/StatusPill.vue | DA: gemeinsame Semantik und Textlabel | P05; bestehende tone-Namen kompatibel, keine Live-Region pro Zeile |

Zusätzlich zwingend erhalten/gezielt anpassen: `main.ts`/`index.html` (lokaler Font, Theme vor Paint, lang=de), `theme-store.ts`, `navigation.ts`, `auth-store.ts`, `fullscreen.ts`, `source-preview.ts`, Clipboard-/Transport-/API-Helfer, dynamische Markdownbuttons sowie native OS-Dateiauswahl/Downloads. Diese sind keine weiteren Vue-Dateien und dürfen nicht durch die 36er-Zahl aus dem Migrationsumfang fallen. Keine Umgestaltung externer IdP-/M 365-/OS-Seiten.

## Status- und Arbeitspaketfolge

1. **P03: hier spezifiziert.** Werte/Verträge/Katalog prüfen, Definition und Evidenz versionieren. Kein Inventareintrag als produktiv umgesetzt/geprüft markieren.
2. **P04: genau eine nächste Implementierungsaufgabe.** Tokens/Primitive minimal an Chat und komplexem Datenquellenrahmen erproben. Reale lange Unterhaltung, Quellen/Anhänge, Datenzeile/Secret und alle sechs Assistentenschritte einschließlich Problemfällen. Änderungen am System zuerst zentral nachführen.
3. **P05: gemeinsame Basis.** Erst nach P04 Basiskomponenten, Portals, Rückmeldung und CSS-Zuständigkeiten vollständig konsolidieren. Abweichende Altrezepte nicht blind entfernen.
4. **P06–P12: Fachpakete** entsprechend [Gesamtplan](UI-REDESIGN-PLAN-2026-11-15.md); jeweils betroffene Elemente, Rollen und Negativzustände real prüfen. Ein gemeinsamer Buttontest ersetzt keinen lokalen API-/Kontexttest.
5. **P13/P14:** erneuter Gesamtvergleich, neue/entfernte Quelltextstellen dokumentieren, jede 109-ID mit Zustandsnachweisen schließen. Nicht prüfbare externe Integrationen bleiben ausdrücklich offen.

Pro Umsetzungspaket: Spezifikation und betroffene IDs, Build/Start, passende reale Tests und Screenshots, kleine Commits unter Marcello Conocchia, regelmäßiger Push. Lokales `scripts/ci-local.sh` auf finalem PR-SHA und unabhängige Reviews bleiben Merge-Gates. Kein Merge/Release/Deployment in diesem Auftrag. Kein externer Bericht/Discordversand ohne gesonderte Anweisung.

## Übergabe an P04 – konkrete Abnahmeliste

| Prüfung | Erforderlicher Beleg |
|---|---|
| Visueller Zusammenhang | Chat mit realem Inhalt und komplexer Assistent, Hell/Dunkel; ruhige Lesefläche und scanbare Fachstruktur |
| Geometrie | 390×844,768×1024,1440×1000,900×640,480×400 sowie 320 px/Zoom; keine verdeckten Footer/Fehler |
| Fokus / Overlay | Öffnen, Tab/Shift+Tab/Escape, Zurück, Fehler, Dirty/Busy, Schließen/Resize; Initialfokus/Rückkehr dokumentiert |
| Quelle | Markierte Textstelle/Seite soweit vorhanden, modal/nichtmodal, Keyboard, Download und Fehler/Rechte |
| Form / Secret | Echte Feldattribute, native Submitsemantik, Serverfehler mit Werterhalt; Geheimnis unverändert/ersetzen/entfernen nach API |
| Kontext | Org/Bereich/KB unterscheidbar; bestehender Chat-Basiswechsel erhält Prompt und startet neuen Chat, kein Datenübertrag fremder Scopes |
| Chatregression | Streaming/Stopp/Retry, Tool-Calling und Rückfall, Zusatzbasen/Anhänge, Vorlagen, Export und Bewertung |
| Assistentregression | Alle sechs Schritte, drei Quellentypen, Test/ACL/Zurück/Abbruch/Aktivierung; kritische Warnungen sichtbar |
| Kontrast / Zielgrößen | Gemessene tatsächliche Renderfarben, Fokus und Treffflächen; Tokenberechnung allein nicht ausreichend |

Sind notwendige Fälle nicht technisch prüfbar, bleiben sie mit Ursache offen. Die Entscheidung „großes Modal oder eigene Arbeitsansicht“ des Assistenten wird anhand dieser Belege getroffen. Keine Backend-Persistenz oder Routeränderung als verdeckte Voraussetzung einführen.
