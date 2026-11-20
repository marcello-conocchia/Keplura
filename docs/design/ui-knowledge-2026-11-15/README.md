# Wissensansicht – Struktur und Designkatalog

Umgesetzt nach den Browserkommentaren des Inhabers: die gedrängte Reiterleiste durch eine vertikale Basisliste ab 1280 px bzw. eine kompakte Auswahl darunter ersetzt. Sichtbarer Basisname und Dokumentanzahl, eindeutiger Upload, breites Suchfeld. Dokumente als Tabelle mit klarer Typografie, ruhigen Trennlinien und ständig erreichbaren Aktionen. Größe/Datum werden bei wenig Platz in die Metadatenzeile übernommen. Farben, Schrift, 40/44-px-Felder, 6-px-Controlradien, Auswahl und Tastaturfokus stammen aus System 1.0.

Ein eigener Ladezustand unterscheidet „wird geladen“ von einer leeren Basis. Der Frontend-Store prüft Requestnummer und aktuellen Organisation-/Bereich-/Basis-Kontext, bevor eine Dokumentantwort übernommen wird. Ein verspäteter alter Abruf darf die aktuelle Ansicht nicht ersetzen; Reset/Logout invalidiert offene Abrufe ebenfalls.

## Tatsächlich durchgeführt

- Desktop-Build mit Tokencheck, TypeScript und Vite erfolgreich.
- `results.json`: 12/12 über `ui8_knowledge_layout_e2e.cjs`. Echte QA-Listen und echter Download; Dialoge Anlegen/Umbenennen sowie Dokument-Löschen geöffnet und abgebrochen. Kein vorhandener Datensatz gelöscht oder umbenannt.
- Lade-/Rennbedingung mit echtem Abruf geprüft: Antwort der Zusatzbasis angehalten, zurück zur Hauptbasis gewechselt, alte Antwort anschließend freigegeben; die Hauptbasisliste bleibt erhalten.
- Namensfilter, leere Treffermenge und mehrere Statusgruppen mit ausdrücklich synthetischer Stressliste. Suchantworten leer bzw. HTTP 503 simuliert, keine Behauptung über Suchqualität. Kein Modellaufruf durch diesen Test.
- Hell/Dunkel bei 390/768/938/1440, keine horizontale Seitenüberbreite, 40/44-px-Felder, 6-px-Radius, 44-px-Dokumentaktionen auf dem Handy; Auswahlmenü per Escape geschlossen. Screenshots nach Ende der Farbtransitionen.
- `chat-regression/results.json`: 14/14 für den vorigen Chatumbau, einschließlich neutralem Einstieg, Entwurferhalt, Tastatur/IME und simulierter Senden-/Stopp-Anfrage.
- `base-regression/results.json`: 17/17 für gemeinsame Felder, Bestätigungen sowie Team/Wissen/Administration. Simulierte MCP-/Modellantworten sind in den Einzelpunkten bezeichnet.

`knowledge-dark-938.jpg` zeigt die tatsächliche vom Inhaber geöffnete Ansicht mit vorhandenen QA-Dokumenten. Die übrigen Wissensscreenshots enthalten gekennzeichnete Stressdateien aus dem Browser-Test, keine neu angelegten Backend-Dokumente.

## Offen

Echten Upload, Reindex, endgültiges Löschen, Such-/Antwortqualität und native Tauri-Ausführung in dieser Anordnung nicht erneut ausgeführt. Vollständige lokale CI und unabhängige Reviewer-/QA-/ggf. Security-Gates vor einem Merge weiterhin erforderlich. Bekannter Server-CORS-Befund S-1 bleibt bestehen. Die gezielten Browserprüfungen ersetzen keine vollständige Abnahme des gesamten Redesigns. Kein Merge, Release oder Deployment.
