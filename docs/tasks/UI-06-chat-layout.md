# Chat und Navigation – Rückmeldung des Inhabers 15.11.2026

Aktive Implementierung auf `feat/ui-chat-layout`, Reviewbase `feat/ui-base@7e29b00`.
Der Inhaber priorisiert diese sichtbaren Änderungen vor dem weiteren Gesamtlauf.
UI-05 ist implementiert, seine noch offenen Gates werden dadurch nicht als bestanden gewertet.

## Ziel und Scope

Die markierte Chat-Eingabe wirkt wie ein großes Verwaltungsformular; der Verlauf trennt Chat und Wissen in der Hauptnavigation. Eine kompakte Eingabe nach dem Interaktionsprinzip von Open WebUI und eine feste Hauptnavigation ersetzen diese Anordnung. Es gibt einen einzigen Einstieg „Neuer Chat“; Wissen, Anhänge und Web sind optionale Ergänzungen. Keine neuen Backend-Funktionen, keine Modellläufe für diese Prüfung, kein Frameworkwechsel.

Referenz: [Open WebUI – Chat Features](https://docs.openwebui.com/features/chat-conversations/chat-features/), insbesondere Kontextaktionen am Eingabefeld. Farben, Schrift und Fokus folgen weiterhin dem eigenen System 1.0. Die runde Sendeaktion und der 16-px-Radius der Eingabehülle sind bewusste Ausnahmen für dieses zusammengesetzte Eingabefeld.

## Abnahme

| ID | Kriterium | Stand |
|---|---|---|
| C01 | Nur „Neuer Chat“, neutraler Einstieg ohne automatisch ausgewählte Wissensbasis; bestehende Chats behalten ihren Kontext | geprüft (14/14 Browserpunkte) |
| C02 | Begrüßung und Beispiele für allgemeine Arbeit; keine Beschränkung auf Dokumentensuche | geprüft (14/14 Browserpunkte) |
| C03 | Eine kompakte Eingabehülle, optionaler Wissenschip, Plusmenü für Datei/Zusatzwissen, Vorlagen und Web weiterhin erreichbar | geprüft (14/14 Browserpunkte) |
| C04 | Hauptmenü Chat/Wissen/Team zusammen, separater Verlauf; Ein-/Ausklappen verschiebt die Hauptnavigation nicht | geprüft (14/14 Browserpunkte) |
| C05 | Entwurf bleibt beim bestehenden Basiswechsel erhalten; ein Wechsel der primären Basis öffnet weiterhin einen neuen Chat (bestehender API-Vertrag) | geprüft (gezielte Browserprüfung) |
| C06 | Enter, Shift+Enter, IME, wachsende Eingabe, Senden/Stoppen und Disabled-Zustände | geprüft (gezielte Browserprüfung) |
| C07 | Tastatur und mobile Ziele; Hell/Dunkel bei 390/768/949/1440; Screenshots | geprüft (gezielte Browserprüfung) |
| C08 | Build, angepasste Prüfwege, Git-Checkpoint und dokumentierte Grenzen | geprüft (gezielte Browserprüfung) |

## Prüfungen und Grenzen

Gezielte Browserprüfung gegen den lokalen QA-Server. Senden/Stoppen werden mit ausdrücklich simulierter/angehaltener HTTP-Antwort geprüft; kein Ollama-Aufruf, damit der Rechner beim Spielen nutzbar bleibt. Die vollständige lokale CI und unabhängige Reviews bleiben vor einem Merge erforderlich. Keine Freigabe für Merge, Release oder Deployment.
