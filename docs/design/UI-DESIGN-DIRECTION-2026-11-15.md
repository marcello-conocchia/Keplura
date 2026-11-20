# Phase 2 – empfohlene Gestaltungsrichtung

Stand: **15.11.2026**, Forschungsbasis: [16 Primärreferenzen mit Abrufdatum, Beobachtungsart und Grenzen](UI-DESIGN-RESEARCH-2026-11-15.md). Produktbasis bleibt `2de0a8a`; Inventarbasis ist [Phase 1 mit 109 Fachbereichen](UI-INVENTORY-2026-11-15.md). **Empfehlung für die folgenden Phasen, noch kein validiertes Designsystem und keine umgesetzte Oberfläche.**

## Zielbild

Keplura soll wie ein sorgfältig gestalteter **Wissensarbeitsplatz für Unternehmen** wirken: eine ruhige Arbeitsumgebung, in der Mitarbeiter verlässliche Antworten lesen und ihre Quellen verstehen; Administratoren konfigurieren dieselbe Anwendung in klar gegliederten Arbeitsansichten. Qualität entsteht durch Informationsordnung, lesbaren Inhalt und vorhersehbares Verhalten bis in Fehler- und Abbruchfälle.

Empfohlen wird eine helle Grundansicht mit neutralen, leicht warmen Flächen, dunklem gut lesbarem Text und einem sparsamen gedeckten Blau als Arbeitsakzent. Ein gleichwertiger Dunkelmodus gehört dazu. Die genaue Palette ist Gegenstand von Phase 3 und muss gemessen werden. Die heutige Farbwelt darf nicht einfach nochmals übermalt werden: bereits ähnliche Farben beheben weder widersprüchliche Komponenten noch unpassende Dialoggrößen.

Visueller Eindruck: links eine zurückhaltende Navigation, im Hauptbereich präzise ausgerichteter Inhalt; klare Überschriften, ruhige Zeilen und kleine kontextuelle Aktionen. Ein Dialog ist als zusammengehörige Aufgabe erkennbar. Eine lange Antwort wirkt wie ein gut gesetztes Dokument. Eine Verwaltungsansicht zeigt die relevanten Daten, ohne sie in große dekorative Karten zu zerlegen.

Die Referenzen dienen unterschiedlichen Aufgaben: Apple für Hierarchie und bewusstes Interaktionsverhalten, Notion für den Umgang mit Inhalten, Linear für Konsistenz über Fachbereiche hinweg, Carbon und PatternFly für anspruchsvolle Verwaltungsmuster. Die folgenden Entscheidungen sind eigene Anpassungen an Keplura und seine deutschsprachigen Mitarbeiter und Sysadmins.

## Richtungsentscheidungen und überprüfbare Leitplanken

### E01 – Eine Umgebung mit drei fachlichen Layouttypen

**Befund:** PageContainer, Header und eigene Flächen konkurrieren; Listen, Chat und lange Konfigurationen brauchen unterschiedlich viel Raum (F12/F13). **Entscheidung:** Gemeinsame Shell und Seitenkopf-Regel, darunter drei Grundtypen: Lesen/Schreiben im Chat, Explorer für Wissen und Daten-/Formansicht für Team/Administration. Referenzanschluss: R03/R10.

Eine Seite erklärt zuerst ihren Zweck und Geltungsbereich, danach folgen ihre wichtigsten Aktionen und Inhalte. Ein vorhandener Primärvorgang erhält einen klaren Ort. Mehrere verschachtelte Karten mit derselben Überschrift entfallen als Entwurfsstandard. **Spätere Abnahme:** Chat, Wissen und eine Adminseite sind als dasselbe Produkt erkennbar; alle bisherigen Ziele und Aktionen bleiben erreichbar. Kein universeller Dashboard-Rahmen, der fachliche Unterschiede verschluckt.

### E02 – Navigation nach Aufgaben, nicht nach vollständiger Datenhierarchie

**Befund:** Verlauf, Bereich-/KB-Baum und Auswahlen beanspruchen gleichzeitig Aufmerksamkeit (G01–G04). **Entscheidung:** Chat, Wissen und Team bleiben feste beschriftete Einstiege; Administration bleibt entsprechend der bestehenden Rolle verfügbar. Die Sidebar unterstützt die aktuelle Aufgabe: im Chat persönlicher Verlauf, im Wissen Zugang zu Basen/Dokumenten. Vollständige Wissensverwaltung wird nicht überall wiederholt. Referenzanschluss: R07/R10/R11.

Das bestehende dreigruppige Adminmodell ist der Ausgangspunkt für die fachliche Navigation, kein zusätzlicher neuer Navigationsbaum. Keine frei konfigurierbaren Tabs, Icon-Rail ohne Begriffe oder neuen Favoritenfunktionen. **Spätere Abnahme:** Auch ein Erstnutzer findet Wissen, Team und Rückweg zum Chat; der aktive Ort bleibt sichtbar, alle Adminziele bleiben erreichbar. Präziser Aufbau und Umschaltverhalten werden in P03 festgelegt.

### E03 – Geltungsbereich sichtbar, Auswahl am richtigen Ort

**Befund:** Organisation, Arbeitsbereich und Wissensbasis werden an mehreren Stellen wiederholt; unterschiedliche Bedeutungen sind schwer erkennbar (G02/G03/C07/C08/K01/T01). **Entscheidung:** Die Shell zeigt den aktiven organisatorischen Kontext eindeutig. Der Composer zeigt die für die Antwort verwendete Hauptbasis und zusätzlichen Kontext; Wissen zeigt den zu verwaltenden Bestand. Gleiche Auswahl darf nicht wie drei unabhängige Einstellungen aussehen.

Für Team und Administration muss erkennbar sein, ob eine Aktion Organisation, Arbeitsbereich oder System betrifft. Ein globaler Kontextwechsel darf keine ungespeicherten Eingaben still verlieren. **Spätere Abnahme:** Vor Senden, Upload, Einladung und Konfigurationsänderung ist der Geltungsbereich klar; Rollen und serverseitige Filter wirken unverändert. Keine erfundene Modellwahl pro Chat und keine neue automatische Kontextübernahme. P03 definiert die genaue Zuordnung zum vorhandenen Zustand.

### E04 – Typografie trägt die Qualität

**Befund:** Chat, Metadaten, Listen und technische Formulare verlangen unterschiedliche Lesemuster (F11/F13). **Entscheidung:** Eine gemeinsame Schriftfamilie; die bereits vorhandene Inter ist der erste zu prüfende Kandidat. Deutliche Abstufung zwischen Seiten-/Abschnittstitel, Bedienlabel, Haupttext und Metadatum; Hierarchie entsteht auch durch Gewicht und Abstand. Referenzanschluss: R02.

Antworttext erhält mehr Leseraum und eine passende Zeilenlänge; Bedienflächen bleiben kompakter. Lange deutsche Begriffe, URLs, Pfade, Zahlen und Code sind Pflichtproben. Keine Winzigschrift für notwendige Informationen und keine ungeprüfte Nachbildung von San Francisco. **Spätere Abnahme:** Lange Antwort und dichte Datenansicht sind in Hell/Dunkel und bei Zoom lesbar; Vergrößern führt nicht zu überdeckten Aktionen. Exakte Skala, Zeilenhöhe und Lesebreite erst P03/P04.

### E05 – Großzügigkeit dort, wo sie Orientierung schafft

**Befund:** Ein schmaler Dialog für alle Aufgaben und ungleichmäßige Seitenabstände (F05/F06/F10/F12). **Entscheidung:** Ein gemeinsames Abstandsraster mit drei bewusst definierten Anwendungen: entspannter Lesefluss, klar getrennte Formgruppen und kompakte, gut scannbare Datenzeilen. Referenzanschluss: R03/R12/R13.

Zusammengehörige Elemente rücken zusammen, Abschnitte erhalten mehr Abstand. Datenansichten nutzen vorhandene Breite; Textformulare erhalten eine sinnvolle maximale Lesebreite. Kein Pflicht-Leerraum um jede Zeile, keine Kartengitter für einfache Datensätze. **Spätere Abnahme:** Abstand vermittelt fachliche Beziehungen; Tabellenköpfe und Zeilen fluchten, zusammengehörige Felder bleiben erkennbar. Touch-Erreichbarkeit darf nicht der visuellen Dichte geopfert werden. Kein neuer Dichte-Umschalter als Zusatzfeature.

### E06 – Flächen, Farbe und Ebenen mit Bedeutung

**Befund:** Mehrere Token-/Override-Systeme und unterschiedliche Hervorhebungen (F01/F08/F11). **Entscheidung:** Ruhige neutrale Basis, ein Arbeitsakzent, getrennte semantische Rollen für Erfolg, Hinweis, Warnung und Fehler. Status braucht Text und bei Bedarf ein Symbol. Eine Inhaltsfläche ist nicht automatisch eine Karte. Schatten unterscheiden schwebende Ebenen vom Dokumentfluss. Referenzanschluss: R11.

Kein Glas, dekorativer Verlauf, großes Branding in Arbeitsansichten oder willkürlicher Schatten. Kleine Radien sollen präzise wirken, ohne jedes Objekt zur Pillenform zu machen. **Spätere Abnahme:** Fokus, Auswahl, Fehler und deaktiviert sind unterscheidbar, auch ohne Farbe; Portal-/Dialogflächen funktionieren in beiden Themes. Exakte Werte und Kontraste erst P03, gemessene Prüfung später. Das historische DESIGN.md und der Entwurf vom 14.11. sind kein bereits validierter neuer Vertrag.

### E07 – Aktionen erklären, was passiert

**Befund:** Zwei Buttonfamilien, uneinheitliche Bestätigungen und teilweise direkte Löschungen (F02/F06/F09). **Entscheidung:** Gemeinsame Hierarchie für primäre, sekundäre, unauffällige und destruktive Aktionen. Verben beschreiben den Effekt: etwa „Wissensbasis anlegen“, „Verbindung testen“, „Mitglied entfernen“. Wiederkehrende Aktionen behalten ihren Ort. Referenzanschluss: R05/R16.

Eindeutige Icons ergänzen Text; notwendige Funktionen werden nicht allein bei Hover sichtbar. Bestätigungen nennen Objekt und Folge, sensible Aktionen erhalten bewusst gewählten Fokus. Keine falschen „Rückgängig“-Versprechen ohne Backend. **Spätere Abnahme:** Bedeutung vor Ausführung verständlich, Keyboard-/Touch-Zugang vorhanden, Busy verhindert Doppelaktionen. Details zu Default-, Dirty- und Busy-Verhalten folgen P03; eine Warnung ist kein Ersatz für echte Rechteprüfung.

### E08 – Formulare nach Verständnis gliedern

**Befund:** Input/TextField/rohe Felder, lange LDAP-Dialoge und Inline-Adminformen verwenden unterschiedliche Regeln (F03–F05/L02–L04/AD03–AD08/W01–W04/M02). **Entscheidung:** Dauerhaft sichtbare Labels, lokale Hilfen, fachliche Abschnitte und Fehler am betroffenen Feld. Eine einspaltige Lesereihenfolge ist der Standard; nebeneinander nur kurze eng zusammengehörige Werte. Referenzanschluss: R09/R12.

Technische Zusatzoptionen dürfen unter „Erweitert“ liegen; Sicherheitswirkung, Pflichtangaben und ACL-Warnungen bleiben sichtbar. Secrets behalten die vorhandene Beibehalten/Ersetzen/Entfernen-Semantik. **Spätere Abnahme:** Das längste Verzeichnisformular und alle Anbieter-/Quellenvarianten bleiben bedienbar; Fehler erhalten Eingaben, Fehlerzusammenfassung und Fokuszuordnung werden definiert. Keine Platzhalter als Labelersatz, keine neuen Konfigurationseigenschaften durch Gestaltung.

### E09 – Kleine Aufgabe, Suchmodus und Quellenlesen unterschiedlich behandeln

**Befund:** AppDialog, native Bestätigung, Palette und SourcePreview besitzen getrennte technische Pfade (F06/F07/G06/K11/K12). **Entscheidung:** Kleine Anlege-/Umbenennungsdialoge bleiben fokussierte Aufgaben. Suche bekommt einen klar begrenzten Suchraum und verständliche Ergebniszustände. Das Quellenpanel ergänzt Lesen, ohne den Gesprächskontext unnötig zu blockieren. Referenzanschluss: R04/R08/R16.

Menü, Popover, modaler Dialog, mobile Navigation und nichtmodales Quellenpanel erhalten jeweils den passenden Interaktionsvertrag. Keine Gleichsetzung aller Overlays und keine neuen Modalstapel für Hilfetexte. **Spätere Abnahme:** Escape, Fokus-Rückkehr, Schließen, Scrollen und Überlagerung funktionieren pro Typ. Quelle/Seite/Abschnitt bleiben erkennbar; Download und bestehende Fehlerpfade bleiben verfügbar. Rechte verweigert und Vorschau nicht verfügbar erscheinen nicht als leeres Dokument.

### E10 – Der Datenquellen-Assistent bekommt ausreichend Arbeitsfläche

**Befund:** Sechs Schritte, Quellenvarianten und Rechteprüfung passen nicht in den heutigen kleinen Dialog (D02–D07). **Entscheidung:** Eigener großer Assistentenrahmen mit getrenntem Fortschritt, Hauptinhalt und erreichbaren Aktionen. Desktop bevorzugt klare Schrittspalte; schmale Ansicht zeigt aktuellen Schritt und aufklappbare Übersicht. Referenzanschluss: R15.

Die Wahl zwischen großem modalen Rahmen und eigener Arbeitsansicht wird in P04 an realen Eingaben verglichen; sie ist nicht durch ein Referenzbild entschieden. Keine zusätzliche Route oder Draft-Speicherung voraussetzen. Bestehende sechs Schritte, Tests und Aktivierung bleiben fachlich erhalten. **Spätere Abnahme:** SMB/LOCAL/SharePoint-Verbindung, Ordner-/Ausschlusswahl, ACL-Vorschau und Aktivierung funktionieren einschließlich Zurück, Fehler und Abbruch. Sicherheitswarnung bleibt deutlich; kein fiktiver Fortschrittsprozentsatz oder Abbrechen eines serverseitig nicht abbrechbaren Vorgangs.

### E11 – Verwaltung als brauchbare Datenansicht

**Befund:** DocumentTable ist eine gruppierte Liste, MCP-Journal eine echte Tabelle; Karten, Zeilen und Metadaten wechseln ihre Ordnung (F10/K02/T04/AD02/AD07/M01/M05). **Entscheidung:** Vergleichbare Attribute werden entlang gemeinsamer Spalten/Achsen gezeigt. Titel, Status und Nebeninformationen erhalten feste Priorität. Bestehende Filter/Suche stehen nahe an den betroffenen Daten. Referenzanschluss: R13.

Kleine Anbieter-/Verbindungsbestände können gut gegliederte Zeilenlisten bleiben. Berechtigungsschalter und Journal werden nicht aus Platzgründen versteckt. **Spätere Abnahme:** Lange Namen, null/ein/viele Datensätze und Fehler bleiben verständlich; Zeilenaktionen sind per Touch und Tastatur erreichbar. Keine neue Bulk-Aktion, Sortierung, Pagination oder Rechtefunktion versprechen. Mobile Anordnung darf Metadaten neu gruppieren, aber keine fachlich notwendigen Informationen still entfernen.

### E12 – Jeder Zustand sagt die Wahrheit und bietet einen passenden nächsten Schritt

**Befund:** Such-/MCP-Fehler können wie leerer Bestand wirken; Feedback ist verteilt (F08/F09/G06/M01). **Entscheidung:** Datenzustand und Vorgangszustand getrennt planen. Erster Einstieg erklärt die Aufgabe. Eine erfolgreiche Suche ohne Treffer schlägt andere Suche/Filter vor. Ein Ladefehler benennt Problem und mögliche Wiederholung. Referenzanschluss: R14.

Beispieltexte sind Entwurfsproben, keine Implementierungsbehauptung: „Hier sind noch keine Dokumente“, „Keine passenden Dokumente gefunden“, „Dokumente konnten nicht geladen werden“. Verarbeitung, unterbrochener Abgleich, Teilfehler und Sitzungsende bekommen eigene Aussagen. **Spätere Abnahme:** Derselbe technische Fehler erscheint nicht als erfolgreicher Leerzustand. Rückmeldung steht am betroffenen Objekt; wichtige Fehler verschwinden nicht ausschließlich in einem kurzen Toast. Nur bekannte Fortschrittsdaten zeigen; automatischer Retry ist kein neues Feature dieses Auftrags.

### E13 – Im Chat stehen Antworten, Wissenskontext und Quellen im Mittelpunkt

**Befund:** C01–C13 umfassen weit mehr als den Begrüßungsscreen; Markdown, Tool-/Webkontext und Vorlagen benötigen eine kohärente Hierarchie. **Entscheidung:** Wenig Rahmen um Nachrichten, gut gesetzter Antwortinhalt und eine klare Eingabezone. Die Hauptbasis ist auffindbar, zusätzliche Basen/Anhänge sind als Kontext erkennbar und entfernbar. Websuche erhält einen verständlichen Zustand. Quellen führen zum passenden ergänzenden Panel.

Senden/Stopp, Wiederholen, Feedback, Export, Codekopieren und alle Vorlagenwege bleiben erhalten. Weniger visuelles Gewicht darf keine Funktion unsichtbar machen. **Spätere Abnahme:** Echte lange Unterhaltung einschließlich Tabelle, Code, Links, Quellen, Anhang, Tool-Calling/Rückfall und Überlauf prüfen. Kein horizontaler Scrollzwang für die ganze App. Keine neue Chat-Teilung, Historienpersistenz oder Umschaltung auf fremde Produktionen aus Referenzen ergänzen. Die fachliche Sicherheitslogik des Antwort-Renderers bleibt erhalten.

### E14 – Gleiche Sorgfalt für Zugang, Rollen und sensible Ausgaben

**Befund:** Anmeldung, Passwortwechsel, Onboarding, Einladungs-/Startcodes und Adminzugriff sind eigene Flows (A01–A06/T06/T08/T10/AD01). **Entscheidung:** Ruhige Zugangsmasken mit eindeutigem lokalem oder Firmenkonto-Einstieg, klarer Rückmeldung und nachvollziehbarer Folgeaktion. Einrichtung führt durch bestehende Org-/Bereich-/Basis-Schritte. Sensible Codes bekommen einen gut lesbaren, erklärten eigenen Bereich.

Keine Dekoration, die Fehler oder Erfolg überlagert. Rolle und Reichweite sind verständlich benannt; vorhandene Zugriffsbeschränkung bleibt verbindlich. **Spätere Abnahme:** Sitzung wiederherstellen, ablaufen lassen, falsche Eingaben und eingeschränkte Rollen prüfen. Kopieren/Einmaligkeit sensibler Ergebnisse erklären, Belege maskieren. Keine neue Registrierung, Passwort-zurücksetzen, OIDC-Administration oder Einladung per zusätzlichem Kanal. Externe Firmenanmeldung und Betriebssystemdialoge bleiben externe Flächen.

### E15 – Responsive, Fokus und Bewegung sind Teil des Entwurfs

**Befund:** Verteilte Breakpoints, geringe native Fensterhöhe und verschiedene Fokus-/Portalpfade (F14/G07/G08). **Entscheidung:** Layoutwechsel statt bloßer Verkleinerung. Mobil eine eindeutig bedienbare Navigation; Quellenlesen und komplexe Konfiguration erhalten den verfügbaren Raum. Touch und Tastatur müssen denselben Funktionsumfang erreichen. Referenzanschluss: R03/R06/R15/R16.

Bewegung erklärt Öffnen, Auswahl oder Wechsel und verzögert keine Arbeit. Reduzierte Bewegung erhält dieselbe Rückmeldung ohne störende räumliche Effekte. **Spätere Abnahme:** Kleine Webbreite, geringe Höhe, Zoom, Bildschirmtastatur, Tab/Shift+Tab/Escape, Fokus-Rückkehr und Screenreader-Stichproben real ausführen. Kontrast-/Zielgrößenschwellen sowie Breakpoints und Animationen werden in P03 definiert. Keine behauptete Accessibility-Konformität allein aus einer Referenz oder Bibliothek.

### E16 – Ein System muss auch technisch eines bleiben

**Befund:** Mehrere CSS-Quellen, zwei Basisfamilien und alte Designtexte widersprechen sich (F01–F07/Z01/Z02). **Entscheidung:** Phase 3 definiert eine autoritative Systembeschreibung mit Migrationszuordnung für alle vorhandenen Primitive und Zustände. Bestehende Vue/Reka/Tailwind-Basis bleibt Ausgangspunkt. Ein zusätzlicher großer Override-Block würde die Ursache verstärken.

P04 validiert den Zusammenhang, bevor einzelne Fachseiten umgebaut werden. **Spätere Abnahme:** Gemeinsame Komponenten sind auch in Portals/Themes konsistent; bestehende Events, Testids, Formular- und Secret-Verträge sind erhalten. Toter Präsentationscode wird bewusst behandelt. Kein Frameworkwechsel aus Geschmacksgründen und keine neuen Abhängigkeiten in Phase 2. Reviewer, QA, Sicherheitsprüfung wo nötig und lokales CI bleiben echte spätere Gates, keine durch das Research ersetzten Schritte.

## Erwogene Alternativen

| Alternative | Vorteil | Nachteil für Keplura | Auswahl |
|---|---|---|---|
| Stark Apple-nativ mit durchscheinenden Flächen und Plattform-Schrift | Markante Materialwirkung auf passenden Geräten | Widerspricht gewünschter Ruhe ohne Glas; plattformübergreifende Web-App braucht eigene Regeln | Nur Hierarchie und sorgfältiges Verhalten übernehmen |
| Notion-ähnliche, frei anpassbare Inhalts-Sidebar | Viel individuelle Organisation | Zusätzliche Produktfunktionen, schwer erklärbare Kontext-/Rechtewirkung | Feste Einstiege mit kontextuellen Inhalten |
| Sehr dichte Linear-artige Poweruser-Oberfläche | Schnell für geübte Nutzer und große Bestände | Kleine Bedienelemente und unbeschriftete Icons können Mitarbeiter ausschließen | Präzise Ausrichtung, angemessene statt maximale Dichte |
| Luftiges Karten-Dashboard für alle Bereiche | Einfach einheitlich zu zeichnen | Schwacher Vergleich, viel Scrollen, versteckte Aktionen, generischer Eindruck | Fachgerechte Listen/Form-/Leselayouts auf gemeinsamer Basis |
| Ein kleines Modal für alle Vorgänge | Ein einziger Rahmen | Lange Verzeichnisform und ACL-Vorschau bleiben unbrauchbar eng | Kleine Dialoge plus eigener komplexer Layouttyp |
| Neuer UI-Stack / vollständige fremde Komponentenbibliothek | Fertige neue Stilwelt | Migrationsrisiko und neue Systeme lösen Informationsarchitektur nicht automatisch | Bestehenden Stack gezielt konsolidieren; konkrete Grenzen gegebenenfalls später belegen |

## Zuordnung zur vollständigen Bestandsaufnahme

Diese Matrix verknüpft **alle 109 IDs** mit der Richtung. Bereiche bleiben vollständig im [ursprünglichen Tracker](ui-audit-2026-11-15/coverage.json); sie werden durch die Recherche nicht auf „umgesetzt“ oder „geprüft“ gesetzt. Die detaillierten Ausgangs-Abnahmekriterien des Inventars gelten weiter. Referenznummern R beziehen sich auf das Recherche-Dokument, E auf die obigen eigenen Entscheidungen.

| Inventarbereich / Anzahl | Schwerpunkt aus Phase 1 | Richtung | Referenzanschluss / spätere Prüfung |
|---|---|---|---|
| F01–F14 / 14 | Tokens, Primitive, Form/Overlay, Status, Daten, Typografie, Markdown, Geräte | E01/E04–E09/E11/E12/E15/E16 | R01–R06/R11–R16; gemeinsame Zustands- und Migrationsverträge |
| G01–G08 / 8 | Shell, Kontext, Verlauf, Konto, Suche, mobile/native Umgebung | E01–E03/E09/E15 | R03/R07/R08/R10/R11/R16; alle Einstiege und Rückwege |
| A01–A06 / 6 | Login, OIDC, Passwort, Sitzung, Einrichtung/Folge | E07/E08/E12/E14/E15 | R01/R02/R12/R14–R16; vollständiger Zugang statt nur Loginbild |
| C01–C13 / 13 | Unterhaltung und alle Eingabe-/Aktions-/Vorlagenzustände | E03/E04/E07/E09/E12/E13/E15 | R02–R04/R08/R14/R16; längere reale Unterhaltung |
| K01–K12 / 12 | Explorer, Dokumente, Upload, Suche, Anlage/Löschen, Quelle | E01/E03/E05/E07/E09/E11/E12/E15 | R03/R04/R08/R13/R14/R16; Wissen und Quellenpanel zusammen |
| T01–T10 / 10 | Organisation, Mitglieder, Einladungen, einmalige Zugangsdaten | E03/E07–E09/E11/E12/E14 | R05/R09/R12–R14/R16; Rollengrenzen und Codeausgabe |
| AD01–AD11 / 11 | Adminnavigation, Anbieter/Modelle, Standardwechsel, RAG, Status | E01–E03/E07/E08/E11/E12/E14 | R09/R10/R12–R14/R16; alle Inline-Varianten und Betriebsaussagen |
| D01–D10 / 10 | Quellenstatus, sechs Schritte, Läufe, Probleme, Entfernen | E03/E07/E08/E10–E12/E15 | R05/R12–R16; ACL-/Variantendaten und kleine Höhe |
| L01–L06 / 6 | Verzeichnisliste, Langform, Sicherheit, Test/Lookup, Entfernen | E07–E09/E11/E12/E15 | R05/R12–R14/R16; lange DNs, TLS und Geheimnisse |
| Q01–Q04 / 4 | Qualitätsdaten, Testfragen, Anlage, Import | E07–E09/E11/E12 | R12–R14/R16; Fehl-/Teilimport und verständliche Kennzahlen |
| W01–W04 / 4 | Vier Suchanbieter-Varianten, Secret, Grenzen/Domains, Test | E07/E08/E12 | R12/R14/R16; alle Varianten, Schlüssel unverändert |
| M01–M06 / 6 | Serverliste, Verbindung, Werkzeug-/Bereichsfreigaben, Journal, Entfernen | E03/E07–E09/E11/E12 | R05/R09/R12–R14/R16; Sichtbarkeit von Freigaben und Journal |
| B01–B03 / 3 | Status, Backup-/Zeitplan-/Restore-Anleitung | E01/E04/E07/E12/E14 | R01/R02/R14; verständliche Betriebshilfe, CLI bleibt CLI |
| Z01–Z02 / 2 | Toter Präsentationscode und konkurrierende Dokumentation | E16 | Bestandsabgleich; aktuelle Systemquelle statt weiterer Parallelentwürfe |

## Was Phase 3 konkret liefern muss

| Offene Definition | Verlangtes Ergebnis | Entscheidend für |
|---|---|---|
| Palette / Kontraste | Semantische Rollen in Hell/Dunkel, gemessene Prüfziele, Fokus-/Status-/Disabled-Abgrenzung | E06/E12/E15 |
| Typografieskala | Bedien- und Lesehierarchie, Schrift-/Fallback-Entscheidung, lange deutsche Texte, Code/Zahlen | E04/E13 |
| Maße / Dichte / Raster | Gemeinsame Abstände, Seitenbreiten, Form-/Tabellendichte, Radien und Ebenen | E01/E05/E06/E11 |
| Komponenten und Migration | Ein Button-/Feld-/Status-/Datenvertrag, Zuordnung jedes Altbausteins und Portalpfads | E07/E08/E11/E16 |
| Kontext / Navigation | Sichtbare Orte für Organisation, Arbeitsbereich und Basis; Zielerreichbarkeit, Kontextwechsel/Dirty-Verhalten | E02/E03/E14 |
| Overlay-Verträge | Kleine/komplexe Form, Confirm, Menü, Suchdialog, Quellenpanel, Mobile Drawer; Focus/Scroll/Escape/Busy | E09/E10/E15 |
| Zustandskatalog | Laden/leer/kein Treffer/Fehler/Teilstand/Erfolg/Sitzungsende plus Tonalität und nächste Handlung | E12/E14 |
| Geräte / Animation / Accessibility | Breakpoints, geringe Höhen, Zoom, Touch, Fokus, reduzierte Bewegung und überprüfbare Schwellen | E15 |

Die Phase-1-Reihenfolge bleibt sinnvoll: **P03 System → P04 Validierung → P05 Basis → P06 Shell/Zugang → P07 Chat/Quellen → P08 Wissen → P09 Team → P10 Admin-Kern → P11 Datenquellen/Verzeichnis → P12 restliche Administration → P13 Querprüfung → P14 Abschluss.** Die Recherche begründet die fachlichen Layoutunterschiede innerhalb dieser Reihenfolge. Sie rechtfertigt keinen vorgezogenen MCP-Sonderstil und kein erneutes Einzel-Facelift.

## Geplante Validierung in Phase 4

**Chat als Hauptscreen:** Zuerst eine echte Unterhaltung mit langer Antwort, Markdown-Tabelle, Code, Quellen und Anhang beurteilen. Dazu Auswahl Hauptbasis/Zusatzbasis, Websuche, Streaming/Stopp, fehlende Antwort, Wiederholung, Feedback, Export und Vorlagen. Leerer Einstieg ist nur einer der Zustände. Schwerpunkt: E02–E04/E09/E12/E13/E15.

**Datenquelle als komplexe Aufgabe:** Bestehende sechs Schritte mit repräsentativen SMB-/LOCAL-/SharePoint-Daten aufbauen. Schwerpunkt Verbindung und ACL-Vorschau; auch lange Gruppen-/Pfadnamen, Lade-/Testfehler, Zurück und Abbruch. Großer modaler Rahmen gegen eigene Arbeitsansicht vergleichen, ohne neue fachliche Persistenz zu erfinden. Schwerpunkt: E05/E08/E10/E12/E15.

**Gemeinsamer Belastungstest:** Datenzeile/MCP-Journal, Secret-Feld, Löschbestätigung und einmalige Zugangsdaten müssen in dieselbe Sprache passen. Hell/Dunkel, schmale Webansicht, niedrige native Fensterhöhe und Zoom gehören in die erste Validierung. Wenn eine Variante Platz oder Semantik verliert, wird der Vertrag korrigiert, bevor weitere Seiten folgen.

**Ausstiegskriterium von P04:** Der Nutzerweg ist ohne Vorwissen nachvollziehbar, alle vorhandenen Aktionen bleiben auffindbar, Layout und Fokus funktionieren in den definierten Zuständen, typische lange Inhalte passen, und konkrete visuelle Belege zeigen das. Kein bloßes Urteil „sieht modern aus“, keine pauschale Abnahme aller Inventar-IDs.

## Status, Arbeitsumfang und Nachweise dieser Phase

- **Ziel / Scope:** Recherche und begründete Richtung für das gesamte Phase-1-Inventar; 16 Primärreferenzen, fünf visuell betrachtete veröffentlichte Abbildungen, Entscheidungskatalog, Alternativen, vollständige Bereichszuordnung und präzise Übergabe an P03/P04.
- **Nicht-Ziele:** Produktcode, neue Features, Frameworkwechsel, Tokenwerte, Komponentenimplementierung, fertige Mockups, Build-/Funktions-/A11y-Abnahme oder Merge/Release/Deployment.
- **Workspace / Git:** Separates Worktree `.worktrees/ui-research`, Branch `codex/ui-research`, von `main` angelegt und mit den noch ungemergten Abhängigkeiten bis `codex/ui-audit@f749083` per Fast-forward fortgeführt. Review-Diff gegen `codex/ui-audit` ist reine Dokumentation; die vorherigen PR-Gates bleiben unabhängig offen.
- **Dokument-Abnahme:** Quellen und Belegarten benannt; Typografie, Farbe, Abstände, Navigation, Daten, Form, Dialog, Motion und Responsive behandelt; alle 109 IDs zugeordnet; Auswahl und Alternativen erklärt; Grenzen ehrlich benannt. Prüfungsergebnisse stehen im [Phase-2-Prüfprotokoll](ui-research-2026-11-15/README.md).
- **Offen:** P03–P14 des Paketplans, Nutzer-/visuelle Validierung in P04, spätere Implementierung und alle dazugehörigen realen Qualitätsgates. Alle 109 Designstatus bleiben offen. Die Recherche ist keine Freigabe des bisherigen Entwurfs oder vorweggenommene Konformitätsbehauptung.
