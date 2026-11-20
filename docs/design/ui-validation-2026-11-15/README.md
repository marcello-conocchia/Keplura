# UI-04 Phase 4 – Validierungsbelege (15.11.2026)

Reale Prüfung von System 1.0 an Chat/Quelle und Datenquellen-Assistent gegen die QA-Instanz
(PostgreSQL, qwen2.5:7b über Ollama, Samba-AD, lokale Testablage). Auftrag und Prüfliste:
[`docs/tasks/UI-04-design-validation.md`](../../tasks/UI-04-design-validation.md).

## Umgebung und Grenzen

- Browser: Playwright-Chromium (headless) im QA-Container, Frontend per Vite aus dem Branchstand, Backend aus der
  unveränderten QA-JAR `d541ce1`. Kein WebKitGTK/WebView2 in diesen drei Läufen; native Tauri-Beine nur über das CI-Gate.
- Testdaten: eigene Organisation „UI4 Gestaltung …“ mit Bereich „Validierung“, Basen „Pilot-Handbuch“ (indiziertes
  Markdown-Handbuch) und „Zusatzwissen“ (leer, nur für Zusatzbasis-Auswahl). Pilotinstanz `:18080` unberührt.
- Zugangsdaten nur aus der ignorierten QA-Umgebung; jede Aufnahme prüft, dass kein Passwort im DOM steht.
- **Simuliert** (per `page.route`) und so in `results.json` gekennzeichnet: Busy-Verzögerung, Probelauf-HTTP-500,
  Start-HTTP-503 nach echter Aktivierung, Vorschau-Ladeverzögerung, Vorschau-HTTP-404/403. Alles andere ist echt.
- Zoom als CSS-Viewport-Äquivalent (200 % von 1440 px = 720 CSS px, 400 % von 1280 px = 320 CSS px), nicht über die
  Zoomsteuerung eines Browsers. Kontraste aus `getComputedStyle` der gerenderten Elemente gegen den effektiven,
  alpha-verrechneten Hintergrund, nach abgeschlossenen Farbübergängen. Kein Screenreader-Test.
- QA-Infrastruktur: Der containerinterne Port-Proxy `18180→8080` gab Verbindungsabbrüche nicht an den Server weiter;
  dadurch rechnete der Server nach „Stopp“ weiter und speicherte „complete“
  ([Beleg](befunde-vor-fix/chat-stopp-ohne-abbruchweitergabe-proxy.png)). Für die finalen Läufe ersetzt durch eine
  Proxyvariante mit Abbruchweitergabe (nur QA, kein Produktcode).

## Läufe (final, Stand Produktcode `bf7b7d0`)

| Lauf | Skript | Ergebnis | Belege |
|---|---|---|---|
| Assistent/Dialoge | `desktop/e2e/ui4_wizard_e2e.cjs` | 34/34 bestanden | [`wizard/`](wizard/) |
| Chat/Quelle | `desktop/e2e/ui4_chat_e2e.cjs` | 31/31 bestanden | [`chat/`](chat/) |
| A11y | `desktop/e2e/ui4_a11y_e2e.cjs` | 12/12 bestanden | [`a11y/`](a11y/) (`details.json` mit Messwerten) |

Frühere rote Läufe derselben Skripte haben die unten genannten Produktfehler gefunden; Vorher-Bilder in
[`befunde-vor-fix/`](befunde-vor-fix/).

## Gefundene und behobene Produktfehler

| Befund | Wirkung | Fix |
|---|---|---|
| Startfehler nach Aktivierung ignoriert (Übergabe behauptete Fix, Code fehlte) | „läuft“ obwohl Start scheiterte | Teilstand-Meldung, `3123e47` |
| Secret blieb nach Speichern im Formularzustand (ebenso nur behauptet) | Passwort im Speicher, falscher Verwerfen-Dialog | Feld geleert, gespeicherter Stand = sauber, `3123e47` |
| Doppeltes Escape schloss Assistent trotz Änderungen | Datenverlust ohne Rückfrage | Wiederöffnen + Verwerfen-Abfrage, `3123e47` |
| Tab verließ das Modal | Vertrag „eine Fokusfalle“ verletzt | Tab-Umlauf in AppDialog, `3123e47` |
| Assistent ≤600 px Höhe: Fuß überdeckte Felder | Name/Ordner unbedienbar (480×400) | Höhenmodus, `e610b3a` |
| Deaktivierte Ghost-Aktionen gefüllt | wirkten prominenter als aktive | `e610b3a` |
| Code-Kopieren nur bei Hover sichtbar | auf Touch unerreichbar | ein Rezept, sichtbar, 44 px mobil, `7226454` |
| 320×640: „Senden“ abgeschnitten | Chat nicht bedienbar | Scrollmodus <360 px, `7226454` |
| `sr-only` ohne Anker: Chatbereich 4144 px hoch | Leeransicht nach Drehen | `467eced` |
| Scrollmodus: Thread max-content breit | Text rechts abgeschnitten (480×400, 200 % Zoom) | `6c76bdb` |
| Zielgrößen 17–22 px (Desktop), 28–36 px (mobil) | unter 24/44 px | `6c76bdb`, Admin-Eingaben mobil `3123e47` |

## Prüfliste V01–V15

| ID | Ergebnis | Beleg / Grund |
|---|---|---|
| V01 | **bestanden** | `sync-design-tokens --check` grün im Build; außer `theme.css` keine `--ic-*`-Definitionen in `src`; Hell/Dunkel über `data-ic-theme` in allen Läufen gemessen. |
| V02 | **bestanden** | Echte qwen-Antwort mit Tabelle, Python-Codeblock, 4 Quellen, 16/26 px Inter; Kopieren ohne Hover sichtbar, Zwischenablage enthält Code; Anhang wird in echter Antwort genutzt. `chat/chat-answer-1440.png`, `chat-attachment-chip.png`. |
| V03 | **teilweise** | Senden/Streaming, Stopp (Teiltext als „gestoppt“ gespeichert), Retry, Feedback setzen/zurücknehmen, Export DOCX/PDF (Dateiköpfe geprüft), Vorlagen bestanden. **Tool-Rückfall (MCP) in Phase 4 nicht erneut geprüft**; letzter Nachweis MCP-01 aus Funktionsabnahme. |
| V04 | **bestanden** | Basiswechsel: neue Unterhaltung per API an Basis gebunden, Entwurf erhalten, Fokus im Composer; Zusatzbasis hinzufügen/entfernen ändert Hauptbasis nicht. Sidebar zeigt nur 10 Einträge („Alle … anzeigen“), daher Zählung dort unverändert. |
| V05 | **bestanden mit Einschränkung** | Zitierte Stelle markiert und im Sichtbereich, Download HTTP 200, nichtmodal ≥1440 (Escape nur im Panel, Fokus zurück zum Zitat), Resize macht modal/nichtmodal ohne Fokusverlust. Laden/404/403 simuliert; echte veraltete Zitation nach Entfernen einer Datenquelle beobachtet (`befunde-vor-fix/quelle-echt-veraltete-zitation.png`). Server liefert für fehlende Rechte 404; reiner 403 zeigt nur die allgemeine Lade-Meldung. Keine Seitennummern im Markdown-Testdokument (PDF-Seiten hier nicht geprüft). Vorschau zeigt Markdown-Quelltext (`## Frist`) als Textansicht. |
| V06 | **bestanden** | 1440/1200/900×640/768/480×400/390/320×640, hell und dunkel; kein Root- oder innerer Überlauf; „Senden“ erreichbar (bei 480×400 und 320×640 nach Scrollen, gewollter Scrollmodus); Drehen Quer→Hoch ohne Restversatz. |
| V07 | **bestanden** | Eigener großer Rahmen 1040 px, sechs benannte Schritte mit Schrittspalte, mobile Übersicht, Kontext und Fuß mit Abbrechen/Zurück/Weiter. `wizard/wizard-1440-step1.png`, `local-step*.png`. |
| V08 | **bestanden für LOCAL/SMB; SharePoint offen** | LOCAL (/qa/files, 43 Dateien) und SMB (`\\keplura-qa-ad\pilot`, NTFS-SIDs) jeweils Verbindung→Probelauf→Rechte→Zurück→Zeitplan→Aktivierung→„Aktuell“. SharePoint nur Formular; echte Integration ohne Testtenant offen. SIDs erscheinen roh, weil die QA-Verzeichnisanbindung keine Namen auflöst. |
| V09 | **bestanden mit Befund** | ACL-Warnung beim Abwählen sichtbar, echte Verbindungs- und Anmeldefehler wörtlich, Startfehler-Teilstand (simuliert), keine Erfolgsmeldung bei fehlgeschlagenem Entfernen. **Befund S-1 (Server, nicht Phase 4):** CORS für `/api/v1/admin/**` erlaubt kein `DELETE`; im Browser-Dev-Modus (Ursprung 1420→Server) scheitert Entfernen am Preflight mit „Der Server hat nicht geantwortet“. Tauri-Paket (Rust-HTTP) und Same-Origin nicht betroffen – dort nicht erneut geprüft. |
| V10 | **bestanden** | Passwortfeld nach Speichern leer, Hinweis „Zugang gespeichert“, leeres Feld behält Zugang (Probelauf grün), falsches Ersatzpasswort abgelehnt, richtiges ersetzt wieder. API-Liste enthält nur `secretSet`. Kein Secret in DOM/Aufnahmen (automatisch geprüft). |
| V11 | **bestanden** | Initialfokus Titel, Tab/Shift+Tab-Fokusfalle, Escape sauber/mit Änderung, Weiter bearbeiten mit Fokusrückkehr, doppeltes Escape, Footer-Abbrechen, Hintergrundklick, Busy-Sperre (simuliert), Fehler mit Werterhalt, Entfernen-Dialog mit Fokusrückkehr. |
| V12 | **bestanden, Entscheidung: großes Modal** | Alle sechs Größen hell/dunkel, Treffertest je Feld (nichts verdeckt), Aktionen erreichbar, Ziele ≥44 px mobil. Begründung siehe unten. |
| V13 | **bestanden mit Grenzen** | Kontraste Chat/Quelle/Assistent hell ≥4,97:1, dunkel ≥6,51:1 (ACL-Warnung über Probeelement mit Komponentenklasse gemessen); Ziele ≥24 px Desktop, ≥44 px mobil; Reflow 200 %/400 % mit Quelle; reduced-motion 0 ms; Tastaturpfad bis Composer mit sichtbarem Fokus. Kein Screenreader, keine Browser-Zoomsteuerung, Kontrast nur für geprüfte Ansichten. |
| V14 | **bestanden** | Verbindliches `bash scripts/ci-local.sh --with-e2e` auf `7bd2304` (frischer Klon im separaten Container `keplura-ui4-ci`): Exit 0, 449 Servertests in 66 Klassen, Desktop-Build inkl. vue-tsc, cargo check, Tauri-Build, alle neun E2E-Suiten (knowledge, dark, dark-packaged, chat, invite, admin, oidc, auth). Evidence `ci/evidence/UI-04-20261115-144820/` (MATRIX.md, ci-local.log). Vorläufe scheiterten an Container-Ausstattung (playwright-core, Chromium, Systembibliotheken, Restserver auf 8080) und an zwei Dark-Suiten mit fest verdrahteten Vor-System-1.0-Farben; diese lesen jetzt `tokens.json` (`51e1c1f`, `7bd2304`). Produktcode seit `bf7b7d0` unverändert. Windows/macOS nicht geprüft (Release-Gate). |
| V15 | **bestanden** | Diese Datei, Bilder und `results.json` je Lauf; Systemkorrekturen zentral in `UI-COMPONENT-CONTRACTS-2026-11-15.md` (Abschnitt Phase-4-Korrekturen). Ursprünglicher 109er-Inventartracker bleibt unverändert offen (nur geprüfte Fälle sind hier bewertet). |

## Entscheidung V12: großes Modal statt eigener Arbeitsansicht

Belege: Desktop 1040 px Rahmen mit Schrittspalte, unter 768 px Vollbild (faktisch eine Arbeitsansicht), bei ≤600 px
Höhe scrollt der ganze Dialog ohne Verdeckung, Fokusfalle und Dirty-/Busy-Schutz greifen. Der Ablauf ist kurz (sechs
Schritte), die Quellenliste bleibt als Kontext dahinter, und eine eigene Route hätte neue Router-/Persistenzfunktionen
erfordert (Nicht-Ziel). Deshalb bleibt das große Modal; eine eigene Ansicht ist erst bei deutlich längeren Abläufen
(z. B. Massenbearbeitung) neu zu bewerten.

## Offene Punkte

- S-1 Server-CORS `DELETE` für Admin-Endpunkte (eigene Aufgabe, Security-Review nötig; Server-Code nicht Teil von UI-04).
- MCP-Tool-Rückfall im neuen Chatlayout, SharePoint/Entra-Integration, PDF-Seitenzitate, Screenreader, echter Browser-Zoom,
  WebView2 unter Windows.
- Unabhängige Reviewer-/QA-/Security-Freigaben stehen aus; diese Läufe ersetzen sie nicht.
