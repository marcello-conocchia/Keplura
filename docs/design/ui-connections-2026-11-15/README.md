# Verbindungen im Designsystem 1.0

Produktstand `d8cc94d`, Branch `codex/ui-connections`, Reviewbase `codex/ui-datasources@875c83d` (PR #62). Expliziter PO-Auftrag: Websuche, MCP-Server und Verzeichnis im selben Stil wie die vorherigen Bereiche. Änderungen betreffen ausschließlich Frontend, Browserprüfungen und Dokumentation. Keine neue Abhängigkeit, kein geändertes Backend oder Rechtekonzept.

## Sichtbare Änderungen

Gemeinsame Seitenköpfe, Formulargruppen, Statusanzeigen und Verbindungslisten ohne Kartenstapel. Aktionen wechseln bei wenig Platz unter Name/Adresse. Native Formelemente und gemeinsame Input-/Button-/Dialog-Verträge verwenden dieselben Tokens. Hell/Dunkel und mobile Administration bleiben nutzbar.

- Websuche: Suchdienst und Ergebnisse klar gegliedert; gespeicherte Aktivierung vom Entwurf getrennt. Verbindungstest erst nach dem Speichern geänderter Werte; keine alten Treffer nach Fehlern. Alle vier Anbieter erhalten. Schlüssel bleiben leer, Behalten/Entfernen/Ersetzen ausdrücklich getrennt.
- MCP: Neuanlage in fokussiertem Dialog, Werkzeuge und Arbeitsbereiche je Verbindung aufklappbar, seltener verwendete Aktionen im Menü. Aktivierung und vergangene Verbindungsprüfung getrennt; Teilfehler nach Speicherung ehrlich angezeigt. Pro Server sind parallele Änderungen gesperrt; abgelehnte Checkboxänderungen bleiben beim gespeicherten Wert. Journal als lokal scrollbar zugängliche Tabelle.
- Verzeichnis: AD/LDAP, Organisation und Loginoptionen je Verbindung erkennbar. Anlage/Bearbeitung und Nachschlagen als gemeinsame Dialoge; unverändertes Passwort wird nicht gesendet. Entwurfs-/Busy-Schutz, abbrechbare Abfrage mit Schutz gegen verspätete Ergebnisse, Löschfehler bleiben im Bestätigungsdialog.

## Tatsächlich ausgeführte Prüfungen

- `npm --prefix desktop run build`: Tokencheck, TypeScript und Vite erfolgreich, Exit 0. Letzter Lauf enthält auch die beim visuellen Prüfen korrigierte Umbruchregel.
- `ui10_connections_e2e.cjs`: **34/34 bestanden**, `results.json`. Die ersten drei Punkte lesen echte QA-Konfiguration. Anschließend ausschließlich synthetische Konfiguration und simulierte HTTP-Antworten. Alle Schreib-/Testanfragen abgefangen; keine vorhandenen Verbindungen, Zugangsdaten oder Freigaben geändert.
- Geprüft: Anbieterwechsel inklusive Ausgeschaltet, Schlüsselbehandlung, Save/Test/Busy, Testfehler ohne alte Treffer, MCP-Werkzeug-/Bereichsauswahl einschließlich abgelehnter Änderung, Verbindungsteilfehler, leere Listen vs. Ladefehler, Retry, Entwurfsschutz, Entfernen-Fehler/Abbruch und Dialogfokusrückgabe. Verzeichnisbearbeitung erhält das Passwort, Test und Benutzerabfrage mit simuliertem Ergebnis.
- Seiten und Anlagedialoge bei 1440/768/390 in Hell/Dunkel, Root-Reflow, Feldradius; Screenshots je Kombination. Bildschirmfotos können einen gescrollten Formularausschnitt zeigen. Die `real-*`-Bilder zeigen echte gelesene QA-Metadaten; die Größen-/Zustandsbilder synthetische Daten.
- `ui5_base_e2e.cjs`: **17/17 bestanden**, `base-regression/results.json`; gemeinsame Formularverträge und echte Lese-/Formansichten von Team, Wissen und Administration. Lief vor der letzten ausschließlich diese Verbindungsseiten betreffenden Umbruchkorrektur.
- Manuell im laufenden Browser: alle drei echten Leseansichten, MCP-Anlage und Verzeichnisanlage betrachtet; mittelbreite Liste entzerrt. `browser-*.jpg` dokumentieren diese Kontrolle. Keine echten Verbindungstests oder Speichervorgänge im manuellen Durchgang.
- Bestehende MCP-/Datenquellen-Suiten an neue Menü-/Dialogpfade angepasst, in diesem Paket nicht mit echten Connector-Schreibvorgängen ausgeführt.

## Offen / Grenzen

Kein neuer Nachweis für echte Suchanbieter, MCP-Handshakes/Tool-Calling, AD-/LDAP-Anmeldung, TLS-/Bindfehler oder erfolgreiche echte Löschung. Servervalidierung/SSRF und vollständige Fehler-/Stressmatrix hier nicht erneut geprüft. Der bekannte Befund S-1 (Browser-Dev-CORS ohne DELETE für Admin-Endpunkte) bleibt unverändert; simulierte Antworten belegen keine echte Löschfähigkeit.

Nach einem Browser-Neuladen erschien im bestehenden QA-Login gelegentlich nur die generische Identität ohne Adminnavigation; reguläres erneutes Anmelden stellte sie wieder her. Als Folgeprüfung für Sitzungswiederherstellung offen, in diesem Paket nicht behoben.

Vollständige lokale CI auf dem finalen PR-SHA, native Prüfung und unabhängige Code-/QA-/Security-Reviews offen. Schwere CI-/Modellläufe wurden wegen der vom Inhaber gemeldeten Rechnerlast zurückgestellt. Die gezielten Prüfungen ersetzen diese Gates nicht. Kein Merge, Release oder Deployment; das gesamte UI-Redesign ist noch nicht abgeschlossen. Inventarbereiche L01–L06/W01–W04/M01–M06 deshalb nur als umgesetzt, nicht vollständig geprüft markiert.
