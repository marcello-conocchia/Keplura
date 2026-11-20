# Keplura – Designsystem 1.0, Phase 3

Stand: 15.11.2026. Status: **Spezifiziert; Produktintegration und visuelle Validierung offen.** Auftrag des Inhabers: „Ja fang an mit Phase 3“. Baut auf [Inventar](UI-INVENTORY-2026-11-15.md) und [Richtungsentscheidungen E01–E16](UI-DESIGN-DIRECTION-2026-11-15.md) auf. Kein Frameworkwechsel, keine neue Geschäftsfunktion, kein produktiver CSS-/Vue-Umbau in dieser Phase.

## Autorität und Umfang

Dieses Dokument ist die aktuelle **Soll-Definition**. [tokens.json](system-v1/tokens.json) ist die einzige Wertetabelle für das neue System; [Komponentenverträge](UI-COMPONENT-CONTRACTS-2026-11-15.md) legen Verhalten fest, [Migration](UI-MIGRATION-2026-11-15.md) verbindet den Bestand mit dem Ziel. Der [Komponentenkatalog](system-v1/catalogue.html) illustriert diese Werte offline, ohne Produkt- oder Serveranbindung. Er wird aus der Wertetabelle erzeugt und ist keine zweite Quelle.

Das bisherige `desktop/src/styles/theme.css` bleibt bis zur späteren Integration die Laufzeitquelle. Alte Designtexte und der frühere Entwurf sind historisch; ihre Behauptung eines fertigen Systems gilt nicht für diesen neuen Auftrag. Änderungen an Werten erfolgen zuerst in tokens.json, dann Katalog/Prüfbericht neu erzeugen. In P04 sind begründete Korrekturen zulässig; sie müssen hier statt in einem lokalen Sonderstil landen. „Definiert“ bedeutet weder umgesetzt noch visuell/funktional abgenommen.

## DS – Farben, Typografie und Maße

### Semantische Palette

Jeder Tabellenname entspricht `--ic-<Name>`. Alle endgültigen Werte einschließlich Schatten/Scrim stehen maschinenlesbar in tokens.json. Farben werden nach Rolle verwendet, nie als lokale Fachseiten-Palette.

| Rolle | Hell | Dunkel | Verwendung |
|---|---|---|---|
| canvas | #F7F7F5 | #181A18 | Fenster-/App-Hintergrund |
| content | #FFFFFF | #1E201E | Hauptinhalt, Lesefläche |
| surface | #FFFFFF | #252825 | Eingabefeld, Menü, Dialog |
| sidebar | #F3F3F0 | #181A18 | Zurücktretende Navigation |
| surface-subtle | #F1F1EE | #2B2E2A | Code, leise Gruppen, schreibgeschützte Werte |
| text | #20211F | #F0F1ED | Inhalt und wichtige Labels |
| text-2 | #545750 | #C0C5BC | Erläuterungen und Nebeninformationen |
| text-3 | #666A62 | #A2AA9C | Metadaten und Platzhalter, ebenfalls lesbar |
| accent / focus-color | #315CB5 | #A6BFFF | Primäraktion, Links, Fokus und aktive Auswahl |
| accent-hover | #254A97 | #BED0FF | Hover/Pressed der Akzentaktion |
| on-accent | #FFFFFF | #172544 | Text/Icon auf Akzentfüllung |
| hover-tint | #EAEAE6 | #30352F | Ruhiger Hoverzustand ohne Layoutänderung |
| selection | #E7EDF8 | #263A5A | Ausgewählte Zeile, Quellabschnitt |
| input-edge | #7B8076 | #838D7D | Erkennbare Feld-/Checkbox-/Sekundärbutton-Grenze |
| separator | #DDDFD9 | #3B4238 | Dekorativer Trenner; nicht alleinige Control-/Statuserkennung |
| status-ok / status-ok-bg | #24633B / #EAF4EC | #99D4A8 / #223B2A | Bestätigtes Ergebnis, „Durchsuchbar“ |
| status-wait / status-wait-bg | #7A5100 / #FFF2D5 | #F1CB80 / #3D321F | Warten, Warnung; Symbol/Text differenzieren |
| status-error / status-error-bg | #A52D2A / #FBEDEC | #FFB3AA / #422825 | Fehler und destruktive Bestätigung |

`primary`, `on-primary`, `primary-hover` sind **Aliasse** für accent/on-accent/accent-hover. Beide bisherigen Buttonfamilien erhalten somit dieselbe Primäraktion. Information nutzt Akzent/selection; neutrale Zustände text-2/surface-subtle. Disabled nutzt eigene deckende disabled-bg/disabled-text statt pauschaler Opacity über beliebigen Untergründen. Alle Texte normal sichtbar; Disabled ist zusätzlich semantisch gesperrt und erklärt bei Bedarf den Grund. Nie Grün allein als „öffentlich“ oder Rot allein als „gesperrt“ verwenden.

Fokus: durchgehender 2 px-Outline in focus-color mit 2 px Abstand. Auf einer Akzentfüllung liegt der Abstand auf content; ein zweifarbiger Ring ist für dicht anliegende Flächen zulässig. Ring nicht abschneiden. Forced-colors erhält System-Outline, keine alleinige box-shadow-Lösung. Auswahl wird zusätzlich durch sichtbares Label/Häkchen und passenden `aria-current`/`aria-selected`/`aria-checked` angezeigt. Keine native Fokusmarkierung entfernen, bevor gleichwertiger Ersatz funktioniert.

### Typografie

Inter Variable ist bereits über `@fontsource-variable/inter` lokal eingebunden (`main.ts`); keine neue Schriftabhängigkeit oder CDN-Anfrage. Fallback: Inter → Segoe UI → system-ui → sans-serif. Die Anwendung muss auch ohne geladenen Font benutzbar bleiben. Root 16 px, keine Sperre von Browserzoom; Größen als rem. Gewichte 400/500/600, kein 300 oder erzwungenes Font-Smoothing.

| Anwendung | Größe / Zeilenhöhe | Gewicht | Regel |
|---|---|---|---|
| Seitentitel | 24 / 32 px | 600 | Ein H1; auf schmalem Raum umbruchfähig |
| Abschnitt | 20 / 28 px | 600 | Fachliche Gruppe, kein Titel über jeder Zeile |
| Unterabschnitt / Dialogtitel | 16 / 24 px; großer Dialog 20 / 28 px | 600 | Vorhersehbare Hierarchie |
| UI / Datenzeilen / Buttons | 14 / 20 px | 400; Aktion 500 | Standardbedienung |
| Feldlabel / Status | 13 / 20 px | 500 | Label bleibt außerhalb des Feldes sichtbar |
| Erläuterung | 14 / 20 px | 400 | Notwendige Anweisung nicht als Kleinstschrift |
| Metadatum | 12 / 18 px | 400 | Zeit, Größe; nicht für Fehler-/Sicherheitshinweise |
| Antwort / Quelle | 16 / 26 px | 400 | Maximale Lesebreite 720 px, bei Platzmangel flexibel |
| Code / Pfad | 13 / 20 px | 400 | System-Monospace, Zahlen tabellarisch bei Vergleichen |

Labels in normaler deutscher Schreibweise, keine winzigen Großbuchstaben-Gruppentitel. Schriftlaufweite normal; Seitentitel höchstens −0.01em. Eingabefelder unter 768 px und bei Touch mindestens 16 px Text, um unerwünschtes mobiles Zoomen zu vermeiden. Texte erlauben Umbruch; technische Einzeiler bekommen gezielten lokalen Scrollbereich und Kopierzugang, statt die ganze Seite aufzubrechen.

### Abstände, Dichte und Geometrie

Raster in px: **0, 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64**. Bestehende space-1=4, space-2=8 usw. bleiben konsistent. 2 px nur für optische Detailabstände, kein regulärer Zeilenabstand.

| Beziehung / Element | Festlegung |
|---|---|
| Icon/Text; Label/Feld | 8 px / 8 px |
| Zusammengehörige Aktionen | 8 px; bei Umbruch vollständige Buttons erhalten |
| Formfelder | 20 px zwischen Feldgruppen; Hilfe/Fehler 4 px zum Control |
| Inhaltliche Abschnitte | 32 px; Abschnittstitel zum Inhalt 16 px |
| Seitenrand | <768: 16 px; 768–1199: 24 px; ab 1200: 32 px |
| Header | min 56 px, wächst bei Umbruch; mobil Kontext auf zweite Zeile |
| Hauptinhalt / Form | max 1280 px / max 640 px; volle verfügbare Breite darunter |
| Sidebar / Wissensrail / Adminrail | 248 px / 224 px / 208 px, nur bei ausreichender Inhaltsbreite |
| Button / Feld | Standard min 40 px; kompakte Aktion min 32 px; Touch min 44 px |
| Datenzeile | min 48 px; mehrzeilig min 64 px; Inhalt bestimmt tatsächliche Höhe |
| Checkbox/Radio | sichtbare Form 16 px; gesamter Label-/Klickbereich min 32 px, Touch 44 px |
| Icon | 16 px bei Zeile, 20 px bei Hauptbedienung; Trefffläche unabhängig größer |
| Radius | kleine Tags 4 px, Buttons/Felder 6 px, Inhaltsgruppen 8 px, Dialog 12 px; Kreis nur Avatar |

Chip ist eine kleine Kontexteinheit mit Radius 6 px, kein unnötig breites Pillenobjekt. Ein Statusbadge darf 4 px verwenden. Einfache Inhalte bleiben frei auf content, ohne Schattenkarte. Tabellen keine festen Zeilenhöhen, die deutschen Text abschneiden. Keine globale Dichte-Einstellung als neues Produktfeature.

### Schatten, Icons und Bewegung

Schatten nur für schwebende Ebenen: float für Menü/Popover, panel für Dialog/temporäres Panel. Permanenter Explorer/Lesebereich bleibt ohne Schatten. Kontur und Hintergrund tragen auch im Dunkelmodus die Ebenenerkennung. Scrim hell 32%, dunkel 64%; kein Blur oder Glas. z-Stufen: Inhalt 0, sticky 10, Menü 30, Ergänzungspanel 40, Scrim 50, Modal 60, Feedback 80. Native Top-Layer-Dialoge bilden ihren eigenen Stapel; z-index hebt Body-Portals nicht darüber. Menüs in Dialogen müssen in dessen Overlay-Container gerendert werden.

Bestehendes AppIcon als einzige Linienfamilie; 24er viewBox, stroke 1.75, konsistente Größen 16/20. Die heute abweichenden SVG-Striche/Inline-Icons werden bewusst angeglichen; kein neues Iconpaket. Deko `aria-hidden`; Iconaktionen mit deutschem Accessible Name und Tooltip bei Bedarf. Marke nur als kleiner Wort-/Symbolabdruck, keine Hero-Grafik in Arbeitsansichten.

Hover/Farbe 120 ms, Zustands-/Menüwechsel 160 ms, Panel 200 ms, Kurve cubic-bezier(0.2,0,0,1). Maximal 4 px Bewegung beim Einblenden, kein Springen/Skalieren von Formularen. Nur opacity/transform animieren, keine verzögerte Eingabe oder künstliche Mindest-Ladezeit. Bei `prefers-reduced-motion: reduce` alle nicht notwendigen Transitionen 0 ms, kein Smooth-Scroll; statischer Ladeindikator plus Text bleibt verständlich. Live-Text/Streaming wird nicht wortweise dekorativ animiert.

## SH – Navigation, Geltungsbereich und Seitengerüste

Die gemeinsamen Navigationsziele bleiben `chat`, `wissen`, `team`, `admin`. Es wird keine neue Router-/URL-/Backendstruktur vorausgesetzt. AppSidebar zeigt oben Organisation und Arbeitsbereich als **zwei eindeutig beschriftete Auswahlen**, darunter Suche und Hauptziele. Änderungen verwenden die vorhandenen `selectOrg`-/`selectSpace`-Pfade. Konto, Darstellung, Verbindung und Desktop-Vollbild bleiben im Fuß-/Kontobereich. F11 bleibt Tauri-only, kein nutzloser Browser-Schalter.

Im Chat zeigt die Kontextzone der Sidebar ausschließlich den persönlichen Verlauf mit einem „Neuer Chat“-Einstieg. Im Wissen liegt der vollständige Wissensbasis-Explorer im Fachbereich, nicht nochmals permanent in der App-Sidebar. Team zeigt Organisationen/Mitglieder/Einladungen im eigenen Inhalt. Administration erhält eine einzige fachliche Unternavigation mit den vorhandenen Gruppen: **KI & Wissen** (Anbieter, Modelle, Suche & Wissen), **Verbindungen** (Datenquellen, Websuche, MCP, Verzeichnis), **Betrieb** (Systemstatus, Antwortqualität, Datensicherung). Auf kleinen Breiten ersetzt ein beschrifteter Abschnitts-Select die Unternavigation. Keine bloße Icon-Rail.

| Kontext / Vorgang | Zustandsvertrag und sichtbare Rückmeldung |
|---|---|
| Organisation wechseln | Nach Schutz ungespeicherter Arbeit bestehenden selectOrg-Pfad aufrufen; alte Bereichs-/KB-/Dokument-/Mitglied-/Chatdaten sofort gemäß vorhandener Teardown-Logik entfernen; neue Organisation beim Laden klar benennen. Fehler zeigt keinen alten fremden Bestand |
| Arbeitsbereich wechseln | Gleiches Prinzip über selectSpace; bestehende automatische Auswahl der ersten verfügbaren Basis nur im bisherigen Store-Vertrag, keine neue kontextabhängige Heuristik |
| Wissensbasis im Wissen wählen | selectKnowledgeBase; verwalteten Bestand/Suche wechseln. Kein stilles Umschreiben der Basis einer bestehenden Unterhaltung |
| Neue Unterhaltung | Basis aus bestehendem ausgewähltem Kontext; zusätzliches Wissen/Anhänge nach bestehendem Gesprächsvertrag. Keine globale Modellwahl erfinden |
| Basis im bestehenden Chat wechseln | Bestehendes changeKnowledgeContext eröffnet eine neue Unterhaltung und erhält den Eingabetext. Vor Auswahl klar „Andere Wissensbasis startet einen neuen Chat“; während Streaming/ContextBusy keine Änderung. Fehler setzt Auswahl gemäß bestehendem Handler zurück |
| Zusätzliche Basen / Anhänge | Gehören zum aktiven Gespräch, deutlich getrennt von Hauptbasis. Höchstzahlen und Größen vom vorhandenen Vertrag übernehmen; gekürzte Dateien sichtbar kennzeichnen |
| Team / Administration | Teamhandlungen zeigen Zielorganisation; globale ADMIN-Rolle ist nicht Org-OWNER. Adminseiten sind Systemkonfiguration, einzelne Bereichs-/Werkzeugfreigaben nennen den Zielbereich zusätzlich |
| Globale Suche | Namen innerhalb aktueller Organisation, gruppierte Treffer mit Bereich/Basis. Dokumenttreffer öffnet wie bisher die Basis; keine Volltextpassage vortäuschen. Inhaltsuche im Wissen bleibt separat |
| Ablaufende Sitzung | Vorhandenes dropExpiredSession vollständig erhalten. Kein „alte Daten behalten“-Entwurf über Sitzungs-/Tenantgrenzen, sensible Einmalausgaben verschwinden |

Kontextwechsel/Navigieren/Abmelden mit geänderten nicht gespeicherten Eingaben fragt **„Änderungen verwerfen?“** mit „Weiter bearbeiten“ (Initialfokus) und „Verwerfen und wechseln“. Zielwechsel wird erst danach ausgelöst; keine zusätzliche Entwurfsspeicherung. Während nicht abbrechbarem Mutationsrequest Wechsel/Schließen sperren und den Grund sichtbar anzeigen. Streaming blockiert Kontextwechsel bis Stopp/Ende; „Stopp“ beendet den Antwortstream, keine erfundene Rücknahme bereits gespeicherter Nutzerfragen. Nach Requestfehler bleiben Eingaben im selben sicheren Kontext bearbeitbar. Diese Schutzregeln sind später zu implementierende UI-Verträge, keine behaupteten heutigen Funktionen.

Seitentitel und Kontext stehen links, höchstens eine hervorgehobene Hauptaktion pro Arbeitsbereich rechts. Liste/Form werden darunter angeordnet. Formaktionen gehören an das Formular, nicht an eine entfernte globale Leiste. Mehrere voneinander unabhängige Inline-Anlagen werden nicht gleichzeitig offen gehalten. Erst-Admin-Einrichtung behält Org → Bereich → erste Basis und die vorhandenen Folgeaktionen Upload/Team.

## Geräte, Scrollen und ergänzende Flächen

| Breite / Höhe | Verbindliches Layout |
|---|---|
| 320–767 px | Mobile Topbar mit beschriftetem Menübutton; Sidebar als modaler Drawer min(320 px, viewport−32 px), Touch 44 px. Ein fachlicher Hauptinhalt. Quelle als vollflächiger modaler Lesemodus; Assistent vollflächig, aktueller Schritt plus Übersicht |
| 768–1199 px | Sidebar 248 px bleibt, Fachinhalt ohne zusätzliche permanente Rails, Basis-/Adminauswahl als Select. Quelle als modaler Lesemodus. Kein 2-Spalten-Formular aus Platzgründen erzwingen |
| 1200–1439 px | Explorer-/Adminrail im Inhalt möglich, wenn Hauptinhalt min 640 px bleibt. Quelle temporär modal, damit Chat und Quelle nicht gequetscht werden |
| ab 1440 px | Quelle nichtmodal neben dem Gespräch: 400 px +24 px Abstand, Lesefläche flexibel bis 720 px. Wenn tatsächlicher Containerplatz min 600 px Gespräch +400 px Quelle +24 px nicht reicht, wieder Modal statt Seitenüberlauf |
| Höhe≤600 px, auch Tauri 480×400 | Keine vertikale Zentrierpflicht; Dialog/Assistent nutzt verfügbare Höhe mit 16 px Rand, Header/Footer wachsen und Inhalt scrollt. Wenn Header/Footer den Inhalt verdrängen, ein gemeinsamer Dialogscroll statt kaum sichtbarer Innenfläche |

Breakpoints sind CSS-Pixel und Ausgangsverträge; P04 muss sie an realen Inhalten beurteilen. Touchbedingungen gelten zusätzlich über `(any-pointer: coarse)`, nicht allein durch Bildschirmbreite. Kein horizontales Scrollen des App-Roots ab 320 px. Echte breite Datentabellen und Code dürfen **lokal**, beschriftet und per Tastatur erreichbar horizontal scrollen; normale Texte und einzelne Zellen umbrechen. Bei 200% Schriftzoom und 400% Browserzoom bleiben Funktionen erreichbar. Tauri-Mindestgröße wird dadurch nicht geändert.

Scrollbesitz: Shell füllt Fenster, Sidebar scrollt unabhängig; Hauptinhalt besitzt einen Lesescroll. Chat: Transcript scrollt, Composer ist ein eigener Layoutbereich mit max 240 px Texteingabehöhe und eigenem Textarea-Scroll. Kein fest positionierter Composer über Nachrichten. Mobile Bildschirmtastatur via dynamischer Viewporthöhe berücksichtigen; Fokus/Fehler nicht hinter Footer oder Header verstecken. Sticky-Flächen nutzen deckendes content, keine Transparenz. Dialogheader/Footer nur sticky, wenn Bedienung und Fokus bei geringer Höhe frei bleiben.

## Messbare Qualitätsziele und Quellen

Die folgenden Quellen wurden am 15.11.2026 überprüft. Keplura setzt teilweise bewusst strengere Designziele als die jeweiligen Mindestanforderungen. Ihre Definition ist keine Konformitätsbescheinigung.

- Jeder aktive Normaltext einschließlich Metadaten/Placeholder mindestens **4.5:1**, ohne Großschrift-Ausnahme im eigenen System. WCAG unterscheidet Normal-/Großschrift: [SC 1.4.3](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html).
- Notwendige Feldgrenze, Icon-/Statusinformation und Fokusindikator mindestens **3:1** zum angrenzenden Grund. Dekorative Trenner brauchen diese Rolle nicht zu erfüllen: [SC 1.4.11](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).
- Fokus jederzeit sichtbar; eigenes Ziel **vollständig** sichtbar einschließlich Ring. WCAG-AA verbietet vollständig verdeckten Fokus: [SC 2.4.7](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html), [SC 2.4.11](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html).
- Treffflächen mindestens 32 px im eigenen Desktop-Vertrag,44 px bei Touch; Mindestziel 24 px wird auch bei kleinen Kontextaktionen eingehalten. Inline-Textlinks folgen dem Lesefluss. WCAG enthält zusätzliche Ausnahmen: [SC 2.5.8](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html).
- Reflow bis **320 CSS-Pixel**, lokale Ausnahme für echte zweidimensionale Inhalte; Prüfung 400% Zoom an 1280 px und zusätzlich 200% Textvergrößerung: [SC 1.4.10](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html).
- Native Semantik vor nachgebautem ARIA. Keine Steuerung nur per Hover oder Drag&Drop. Menüs, Modals und Auswahlfelder erhalten den passenden Tastaturvertrag: [APG Dialog](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/), [Menu button](https://www.w3.org/WAI/ARIA/apg/patterns/menu-button/), [Combobox](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/).

Berechnete Palette-Paare, vollständiger Komponenten-/Inventarabgleich und tatsächlich betrachtete Katalogzustände werden im [Prüfprotokoll](system-v1/README.md) getrennt von offenen Produktprüfungen dokumentiert. Vor P04 sind insbesondere keine App-Kontraste, Screenreader-Flows, Browser-/Tauri-Zustände oder Mutationsergebnisse pauschal grün.
