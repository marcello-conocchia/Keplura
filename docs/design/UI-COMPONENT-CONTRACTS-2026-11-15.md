# Komponenten- und Zustandsverträge – System 1.0

Stand 15.11.2026, **Phase-3-Soll, noch nicht im Produkt implementiert**. Werte und Layout: [Designsystem](UI-DESIGN-SYSTEM-2026-11-15.md), [tokens.json](system-v1/tokens.json). Erfasst werden acht Vertragsfamilien DS/BT/FO/OV/DA/SH/CH/MG; [contracts.json](system-v1/contracts.json) verbindet alle 109 Inventar-IDs und alle 36 bestehenden Vue-Dateien damit. Die Familien sind keine Aufforderung, jede Komponente neu zu schreiben.

## Gemeinsame Regeln für alle Komponenten

Ein Control hat einen sichtbaren oder gleichwertigen zugänglichen Namen, passenden nativen Typ und vollständige Tastaturbedienung. Dekorative Icons sind unsichtbar für Assistenztechnik. `id`, `name`, `required`, `autocomplete`, `min/max/step`, `maxlength`, `data-testid`, `aria-*` und `disabled` landen auf dem tatsächlichen Control, nicht auf dessen Wrapper. Vorhandene Events, Payload-Typen und Submit-Semantik bleiben erhalten. CSS-Varianten verändern keine Berechtigungen oder Daten.

Pflichtzustände jedes anwendbaren Controls: default, hover, pressed, focus-visible, disabled, busy, invalid und selected/open. „Nicht anwendbar“ wird pro Variante begründet. Selected und Pressed sind nicht derselbe Zustand. Busy und Error dürfen gleichzeitig vorkommende fachliche Datenzustände nicht auslöschen. Bei Themewechsel bleiben Namen, Werte und Fokus erhalten. Kein Fokusverlust durch rein dekorative Transitionen.

## BT – Button, Iconaktion, Link und Aktionsgruppe

Zielkomponente bleibt `ui/Button.vue`. **Vertrag:** `variant=primary|secondary|ghost|danger`, `size=compact|default`, `type=button|submit`, `disabled=false`, `busy=false`; optional aktiver Togglezustand über semantisches `aria-pressed`. Default secondary/button/default. `outline` wird kompatibel auf secondary abgebildet; `icon` ist eine Inhalts-/Treffflächenvariante mit erforderlichem Namen, keine eigene Aktionspriorität. AppButton delegiert während Migration an Button, kein zweites Rezept.

| Variante | Default / Hover / Pressed | Disabled / Busy / Fokus |
|---|---|---|
| primary | accent/on-accent; Hover und Pressed accent-hover, kein Verkleinern | Disabled deckende Disabled-Tokens; Busy behält Breite/Label und ergänzt Status; Ring außerhalb der Füllung |
| secondary | surface/text, input-edge 1 px; Hover hover-tint, Pressed selection | Wie primary, niemals nur schwache Trennlinie als notwendige Begrenzung |
| ghost | transparent/text-2; Hover hover-tint/text, Pressed selection/text | Für Zusatzaktion; eigenständiger Name/Hitbereich, keine unsichtbare Pflichtaktion |
| danger | status-error-Füllung/on-accent, kontrastgeprüft; Hover gleicher Ton plus sichtbare Kontur | Gefüllte Form nur bestätigender destruktiver Abschluss. Zeilen-Löschen als textliche/ghost Aktion mit eindeutiger Beschriftung |
| Iconaktion | Wie ghost,16/20 px Icon in 32 px Trefffläche; Touch 44 px | `aria-label`, optional Tooltip; volle Trefffläche erhält Fokus |

`as`-Kompatibilität aus Bestand erhalten; Navigation mit href bleibt ein echtes `<a>`. Ein deaktivierter Link bekommt keine aktivierbare href/Click-Restwirkung, keinen erfundenen `disabled`-HTML-Effekt. Datenmutation ist nie als Link getarnt. Links im Fließtext sind unterstrichen; Hover verstärkt, nicht erst erzeugt, die Erkennbarkeit.

Busy: Ausführung im Handler sperren, `aria-busy` und verständliche Beschriftung („Wird gespeichert …“). Die auslösende Schaltfläche kann über `aria-disabled` fokussierbar bleiben, sofern Klick/Enter/Space tatsächlich gesperrt werden; statisch disabled nutzt natives disabled. Keine pauschale Form-Opacity, kein Fokus auf rotierenden Spinner. Status bei längeren Vorgängen daneben sichtbar. Erfolgslabel kurz rückmelden, Eingaben erst nach tatsächlichem Erfolg zurücksetzen.

Aktionsgruppen: Abbrechen/Zurück vor der primären Aktion, destruktiver Abschluss klar benannt. Footer wächst bei Umbruch; unter engem Raum Buttons voller Breite **in unveränderter DOM-Reihenfolge** stapeln. Keine CSS-order-Umkehr gegenüber Tastaturreihenfolge. Login/Speichern erlaubt natives Enter; Enter in einer Löschbestätigung darf keine ungeprüfte Standardaktion auslösen.

## FO – Field, Form, Auswahl und Geheimnis

Zielkomponente `ui/Input.vue` vereinheitlicht den Textfeld-Vertrag. `TextField.vue` bleibt nur als delegierender Adapter, bis alle Verwender migriert sind. **Text-Vertrag:** `id`, `label`, `modelValue:string`, optional `type`, `hint`, `error`, `required`, `disabled`, `readonly`, `icon`; `update:modelValue` bleibt String, `focus()` verfügbar. Vorhandene attrs werden kontrolliert auf das Input weitergereicht. Label zeigt „(optional)“, wenn das Formular überwiegend Pflichtangaben hat; Required ist zusätzlich nativ definiert.

Anatomie: Label → Control → Hilfe → konkreter Fehler. Hinweise und Fehler über stabile IDs mit `aria-describedby` verknüpfen, invalid über `aria-invalid`. Hilfetext wird nicht vom Fehler verdrängt, wenn er für die Korrektur nötig ist. Root-Wrapper keine implizite p-Verschachtelung um Blockelemente. Icon nur, wenn es Information unterstützt; keine flächendeckende Symboldekoration.

| Control | Semantik / Interaktion | Variante und Zustand |
|---|---|---|
| Text / Email / URL / Password | Nativer Inputtyp, bestehendes Autocomplete, Auswahl/Kopieren/Einfügen erlauben |40 px min,16 px Schrift auf schmalen/Touchflächen; Fehlerkante plus Fehlertext, Fokus zusätzlich sichtbar |
| Textarea | Nativer mehrzeiliger Wert; Enter erzeugt Zeilenumbruch | min 88 px, vertikal vergrößerbar; Chat-Sondervertrag unten |
| Number | Natives min/max/step; vorhandenes Parsing und Leerwertverhalten pro Fachfeld bewahren | Nicht automatisch auf 0 setzen, Grenzen in Hilfe nennen; kein eigener Spinbutton ohne Bedarf |
| Select | Native Auswahl als Standard, Label und bestehende Werte | Auswahl/Pfeile/Home/End/Zeichensuche nach Browserkonvention. Lange Optionen im gewählten Kontext nachvollziehbar, nicht nur abgeschnitten |
| Checkbox | Native binäre Auswahl oder vorhandener gemischter Zustand | Gesamtes Label anklickbar; Space schaltet. Pflichtschalter nicht im Popup verstecken |
| Radio | Native Gruppe, fieldset/legend, gemeinsamer Name | Pfeile innerhalb Gruppe; größere Auswahlfläche für Quellenart, Symbol plus verständlicher Text |
| Switch | Nur vorhandene unmittelbare An/Aus-Funktion, zugänglicher Checked-Zustand | Kein Switch für eine Auswahl, die erst mit „Speichern“ gilt; dann Checkbox |
| Readonly | Wert fokussierbar/kopierbar; readonly statt disabled für lesbare notwendige Werte | Keine Gleichsetzung mit nicht verfügbar |

Ein neuer durchsuchbarer Select ist keine Voraussetzung. Falls eine vorhandene zusammengesetzte Auswahl erhalten wird: echter Combobox-/Listboxvertrag mit Arrow/Enter/Escape, Auslöserfokus und Active-Descendant/Option-Semantik; nie ein ARIA-Menü als beliebiger Form-Container. Zusätzliche KB-Auswahl bleibt das vorhandene Checkbox-Menü.

**Secret-Vertrag:** Serverwerte nie in ein Feld zurückfüllen oder als Punktfolge zurücksenden. Sichtbarer Zustand „Schlüssel gespeichert“/„Kein Schlüssel hinterlegt“, leeres Änderungsfeld für Ersatz; ausdrückliche separate Entfernen-Aktion nach dem bestehenden API-Vertrag. Leeres Feld bedeutet ausschließlich die bisherige Beibehalten-Semantik, niemals beiläufig Löschen. Kennwort-Ein-/Ausblenden nur, wenn vorhandener Kontrollumfang dies anbietet; kein neues Feature nötig. Geheimnisse nicht in Toast, URL, Formularzusammenfassung, Journal oder Screenshot. Einmalige Codes separat mit Erklärung und Kopierfehlerzustand, bei Scope-/Sitzungswechsel vollständig entfernen.

**Form-Vertrag:** Sichtbare Abschnitte, korrekte DOM-Lesereihenfolge, native Browservalidierung und vorhandene Servervalidierung ergänzen sich. Erste Validierung bei Submit, danach Korrektur pro bearbeitetem Feld; kein Fehlerschwall vor der ersten Eingabe. Bei mehreren Fehlern Zusammenfassung oben mit Verweisen, Fokus dorthin; bei einem Fehler Fokus zum Feld. Allgemeiner Serverfehler im Formular, Werte erhalten. Submitbusy verhindert Doppelanfragen; zum Auslöser oder ersten relevanten Fehler zurückkehren. Clipboardfehler erklärt manuelles Kopieren. „Erweitert“ erhält Zustand bei Fehler und öffnet sich, wenn dort ein Fehler liegt.

## OV – Dialog, Confirm, Wizard, Suche, Menü und Quellenpanel

`AppDialog` erhält Größen-/Verhaltensvarianten; native `<dialog>`-Grundlage bleibt möglich, kein pauschaler Bibliothekswechsel. **Vertrag:** bestehende open/title/error/closeLabel sowie `size=sm|md|lg|wizard`, `busy`, `dirty`; Slots header/body/footer. Der Elternflow besitzt die Werte/Requests. `close` wird erst nach erfolgreicher Schließentscheidung emittiert; native `cancel`/Backdrop gehen durch denselben Guard. Keine parallelen schließenden Emits. Initialfokus bewusst wählen, nicht stets blind zum ersten Input springen.

| Overlaytyp | Maß / Modalität | Fokus / Escape / Außenklick | Scroll / Sonderregel |
|---|---|---|---|
| Kurze Anlege-/Umbenennungsform | sm 440 px oder md 640 px, max viewport−32 px; modal | Initial erstes Feld, Enter normales Submit; Escape/Abbrechen/Außenklick clean schließen, dirty erst Verwerfentscheidung | Header/Footer getrennt bei Platz, sonst gemeinsamer Scroll; Fehler innen |
| Lange Verzeichnisform / Import / Vorlagenverwaltung | lg 880 px; modal | Initial Titel bei langer Struktur, danach Felder; gleiche Dirtyregel | Sinnvolle Formgruppen; keine lange Form in 380 px-Rahmen |
| Confirm | sm 440 px; modal | Fokus „Abbrechen“/„Weiter bearbeiten“ bei Risiko; keine automatisch fokussierte Löschung; Escape clean schließt | Folge/Objekt nennen; serverseitige Löschfehler bleiben im Dialog |
| Datenquellen-Assistent | wizard 1040 px, Höhe max viewport−32 px; mobil vollflächig | Initial Schrittüberschrift; Weiter/Zurück fokussiert neue Überschrift, Fehler bleibt im Schritt; Escape guarded | Schrittspalte 192 px, Inhalt flexibel; Footer erreichbar; P04 vergleicht großen Rahmen mit eigener Arbeitsansicht |
| Globale Suche | md 640 px, oberer sicherer Viewportbereich; modal | Input zuerst; ArrowUp/Down wählt Treffer, Enter führt aus, Escape schließt und fokussiert Auslöser | Trefferliste scrollt, aktiver Treffer sichtbar; leer/eingeben/laden/Fehler/kein Treffer trennen |
| Aktions-/Checkbox-Menü | min 208 px, max min(360 px,viewport−32 px), max verfügbare Höhe | Enter/Space öffnen, Arrow/Home/End bewegen, Escape zurück; Tab verlässt gemäß bestehender Reka-Semantik, kein modaler App-Fokusfang | Kollisionsposition mit 8 px Viewportrand; Checkboxauswahl bleibt offen, erfolglose Mutation zeigt Fehler |
| Tooltip / Informationspopover | max 288 px, bei Textbedarf umbrechen | Tooltip auf Hover **und** Fokus; Escape schließt. Interaktiver Inhalt ist ein Popover mit zugänglichen Controls | Kein Pflichtwissen nur im Tooltip; keine Fokusfalle in einem reinen Hinweis |
| Mobile App-Navigation | Drawer min(320 px,viewport−32 px); modal | Fokus aktives Ziel/erste sinnvolle Aktion; Escape/Außenklick schließt; nach Zielwahl Fokus H1 | Hintergrund inert; Körper nicht zusätzlich scrollbar |
| Quelle – breit |400 px, nichtmodal bei ausreichend Containerplatz | Beim bewussten Öffnen Titel/Schließen fokussieren, danach Tab zwischen Arbeitsbereichen möglich; Escape nur wenn Fokus im Panel | Quelle scrollt selbst; Conversation bleibt bedienbar. Rückkehr zum Quellverweis |
| Quelle – schmal | Vollflächiger modaler Lesemodus | Fokus Titel, Trap, Escape/Schließen zurück zum Verweis; Hintergrund inert | Original/Seite/Abschnitt und Sprung zur Stelle erreichbar, keine Textminiatur |

Modals haben einen eindeutigen sichtbaren Titel, passende role/aria-modal, korrekte Benennung und genau eine aktive Fokusfalle. Bei umfangreichem Inhalt nicht die gesamte Form als aria-describedby vorlesen. Beim Schließen Fokus zum Auslöser; existiert er nicht mehr, zur passenden Folgeaktion/Listenüberschrift. Gleichzeitige Overlays nach Hierarchie schließen, nicht über mehrere globale Escapehandler. Quelle bei Kontext-/Sitzungswechsel gemäß vorhandenem Vertrag schließen.

**Ungespeichert / Busy:** Dirty wird aus tatsächlichen Eingabeänderungen abgeleitet, nicht allein daraus, dass ein Dialog offen war. Verwerfentscheidung ersetzt den Dialoginhalt vorübergehend innerhalb desselben aktiven modalen Rahmens; Feldzustand bleibt bis zur Entscheidung im Speicher, kein zweites Modal darüber. „Weiter bearbeiten“ stellt Fokus und Werte wieder her. Busy bei nicht abbrechbarer Mutation blockiert alle Schließwege, erklärt „Speichern läuft …“ und wird bei Erfolg/Fehler aufgehoben. Reine Vorschau-/Suchrequests dürfen beim Schließen ignoriert/abgebrochen werden. Browser-/OS-Schließen kann eine normale UI nicht garantieren abfangen; keine Persistenzzusage.

**Portalvertrag:** Menüinhalt innerhalb eines nativen modalen Dialogs in dessen vorgesehenen Overlay-Container, sonst in gemeinsamen Body-Overlayroot. Tokens am html-Attribut für beide Ziele verfügbar. Native Top Layer und normales z-index nicht gegeneinander hochschrauben. Beim Wechsel über Responsive-Grenze Modalität/Fokus aufbauen oder abbauen, vorhandenen Fokus/Lesepunkt erhalten; mobile Trap nicht nach Resize im Desktop behalten.

**Wizardvertrag:** Vorhandene sechs Schritte: Art, Verbindung, Auswahl/Probelauf, Rechte, Zeitplan, Aktivieren. Keine neue siebte Seite. Kurze Labels, aktueller Schritt textlich und aria-current=step; abgeschlossene Schritte als solche, spätere Schritte nur gemäß bisherigen Voraussetzungen zugänglich. Fehler werden nicht als erledigter Schritt markiert. Schritt 6 zeigt vorhandene aktivierungsrelevante Daten; neue Zusammenfassungen nur aus bereits vorhandenem lokalen Zustand, ohne zusätzliche Backendversprechen. Rechtefehler bleiben explizit und fail-closed; kein gestalterisches Wegklappen kritischer ACL-Warnungen.

## DA – Listen, Tabellen, Status und Rückmeldung

DocumentTable kann eine semantische Liste bleiben, wenn Zeilengruppen statt echter Spalten die Aufgabe tragen. Echte Vergleiche/Journal erhalten `<table>` mit Caption/Spaltenköpfen; keine simulierte Klicktabelle aus verschachtelten Buttons. Keine neue Sortierung, Auswahl oder Pagination allein durch den Designkatalog. Pro Zeile Hauptname, relevante Metadaten, Status, klar benannte Aktionen. Namen dürfen umbrechen; Kürzung nur mit vollständig erreichbarer Alternative auch ohne Hover.

Navigation in Listen verwendet native Links/Buttons. Tabellenzeilen nicht künstlich in einen ARIA-Grid-Tastaturmodus versetzen. Wenn eine ganze Zeile navigiert, bleiben ihre unabhängigen Aktionen getrennte Controls, kein verschachtelter Link. Delete/Refresh/Test in derselben Zeile während Requests gezielt sperren; andere Datensätze bleiben soweit fachlich sicher bedienbar. Touch enthält erreichbare Zeilenaktionen oder einen klar sichtbaren Menüauslöser.

StatusPill wird der gemeinsame Text-/Icon-Baustein: `tone=neutral|info|ok|wait|error`, sichtbares Label verpflichtend, keine automatische Live-Region pro Zeile. StatusRing bleibt nur als Fortschrittsdarstellung mit demselben Statusvokabular; nur echte bekannte Mengen ergeben Prozent. Unbekannt: „Wird verarbeitet …“, keine geschätzte 99%-Animation. Beispiele: Durchsuchbar / Wird verarbeitet / Fehlgeschlagen / Kein Text erkannt / Nicht durchsuchbar / Pausiert. Nicht jeder Wartezustand ist eine Warnung; passende Iconform und Text erklären die Ursache.

| Daten-/Vorgangszustand | Darstellung / nächste Handlung | Ankündigung / Erhalt |
|---|---|---|
| Noch nicht angefragt | Platzhalter oder knapper Arbeitsauftrag | Kein falsches 0-Ergebnis |
| Erstes Laden | Stabiler Platz für Zeilen/Text, „Dokumente werden geladen …“ | Ein polite status, Skeleton dekorativ; keine künstliche Mindestdauer |
| Aktualisieren | Gleicher sicherer Scope: geladene Daten mit „Wird aktualisiert …“ erhalten | Kein Leeren/Flackern und kein falscher Erfolg |
| Leer nach erfolgreicher Anfrage | „Hier sind noch keine Dokumente“ plus vorhandene Upload-/Anlageaktion | Nur wenn tatsächlich erfolgreich geladen |
| Keine Suchtreffer | Suchbegriff/Filterkontext, „Keine passenden Dokumente gefunden“; Filter zurücksetzen, sofern vorhanden | Kein Vorschlag zur Anlage, wenn nur Filter schuld ist |
| Konfiguration fehlt | Benennen, was fehlt und welche vorhandene Einrichtung zuständige Rolle nutzen kann | Keine Adminaktion für normale Mitarbeiter vortäuschen |
| Fehler ohne nutzbare Daten | Fehler am betroffenen Bereich, „Erneut versuchen“ falls verfügbar | Alert einmal beim Auftreten; nicht in jedem Render |
| Teilfehler / veraltet | Verwertbare Daten im selben autorisierten Scope markieren, fehlgeschlagene Teile konkret nennen | Kein altes Bild als aktuelles Ergebnis; keine Daten über Scope-/Sitzungsgrenzen erhalten |
| Zugriff verweigert / nicht mehr verfügbar | Bestehende sichere kombinierte Meldung erhalten, keine Existenz fremder Dateien verraten | Kein Secret/technischer Stacktrace; Rückweg/Schließen |
| Sitzung abgelaufen | Vorhandene vollständige Bereinigung, verständlicher Login-Rückweg | Keine alten Daten/Einmalcodes hinter Login sichtbar |
| Mutation erfolgreich | Tatsächliches Ergebnis am Objekt sichtbar, knapper Status | Kein Modal für jede kleine Aktion; Fokus sinnvoll setzen |
| Mutation fehlgeschlagen | Eingaben erhalten, Fehler im Formular/Schritt, Wiederholung bewusst auslösen | Kein automatisches Löschen, Optimismus bei Rechten nicht als bestätigt darstellen |

Kopieren meldet „Kopiert“ erst nach erfolgreicher Clipboard-Operation. Toast für nicht kritische Bestätigung,8s sichtbar oder bei Fokus/Hover pausiert; wichtige Fehler dauerhaft am Kontext. Nutzer kann schließen. Keine neue Notification-Historie. Pro Bereich eine Live-Region; Streaming nicht tokenweise vorlesen, erst sinnvoller Beginn/Ende/Fehler. Upload behält Datei-/Typ-/Größengrenzen; Drop ist Zusatzweg neben sichtbarer Dateiauswahl, Fehler pro Datei statt pauschal alle verloren.

## CH – Antworten, Composer und Quellen

Nachrichten bleiben semantisch geordnete Beiträge mit Name/Rolle und optional Metadatum. Assistenteninhalt auf content, keine Schachtelung großer Chatkarten. Nutzerbeitrag dezent abgesetzt, Textkontrast unverändert. Markdown-Hierarchie respektiert den Dokumentkontext; Code und breite Tabelle lokal scrollbar. Linksicherheit, Sanitizing, Download-/Exportverträge und dynamische Codekopierhandler bleiben erhalten. Quellenverweis ist per Tastatur erreichbar, beschriftet mit Dokument und zitierter Stelle; nicht nur eine unverständliche Nummer.

Composer: Textarea wächst bis 240 px, Enter sendet nach vorhandenem Vertrag, Shift+Enter erzeugt neue Zeile; IME-Komposition löst kein verfrühtes Senden aus. Hauptbasis sichtbar beschriftet, Zusatzbasen/Dateien als entfernbare Kontexte; Name hat Vorrang vor Icon. Lange Kontextlabels umbrechen statt Sendebutton verdrängen. Toolbar darf mehrzeilig werden. Sendefläche 40/44 px, im Stream „Antwort stoppen“. Leerer Prompt kann nicht senden, Ursache ohne Fehlerschwall erkennbar. Fehlende Hauptbasis richtet sich nach dem bestehenden zulässigen Dateikontext, keine neue pauschale Sperre.

Vorlagen öffnen über vorhandenes Menü; Anlegen/Verwalten nutzen dieselben Form-/Dialogverträge. Wiederholen, Bewertung, Export und Quellaktionen bleiben auffindbar ohne Mouse-Hover. „Werkzeuge werden verwendet …“ und „Websuche läuft …“ sind nur bei tatsächlichen Events sichtbar, keine erfundene Werkzeugaktivität oder offengelegte interne Denkkette. Rückfall bei Modellen ohne Tool-Calling bleibt fachlich erhalten.

Quellenpanel kennzeichnet **Textansicht**, Dokumentname, bekannte Seite/Abschnitt und markierte zitierte Passage mit selection plus sichtbarer Randmarkierung. Fehlende Seitenzahl nicht erfinden. Vorhandenes Paging/Sprung/Download und bestehende Rechte-/Timeoutfehler behalten. Kein ungeprüfter nativer PDF-Renderer oder Dokumenteditor als Teil der Designarbeit.

## SH / DS / MG – Zusammenspiel und Abnahme

Shell-/Scope-, Geräte-, Farb-, Schrift- und Motionverträge stehen vollständig im [Designsystem](UI-DESIGN-SYSTEM-2026-11-15.md). Migration, Verantwortlichkeit jeder alten Vue-Datei und Reihenfolge stehen im [Migrationsplan](UI-MIGRATION-2026-11-15.md). Für jede Variante sind später passende Normal-/Negativfälle mit Rolle, Scope, Theme und Viewport an die Inventar-ID zu hängen.

Keine Komponente gilt allein durch Definition, Katalogabbildung oder eine grüne Kontrastberechnung als produktiv geprüft. P04 prüft zuerst den Zusammenhang von Chat und komplexem Assistenten; P05–P12 migrieren, P13/P14 vergleichen vollständig mit dem Inventar. Form-, Fokus-, Busy-/Dirty- und Secretregeln sind dabei Vertragsbestandteil, keine optionale spätere Politur.

## Phase-4-Korrekturen (15.11.2026, aus realer Validierung)

Verbindliche Nachträge aus `docs/design/ui-validation-2026-11-15/README.md`; sie präzisieren die Verträge oben, ohne neue Komponentenfamilien.

- **OV Modal-Fokusfalle:** Natives `showModal()` allein genügt nicht; Chromium/WebView2 lassen Tab nach dem letzten Ziel in den Fensterrahmen. AppDialog führt Tab/Shift+Tab am Rand selbst im Dialog herum (Radiogruppen zählen als ein Ziel).
- **OV Dirty-/Busy-Schutz:** Wiederholtes Escape ohne Nutzeraktivierung schließt ein `<dialog>` in Chromium ohne `cancel` (Close Watcher). Ein solches erzwungenes Schließen öffnet den Dialog bei ungespeicherten Änderungen bzw. laufendem Vorgang sofort wieder und zeigt Verwerfen-Abfrage bzw. Busy-Hinweis. Gespeicherter Zwischenstand (Test der Verbindung) gilt danach nicht mehr als ungespeichert.
- **OV Wizard bei geringer Höhe (≤600 px):** Gesamter Dialog scrollt, Fuß liegt am Ende des Inhalts; kein Feld darf vom Fuß überdeckt werden (Prüfung per Treffertest je Feld).
- **BT deaktivierte Ghost-/Iconaktionen:** keine Füllfläche; deaktiviert darf nie prominenter wirken als aktiv.
- **BT Zielgrößen:** jedes Ziel am Desktop mind. 24×24 px (auch Inline-Iconaktionen in Listen/Chips), mobil bzw. `any-pointer: coarse` 44 px inklusive Admin-Eingaben, Menü- und Vorlagenknopf.
- **CH Codeblock-Kopieren:** dauerhaft sichtbar (nicht nur bei Hover, sonst auf Touch unerreichbar), oberhalb des Codes ohne Überdeckung; nur ein Rezept in MarkdownAnswer.
- **CH Chat bei geringer Höhe (≤600 px) oder Breite <360 px:** Chatansicht scrollt als Ganzes, Composer am Ende erreichbar; der Thread behält Containerbreite (kein `flex:none` in der Zeilen-Flexbox, sonst max-content-breit).
- **CH Screenreader-Hilfstexte:** absolut positionierte `sr-only`-Elemente brauchen einen positionierten Vorfahren in der Zeile, sonst vergrößern sie den Scrollbereich und hinterlassen nach Drehen einen Versatz.
- **DS Aktivierung:** Aktivierung erfolgreich, Start des ersten Abgleichs fehlgeschlagen ⇒ genau dieser Teilstand wird gemeldet, nie „läuft“.
