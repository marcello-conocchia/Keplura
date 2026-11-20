# Phase 2 – Designrecherche für Keplura

Stand und Abrufdatum aller Quellen: **15.11.2026**. Auftrag: Recherche und begründete Gestaltungsrichtung, ausgehend vom [Phase-1-Inventar](UI-INVENTORY-2026-11-15.md). Ergebnis: [Gestaltungsrichtung und Entscheidungen](UI-DESIGN-DIRECTION-2026-11-15.md). Keine Designimplementierung und keine vorgezogene Definition von Tokens oder Komponenten-APIs.

## Methode und Aussagegrenzen

Untersucht wurden öffentliche Primärquellen der jeweiligen Hersteller: Guidelines, Hilfeseiten, veröffentlichte Produktabbildungen und der aktuelle Linear-Designbericht. Keine Dribbble-Konzepte, nachgebauten Vorlagen oder reine Marketingästhetik als Arbeitsgrundlage. Die zusätzlichen Referenzen Carbon und PatternFly beantworten konkrete Lücken: Datenansichten, lange Formulare, Zustände und komplexe Einrichtungsabläufe. Sie sind **Musterreferenzen, keine Empfehlung, deren React-Bibliotheken in Vue einzubauen**.

**Belegarten:** D = Dokumentation tatsächlich gelesen; V = Produkt-/Musterabbildung im Browser visuell betrachtet. V beweist nur das sichtbare Beispiel, keine Bedienbarkeit des dahinterliegenden Produkts. Kein angemeldeter Fremdprodukt-Workspace, keine reale Fremdprodukt-Einrichtung und kein fremdes Produkt mit Tastatur, Screenreader oder auf einem Mobilgerät getestet. Herstellerangaben zu Verhalten sind deshalb als solche zu verstehen. Nicht aus Standbildern abgeleitet: exakte CSS-Werte, Kontrastverhältnisse, Fokusführung oder Animationsdauern.

Aktualität bedeutet hier aktueller öffentlich abrufbarer Stand, nicht garantiert identisch mit jedem Kundenkonto. Linear nennt für den untersuchten Refresh den 12.03.2026. Carbon nennt für Formulare und Tabellen den 30.09.2026, für Leerzustände den 11.08.2026. Notion veröffentlicht auf den herangezogenen Hilfeseiten kein eindeutiges Änderungsdatum. Das untersuchte Notion-Bild zeigt älteren Bestand; seine Platzierungen werden nicht als heutige Navigationsspezifikation übernommen. Apple enthält plattformspezifische Regeln einschließlich Liquid Glass; diese Materialwahl widerspricht dem ausdrücklichen Keplura-Auftrag und wird nicht übernommen.

Die folgenden kurzen Referenznotizen beschreiben die Quelle. Die Verknüpfung mit Inventarproblemen und die Entscheidungen im zweiten Dokument sind **eigene Keplura-Entwurfsentscheidungen**, keine Behauptungen der Hersteller. Es werden keine Fremdoberflächen oder Assets in das Produkt kopiert.

## Referenzen und Befunde

### R01 – Apple: Gestaltungsprinzipien (D)

[Human Interface Guidelines – Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles/) · [gelesene Textfassung](https://developer.apple.com/design/human-interface-guidelines/design-principles.md).

Apple beschreibt Vertrautheit, Einfachheit, Sorgfalt, Selbstbestimmung und die Berücksichtigung verschiedener Nutzungssituationen. Entscheidend ist die durchdachte Aufgabe einschließlich Rückmeldung und Fehlerbehandlung. Die Quelle ist eine Prinzipiensammlung, kein geprüfter SaaS-Screen und keine verbindliche Web-Palette. Für Keplura relevant: F01–F14 und durchgängiger Funktionserhalt. Daraus folgt kein Anspruch, dass eine Oberfläche allein durch Apple-Anmutung intuitiv wäre.

### R02 – Apple: Typografie und Lesen (D)

[Human Interface Guidelines – Typography](https://developer.apple.com/design/human-interface-guidelines/typography/) · [gelesene Textfassung](https://developer.apple.com/design/human-interface-guidelines/typography.md).

Untersucht: Hierarchie, Lesbarkeit und Anpassung bei größerer Schrift, einschließlich der dokumentierten Mail-Inbox-Hierarchie. Gewicht, Größe und Farbe unterscheiden Informationsebenen; zu leichte Schnitte erschweren das Lesen. Wenige Schriftfamilien halten die Darstellung zusammen. Vergrößerung kann ein anderes Layout erfordern. Apples native Punktgrößen und Systemschrift-Verwendung sind keine direkt übertragbaren CSS-Vorgaben. Relevant für F11/F13/F14, Chatantworten, Dokumentnamen und umfangreiche Adminformulare. Schriftlizenz, deutsche Texte und reale Renderingqualität bleiben eigene Prüfungen.

### R03 – Apple: Layout und Inhaltshierarchie (D)

[Human Interface Guidelines – Layout](https://developer.apple.com/design/human-interface-guidelines/layout/) · [gelesene Textfassung](https://developer.apple.com/design/human-interface-guidelines/layout.md).

Untersucht: Ausrichtung, Gruppierung und schrittweise Offenlegung. Gemeinsame Achsen und Einrückung vermitteln Beziehungen; Freiraum und sparsame Trennung strukturieren Inhalte. Layouts sollen unterschiedliche Fenstergrößen berücksichtigen. Die aktuelle Quelle empfiehlt auch Apple-spezifische Materialien und Hintergrundeffekte. Keplura übernimmt die Strukturprinzipien, nicht diese Effekte. Relevant für F12/F14, G01–G07, D02–D07 und L02–L05. Es wurden keine Apple-Geräteansichten live bedient.

### R04 – Apple: aufgabenbezogene Sheets (D)

[Human Interface Guidelines – Sheets](https://developer.apple.com/design/human-interface-guidelines/sheets/) · [gelesene Textfassung](https://developer.apple.com/design/human-interface-guidelines/sheets.md).

Untersucht: Dialogkontext, Abschluss/Abbruch und ergänzende Inhalte. Apple warnt vor Sheet-Ketten; ergänzende Informationen können in einem nichtmodalen Panel besser passen. Abschluss braucht einen nachvollziehbaren Ausweg. Die konkreten Button-Platzierungen unterscheiden sich je Apple-Plattform. Übertragbares Problem: F06/F07, Quellenlesen K11/K12 und alle Verwaltungsdialoge. Kein Argument, im Web jede Aufgabe in eine kleine schwebende Box zu pressen oder iOS-Titelleisten nachzuahmen.

### R05 – Apple: Bestätigungen und Aktionsbeschriftung (D)

[Human Interface Guidelines – Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts/) · [gelesene Textfassung](https://developer.apple.com/design/human-interface-guidelines/alerts.md).

Untersucht: kurze Konsequenzbeschreibung und eindeutige Verben. Ein konkretes Aktionswort ist verständlicher als ein mehrdeutiges „OK“; bei destruktiven Folgen braucht es einen sicheren Ausweg. Apple differenziert die Gestaltung destruktiver Aktionen nach Anlass und Plattform. Keplura benötigt dafür eine eigene konsistente Web-Regel. Relevant: F06/F09, K10, T03/T07/T09, AD09, D10, L06 und M06. Kein Ableiten von tatsächlicher Wiederherstellbarkeit aus einer eleganten Bestätigung.

### R06 – Apple: Bewegung und reduzierte Bewegung (D)

[Reduced Motion evaluation criteria](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria).

Untersucht: Auswirkungen von Skalierung, Rotation und räumlich wirkender Bewegung. Apple verlangt im beschriebenen App-Store-Kontext Alternativen beziehungsweise optionale Effekte für bewegungsempfindliche Menschen. Die Quelle ist keine Vorgabe für Kepluras Vertrieb. Relevanz für F08/F14, Sidebar, Menüs und Quellenpanel: Bewegung darf Verständlichkeit und Bedienbarkeit nicht voraussetzen. Die HIG-Adresse „motion.md“ war über das Recherchewerkzeug nicht zugänglich; sie wird nicht als gelesen ausgegeben. Es wurden keine Apple-Animationen vermessen.

### R07 – Notion: Sidebar und kontextuelle Menüs (D, V)

[Navigate with the sidebar](https://www.notion.com/help/navigate-with-the-sidebar) · [betrachtetes Beispielbild: Sidebar-Kontextmenü](https://images.ctfassets.net/spoqsaf9291f/2tpJheiXLOwBH9ZdZGnQt3/514bd06e33972f166580fa5c50bcdbdf/Group_61.png?w=3840&q=55&fm=webp).

Die aktuelle Hilfe beschreibt gruppierte, einblendbare Inhaltsbereiche und einen Arbeitsbereichwechsel. Das betrachtete Bild zeigt ruhige helle Flächen, kompakte Text/Icon-Zeilen, einen dezenten Auswahlhintergrund und ein Menü nahe am Auslöser. Im Bild stehen ältere Sidebar-Einträge; die aktuelle Beschreibung nennt bereits Tabs und andere feste Positionen. Deshalb dient das Bild der visuellen Hierarchie, nicht der aktuellen Architektur. Relevant: G01–G07/F07. Keine Übernahme von Emoji-Dekoration, freier Sidebar-Konfiguration, Favoritenverwaltung oder Notions Berechtigungsmodell.

### R08 – Notion: Suche als eigener Arbeitsmodus (D)

[Search in your workspace](https://www.notion.com/help/search).

Untersucht: Sucheinstieg, Suchfenster, Ergebnisnavigation und Tastenkürzel laut Hilfe. Notion trennt schnelle Orientierung von weiterführender Suche. Die Seite beschreibt außerdem Desktop- und KI-Funktionen, die für Keplura kein Zusatzauftrag sind. Relevant: G06/K06/C10. Eine klare Suchoberfläche muss ihren Suchraum erklären. Kein Fremdprodukt-Suchdialog live geöffnet; die dort eingebundene Tipp-Illustration ist kein UI-Beleg. Aktuelle Keplura-Namenssuche und Wissensinhaltsuche bleiben fachlich verschieden.

### R09 – Notion: Einstellungen und Rollen (D)

[Workspace settings](https://www.notion.com/help/workspace-settings).

Untersucht: thematische Einstellungsseiten, sichtbarer Geltungsbereich und rollenabhängiger Zugriff. Notion dokumentiert, dass nur zugängliche Einstellungen auffindbar sind; mehrere Einstellungen sind mobil nicht verfügbar. Übertragbar ist die Orientierung innerhalb der Verwaltung. Keplura übernimmt weder diese mobilen Ausschlüsse noch Einstellungssuche, Tariflogik, Domain-Beitritt oder zusätzliche Rollen. Relevant für T01–T10 und AD01. Konkrete Abstände oder Formularzustände wurden aus dieser Textquelle nicht vermessen.

### R10 – Linear: aktueller produktweiter Refresh (D)

[UI refresh – Changelog, 12.03.2026](https://linear.app/changelog/2026-03-12-ui-refresh).

Linear nennt einheitlichere Header, Navigation und Ansichtssteuerung über mehrere Arbeitsbereiche hinweg, überarbeitete Icons und eine zurückgenommene Sidebar. Diese Quelle belegt den Umfang und Zeitpunkt des aktuellen Refreshs; sie ist keine Zusage zur Eignung für Keplura. Relevant für F11/F12/G01/AD01: Konsistenz muss Fachbereiche verbinden. Unabhängige Zusätze im selben Changelog gehören nicht zur Gestaltungsreferenz.

### R11 – Linear: Gewichtung, Flächen und Wiederholung (D, V)

[A calmer interface for a product in motion, 12.03.2026](https://linear.app/now/behind-the-latest-design-refresh) · [betrachteter Vorher/Nachher-Vergleich der Sidebar](https://webassets.linear.app/images/ornj730p/production/b6d6be14c96978b10553cfb9205be1065087e793-3904x2720.png?auto=format&dpr=2&q=95).

Betrachtet: dunkle Sidebar im vergrößerten Vergleich. Inaktive Einträge treten zurück, Icons beanspruchen weniger Aufmerksamkeit, Gruppen erhalten mehr vertikalen Abstand. Der Bericht erläutert zurückhaltendere Trennlinien und eine weniger gesättigte, etwas wärmere Neutralpalette. Das Beispiel zeigt, wie Unterschiede innerhalb einer dichten Oberfläche wirken. Es beweist keine für uns ausreichenden Kontraste. Relevant für F01/F11/F12 und Shell/Admin. Kein Nachbau der kleinen Entwickleroberfläche oder icon-only Navigation; Keplura braucht sichtbare deutsche Begriffe.

### R12 – IBM Carbon: konventionelle, gut strukturierte Formulare (D, V)

[Form – Guidelines, aktualisiert 30.09.2026](https://www.carbondesignsystem.com/building-blocks/core/components/form/guidelines) · [betrachtete Formularanatomie](https://cdn.carbondesignsystem.com/cxp-prod-payload-cms/form-usage-1.png).

Betrachtet: Formular „Edit mailing address“ mit Titel/Hinweis, Labels über den Feldern, klarer vertikaler Achse und getrenntem Aktionsbereich. Die Guidelines unterscheiden luftige Standardfelder und bündige Fluid-Formulare. Im Bild liegen zusammengehörige kurze Angaben nebeneinander. Relevant: F03–F05, A01/A03/A05, T05 und sämtliche Adminformen. Keplura benötigt eigene Dichte und Aktionsgewichte; die großen farbigen Carbon-Buttons, rechteckige Optik und alle Rasterwerte werden nicht übernommen. Tastatur/Validierung nicht an diesem Standbild geprüft.

### R13 – IBM Carbon: Datenansichten und angemessene Dichte (D)

[Data table – Guidelines, aktualisiert 30.09.2026](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines).

Untersucht: Tabellenanatomie, Toolbar, Spaltenköpfe, Zeilen und Größenvarianten. Die Guidelines behandeln gemeinsame Ausrichtung und passende Dichte einschließlich mehrzeiliger Inhalte. Tabellen benötigen ausreichend Hauptinhaltsbreite. Die beschriebenen Optionen wie Sortierung, Auswahl und Pagination sind Komponentenfähigkeiten, keine Forderung, diese Keplura hinzuzufügen. Relevant: F10, K02/K03, T04/T09, AD02/AD07, Q02 und M05. Kein Live-Tabellentest, keine pauschale Umwandlung vorhandener Listen in interaktive Datengrids.

### R14 – IBM Carbon: verschiedene Ursachen für leere Flächen (D)

[Empty states, aktualisiert 11.08.2026](https://www.carbondesignsystem.com/building-blocks/core/patterns/empty-states).

Untersucht: Erstanlage, erfolglose Suche und Probleme mit Berechtigungen, System oder Konfiguration. Die Quelle ordnet Erklärung und nächste Handlung dem konkreten Anlass zu; Bilder sind optional. Kleine Bereiche benötigen weniger Erklärung als eine Ersteinrichtung. Relevant: F08/F09, A04–A06, C01/C03, G06, K05/K06/K12 und alle Adminlisten. Kein provozierter Fehler im Fremdprodukt. Eine ausgefallene Keplura-Anfrage darf nicht dieselbe Aussage wie eine erfolgreich geladene leere Liste erhalten.

### R15 – Red Hat PatternFly: komplexer Assistent und mobile Schrittführung (D, V)

[Wizard – Design guidelines, Release 6.7.0](https://www.patternfly.org/components/wizard/design-guidelines/) · [betrachtete Desktop-Anatomie](https://www.patternfly.org/images/207b0e8e.svg) · [betrachtete mobile Schrittübersicht](https://www.patternfly.org/images/0c122590.svg).

Betrachtet: separater Schrittnavigator, Inhaltsbereich und Aktionsfuß; mobil ersetzt eine aufklappbare Schrittübersicht die linke Spalte. Die Guidelines unterscheiden modalen und seitenfüllenden Assistenten sowie Prüfung/Abschluss. Relevant: D02–D07 und A05/A06. Keplura erhält keine neuen Schritte, Draft-Persistenz oder Hintergrund-Abbruchfähigkeit aus dieser Referenz. Ein bestehender sechsstufiger Ablauf muss verständlich dargestellt werden. Keine Flow-Ausführung im PatternFly-Beispiel; SVGs zeigen die vorgesehenen Layouts, keine getesteten Responsive-Breakpoints.

### R16 – W3C WAI: modal bedeutet ein konkretes Fokusverhalten (D)

[ARIA Authoring Practices Guide – Dialog (Modal) Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/).

Untersucht: Initialfokus, Tab-Reihenfolge, Escape, Rückkehr zum Auslöser und Benennung. Der APG beschreibt auch den Fokus auf einen einleitenden Inhalt bei komplexen Dialogen sowie vorsichtige Initialfokuswahl bei irreversiblen Aktionen. Er ergänzt die visuellen Referenzen um Web-Interaktion. Relevant: F06/F07/F14 und alle Overlays. APG ist eine Implementierungshilfe; seine Verwendung oder eine Komponentenbibliothek allein beweist keine WCAG-Konformität. Keine Accessibility-Abnahme in Phase 2.

## Vergleich nach Gestaltungsdimension

Diese Tabelle ist die eigene Auswahl für Keplura. „Offen“ beschreibt ausdrücklich eine spätere Definition/Prüfung, keine fehlende Bestandsoberfläche.

| Dimension | Tragfähige Referenz / Einschränkung | Konsequenz für die nächste Entwurfsphase |
|---|---|---|
| Typografie | R02: lesbare Hierarchie; R07/R11 zeigen kompakte Navigation, ohne CSS-Messung | Vorhandene Inter als Ausgangskandidat prüfen; getrennte Lese- und Bedienhierarchie, keine ultraleichten Schnitte oder nachgebauten SF-Fonts |
| Abstände / Dichte | R03: Beziehungen; R12/R13: Form- gegenüber Datenlayout; R11: Gruppen statt jeder Zeile gleich viel Luft | Ein gemeinsames Raster, daraus bewusste Leseflächen, Formgruppen und Datenzeilen. Konkrete Werte erst P03 |
| Farbe | R11: neutrale Umgebung; keine Kontrastmessung | Hell als primäre Arbeitsansicht, gleichwertiges Dunkel; ein zurückhaltender Akzent, semantische Statusfarben. Kein ungeprüftes Kopieren blasser Sekundärtexte |
| Navigation | R07/R10/R11: wiederkehrende Orte; Notion-Bild und -Text nicht gleich aktuell | Feste aufgabenbezogene Einstiege und klarer Geltungsbereich. Funktionen erreichbar halten; keine frei konfigurierbare App-Shell |
| Inhalt / Layout | R03/R04: Hierarchie und ergänzendes Panel | Chat als Lesefläche, Wissen als Explorer, Administration als Daten-/Konfigurationsarbeitsplatz. Ein System, unterschiedliche fachliche Layouts |
| Tabellen / Listen | R13: Spalten und Toolbar; Fähigkeiten sind optional | Gleichartige Daten ausrichten, Metadaten ordnen, vorhandene Filter auffindbar machen; kein neuer Sortier-/Batch-/Paging-Scope |
| Formulare / Einstellungen | R09/R12: Kontext, Labels und Gruppierung | Einspaltiger Grundfluss, kurze logisch gekoppelte Angaben nur bei Platz nebeneinander; lange technische Werte dürfen nicht abgeschnitten werden |
| Kleine Dialoge | R04/R05/R16: Kontext, Konsequenz, Fokus | Eine Aufgabe, ein klarer Abschluss, sichtbarer Abbruch, definierte Fokus-Rückkehr. Größen-/Dirty-/Busy-Verträge in P03 |
| Komplexe Abläufe | R15: ausreichend Arbeitsfläche und separate Schrittübersicht | Datenquellen-Assistent als eigener großer Layouttyp; mobil übersichtliche Schrittangabe statt umgebrochener Tabreihe |
| Menüs / Popovers | R07 visuell; R16 nur für tatsächlich modale Flächen | Aktionen nahe am Objekt; Interaktionsrollen getrennt definieren. Kein Focus-Trap für jedes normale Menü oder Quellenpanel |
| Zustände | R14: Ursachen unterscheiden; R01: Vertrauen | Loading, leer, kein Treffer, Fehler, fehlende Rechte, Teilstand und Erfolg explizit benennen. Kein pauschales „Keine Daten“ |
| Microinteractions | R06: Bewegungsalternativen; bei Produktbildern nicht beobachtbar | Auswahl, Speichern, Kopieren und Laden verständlich rückmelden; Bewegung kurz und funktional, keine künstliche Wartezeit. Dauern/offene Reduced-Motion-Verträge P03 |
| Responsive | R03/R15: Layoutwechsel; keine Fremdgeräteprüfung | Spalten und ergänzende Panels sinnvoll umordnen; kleine Höhe, Zoom und Bildschirmtastatur später real prüfen |
| Accessibility | R16: Web-Fokusvertrag; R02/R06 ergänzen Lesen/Bewegung | Fokus, Tastatur und Semantik als Teil des Designs; konkrete Messschwellen P03, reale Ausführung P04/P13 |

## Belegabdeckung der geforderten Oberflächentypen

| Erforderliches Beispiel | Tatsächlich untersucht | Grenze |
|---|---|---|
| Navigation / Arbeitsbereich | R07 Notion-Hilfe + Kontextmenübild; R10/R11 aktueller Linear-Refresh + Sidebarbild | Kein fremder Workspace live bedient; Versionsunterschied bei Notion benannt |
| Datenliste / Tabelle | R13 Tabellenanatomie und Dichte-Guidelines | Dokumentationsanalyse, kein Live-Grid-Test |
| Formular / Einstellungen | R12 Formularbild + Guidelines; R09 Settings-/Rollenbeschreibung | Keine realen Einstellungen verändert, keine Validierung ausgelöst |
| Einfacher Dialog / Bestätigung | R04/R05 Sheets/Alerts, R16 Web-Dialogvertrag | Regel-/Musteranalyse, kein Apple-Gerätetest |
| Komplexer Ablauf | R15 Wizard-Anatomie, Desktop- und Mobilabbildung | Layoutmuster, keine ausgeführte Quelleneinrichtung |
| Fehler / leer | R14 Ursachen und situative Handlung | Keine Fremdproduktfehler provoziert |

Die fünf V-Abbildungen wurden im Recherchebrowser betrachtet, nicht als Assets in das Repository oder Produkt übernommen. Manche direkten Bilder ließen sich im Text-Recherchewerkzeug nicht anzeigen; die visuelle Betrachtung erfolgte im Browser. Die Hilfeseiten selbst bleiben die Quellen für beschriebene Interaktionen.

## Recherchefazit und Übergabe

Es gibt keinen einzelnen Referenzscreen, der Kepluras ganze Aufgabenbreite sinnvoll vorgibt. Der tragfähige Zusammenhang ist: präzise Hierarchie und verständliche Rückmeldung, ruhige Inhalte, konsistente Orte für wiederkehrende Steuerung sowie eine eigene Arbeitsfläche für komplexe Administration. Die konkrete Auswahl, verworfene Alternativen, fachliche Zuordnung und P03/P04-Prüffragen stehen in der [Gestaltungsrichtung](UI-DESIGN-DIRECTION-2026-11-15.md).

P02 ist als Rechercheartefakt ausgearbeitet. Keine visuelle Nutzerabnahme oder Implementierungsfreigabe behauptet. Phasen 3–7 beziehungsweise Pakete P03–P14 sind nicht ausgeführt; alle 109 Inventar-IDs bleiben für Umsetzung und Abnahme offen.
