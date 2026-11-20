# Datenquellen – Übersicht und ruhiger Assistent

Die Übersicht trennt Quelle, Betriebsmodus, letztes Abgleichsergebnis und Aktionen. Aktiv/Pausiert/Nicht aktiviert sind unabhängig von Erfolgreich/Teilweise erfolgreich/Fehlgeschlagen/Laufend. Ein erfolgreicher alter Abruf wird ausdrücklich als vergangenes Ergebnis mit Datum angezeigt, nicht als „Aktuell“. Suche, Betriebsfilter und Trefferzahl erleichtern größere Listen. Auf schmalen Flächen werden die Spalten zu beschrifteten Zeilenblöcken.

Seltene Aktionen liegen im Zeilenmenü; Abgleichen bleibt direkt erreichbar. Menü → Dialog → Schließen gibt den Fokus an den beständigen Zeilenknopf zurück. Schreibende Vorgänge sperren Mehrfachaktionen. Fehler beim Entfernen bleiben in der Bestätigung, statt die Quelle als entfernt erscheinen zu lassen. Der Problemdialog unterscheidet Laden, Abruffehler und eine wirklich leere Problemliste.

PO-Korrektur am Einrichtungsdialog: Kopf-/Fußtrenner, Seitenlinie und Rahmen um alle Ablageoptionen entfernt. Die gewählte Option verwendet die Auswahlfläche des Katalogs; Tastaturfokus bleibt sichtbar. Formabstände, Radio-Gruppe und Typografie vereinheitlicht. Mobile Adminnavigation ist eine kompakte Bereichsauswahl; die Desktopnavigation bleibt erhalten.

## Nachweise

- Build mit Tokencheck/TypeScript/Vite erfolgreich.
- `results.json`: 27/27 in `ui9_datasources_layout_e2e.cjs`. Zuerst echte QA-Quellen nur gelesen (einschließlich pausierter Quelle mit erfolgreichem Alt-Abruf). Danach synthetische Zeilen/HTTP-Antworten für Zustände, Filter, Probleme, Busy, Menüfokus, Entfernen und den Einrichtungsablauf.
- Alle schreibenden Anfragen des Tests abgefangen. Kein echter Abgleich, keine vorhandene Quelle pausiert/aktiviert/entfernt, keine echten Zugangsdaten geändert.
- Assistent mit simuliertem Probelauf durch alle sechs Schritte, Dirty-/Busy-Schutz und ehrlicher Meldung bei erfolgreicher Aktivierung, aber fehlgeschlagenem Start. Einstellungen einer synthetischen SMB-Verbindung öffnen mit leerem Passwortfeld.
- Hell/Dunkel bei 1440/1030/768/390; eigener Dialog-Screenshot je Größe. Zusätzlich bei 480×400 geprüft, dass ein Formularfeld per Treffertest erreichbar bleibt und der Dialog regulär geschlossen werden kann.
- `real-overview-dark.png` zeigt echte QA-Metadaten; `overview-*` und `wizard-*` gehören zur ausdrücklich synthetischen Zustandsprüfung.
- Bestehende `ui4_wizard_e2e.cjs` an Menüpfade, Ergebnisbezeichnung und im Dialog verbleibende Fehler angepasst, in diesem Paket nicht mit echten Connector-Schreibvorgängen ausgeführt.

Vollständige lokale CI, native Prüfung und unabhängige Reviews weiterhin offen; bekannte Server-CORS-Einschränkung S-1 unverändert. Der simulierte DELETE-Erfolg ist kein Nachweis für echten Browser-DELETE. Kein Merge/Release/Deployment. Nächster autorisierter Auftrag: Websuche, MCP und Verzeichnis im selben Stil.
