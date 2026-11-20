# Phase 1 – vollständiges UI-Inventar

Stand: 15.11.2026, Produktbasis `2de0a8a5203735507c00f56548daca2d2c2a05f9` (PR #54). Der bisherige Umbau ist Bestand, keine abgenommene Lösung für den neuen Auftrag. Dieser Audit verändert keinen Produktcode. Nachfolgend wurden Phase 2 [recherchiert](UI-DESIGN-RESEARCH-2026-11-15.md) und Phase 3 [spezifiziert](UI-DESIGN-SYSTEM-2026-11-15.md); alle Inventarstatus bleiben offen. Phasen 4–7 sind nicht begonnen.

## Umfang und Grenzen

- Alle **36 Vue-Dateien** statisch per SFC-/Template-AST erfasst. [Komponentenregister](ui-audit-2026-11-15/COMPONENTS.md).
- [Quelltextindex](ui-audit-2026-11-15/source-index.json): **527 Elementstellen** mit Datei, Zeile, Tag, Attributen, Events und Testids; **314 bedingte/wiederholende Template-Stellen**. Darin sind auch Icons, Rollen und Komponenten enthalten: keine Behauptung von 527 Buttons/Screens. Datensätze erzeugen weitere Instanzen derselben Stelle.
- Zusätzlich manuell erfasst: dynamische Markdown-Code-Kopierbuttons, zwei native `window.confirm`-Aufrufe, Betriebssystem-Dateiauswahl/Downloads, externe Firmenanmeldung und das Tauri-Fenster.
- **32 Ansichten/Overlays live geöffnet und erfasst**, in der isolierten QA-App. Nur die Anmeldung abgeschickt; keine fachlichen Änderungsformulare gespeichert, Verbindungstests ausgelöst, Quellen aktiviert oder Löschungen bestätigt. [Protokoll](ui-audit-2026-11-15/observations.json), [Screenshots](ui-audit-2026-11-15/screenshots/).
- Live geöffnet ist keine vollständige Funktions-/Designabnahme. Besonders visuell begutachtet: Assistent/Art, Verzeichnisanlage, Fragenimport und Mitgliedanlage. Frühere Überblicks-Screenshots ersetzen keine Dialog-/Zustandsabnahme.
- Nicht live durchgespielt: Ersteinrichtung, Passwortwechsel, OIDC-Weiterleitung, destruktive Aktionen, Assistentenschritte 2–6, provozierte Server-/Netzfehler, alle Leer-/Rollenvarianten, alle mobilen Dialoge, native Windows-/macOS-Flächen. Diese sind quellenbasiert inventarisiert; Ausführung bleibt offen.
- SharePoint/Entra brauchen einen Testtenant, DFS/NFS passende Quellen. Das begrenzt spätere Integrationsabnahmen, verhindert keine Layoutplanung.

## Navigation heute

```text
Sitzungsprüfung
├─ ohne Sitzung → Login lokal / Firmenkonto → externer IdP
├─ Startpasswort → Eigenes Passwort festlegen
└─ Shell
   ├─ Organisationen-Menü / Konto-Menü / globale Suche
   ├─ Chat und persönlicher Verlauf
   │  ├─ fehlende Organisation/Bereich/Basis → Onboarding
   │  ├─ Composer: Basis / Vorlagen / Web / Datei / zusätzliche Basen
   │  └─ Antwort: Kopieren / Wiederholen / Bewerten / Export / Quelle
   ├─ Wissen: Arbeitsbereich / Basen / Dokumente / Inhaltsuche
   ├─ Team: Organisationen / Mitglieder / Einladungen
   └─ Administration (nur globale ADMIN-Rolle)
      ├─ KI & Wissen: Anbieter / Modelle / Suche & Wissen
      ├─ Verbindungen: Datenquellen / Websuche / MCP / Verzeichnis
      └─ Betrieb: Systemstatus / Antwortqualität / Datensicherung
```

`App.vue`/`navigation.ts` halten den View-Zustand; keine eigene URL pro Fachseite. Organisation, Arbeitsbereich und Basis beeinflussen mehrere Flächen. Sidebar und Fachansichten wiederholen Kontextauswahl. Globale Namenssuche und inhaltliche Wissenssuche sind verschieden; ein globaler Dokumenttreffer öffnet derzeit die zugehörige Basis.

Kein eigener bestehender Screen für OIDC-Anbieter-Verwaltung, Modellwahl je Chat, Registrierung, Passwort-zurücksetzen, Chat-Teilen/Archiv oder Undo-Historie. Solche Features werden nicht stillschweigend hinzugeplant. Backup/Restore bleiben Serverbefehle. Externe IdP-/Microsoft-/OS-Oberflächen gehören nicht zum gestaltbaren Produktumfang.

Zusätzliche Quelldateien: `desktop/index.html` für Boot/Theme vor dem ersten Paint; `auth-store.ts`, `navigation.ts`, `theme-store.ts`, `fullscreen.ts`, `source-preview.ts`, `transport.ts` und `ui/clipboard.ts` für Zustands-/Native-/Clipboard-Pfade; `desktop/src-tauri/tauri.conf.json` für Fenstergrenzen. `docs/design/apple-refined.html` ist eine historische Designreferenz, kein weiterer produktiver Screen. Im Repo-Abgleich wurden keine weiteren produktiven TSX-/JSX-/Svelte-Oberflächen gefunden.

## Befunde mit nachvollziehbarer Grundlage

| Befund | Grundlage | Konsequenz |
|---|---|---|
| Zwei Button-Systeme | AppButton: Akzentfüllung, 3 direkte Vue-Verwender; Button: Primärtoken, 16 Verwender | Gemeinsame Varianten/Größen, Handler und Submit-Semantik bewahren |
| Zwei Feld-Systeme plus rohe Inputs | Input im Login, TextField in vier Fachkomponenten, Spezialfelder separat | Einheitlicher Label-/Hilfe-/Fehler-/Busy-Vertrag |
| Überlagerte Stilarchitektur | theme.css, tailwind.css, workspace.css plus scoped CSS, ID-Selektoren und !important | Zuständigkeiten festlegen, keine weitere Override-Schicht |
| Ein Dialogmaß für komplexe und einfache Aufgaben | AppDialog: min(380px, calc(100vw - 48px)); live umbrechende Schrittübersicht und langes Verzeichnisformular | Dialoggrößen und Layouttypen nach Aufgabe definieren |
| Uneinheitliche Bestätigungen | ConfirmDialog in fünf Fachkomponenten; native Bestätigungen für Embedding-Standard und MCP-Löschen; andere Löschungen direkt | Gemeinsames risikobasiertes Muster; kein erfundenes Undo |
| Fehler können Leerzustand imitieren | Quelltext: globale Suche setzt Fehler auf result=null; MCP-Load übernimmt nur erfolgreiche Ergebnisse | Laden/leer/Fehler/Sitzungsende trennen; Laufzeitfehler hierfür noch nicht provoziert |
| Kontext/Aktionen doppelt | Sidebar-Baum, KB-Leiste, Composer; zwei New-Chat-Einstiege | Aufgabenführung und Kontextwechsel klären, Funktionen erhalten |
| Verschiedene Feedbacksysteme | StatusPill, StatusRing, lokale Kreise/Notizen/Toasts | Einheitliche Semantik, Text plus Symbol, passende Folgeaktion |
| Unterschiedliche Overlay-Pfade | Reka-Portals, native dialog, CommandPalette, SourcePreview-Teleport | Fokus, Stacking und Portal-Stile explizit behandeln; #app-Regeln erreichen Body-Portals nicht |
| Verteilte Responsive-Regeln | Shell 760px, andere Regeln 1000/1200px; Tauri 900×640, Minimum 480×400 | Einheitlicher Layoutvertrag, geringe Höhe und Zoom prüfen |
| Datenansichten sind nicht überall Tabellen | Dokumente gruppierte Listen, Anbieter/Modelle Zeilenlisten; MCP-Journal echte Tabelle | Semantik und Scanbarkeit je Aufgabe, keine pauschale Kartenumwandlung |
| Präsentations-Altbestand | Chat-Aside v-if=false; lokales Label.vue ohne direkten Vue-Import | Bewusst migrieren/aufräumen, aktive Render-/Sicherheitslogik erhalten |
| Historische Designtexte widersprechen sich | Mehrere Designtexte/Migrationskommentare beschreiben verschiedene Layouts und Radien | Eine aktuelle Systemdefinition und Abnahmeliste |
| Dokumentsprache passt nicht zur Produktsprache | desktop/index.html setzt lang="en", die Produkttexte sind deutsch | Semantische Sprache in Accessibility-/Typografievertrag aufnehmen; noch keine Screenreader-Ausführung |

Kontraste und Screenreader-Konformität wurden nicht gemessen; hierzu keine Pass-Behauptung.

## Inventar und Änderungsplan je Bereich

**Alle Einträge sind offen für das neue Redesign.** Vorhandene Funktionen und früher bestandene Tests sind keine neue Designfreigabe. P0 = systemprägend/Zugangs- oder Verlustrisiko; P1 = regulärer Pflichtumfang; P2 = nachrangiger Pflichtumfang, nicht optional.

Abhängigkeiten: DS = Phase-3-System; PV = Phase-4-Validierung; SH = Shell; FO = Felder/Formulare; OV = Overlays; DA = Daten/Status. Auflösung und Reihenfolge im [Arbeitsplan](UI-REDESIGN-PLAN-2026-11-15.md). Jede Zeile erbt A0 und passende Zustandsprofile am Dokumentende. Quellen-Dateinamen sind über das Komponentenregister eindeutig verlinkt; einzelne Controls mit Events/Attributen stehen im Quelltextindex.

### Gemeinsame Komponenten

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| F01 | Tokens / drei CSS-Dateien | Verteilte Werte und Overrides | Autoritative Tokens und Migrationsliste je Altregel | P0 | Phase 2 | Vollständige semantische Zuordnung Hell/Dunkel; keine neue Override-Schicht |
| F02 | Button/AppButton/rohe Buttons | Zwei Familien, unterschiedliche Primärwirkung | Varianten, Größen, Icon-/Destruktiv-Vertrag | P0 | DS | Gleiche Variante überall gleich; Submit/Disabled/Testids erhalten |
| F03 | Input/TextField/Label/rohe Felder | Verschiedene Feldrezepte | Gemeinsamer Label-/Hilfe-/Fehlervertrag | P0 | DS | Zuordnung und Fehlerankündigung; Autofill und Secret-Schutz |
| F04 | Select/Checkbox/Radio/Number/Textarea | Native und lokale Varianten | Auswahl-, Größen- und Zustandsregeln | P0 | F03 | Lange Optionen, Grenzen, Mehrzeiler, Tastatur und aktive Auswahl |
| F05 | Formulare/Validierung | Busy/Fehler/Entwürfe lokal | Submit-, Fehler-, Abbruch- und Entwurfsregel | P0 | F02,F03 | Kein Doppelsubmit; Fehler am Feld; definierte Wiederöffnung/Cancel |
| F06 | AppDialog/ConfirmDialog | Ein schmales Breitenmaß | Größenklassen, Footer, Scroll- und Fokusvertrag | P0 | F05 | Fokus hinein/zurück, Escape/Backdrop, geringe Fensterhöhe |
| F07 | Reka-Menüs/Checkbox-Menüs/Portals | Gemeinsame Klasse, lokale Inhalte | Einheitlicher Menüvertrag | P0 | DS,F02 | Pfeile/Enter/Escape, Fokus-Rückkehr und Viewport-Kollision |
| F08 | StatusPill/StatusRing/lokale Kreise | Unterschiedliche Statusrezepte | Semantische Statusfamilie | P1 | DS | Ohne Farbe verständlich; tatsächlicher Status und Folgeaktion |
| F09 | Toasts/Inline-Meldungen/Retry | Viele note/msg/toast-Varianten | Ort, Dauer, Priorität und Wiederholen | P0 | F05,F08 | Fehler nicht als leer/Erfolg; sichtbar und zugänglich |
| F10 | Listen/Tabellen/Zeilenaktionen | Fachspezifische Grids, Hover-Aktionen | Wiederverwendbare Datendarstellung | P1 | DS,F02,F08 | Viele/lange Werte; Tastatur/Touch; Rechteaktionen erhalten |
| F11 | Icons/Marke/Typografie/Mikrotexte | Icon-Familie, lokale Hierarchien, HTML-Sprache en | Größen, Hit-Areas, Markenanwendung und Produktsprache | P1 | DS | Icon-Namen, lange deutsche Texte, Titel/Logo und passende Dokumentsprache |
| F12 | PageContainer/PageHeader/eigene Layouts | Wrapper nur für drei Seiten | Gemeinsamer Seitenvertrag mit begründeten Varianten | P0 | DS | Header-Aktionen erreichbar; kein pauschales Kartenlayout |
| F13 | MarkdownAnswer/Code/Links | Sanitisiertes HTML, dynamisches Kopieren | Leseregeln für Listen/Tabellen/Code | P0 | DS,F09 | Lokaler Overflow, Copy-Feedback; Sanitizing und Linkschutz unverändert |
| F14 | Responsive/Fokus/Bewegung/Scrolling | Verteilte Breakpoints/Ebenen | Gemeinsamer Layout-/Interaktionsvertrag | P0 | DS,F06,F07 | A0-Matrix, reduced-motion, kein Fokus-/Scrollverlust |

### Shell, Navigation, Fenster

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| G01 | App/AppSidebar | Vier Bereiche, Verlauf und Kontextbaum | Aufgabenorientierte Hierarchie | P0 | DS,PV,F12 | Alle Bereiche auffindbar; Rollen/aktive Auswahl erhalten |
| G02 | Organisationen-Menü | Auswahl und Verwaltungslink | Kontextwechsel und Verwaltung trennen | P0 | G01,F07 | Richtige aktive Organisation und Kontextwirkung |
| G03 | Bereich-/KB-Baum | Verschachtelte Listen und doppelte Auswahlwege | Hierarchie und Overflow klären | P1 | G01,F10 | Alle Einträge, lange Namen und leere Zweige erreichbar |
| G04 | Persönlicher Verlauf/Löschen | Zeitgruppen, direkte Löschaktion | Verlauf und sichere Aktionsführung | P1 | G01,F10 | Aktiver Chat auffindbar; viele Chats; Delete-Erfolg/-Fehler |
| G05 | Konto/Darstellung/Vollbild/Logout | Ein Reka-Menü | Gruppierung und Auswahlstatus | P1 | F07,F14 | Hell/Dunkel/System, F11/Escape und Logout mit Fokus-Rückkehr |
| G06 | CommandPalette | Eigene Dialog/Listbox, Fehler teils wie leer | Suchgruppen, Ergebnisziele und Feedback | P0 | F06,F09,F10 | Vier Gruppen, kein Treffer/Fehler getrennt; Strg/⌘+K und Navigation |
| G07 | Mobile Topbar/Drawer/Scrim | Eigene Fokus-/Escape-Logik | Mit Overlay-System vereinheitlichen | P0 | G01,F06,F14 | Route/Schließen/Resize; Fokus gefangen/zurück; Hintergrund gesperrt |
| G08 | Tauri/OS-Dateidialog/Download | Ein natives Hauptfenster und OS-Flächen | Eigene UI an native Grenzen anpassen | P1 | F14 | 900×640, 480×400, Vollbild, Auswahlabbruch; OS-Flächen nicht als eigene ausgeben |

### Zugang und Einrichtung

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| A01 | LoginView | Karte, zwei Felder, Statusfuß | Feld-, Fehler- und Aktionshierarchie | P0 | FO,F12 | Leer/Busy/falsch/gesperrt/offline; Autofill/Passwortmanager |
| A02 | Firmenkonto/OIDC-Rückkehr | Bedingter Button, externer IdP | Übergang, Fehler und lokalen Alternativweg klären | P0 | A01,F09 | Erfolg/Ablehnung/Rückkehr/Neustart; keine Umgestaltung externer IdP-Seiten |
| A03 | ChangePasswordView | Drei Felder und Regeln | Pflichtwechsel in gemeinsames Zugangsmuster | P0 | A01,F05 | Regeln/Abweichung/falsches Startpasswort/Busy/Erfolg/Logout |
| A04 | Restore/Sitzungsende/Verbindung | Boot-Text, Login-/Sidebarstatus | Offline und Anmeldebedarf unterscheiden | P0 | F09,G01 | Keine stalen/fremden Daten; Wiederholen und abgelaufene Sitzung |
| A05 | Onboarding Org/Bereich/KB | Drei aktive Formschritte, erledigte Schritte | Schrittführung und Wiederaufnahme | P1 | FO,G02 | Alle drei Schritte und Fehler separat; keine Duplikate |
| A06 | Onboarding Upload/Team-Folge | Zwei erklärende Folgepunkte | Nächste echte Aktion klar benennen | P1 | A05,K04,T05 | Text und tatsächlicher Übergang stimmen überein |

### Chat und Kontext

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| C01 | ChatView Einstieg/leer | Begrüßung, Vorschläge, mehrere New-Chat-Wege | Orientierung und Gesprächsstart zusammenführen | P1 | SH,F12 | Ohne Chat, neuer leerer Chat, ohne Bereich/Basis und Vorschläge |
| C02 | Nachrichten/Metadaten | Bubbles, Modell, Uhrzeit, Status | Lesefluss, Breite, Abstände, Metadatenrollen | P1 | F13,C01 | Kurze/lange Antworten, mehrere Turns, lange Wörter |
| C03 | Streaming/Stopp/Fehler/Retry | Live-Text, Stopp, Statuspillen, Hinweise | Prozessfeedback, Scroll- und Wiederholungsregeln | P0 | F08,F09,C02 | Teilantwort bleibt; kein doppelter Turn; Fokus/Scroll stabil |
| C04 | Antwort-/Codeaktionen/Feedback | Kleine Icons, dynamische Code-Kopierbuttons | Aktionsgruppen, Hit-Areas, Rückmeldung | P1 | F02,F09,F13 | Copy erlaubt/verweigert; Daumen wechseln/löschen; Retry-Verfügbarkeit |
| C05 | Exportmenü | PDF, Word, E-Mail-Kopie | Export und Kopieren klar unterscheiden | P1 | F07,C02 | Drei Wege erhalten; eigene/fremde/User-Nachricht wie bisher; Fehler sichtbar |
| C06 | Composer/Senden/Zähler/Kürzel | Schwebende Eingabe und Aktionszeile | Einheitlicher Schreib-/Kontextbereich | P0 | FO,F14 | Enter/Shift+Enter, Länge, Disabled, Busy; Text/Aktionen bei Zoom/mobil getrennt |
| C07 | Primäre Wissensbasis | Select kann neue Unterhaltung öffnen | Konsequenz des Kontextwechsels vermitteln | P0 | C06,G03 | Auswahl/ohne Basis, Entwurf und bisheriger Verlauf bleiben korrekt |
| C08 | Zusätzliche Basen/Chips | Checkbox-Menü, bis fünf zusätzliche Basen | Grenze und Kontextzusammenfassung | P1 | C07,F07 | Hinzufügen/Entfernen/Grenze/Fremdbereich; lange Namen, Tastatur |
| C09 | Anhänge/Picker/Chips | Lesen, gekürzt, Entfernen, Fehlermeldungen | Fortschritt und Extraktionsfehler führen | P0 | C06,F09,G08 | Abbruch, Scan, Typ, 10MB-/Fünf-Dateien-Grenze; kein Doppelupload |
| C10 | Web-Schalter | Nur bei eingerichteter Suche, Pressed-State | Aktive Quellenart verständlich machen | P1 | C06,F08 | Verfügbar/aus/an/Providerfehler; Links/Inhalt unverändert |
| C11 | Vorlagenmenü | Bereich/Organisation, Speichern/Verwalten | Verwenden und Pflegen klar trennen | P1 | F07,C06 | Leerzustand, Scope/Rechte; Einfügen sendet nicht automatisch |
| C12 | Vorlage speichern – Dialog | Name, Text, Radio-Scope | Dichte, Hilfen und Scope erklären | P1 | OV,FO,C11 | Pflicht/Länge/Scope/Busy/Fehler/Abbruch |
| C13 | Vorlagen verwalten – Dialog | Bearbeitbare Vorlagen, direktes Löschen | Listen- und Destruktivmuster | P1 | OV,F10,C11 | Nur zulässige Einträge; leer, Löschen und Fehler |

### Wissen und Dokumente

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| K01 | KnowledgeView/Panel/KbList | Header, KB-Leiste, Sidebar-Duplikat | Explorer-/Kontexthierarchie und Aktionsort | P1 | SH,F12,G03 | Kein Bereich/keine/eine/viele Basen, alle Auswahlwege konsistent |
| K02 | DocumentTable | Statusgruppen, Größe/Datum, Aktionsicons | Scanbare Datenansicht und zugängliche Zeilenaktionen | P1 | F10,F08 | Metadaten erhalten; Rechte/Tastatur/Touch; lange Namen |
| K03 | Dokumentfilter/Gruppen | Filter ab mehr als acht Dateien; Collapse | Filterauffindbarkeit und leere Ergebnisse | P1 | K02,F03 | Schwelle, kein Treffer, viele Gruppen, Filter löschen |
| K04 | Upload/Drag&Drop/Picker | Mehrere Einstiege, sequentielle Verarbeitung | Einheitlicher Ablauf und Ergebnis je Datei | P0 | F05,F09,G08 | Auswahl/Drop/Abbruch, mehrere Dateien, Typ-/Größenfehler, keine UI-Duplikate |
| K05 | Verarbeitung/Reindex | Durchsuchbar/wartend/Fehler/Scan etc. | Fachliche Statussprache und nächste Aktion | P1 | K02,F08 | Jeder bestehende Enum-Zustand, Reindex-Erfolg/-Fehler; Scan ehrlich erklärt |
| K06 | Inhaltsuche/Treffer/Reset | Suchzeile und Quellenlisten | Suchmodus und Trefferstruktur | P1 | F10,F13,G06 | Leer/Busy/kein Treffer/Treffer/Fehler/Reset; Rechtefilter unverändert |
| K07 | Arbeitsbereich anlegen – Dialog | Einfaches Namensformular | Standard einfacher Formdialog | P1 | OV,FO,G02 | Elternkontext, Länge/Kollision/Busy/Erfolg/Cancel, richtige Auswahl |
| K08 | Wissensbasis anlegen – Dialog | Namensformular im aktiven Bereich | Gleicher Vertrag, eindeutiger Zielbereich | P1 | K07 | Pflicht/Länge/Kollision/Busy/Cancel; keine Anlage im falschen Bereich |
| K09 | Basis umbenennen – Dialog | Vorbelegtes Feld | Änderungszustand und Footer | P1 | K08 | Alt/leer/unverändert/neu/Fehler; Sidebar und Liste aktualisiert |
| K10 | KB löschen / Dokument löschen | Gemeinsamer Confirm mit dynamischem Text | Zwei explizite Destruktiv-Varianten | P0 | OV,K01,K02 | Richtiger Name/Umfang; Cancel/Busy/Fehler/Erfolg; Datenwirkung korrekt |
| K11 | SourcePreview Desktop/Mobil | Seitenpanel bzw. modales Overlay, Markierung | Lesefläche und Overlay-Vertrag | P1 | OV,F13,K06,C02 | Aufruf aus Chat/Suche; Seite/Absatz, vor/zurück, Highlight, Schließen |
| K12 | Vorschau-Sonderzustände/Download | Laden/Retry/fehlende Position/leer/Original-Link | Einschränkungen und Fehler differenzieren | P1 | K11,F09,G08 | 403/404/Sitzungsende, Altquelle, kein Text; Download und Fokus-Rückkehr |

### Team und Organisation

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| T01 | TeamView/Organisationen | Auswahl, Rollen, Aktionen | Organisation und Mitgliedschaft klar gliedern | P1 | SH,F10,G02 | Keine/eine/viele; Owner/Member; Ladefehler/Retry und aktive Auswahl |
| T02 | Org anlegen/umbenennen – Dialoge | Zwei Namensformulare | Gemeinsamer Form-/Dialogvertrag | P1 | OV,FO,T01 | Beide separat: Name/Länge/Kollision/Busy/Erfolg/Cancel |
| T03 | Organisation löschen – Confirm | Endgültige Löschung | Folgen und sichere Aktionshierarchie | P0 | OV,T01 | Richtige Organisation; kein versehentlich fokussiertes Confirm; Cancel/Fehler |
| T04 | MembersPanel/Mitgliederliste | Avatar, Rolle, Datum, Entfernen | Konsistente Personenzeilen/Aktionen | P1 | F10,T01 | Owner/Member/eigener Account/fremde Org; Rechte erhalten |
| T05 | Mitglied hinzufügen – Dialog | E-Mail/Name; bestehendes oder neues Konto | Beide möglichen Ergebnisse verständlich führen | P0 | OV,FO,T04 | Vorhanden/neu/ungültig/doppelt/Busy/Fehler; kein Secret in Meldungen |
| T06 | Einmalige Startzugänge | Ergebnisbox, Kopieren, Ausblenden | Einmaligkeit/Weitergabe/Clipboard-Feedback | P0 | T05,F09 | Nach Hide/Reload nicht wieder sichtbar; Copy-Fehler; maskierte Beweise |
| T07 | Mitglied entfernen – Confirm | Benennt Zugriffsverlust | Einheitliches Destruktivmuster | P0 | OV,T04 | Richtige Person/Org; Cancel/Busy/Fehler/Erfolg; Rechtewirkung erhalten |
| T08 | Einladung erstellen/Code | Direkte Anlage, einmalige Codebox | Ergebnis und nächste Aktion strukturieren | P0 | T04,F09 | Einmalig; Reload/Copy erlaubt/verweigert/Fehler; keine Codes in Beweisen |
| T09 | Offene Einladungen/Zurückziehen | Liste mit Ablauf, direkte Aktion | Status/Ablauf/Folgen vereinheitlichen | P1 | F10,T08 | Leer/ausstehend/verwendet/abgelaufen/zurückgezogen; korrekter Listenstand |
| T10 | Einladung einlösen | Inline-Feld und Beitreten | Beitrittsfluss von Verwaltung unterscheiden | P1 | FO,F09,T01 | Gültig/ungültig/verwendet/abgelaufen/Busy; richtige Org nach Beitritt |

### Administration: KI und Betriebskern

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| AD01 | AdminPanel/Navigation | Zehn Bereiche, drei Gruppen | Orientierung KI/Verbindungen/Betrieb | P0 | SH,F12 | Alle zehn Bereiche; USER/ADMIN, 403 und Sitzungsende |
| AD02 | Anbieter-Liste | URL, Typ, Timeout, Secret-Maske | Verbindungsübersicht und Metadaten gewichten | P1 | DA,AD01 | Leer/viele/lange URLs, aktiv/inaktiv; geschützte Schlüssel |
| AD03 | Anbieter anlegen – Inline | Ollama/vLLM/API, URL, Name, Secret, Timeout | Feldgruppen/Hilfe je Verbindungstyp | P0 | FO,AD02 | Drei Arten; URL/SSRF/Secret-Speicherfehler; Save/Cancel/Busy |
| AD04 | Anbieter bearbeiten – Inline | Typ statisch, Secret behalten/ersetzen | Edit-Regeln und Secret-Vertrag erklären | P0 | AD03 | Leer behält Secret; Replace wirkt; Cancel ohne Änderung; kein Klartext |
| AD05 | Anbieter testen/aktivieren | Zeilenaktionen, Testresultat | Laufender Test und Feedback | P1 | AD02,F09 | Erfolg/Timeout/unreachable/inaktiv; kein ungeprüfter Erfolgsstatus |
| AD06 | Installationsvorgaben übernehmen | Seed-Angebot | Herkunft/Übernahme/Resultat unterscheiden | P1 | AD02,F09 | Verfügbar/fehlt/Fehler/übernommen; Geheimnis bleibt verborgen |
| AD07 | Modelllisten Chat/Embedding | Standard/Live/Disabled/ENV-Fallback | Rolle und tatsächliche Nutzung erklären | P1 | DA,AD01 | Beide Gruppen, keine Anbieter, lange IDs; Nutzung korrekt |
| AD08 | Modell anlegen/bearbeiten – Inline | Anbieter/Typ/ID, Tokens oder Dimension | Typabhängige Eigenschaften strukturieren | P1 | FO,AD07 | Chat/Embedding, Anlage/Edit; Validierung, Save/Cancel/Fehler |
| AD09 | Standardmodell wechseln | Embedding warnt nativ, Chat anders | Einheitliche risikobasierte Bestätigung | P0 | OV,AD07 | Cancel/Standardwechsel; keine automatische Neuindexierung suggerieren |
| AD10 | Suche & Wissen | Read-only Retrieval-/Chunk-/Rerankerwerte | Lesbare Betriebsinformation ohne Editierwirkung | P1 | DA,AD07 | Loading/fehlt/aktiv/veraltet; nur echte Änderungswege |
| AD11 | Systemstatus/Neu laden | Komponentenstatus | Priorisierte Diagnose und nächste Aktion | P1 | DA,F09 | Alle Statuswerte, Teilfehler/Offline/Retry; fehlend ist nicht bereit |

### Datenquellen und Assistent

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| D01 | Quellenliste/Status | Typ, Pfad, Ziel-KB, Laufstatus, viele Aktionen | Ziel/Betriebszustand/Aktion priorisieren | P1 | DA,AD01 | Keine KB/keine/viele Quellen; lange Pfade; laufend/fehlgeschlagen/unterbrochen/aktuell |
| D02 | Assistent Schritt 1 / Neu-Edit | Drei Ablagearten, LOCAL ggf. gesperrt | Eigener Assistentenrahmen und Auswahlführung | P0 | OV,PV,D01 | SMB/LOCAL/SharePoint; gesperrt erklärt; Neu/Edit, Abbruch |
| D03 | Schritt 2 Verbindung | SMB-Konto; lokaler Pfad; SharePoint Tenant/Client/Secret; Ziel-KB | Variantenbezogene Feldgruppen und Hilfen | P0 | D02,FO | Alle drei separat; Secret-Erhalt, gesperrtes Edit-Ziel, Verbindungsfehler |
| D04 | Schritt 3 Auswahl/Probelauf | Glob-Textmuster, Größe, Dateizahlen | In-/Ausschluss und Probe verständlich erklären | P1 | D03,F09 | Muster, Typ/Größe/Ausschluss, null Treffer/Teilerfolg; kein nicht vorhandener Ordnerbaum |
| D05 | Schritt 4 Rechtevorschau | Mirror, Warnung, Principals, unlesbare ACL | Folgen und Einschränkungen hervorheben | P0 | D04,F08,F10 | Mirror an/aus, keine/unlesbare Rechte, lange Gruppen; Sperr-/Filterverhalten erhalten |
| D06 | Schritt 5 Zeitplan | Vier Intervalle und Erklärung | Eindeutige Zeitplan-Darstellung | P1 | D04,F04 | Alle Intervalle und Zusammenfassung korrekt; keine neue Scheduler-Fähigkeit |
| D07 | Schritt 6 Aktivierung | Name/Pfad/Zahl/Intervall/Rechte | Prüfansicht und endgültige Aktion trennen | P0 | D03,D05,D06 | Zusammenfassung exakt; Zurück/Ändern, Busy/Fehler, keine doppelte Aktivierung |
| D08 | Abgleichen/Pausieren/Aktivieren | Laufaktionen, teilweise disabled | Sichere Betriebsaktionen und Feedback | P1 | D01,F09 | Laufend sperrt Konflikte; Pause/Fehler/Neustart; keine verlorenen Daten |
| D09 | Problemdateien – Dialog | Pfad-/Fehlerliste, leerer Zustand | Scanbare Diagnose und lokaler Overflow | P1 | OV,DA,D01 | Viele/lange Pfade, leer/Fehler/Schließen; keine Secret-Echos |
| D10 | Quelle entfernen – Confirm | Entfernt Quelle/importierte Dokumente, nicht Originale | Umfang und Folgen gewichten | P0 | OV,D01 | Richtige Quelle/Wirkung, Cancel/Fehler/Erfolg; Originaldateien unverändert |

### Verzeichnisse

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| L01 | DirectoryPanel/Liste | Art, Org, Loginstatus, Test/Lookup/Edit/Delete | Zugang und Verzeichnis klar gliedern | P1 | DA,AD01 | Leer/viele/aktiv/inaktiv, richtige Org, lange Servernamen |
| L02 | Verzeichnis anlegen/editieren – Dialog | AD-/LDAP-Defaults, URL, Base-/Bind-DN, Passwort | Zwei Varianten und Edit-Regeln strukturieren | P0 | OV,FO,L01 | AD/LDAP separat, Neu/Edit, Pflicht/Secret-Erhalt/Busy/Fehler |
| L03 | TLS/Login/Provisionierung/Erweitert | Optionen und Filter in Details | Sicherheitsfolgen nahe an Optionen halten | P0 | L02,F04 | Defaults erhalten; StartTLS/Plain/Sperre klar; keine Lockerung durch neue UI |
| L04 | Verbindungstest | Ergebnis je Verbindung | Laufende Anfrage/Ergebnis vereinheitlichen | P1 | L01,F09 | Erfolg/TLS-/Bind-/Netzfehler; richtige Zeile, kein unerklärter Stillstand |
| L05 | Benutzer nachschlagen – Dialog | Login, Ergebnis/Principals, Busy/Fehler | Eingabe/Ergebnis als klarer Ablauf | P1 | OV,L01,DA | Gefunden/nicht gefunden/leer/Fehler; lange Gruppen; Fokus-Rückkehr |
| L06 | Verzeichnis entfernen – Confirm | Konten bleiben laut Ergebnis bestehen | Auswirkungen auf Zugang klar darstellen | P0 | OV,L01 | Name/Cancel/Fehler/Erfolg; keine falsche Kontolösch-Aussage |

### Qualität, Websuche, MCP und Sicherung

| ID | Bereich / Quelle | Ist | Notwendige Änderung | Prio | Abhängigkeit | Zusätzliche Abnahme |
|---|---|---|---|---|---|---|
| Q01 | Qualität/Lauf/Verlauf/Daumen | KB-Select, Trefferquote, Zahlen, kleiner Verlauf | Metriken, Zeitraum und Datenstand erläutern | P1 | DA,AD01,F09 | Keine Fragen/kein Lauf/Busy/0–100%/Fehler; reale Werte und Prüfendenrechte |
| Q02 | Testfragenliste/Löschen | Ring, Erwartung, direkte Löschung | Ergebnissicht und sichere Aktionen | P1 | F10,Q01 | Ungeprüft/gefunden/nicht gefunden, lang/leer, Löschfehler |
| Q03 | Testfrage hinzufügen – Dialog | Frage/Datei/optionale Phrase | Felder und Hilfen vereinheitlichen | P1 | OV,FO,Q02 | Pflicht/Länge/optional/Busy/Fehler/Erfolg/Cancel |
| Q04 | Liste einfügen – Importdialog | Textfeld, Semikolon-/Tab-Hilfe | Importformat und Teilresultate führen | P1 | OV,FO,Q02 | Leer/gültig/ungültig/teilweise übersprungen; keine stillen Zeilenverluste |
| W01 | Websuche-Anbieter/Formvarianten | Aus/SearXNG/Brave/Tavily, bedingte Felder | Einrichtung nach Anbieter strukturieren | P1 | FO,AD01 | Alle vier, ENV-/DB-Herkunft, Änderung/Save/Fehler |
| W02 | Suchschlüssel/Entfernen/Voraussetzung | Write-only, behalten/löschen, Speicherhinweis | Einheitlicher Secret-Feldvertrag | P0 | W01,F03 | Gespeichert/fehlt/ohne Speicher; Leer-Erhalt/Löschen unverändert |
| W03 | Trefferlimit/Domainsperre | Number und Textarea | Wertebereiche/Mehrfachwerte erklären | P1 | W01,F04 | 1–10, Komma/Zeilen, lange Liste; Werte unverändert gespeichert |
| W04 | Suchtest/Ergebnis/Fehler | Button, Meldung und Trefferliste | Testen, Speichern und Aktivsein trennen | P1 | W01,DA,F09 | Nicht eingerichtet/Busy/keine Treffer/Treffer/unreachable; keine Keys |
| M01 | MCP-Karten/Status/Refresh | Name/URL/Aktiv/Refresh/Entfernen, Details | Verbindungen mit klaren Folgen und Status | P1 | DA,AD01 | Keine/viele/lange Namen, Fehler, Aktiv/Refresh; ehrliche Freigabeanzeige |
| M02 | MCP verbinden/Hilfe | Permanentes Name-/URL-/Secret-Formular | Einrichtung und Resultat klar führen | P0 | FO,M01 | Gültig/SSRF/unreachable/falscher Key/Busy; Key geleert, Tools zunächst gesperrt |
| M03 | Werkzeug-Freigaben | Externe Namen/Beschreibungen, Checkbox-Details | Auffindbarkeit und Auswahl bei großen Listen | P0 | M01,F04,F10 | Null/viele/lange Tools; Freigaben unverändert; Schließen löscht keine Auswahl |
| M04 | Arbeitsbereich-Freigaben | Alle/einzeln mit Organisationsnamen | Geltungsbereich und Wirkung klären | P0 | M03,G02,F04 | Kein/einige/alle; Reload/Refresh erhält Rechte; keine versehentliche Globalfreigabe |
| M05 | Aufrufjournal | Aufklappbare echte Tabelle | Kopf, Datenhierarchie, Status und Overflow | P1 | M01,F10,F08 | Leer/viele/Erfolg/Fehler; gültige Zeiten; keine Inhalte/Secrets |
| M06 | MCP entfernen – native Bestätigung | window.confirm außerhalb gemeinsamer UI | Gemeinsamer Bestätigungsvertrag | P0 | OV,M01 | Cancel ohne Delete; richtige Verbindung/Folgen; Fehler sichtbar |
| B01 | Sicherungsstatus | Lädt/Fehler/keine/veraltet/ungeprüft/geprüft | Datenstand/Warnung/Retry priorisieren | P1 | DA,AD01,F09 | Alle sechs Zustände; fehlend nie als verifiziert ausgeben |
| B02 | Jetzt/regelmäßig sichern – Hilfe | CLI-/Cron-Anleitung | Aufgabenorientierte Betriebsanleitung | P2 | B01,F13 | Ganze Befehle les-/kopierbar; Online-/Offline-Folgen/Aufbewahrung korrekt |
| B03 | Wiederherstellen – Hilfe | Bewusst Server-Befehl, destruktiv | Voraussetzungen und Warnung ordnen | P0 | B02,F09 | Kein neuer UI-Restore; Wirkung klar, Anleitung vollständig |
| Z01 | Totes Chat-Aside/lokales Label | v-if=false bzw. kein direkter Vue-Import | Altbestand bewusst auflösen | P2 | F01,F03,G04 | Keine aktive Funktion/Test-/AT-SPI-Verknüpfung entfernen; Quelltextindex erneuern |
| Z02 | Historische Designtexte/Tests/Handovers | Verschiedene Systembeschreibungen | Eine Wahrheit und aktuelles Abnahmeregister | P1 | Alle Pakete | Jede ID mit Ergebnis/Beleg; alte CI ist keine neue Designabnahme |

## Zustandsprofile – verbindlicher Prüfumfang für die spätere Abnahme

Jede Inventarzeile erbt A0 und alle passenden S-Profile. N/A braucht eine Begründung. Ein fehlender notwendiger Fehlerzustand ist eine offene Designaufgabe, kein N/A. Im [Tracker](ui-audit-2026-11-15/coverage.json) stehen alle Inventar-IDs initial auf **offen**.

- **A0 Grundabnahme:** Normalansicht; Hover; sichtbarer Tastaturfokus; aktiv/ausgewählt/disabled; Accessible Names und Lesereihenfolge; Hell/Dunkel/System; lange deutsche Texte; primäre Aktionen vollständig erreichbar; responsive Größen/Zoom; Beleg pro Testfall.
- **S1 Form:** Leer/vorbelegt/ungültig/gültig, Pflicht/optional, Grenzen, Busy/Erfolg/Feld-/Serverfehler, Cancel/Escape, Wiederöffnen und definierte Entwurfsregel; Secrets separat.
- **S2 Daten:** Laden/leer/wenig/viel/lang/kein Filtertreffer/Teilfehler/Retry/Rechte fehlen; Kontextwechsel während Anfrage, veralteter/entfernter Eintrag.
- **S3 Overlay:** Öffnen per Maus/Touch/Tastatur; initialer Fokus, Tab/Shift+Tab, Pfeile, Enter/Space, Escape/Backdrop nach Vertrag; Fokus zurück; Portals/Stacking, Scroll, geringe Höhe, Hintergrundinteraktion.
- **S4 Prozess:** Läuft/Erfolg/Fehler/Teilerfolg/abgebrochen/unterbrochen/Retry; doppelte Aktivierung und verspätete Antwort; Fortschritt und Datenstand ehrlich.
- **S5 Rechte/Secrets:** USER/ADMIN und Org-Owner/Member getrennt; berechtigte/unberechtigte Org/Basis/Datei; Secret neu/vorhanden/behalten/ersetzen/entfernen; nie in Logs/URL/Screens.
- **S6 Destruktiv:** Gegenstand und Folge, sichere Fokusreihenfolge, Cancel/Confirm/Busy/Fehler/Erfolg; kein erfundenes Undo.
- **S7 Native/Dateien:** Auswahl abgebrochen, Typ/Größe/leer/Scan/mehrere Dateien/Extraktionsfehler, Download/Clipboard erlaubt und verweigert, native Mindestgröße und Vollbild.

Geplante Testgrößen: Web 360/390, 768, 1024, 1440 und 1920 px; native 900×640 und 480×400; niedrige Höhe und 200% Zoom. Verbindliche Breakpoints, Zielkontraste, Hit-Areas und Bewegungsregeln werden erst in Phase 3 definiert. Dies ist noch kein Nachweis ihrer Erfüllung.

## Keine vorweggenommene Umsetzung

Keine neue Palette, Animation, Komponenten-API oder Framework-Migration beschlossen. Keine neue Such-/Ordner-/Undo-/OIDC-Verwaltungsfunktion. Kein Bereich für den neuen Qualitätsanspruch als umgesetzt/geprüft markiert. Der nächste Schritt ist Recherche/Systemdefinition nach vorliegender Planung, nicht weiteres kosmetisches Bearbeiten einzelner Seiten.
