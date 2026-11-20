# Verbindlicher Arbeitsplan – vollständiges UI/UX-Redesign

Stand 15.11.2026. Ursprünglicher Auftrag: ausschließlich Phase 1 und Planung. Nach Phase 2 wurde **Phase 3 ausdrücklich beauftragt**. [Recherche](UI-DESIGN-RESEARCH-2026-11-15.md), [Richtung](UI-DESIGN-DIRECTION-2026-11-15.md) und jetzt das [Designsystem](UI-DESIGN-SYSTEM-2026-11-15.md) liegen vor. Isolierter Komponentenkatalog als Referenz, weiterhin keine neue Produktimplementierung oder Abnahme des bisherigen Facelifts.

## Ergebnis der Bestandsaufnahme

Das [Fachinventar](UI-INVENTORY-2026-11-15.md) führt jeden Arbeitsbereich mit Ist-Zustand, Änderungsbedarf, Priorität, Abhängigkeiten und konkreten Abnahmekriterien auf. [Alle 36 Vue-Dateien](ui-audit-2026-11-15/COMPONENTS.md), [527 Quelltext-Elementstellen und 314 bedingte/wiederholende Stellen](ui-audit-2026-11-15/source-index.json) sind als technische Vollständigkeitsgrundlage erfasst. [32 Ansichten/Overlays](ui-audit-2026-11-15/observations.json) wurden live geöffnet, nicht vollständig getestet.

Der aktuelle Schwerpunkt darf nicht erneut „ein weiteres Stylesheet“ sein. Das Problem umfasst Komponentenverträge, Aufgabennavigation, Kontextwechsel, Datenansichten, komplexe Formulare und die Behandlung von Prozess-/Fehlerzuständen. Ein Frameworkwechsel ist aus diesen Befunden allein nicht begründbar. Vue/Reka/Tailwind sind Bestand; eine Stackänderung wäre eine gesonderte Entscheidung und kein versteckter Bestandteil dieses Plans.

## Status und Verantwortungsgrenzen

- **Phase 1:** inventarisiert und dokumentiert; kein Anspruch, jede Laufzeitkombination bereits ausgeführt zu haben.
- **Phase 2:** Recherche und begründete Richtung ausgearbeitet; Quellen-/Dokumentnachweise separat, keine Nutzer-/Designvalidierung vorweggenommen.
- **Phase 3:** System spezifiziert, inklusive Tokens, Komponenten-/Zustandsverträgen und vollständigem Migrationsplan; isolierter Referenzkatalog geprüft, Produktintegration/Designvalidierung offen.
- **Phasen 4–7:** offen, nicht begonnen.
- **Alle Fachinventar-IDs:** offen für Umsetzung und Abnahme des neuen Designs. Vorhandene Funktionen bleiben erhalten.
- UI-01/PR #54 ist untersuchter Bestand. Seine grünen Tests sagen nichts über die vollständige Erfüllung des neuen Qualitätsanspruchs aus.
- Source of truth: Inventar, [Coverage-Tracker](ui-audit-2026-11-15/coverage.json), Repo/PRs und PROJECT_STATE. Kein Abschluss nur aufgrund von Agent-Memory oder schöner Beispielbilder.
- Keine Änderungen an main, kein Merge, Release oder Deployment; keine Nachrichten an Dritte. Backend-Rechte, Quellenfilter, Geheimnisbehandlung und Datenmodelle werden nicht für das Design vereinfacht.

## Reihenfolge und Begründung

| Paket | Phase / Umfang | IDs / Voraussetzung | Ergebnis und Abnahmeschranke |
|---|---|---|---|
| P01 | 1 – Inventar und Quellenabgleich | Vollständiges aktuelles Inventar | Alle 36 SFCs zugeordnet, alle Control-Stellen nachschlagbar, Live-/Quelltextbefunde getrennt; dieser Auftrag endet hier |
| P02 | 2 – Referenzrecherche | P01 | Dokumentierte aktuelle Beispiele von Apple, Notion, Linear und passenden SaaS-Datenansichten; Entscheidungen mit Quelle/Datum/Begründung, keine Kopie |
| P03 | 3 – Designsystem definieren | P02, F01–F14 | Verbindlicher Token-/Komponenten-/Zustandskatalog, Varianten und Migrationsplan; keine parallelen Systeme |
| P04 | 4 – Repräsentative Validierung | P03 | Chat-Hauptscreen plus komplexer Datenquellen-Dialog als zusammenhängender Prototyp; Normal-/Fehler-/Lade-/Mobil-/Dark-Zustände; visuelle Grundlage nachvollziehbar beurteilt |
| P05 | 5 – Gemeinsame Basis migrieren | P04; F01–F14, Z01 | Einheitliche Buttons/Felder/Overlays/Feedback/Datenmuster, aktiv verwendete Altregeln gezielt ersetzt; bestehende Handler/Testids erhalten |
| P06 | 5 – Shell, Zugang und Onboarding | P05; G01–G08, A01–A06 | Navigations-/Kontextmodell und alle Zugangspfade einschließlich mobiler Shell; keine hängen gebliebenen Sitzungs-/Rollenzustände |
| P07 | 5 – Chat, Suche und Quellenlesen | P06; C01–C13, G06, K11–K12 | Kompletter Chat-Workflow einschließlich Menüs, Vorlagen, Anhängen, Feedback/Export und Quellenpanel; keine reine Empty-State-Abnahme |
| P08 | 5 – Wissen und Dokumente | P05–P07; K01–K10 | Browsen/Suchen/Upload/Verarbeitungsstatus und alle Verwaltungsdialoge konsistent; beide Quellen-Einstiege verbunden |
| P09 | 5 – Team und Organisation | P06; T01–T10 | Rollen-/Organisationswechsel, Mitgliedschaft, Einladungen und einmalige Zugangsdaten vollständig; Schutz sensibler Ergebnisse |
| P10 | 5 – KI- und Betriebskern | P05/P06; AD01–AD11 | Alle Admin-Navziele, Anbieter-/Modellformen, Standardwechsel und Diagnoseflächen; gewöhnlicher Mitarbeiter erhält keinen Admin-Zugang |
| P11 | 5 – Datenquellen und Verzeichnis | P10; D01–D10, L01–L06 | Alle sechs Assistentenschritte, drei Quellenvarianten, Neu/Edit, ACL-Vorschau, Laufprobleme, AD/LDAP/TLS/Lookup und Löschungen |
| P12 | 5 – Übrige Administration | P10; Q01–Q04, W01–W04, M01–M06, B01–B03 | Qualität/Import, vier Suchanbieter-Varianten, MCP-Freigaben/Journal und Sicherungs-/Restore-Zustände vollständig |
| P13 | 5/6 – Querprüfung und Regressionen | P06–P12, F14 | Alle Rollen, Themes, Viewports, Fokus-/Overlay-Kombinationen, lange/fehlende/fehlerhafte Daten; Lücken pro ID zurück in dasselbe Paket |
| P14 | 7 – Schlussabgleich | P13, Z02 | Jeder Eintrag mit Zustandsergebnissen und Belegen; keine vergessene Fläche, keine stillen N/A; verbleibende Blocker ausdrücklich benannt |

**Warum diese Reihenfolge:** Die Navigation und Fachseiten hängen von gemeinsamen Komponenten ab. Werden sie zuerst einzeln gezeichnet, entstehen erneut konkurrierende Rezepte. Der Chat ist der häufige Alltagsfluss und prüft Lesbarkeit, Eingabe und Kontext; der Assistent prüft das gegenteilige Extrem aus langen Formularen, Rechten und Prozessführung. Danach werden dieselben Verträge an Datenansichten, Team und Administration angewendet. Quellenlesen wird früh zusammen mit Chat entworfen und anschließend im Wissensbereich wiederverwendet. Fachpakete bleiben jeweils klein genug für tatsächlichen Build, Start und Rückmeldung; es gibt nur eine aktive Implementierungsaufgabe gleichzeitig.

Priorität bewertet Risiko und Wirkung, nicht eine starre Sortierung aller P0-Zeilen vor P1: fachliche Voraussetzungen gehen vor. Auch P2 bleibt Pflicht. Responsive, Accessibility und Sonderzustände sind **Teil jedes Pakets**, nicht Arbeit, die erst in P13 beginnt. P13 ist die erneute bereichsübergreifende Kontrolle.

## Phase 2 – Rechercheauftrag und Ergebnis

Ausgeführt auf ausdrücklichen Folgeauftrag „Starte mir Phase 2“. Ergebnisse: [Quellen und Befunde](UI-DESIGN-RESEARCH-2026-11-15.md), [Richtungsentscheidungen und vollständige Bereichszuordnung](UI-DESIGN-DIRECTION-2026-11-15.md), [Prüfprotokoll und Grenzen](ui-research-2026-11-15/README.md). Der folgende konkrete Auftrag bleibt als Maßstab erhalten:

1. Primäre Referenzen untersuchen: Apple Human Interface Guidelines und tatsächliche Apple-Interaktionsmuster; Notion für Inhaltshierarchie und kontextuelle Aktionen; Linear für Navigation, Datendichte und Einstellungen. Weitere Beispiele nur, wenn sie eine konkrete Inventarlücke adressieren.
2. Nicht nur Marketing-Startseiten betrachten: mindestens Navigation/Arbeitsbereich, Datenliste/Tabelle, Form-/Einstellungsseite, einfacher Dialog, komplexer Ablauf und Fehler-/Leerzustand belegen.
3. Pro Referenz festhalten: URL, Abrufdatum, betrachteter Screen/Zustand, Typografie, Dichte/Abstände, Farbe, Gruppierung, Aktionshierarchie, Fokus-/Tastaturverhalten, Animation und Responsive-Muster.
4. Je übernommenes Prinzip erklären, welches Keplura-Problem es löst und welche Anpassung für deutschsprachige Mitarbeiter/Sysadmins nötig ist. Konkurrenzlösungen mit Vor-/Nachteilen nennen. Keine pauschale Behauptung „Apple-like“.
5. Ergebnis: begründete Gestaltungsrichtung mit überprüfbaren Leitplanken. Keine großen Karten als Standardcontainer, keine dekorativen Glaseffekte/Verläufe, keine wahllosen Schatten. Farbe und Bewegung müssen eine Funktion haben.

## Phase 3 – spezifizierte Systemverträge

Ergebnis des Folgeauftrags: [Systemdefinition](UI-DESIGN-SYSTEM-2026-11-15.md), [Komponentenverträge](UI-COMPONENT-CONTRACTS-2026-11-15.md), [Migration aller 36 Vue-Dateien](UI-MIGRATION-2026-11-15.md), [Referenz/Prüfprotokoll](system-v1/README.md). Die folgende Tabelle bleibt Umfangsmaßstab; sie ist jetzt konkretisiert, nicht produktiv umgesetzt.

| Vertrag | Muss festgelegt werden | Migrations-/Erhaltungsbedingung |
|---|---|---|
| DS: Tokens | Semantische Farben inkl. Status/Flächen/Fokus; Hell/Dunkel; Typografie; Spacing; Radien; Ebenen/Schatten | Ein autoritativer Ort; Werte nicht erneut in Spezialpanels duplizieren |
| FO: Felder/Formulare | Label/Hilfe/Fehler, erforderlich/optional, Text/Secret/Number/Textarea/Select, Radio/Checkbox, Submit/Busy/Cancel | Attribute auf nativem Control, gültige Name/ID-Verknüpfung, keine Doppelsubmits; Secret-Vertrag unverändert |
| OV: Overlays | Kleine/komplexe Form, Assistent, Confirm, Menü/Popover, Suchdialog, Quellenpanel, Mobile Drawer | Initialfokus, Fokus-Rückkehr, Escape/Backdrop, Dirty/Busy-Regel, Scroll/Stacking/Portals |
| DA: Daten/Feedback | Liste/Tabelle/Gruppe, Kopf/Zeile/Aktionen, Filter, Metadaten, Loading/Empty/Error/Retry/Partial/Stale | Kein falscher Empty State bei Ladefehler; neue Semantik ändert keine Rechtefilter |
| SH: Shell/Kontext | Globale Navigation, Verlauf, Org/Bereich/Basis, Fach- versus Systemverwaltung, Header | Kontextwechsel verliert keine Daten; alle bestehenden Ziele erreichbar |
| Lesen/Schreiben | Nachrichten, Quellen, Markdown, Code, Tabellen, Links, Composer und Zusatzkontext | Sanitizing, Linksicherheit, Streaming/Abbruch, Zitate und Export bleiben erhalten |
| Geräte/Interaktion | Layout-Raster, Breakpoints, geringe Höhen, Touch-Hit-Areas, Zoom, Fokus, Motion/reduced-motion | Browser-first plus Tauri-Mindestgröße; keine alleinige Prüfung eines großen Desktop-Screens |

Die konkreten Werte stehen nun in `system-v1/tokens.json`; P04 validiert ihre Wirkung und darf begründete zentrale Korrekturen vornehmen. Kein Frameworkwechsel. Falls eine bestehende primitive Komponente einen notwendigen Vertrag nicht erfüllen kann, wird das konkret belegt, bevor eine technische Alternative vorgeschlagen wird.

## Phase 4 – nachvollziehbare Designvalidierung

**Hauptscreen: Chat.** Validieren mit neuem/leeren Chat und einer realistischen Unterhaltung mit längerer Markdown-Antwort, Tabelle, Code, Quellen, Anhang und zusätzlicher Basis. Zusätzlich laufend/abgebrochen/fehlgeschlagen/kein Wissenstreffer und langer deutscher Text. Nicht nur die Begrüßungsseite zeigen.

**Komplexer Dialog: Datenquelle einrichten.** Den gesamten Rahmen mit den sechs Schritten prüfen, besonders Schritt 2 (Verbindung mit SMB/LOCAL/SharePoint-Varianten) und Schritt 4 (Rechtevorschau mit Warnung/unlesbaren ACLs). Lange Pfade/Gruppennamen, Fehler nach Verbindungsprüfung, Busy, Zurück, Abbruch und Wiederöffnung gehören dazu.

Als Komponenten-Stichprobe zusätzlich Datenzeile/Tabelle und Secret-Feld betrachten; der Prototyp ersetzt keine spätere Fachabnahme. Vergleich auf kleinem/großem Web-Viewport, Dunkelmodus und niedriger nativer Fensterhöhe. Eingaben/Aktionen müssen ohne Überlagerung erreichbar bleiben; Fokus, Lesereihenfolge und Warnwirkung dokumentieren.

**Ausgangskriterium:** visuelle Richtung und Systemverträge passen zusammen, kritische Layout-/Interaktionsprobleme sind behoben, nachvollziehbare Screenshots/Zustandsbelege vorhanden. Erst dann Übertragung auf restliche Flächen. Diese erste Validierung setzt keine Fachinventar-ID pauschal auf „geprüft“.

## Nachverfolgung pro Inventar-ID

Der Tracker beginnt überall mit **offen**. Erlaubte Umsetzungsschritte: offen → in Bearbeitung → umgesetzt → geprüft. „Umgesetzt“ bedeutet nicht „geprüft“; „geprüft“ setzt alle anwendbaren Zustände voraus. Blockierte Testfälle bleiben mit Grund offen; N/A braucht eine konkrete Begründung.

Vor Implementierung wird die jeweilige Zeile in konkrete Testfälle aus A0/S1–S7 zerlegt. Jeder Testfall bekommt: Zustand/Variante, Rolle, Datenvoraussetzung, Theme, Viewport/Laufzeit, erwartetes Verhalten, tatsächliches Ergebnis, Beleg, Commit und offenen Befund. Die semantische Inventar-ID und die feingranularen EL-Quelltextstellen werden verknüpft. Jede Schaltfläche/Feldeigenschaft/Verzweigung muss dadurch entweder einem Testfall oder einer begründeten unveränderten gemeinsamen Komponentenabnahme zugeordnet sein.

Für Komponenten-Wiederverwendung gilt: Basistest einmal tief, anschließend jede Verwendung auf ihren Kontext prüfen. Ein getesteter Dialograhmen beweist keine korrekte ACL-Warnung; ein getesteter Button beweist keine korrekte Löschwirkung. Instanzen aus v-for sind repräsentativ mit null/einem/vielen/langebenannten Datensätzen zu prüfen, nicht als willkürlich viele neue Screens zu zählen.

## Qualitätssicherung und Funktionserhalt

- Vor jeder Migration vorhandenen Ablauf und API-Vertrag festhalten. Native Submit-/Change-/Keyboard-Ereignisse, Testids, Accessible Names, Rechte und Secret-Semantik nicht beiläufig verändern.
- Nach jedem Paket App tatsächlich bauen/starten; betroffene Workflows und negative Fälle im laufenden Programm prüfen. Kleine Commits unter Marcello Conocchia, regelmäßig pushen, keine Secrets in Code/Logs/Screenshots.
- Testumgebung und Fixtures von echten Pilotdaten trennen. Destruktive Prüfungen nur an eigenen Testdaten; Nachweise maskieren.
- Für die abschließende lokale Abnahme: reproduzierbares `bash scripts/ci-local.sh` auf genau dem finalen SHA; relevante E2E-Suiten plus neue fehlende Dialog-/Zustandsfälle aus dem Inventar. Keine CI eines Vorgängers als neue UI-Freigabe ausgeben.
- Tastatur ohne Maus, Fokus-Rückkehr, Lesereihenfolge, Screenreader-Stichproben, gemessene Kontraste, Zoom/reduced-motion und Touch prüfen. Das Designsystem muss zuvor konkrete Prüfschwellen festlegen. Keine ungemessene Konformitätsbehauptung.
- Web-Viewports 360/390/768/1024/1440/1920 sowie geringe Höhe und 200% Zoom; Tauri 900×640 und 480×400. Windows/macOS bleiben separate Release-Gates gemäß Repo-Regeln.
- Unabhängige Reviewer-/QA-Freigabe; Security bei betroffenen Auth-/Input-/Netz-/Secret-Flächen. Keine eigene finale Freigabe und kein Merge in diesem Auftrag.

## Abschlusskontrolle – harte Vollständigkeitsregel

1. Quelltextindex neu erzeugen; alle 36 ursprünglichen Dateien und alle hinzugekommenen Komponenten abgleichen.
2. Jede EL-Stelle und alle nachträglich gefundenen dynamischen/native Flächen einer Inventar-ID zuordnen. Entfernte Oberflächen mit Begründung und erhaltenem Nutzerweg dokumentieren.
3. Jede Inventar-ID auf Zustandsergebnisse prüfen. Neue gefundene Fläche erweitert zuerst das Inventar; sie verschwindet nicht aus der Schlussbilanz.
4. Pro ID aktuelle Belege/Commit und geschlossene Findings. Keine offen gebliebene Fehlervariante durch einen Happy-Path-Screenshot überdecken.
5. Schlussbericht mit tatsächlich umgesetzt/geprüft/offen/nicht prüfbar und Grund. Sind notwendige Bereiche nicht prüfbar, ist der vollständige Redesign-Auftrag **nicht abgeschlossen**.

## Nächster Schritt

Phasen 1–3 sind dokumentiert; die Produktabnahmen bleiben offen. Es wird **nicht automatisch weiter implementiert**. Nächster gesonderter Auftrag ist P04: repräsentative Validierung des Systems an Chat und komplexem Assistenten einschließlich Zuständen. Erst danach Übertragung auf die ganze App. Die Paketfolge P05–P14 bleibt vollständig bestehen.
