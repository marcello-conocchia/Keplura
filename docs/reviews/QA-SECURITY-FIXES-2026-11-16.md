# Independent Browser-QA PR #72

Geprüfter SHA: **9feb4e28283c5f637886faae10db950257cbb26f**.
Beginn: 2026-11-16 13:20:56 UTC. Ergebnis: **PASS**, Browser-Script exit 0.

Ausgeführt: unverändertes `desktop/e2e/security_fixes_e2e.cjs` mit Playwright-Core und echtem headless Chromium `/usr/bin/chromium`; echter finaler Server-JAR auf localhost:18090 und Vite auf localhost:1420. Isoliertes flüchtiges H2, Prod-Profil mit Flyway=true und ddl-auto=validate, HTTP-Sessioncookie für localhost, OCR ausgeschaltet. Zufälliger lokaler synthetischer Bootstrap-ADMIN; keine externen Konten oder Dienste. Zugangsdaten wurden nicht protokolliert.

Echte Legs: Browser-Adminlogin und Reload mit Profil/Adminnavigation; Browser-CORS-/CSRF-geschütztes Anlegen und DELETE einer deaktivierten lokalen MCP-Konfiguration; Anlegen eines synthetischen Kollegen, Startpasswortlogin und Reload mit Pflichtwechselansicht, Passwortwechsel und anschließende serverseitige Profilprüfung.

Simulierter Leg: ausschließlich GET /auth/me nach dem Admin-Reload per Playwright mit 503 beantwortet; Prüfung, dass Login ohne Ersatzidentität und ohne Adminnavigation erscheint. Kein echter Serverausfall.

Grenzen: Keine Browserprüfung von OIDC, OCR oder MCP-Ausführung; kein nativer Tauri-Webview. Diese QA verwendet den explizit bereitgestellten Browsertest. Fixture-Server und Vite wurden im finally beendet.

Rohlog: `ci/evidence/20261116-141512/browser-qa.log`.

