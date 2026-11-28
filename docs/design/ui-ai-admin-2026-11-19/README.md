# UI-11 Nachweise — KI-Verwaltung und Systemstatus

Stand 19.11.2026, Branch `feat/ui-ai-admin`. Vite-Dev-Server mit **vollständig simulierten** Server-Antworten (Playwright-Route), kein echter Server, kein Modell- oder Verbindungsaufruf.

Gezielt geprüft und bestanden:

- Anbieter: Liste, „Verbindung testen“ erreichbar, SSRF-Ablehnung als „Ziel blockiert (SSRF-Richtlinie)“, Aktivieren über Menü, Anlegen im Dialog.
- Schlüssel write-only: Bearbeiten-Dialog startet mit leerem Schlüsselfeld; Speichern ohne Eingabe sendet `"secret":null`; nach Verwerfen eines getippten Werts ist das Feld beim erneuten Öffnen leer; ein getippter Wert wird gesendet.
- Modelle: Gruppen Antworten/Dokumentsuche, Chat-Standardwechsel ohne Rückfrage, Dimension-Feld nur für Suchmodelle.
- Suche & Wissen und Systemstatus: Anzeige; Datenbank „Bereit“.
- 390 px: kein horizontaler Überlauf, Bereichsauswahl per Select.

Nicht geprüft: echter Server, Dunkelmodus, E2E-Suite `admin_flow_e2e.cjs` (Playwright fehlt auf diesem Rechner). Hinweis: `admin_flow_e2e.cjs` erwartet beim Such-Standardwechsel noch einen nativen `confirm`-Dialog, die App nutzt aber schon vor diesem PR einen eigenen Bestätigungsdialog — dieser Schritt ist bereits auf `main` veraltet.
