# UI-13 Zugang und Ersteinrichtung — Screenshots (19.11.2026)

Vite-Entwicklungsserver, Chromium, helles Design. **Alle Serverantworten simuliert** (Playwright-Routing); Namen, E-Mail und Passwörter sind Platzhalter.

| Datei | Zustand |
|---|---|
| `access-login-oidc-1440.png` | Anmeldung mit aktivem Firmenkonto |
| `access-login-error-1440.png` | Falsche Zugangsdaten (Hinweis mit role=alert) |
| `access-login-offline-1440.png` | Server nicht erreichbar, „Erneut versuchen“ |
| `access-restoring-1440.png` | Sitzungsprüfung beim Start |
| `access-password-1440.png` | Pflicht-Passwortwechsel, zwei von drei Regeln erfüllt |
| `access-onboarding-1440.png` | Ersteinrichtung, Schritt 3 aktiv, Fokus im Feld |
| `access-onboarding-390.png`, `access-login-390.png` | Handybreite, kein waagrechtes Scrollen |

Im selben Lauf geprüft: Fehler „Name gibt es bereits“ erscheint im aktiven Schritt, Fokus springt nach Schritt 2 ins Feld von Schritt 3, Passwortwechsel schließt nach Erfolg. Nicht geprüft: Dunkelmodus, echter Server, OIDC-Rückkehr, Team-Seite nach Entfernen der alten CSS-Überschreibungen.
