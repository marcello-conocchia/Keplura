# Chat und Navigation – sichtbarer Zwischenstand

PO-Rückmeldung: kompakte Eingabe wie bei Open WebUI, Chat/Wissen im Hauptmenü zusammenhalten, ein einziger neutraler Einstieg „Neuer Chat“. Eigene Palette und Typografie aus System 1.0 bleiben verbindlich.

`results.json`: 14/14 im lokalen QA-Browser. Geprüft: neutraler Einstieg, optionale Wissensbasis und Entwurferhalt, feste Hauptnavigation, Plus-/Untermenü, Vorlagen, Escape, Shift+Enter, IME, automatische Eingabehöhe, Enter/Senden/Stoppen, Hell/Dunkel bei 390/768/949/1440. Die Stream-Anfrage wurde im Test abgefangen und angehalten: keine echte Modellantwort und keine Aussage zur Antwortqualität. Der Test ruft kein GPU-Modell auf; laufende Hintergrundjobs des QA-Servers sind davon unabhängig. Build mit Tokencheck und TypeScript erfolgreich.

Screenshots zeigen den wirklichen Produktstand; Dateien `chat-<theme>-<breite>.png`. Kein nativer Tauri-Nachweis für diese neue Anordnung, keine vollständige CI und keine unabhängige Review-Abnahme. Der Inhaber hat als nächste aktive Gestaltung die Wissensansicht priorisiert.
