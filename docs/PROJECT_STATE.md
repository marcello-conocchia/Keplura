# Keplura — PROJECT STATE (autoritative, knappe Projekt-Wahrheit)

**Stand 21.11.2026: Servermeldungen auf Englisch (Branch `feat/server-messages-en`).** Bekannte deutsche Servertexte (Fehler, Abgleich-, Prüf- und Zugriffsergebnisse, Bezeichnungen) werden in der englischen Oberfläche beim Einlesen der API-Antworten übersetzt (`desktop/src/i18n-server-en.ts`, feste Texte + Muster mit Zahlen/Namen); Nutzerdaten bleiben unverändert. Chat antwortet in der Sprache der Frage (Systemanweisung), die feste „nichts gefunden“-Antwort wird in Englisch übersetzt angezeigt. Klasse B. `ci-local.sh` als Nicht-root mit `acl` Exit 0 auf `9fbe53f` (543 Server-Tests, 1 übersprungen; Desktop-Build, cargo check; E2E nicht ausgewählt), Evidence `ci/evidence/20261121-090140/`. Im Browser gegen echten Server geprüft (Oversharing-Hinweis und Gruppenname in EN übersetzt, in DE unverändert). Offen: Installer-/Skriptausgaben weiter deutsch.

**Stand 21.11.2026: Oberfläche Englisch/Deutsch auf main, PR #9, Squash-Merge `6bf2637` verifiziert.** PO-Auftrag: englische Übersetzung und englische README-Screenshots zuerst. Deutscher Text bleibt Schlüssel, Englisch in `desktop/src/i18n-en.ts`, eigene kleine Hilfe ohne neue Abhängigkeit; Sprache folgt dem System, umschaltbar im Kontomenü und auf der Anmeldeseite. `scripts/check-i18n.cjs` läuft vor jedem Build. Offizielles Keplura-Zeichen statt Buch-Symbol in Seitenleiste/Anmeldung. Screenshots neu mit `desktop/e2e/readme_screenshots.cjs`. E2E-Suiten laufen weiter auf `de-DE`. Klasse B. `ci-local.sh` als Nicht-root mit `acl` Exit 0 auf `f88b876` (Evidence `ci/evidence/20261121-002709/`) und nach Korrektur von `check-i18n.cjs` erneut auf `9b77f41` (543 Server-Tests, 1 übersprungen; Desktop-Build, cargo check; E2E nicht ausgewählt), Evidence `ci/evidence/20261121-003606/`. Ein erster Lauf scheiterte nur an fehlender UTF-8-Locale des CI-Nutzers (Umlaute in Testdateinamen), nicht am Code. Offen: Servermeldungen sind in der englischen Oberfläche noch deutsch. Parallel: RISK-02 Inhaltsprüfung auf `feat/oversharing-content` (Server fertig, Oberfläche offen).

**Umzug 20.11.2026:** Dieses Repository (`marcello-conocchia/Keplura`) ist seit
20.11.2026 das Hauptrepo mit sauberem Start (ein Commit). Die bisherige
Historie, alle PRs bis #95 und die Prüfprotokolle (`ci/evidence/`,
`desktop/e2e/evidence/`) liegen im privaten Archiv
`marcello-conocchia/Keplura-intern`. PR-Nummern und Evidence-Pfade in den
Einträgen unten beziehen sich bis einschließlich 20.11.2026 auf das Archiv;
neue PRs zählen hier ab #1.

**Stand 21.11.2026: Sicherheitsupdates Desktop, PR #8 (Klasse A).** DOMPurify 3.4.17, Vite 7.3.8, source-map-js 1.2.4 (Dependabot-Warnungen #1–#11; ersetzt Dependabot-PRs #3–#5). Neu `.github/dependabot.yml`: keine Versions-Updates mehr, Sicherheitsupdates gebündelt als ein PR je Bereich (PO: „aber nicht zu oft“). CI-Nachweis und Security-Prüfung im PR.

**Stand 20.11.2026: H2-Dev-Fehler behoben, PR #7, Merge `fb18d99`.** Dev-Profil baut das Schema mit denselben Flyway-Migrationen wie prod (H2 im PostgreSQL-Modus, Hibernate validiert nur); „Wissen an Chat anhängen“, MCP und Websuche-Einstellungen laufen lokal ohne HTTP 500. Regressionstest `DevProfileSchemaTest`. `ci-local.sh` Exit 0 auf `7275b8c` (Evidence `ci/evidence/20261120-174229/`).

**Stand 20.11.2026: Keplura ist öffentlich** (github.com/marcello-conocchia/Keplura,
Apache-2.0, PO-Go „Yes, go public“). Vor dem Umschalten alle Referenzen erneut
geprüft (5 Commits, nur Inhaber/noreply, keine Zugangsdaten). Aktiv: vertrauliche
Sicherheitsmeldung über GitHub, Secret Scanning mit Push-Schutz,
Dependabot-Warnungen und -Sicherheitsupdates. Hermes-Host frisch geklont (PO).
Archiv `Keplura-intern` bleibt privat. Offen: Branch-Schutz für `main` (PO),
Draft-PR #1 (nachgeholte Reviews aus dem Archiv). H2-Dev-Fehler beim
Wissen-Anhängen: behoben in #7.

**Stand 20.11.2026: Open-Source-Vorbereitung auf main, PR #93 (OPEN-SOURCE-01), Merge `766ad7b` verifiziert.** Öffentliche Doku (README englisch mit Screenshots im Dunkelmodus, `docs/INSTALL.md`, `docs/OPERATIONS.md`, `docs/RELEASING.md`, CONTRIBUTING, SECURITY, CHANGELOG, `.env.example`, PR-Vorlage), Lizenz **Apache-2.0** (`LICENSE`, `NOTICE`, Lizenzfelder in pom/package.json/Cargo.toml), Lizenzinventar `docs/legal/THIRD_PARTY_LICENSES.md` mit Generator `scripts/third-party-licenses.cjs`, `.gitignore` für lokale Agenten-Ordner, CI-Workflow mit read-only Token. Bericht `docs/OPEN_SOURCE_READINESS.md`: Historie inkl. PR-Referenzen ohne Zugangsdaten (keine Rotation nötig), Abhängigkeiten lizenzrechtlich unkritisch; Gesamturteil **NOT READY** wegen offener PO-Punkte B2 (private E-Mail in 197 PR-Kopf-Commits, Empfehlung neues öffentliches Repo mit sauberem Start), B3 (Marke „Keplura“, Herkunft App-Icon), B4 (Sicherheitsmeldeweg beim Veröffentlichen aktivieren). Klasse B; statt vollem `ci-local.sh` Schnellprüfung (`mvn validate`, `cargo metadata --locked`, `npm ci --dry-run`) auf PO-Entscheidung — kein voller CI-Nachweis für #93. Nebenbefund: Dev-Profil (H2) „Wissen an Chat anhängen“ → HTTP 500 (`BadSqlGrammarException`), mit PostgreSQL ok; eigene Aufgabe offen. Repo bleibt privat; Sichtbarkeit nur nach ausdrücklicher PO-Freigabe.

**Stand 19.11.2026: Zugang und Ersteinrichtung im Katalogstil auf main, PR #89 (UI-13) — damit alle Bereiche des UI-Inventars umgestellt.** Anmeldung, Pflicht-Passwortwechsel und Sitzungsprüfung im gemeinsamen Rahmen `AccessShell`; Offline-Hinweis mit „Erneut versuchen“, Meldungen mit role=alert; Ersteinrichtung mit Fortschritt, sichtbaren Feldbeschriftungen, Fehler am Schritt und Fokuswechsel; veraltete globale CSS-Überschreibungen entfernt (betraf auch die Team-Seite aus #87). Klasse A, alle Gates erfüllt: `ci-local.sh` als Nicht-root mit `acl` Exit 0 auf `3d3f7f2` (539 Server-Tests, 1 übersprungen; Evidence `ci/evidence/20261119-210350/`), Reviewer und Security freigegeben. Spec `docs/tasks/UI-13-access.md`, Screenshots `docs/design/ui-access-2026-11-19/` (Serverantworten simuliert). Nicht geprüft: Dunkelmodus, echter Server, OIDC-Rückkehr, Team-Seite nach dem CSS-Aufräumen.

**Vorheriger Stand 19.11.2026: Team im Katalogstil auf main, PR #87, Merge `7122979` verifiziert (UI-12).** Team-Seite mit den Abschnitten Organisationen, Mitglieder & Einladungen und Einladungscode einlösen; seltene Aktionen im „…“-Menü, Dialoge mit Dirty-/Busy-Schutz, echte Lade-/Fehlerzustände, Einmal-Anzeigen mit sichtbarem Kopierfehler. Zusätzlich behoben: Einladungscode wird beim Org-Wechsel im Store verworfen (auch während der Anlage), Startzugang nennt seine Organisation. Klasse A, alle Gates erfüllt: `ci-local.sh` als Nicht-root mit `acl` Exit 0 auf `ed82241` (539 Server-Tests, 1 übersprungen; Evidence `ci/evidence/20261119-202440/`), Reviewer und Security freigegeben, 5 Befunde behoben. Spec `docs/tasks/UI-12-team.md`, Screenshots `docs/design/ui-team-2026-11-19/` (Serverantworten simuliert). Nicht geprüft: Invite-E2E gegen echten Server, Dunkelmodus.

**Vorheriger Stand 19.11.2026: KI-Verwaltung und Systemstatus im Katalogstil auf main, PR #85, Merge `47d897a` verifiziert (UI-11).** Administration → KI-Anbieter, Modelle, Suche & Wissen und Systemstatus als eigene Panels (`desktop/src/components/admin/`); `AdminPanel.vue` nur noch Navigationsrahmen. Sicherheitsregeln unverändert (Schlüssel nur schreibbar, SSRF-Ablehnung nur als Hinweis, Embedding-Standardwechsel mit Bestätigung). Klasse A, alle Gates erfüllt: `ci-local.sh` als Nicht-root mit `acl` Exit 0 auf `1dcccb6` (539 Server-Tests, 1 übersprungen; Evidence `ci/evidence/20261119-180327/`), Reviewer und Security freigegeben, 7 Befunde behoben. Spec `docs/tasks/UI-11-ai-admin.md`, Screenshots `docs/design/ui-ai-admin-2026-11-19/` (Serverantworten simuliert). Nicht geprüft: Admin-E2E (lief nicht im gewählten CI-Umfang), Dunkelmodus, echter Server.

**Vorheriger Stand 18.11.2026: Update-Probe Altinstallation → Keplura bestanden; E2E-Cache-Pfad repariert.** Isolierter Teststack (Projekt `kut`, nur db + server + Mock-Anbieter; Pilotinstallation unberührt): alter Stand `9bfe395` mit alter `.env.production` (21 `INTRACORE_*`), Admin-Login, Anbieter mit gespeichertem Schlüssel, Upload. Neuer Stand `e68ac4d`: direkter `compose up` ohne Umstellung → Start abgebrochen (nur Namen im Log, Schlüsselwert nicht im Log); `install.sh` → 21 Schlüssel umgestellt, Sicherung byte-gleich, Health OK; danach Login mit `KEPLURASESSION`, Anbieter-Schlüssel entschlüsselbar, Dokument-Bytes nach Pfadwechsel `/var/lib/intracore` → `/var/lib/keplura` gleich, Suchabschnitt samt Vektor in der DB erhalten. NICHT geprüft: Such-Anfrage mit Reranker (im Teststack aus), Modelle/Ollama, Desktop-Neuinstallation, echte Pilotinstallation (Deployment = PO). E2E: im CI-Image dieses Rechners fehlen Playwright/Chromium, daher hier nicht lauffähig — gehört auf den Hermes-Host. Dabei gefunden: `ci-local.sh` suchte `playwright-core` nur unter `~/.cache/keplura-e2e`; Rückfall auf `~/.cache/intracore-e2e` ergänzt. ci-local auf `8a0a49c` Exit 0 (539 Server-Tests, 1 übersprungen) — Evidence `ci/evidence/20261118-210913/`.

**Vorheriger Stand 18.11.2026: Gates für die Keplura-Umbenennung nachgeholt, Folge-PR mit Befund-Korrekturen.** `main` @ `de9a6bd` (PR #82): `scripts/ci-local.sh` als Nicht-root mit `acl` (Docker, Image intracore-ci:local, sauberer Klon) Exit 0, 536 Server-Tests (1 übersprungen), nativer Leser, Desktop-Build, `cargo check` — Evidence `ci/evidence/20261118-203037/`; E2E nicht ausgewählt. Unabhängige Prüfung (Reviewer + Security, nicht vom Autor): Security APPROVED, Reviewer CHANGES REQUESTED (keine Blocker). Korrekturen im Folge-PR: `.env.production`-Sicherungen gitignored; nicht umgestellte Compose-`.env` bricht den Start ab statt still ohne Master-Key zu laufen (Erkennung über `INTRACORE_SECRETS_MASTER_KEY`/`_SERVER_HOST_PORT`/`_PROVIDER`); install.sh-Migration (neuer Name gewinnt, export-Zeilen, Rechte/Besitzer, Sicherung nie überschrieben, Temp-Datei aufgeräumt); Rückfall auch für Leser-Pfad und backup/restore; Update nur über install.sh dokumentiert. Folge-PR @ `52ce3ef`: ci-local Exit 0, 539 Server-Tests (1 übersprungen), Installer-Tests 11/11 — Evidence `ci/evidence/20261118-204154/`; Reviewer + Security APPROVED.

**Vorheriger Stand 18.11.2026: Vollständige technische Umbenennung auf Keplura, PR #82 — auf PO-Anweisung gemergt OHNE verbindliches Gate.** Klasse A (Sitzung/CSRF-Cookie, Org-Header, Konfiguration, Installer, Deploy). Java-Pakete `com.keplura`, App-Kennung `com.keplura.desktop`, Binär-/Paketnamen, Cookies `KEPLURASESSION`/`keplura_csrf`, Header `X-Keplura-Org`, Properties `keplura.*`, ENV `KEPLURA_*`. Kompatibilität: Server liest `INTRACORE_*` weiter (Warnung), `install.sh` migriert `.env.production`; unverändert bleiben Compose-Projekt/Volumes, bestehende DB-Namen, HKDF-Salz des SecretCipher, angewandte Flyway-Migrationen, GitHub-Repo, Kanban-Board, Entwickler-Image, historische Nachweise (ADR-0013). GitHub-Repo ist bereits `marcello-conocchia/Keplura` (alte URLs leiten um). Folgen: Desktop-Neuinstallation und Neuanmeldung für alle Nutzer. Einmalige ausdrückliche PO-Ausnahme („merge du“, nach Hinweis auf fehlende Gates): `scripts/ci-local.sh` auf Linux sowie Reviewer- und Security-Prüfung sind NICHT gelaufen und damit NICHT bestanden; Nachweise nur lokal Windows (Server 515/517, 2 umgebungsbedingt; Installer 8/8; Desktop-Build, cargo check). Nachholen: ci-local auf main und unabhängige Prüfung von Cookies/Header/Legacy-ENV-Fallback/Installer-Migration. Keine generelle Aufhebung der Gates.

**Vorheriger Stand 18.11.2026: Anmeldung mit „Angemeldet bleiben“ auf main, PR #80, Merge `cf0cdc0` verifiziert.** Checkbox für Passwort-/Verzeichnis- und Firmenlogin, standardmäßig aus. Mit Auswahl persistentes HttpOnly-Cookie, ohne Auswahl Sitzungscookie. Keine Abmeldung nach 30 Minuten Inaktivität; feste konfigurierbare Höchstdauer von 30 Tagen. Lokale Linux-CI auf Produktstand `7dcc00c` als Nicht-root mit acl vollständig Exit 0: 76 Server-Testklassen, 532 Tests bestanden, ein OCR-Test mangels Tesseract übersprungen; Rechte-Helfer, Desktop-Build und Linux-Tauri-Check bestanden. Evidence `ci/evidence/20261118-200407/`; erster NPM-Cache-Umgebungsfehler unter `20261118-200001/` erhalten. Unabhängige Code-/Security- und gezielte QA-Freigabe auf finalem PR-Head `3127d52`, keine offenen Befunde. Browser-QA 4/4 mit simuliertem HTTP; keine native oder echte IdP-Wiederherstellungsabnahme. Serverneustarts beenden Sitzungen weiterhin. Kein Release/Deployment. Frühere uncommittete Persistenzarbeit bleibt separat; nächste fachliche Arbeit richtet sich weiter nach der bestehenden Roadmap. Spec: `docs/tasks/AUTH-REMEMBER.md`.

**Vorheriger Stand 18.11.2026: Produktname Keplura, PR #78.** Der Inhaber bestätigt die Umbenennung und beauftragt die Durchführung. Klasse B: Produktnamen und Texte in Oberfläche, Installer-Anzeige, Server-Metadaten, Assistent, Exporten und aktuellen Anleitungen. Technische Kennungen, Konfiguration, Daten, Repository-URL und historische Nachweise bleiben erhalten. Lokale Linux-CI auf `32b8696` als Nicht-root mit Java 21, Cargo und `acl`: Exit 0; 75 Server-Testklassen, 529 Tests bestanden und ein OCR-Test mangels Tesseract übersprungen; sieben native Rust-Tests, Desktop-Build und Linux-Tauri-Check bestanden. Evidence: `ci/evidence/20261118-162140/`. E2E und native Installer wurden nicht ausgeführt. Danach nur Dokumentation und Nachweise ergänzt; kein erneuter CI-Lauf nach der Lean-Regel. UI-Aufnahme: `docs/design/keplura-2026-11-18/login.png` (Anmeldeseite ohne Backend-Verbindung, kein Login-Funktionstest).

Historischer Stand: 2026-11-15, `main@a6ae561` (Gesamtübernahme aus PR #64). Der bisherige gestapelte Funktions- und UI-Stand bis einschließlich Websuche/MCP/Verzeichnis ist auf main übernommen. Alte Branch-/Gate-Angaben unten dokumentieren den jeweiligen historischen Prüfstand. Pflegepflicht: jede Haupt-Aufgabe aktualisiert diese Datei nach Merge (AGENTS.md § Task rules).

**Neu auf main (16.11.2026):** Sicherheitskorrekturen aller sieben Befunde aus dem Security-Review (#72, Bericht #71; u. a. hoher Befund S-3 erweiterte POSIX-ACL, Linux-Ordner nur noch über lokalen Rust-Rechteleser, NFS vorerst gesperrt), Quellen-Frische in Zitaten (#67), Excel/PowerPoint als Dokumenttypen (#70). Offen: PR #73 (Klartext-Gründe für gesperrte Linux-Dateien) und nachträgliche Prüfung von F-1 aus #70 durch einen unabhängigen Prüfer; CI muss als Nicht-root laufen (root umgeht Dateirechte im Rechte-Test).

**Neu auf main (15.11.2026, Claude): Oversharing-Check (RISK-01) und OCR für Scans (OCR-01).** Oversharing-Check: Administration → Datenquellen → „Oversharing prüfen“ (auch im Assistenten, Schritt „Rechte“) zeigt vor der Übernahme breit lesbare und nach Namen sensibel wirkende Dateien und schließt sie aus; Spec `docs/tasks/RISK-01-oversharing-check.md`, Migration V24. OCR: gescannte PDF-Seiten werden lokal per Tesseract (deu+eng) gelesen, Seitenzitate bleiben erhalten; Server-Image +116 MB; Spec `docs/tasks/OCR-01-scans.md`. Beide per einmaliger PO-Ausnahme OHNE Reviewer-/Security-/QA-Gate gemergt (siehe PO-ENTSCHEIDE). Offen: unabhängige Review/Security beider Features (Klasse A), echter SMB-Lauf des Checks, Chat-Zitat aus Scan gegen echtes Modell, OCR im Produktions-Image, CI auf dem finalen OCR-Stand. Nächste Schritte laut Produktstrategie: Rechte-Nachweis („Warum sieht Anna diese Datei?“), Inhaltsprüfung als Stufe 2 des Checks; Codex: Quellen-Frische im Zitat.

**Neuer PO-Auftrag 15.11.2026: Quellen-Frische (Klasse B, PR #66):** eigener Branch `codex/source-freshness` von `origin/main@ff18bd9`. Additives `sourceUpdatedAt` in Suche/Zitaten/Vorschau; Original-Änderungszeit der Ablage, sonst Upload-Datum. Gemeinsame ruhige Anzeige „Stand: …“, ab mehr als drei Jahren neutraler Hinweis. Keine Migration oder Rechteänderung, ausdrücklich gesperrte Pfade unverändert. Spec `docs/tasks/SOURCE-FRESHNESS.md`. CI-Nachweis auf finalem SHA und Screenshot werden im Feature-PR verlinkt; kein Merge ohne PO-Blick. Frühere Ausnahme gilt hier nicht.

**Übernahme verifiziert:** PR #64 wurde als Squash-Merge `a6ae56185f236366b05e5cef852750af79093a43` nach main übernommen; Remote anschließend abgerufen und vollständiger Dateibaum gegen den freigegebenen Head `a379c95` verglichen (identisch). Keine Veröffentlichung/Installation/Deployment. Einmalige ausdrückliche PO-Ausnahme für fehlende vollständige CI und unabhängige Reviews; diese Prüfungen sind dadurch NICHT bestanden. Aktuelle Nachweise bleiben Build, gezielte UI-Suiten und die dokumentierten Grenzen. Der restliche UI-Redesign-Auftrag bleibt offen.

**Nächste Arbeit:** UI-Inventar vollständig umgestellt (#85 KI-Verwaltung/Systemstatus, #87 Team, #89 Zugang/Ersteinrichtung); offen: Gesamtabnahme im Dunkelmodus und gegen echten Server, E2E-Suiten (admin/invite/chat) einmal vollständig laufen lassen; Sitzungswiederherstellung prüfen. S-1 (Admin-DELETE-CORS) ist seit #72 (16.11.) behoben. Vollständige CI und unabhängige Reviews vor folgenden regulären Merges wieder verbindlich, native Plattformprüfungen vor Release. Keine pauschale Ausnahme für Folgeaufgaben.

Diese Datei ist die einzige autoritative Statusquelle neben Repo-Log, Kanban-Board
`intracore` und GitHub (PRs #1–#51). Agent-Memory ist KEINE Projektquelle.
Detail-Specs je Meilenstein: `docs/ROADMAP.md` — hier steht nur Stand + Beweispfad.

**Verbindungen im Katalogstil implementiert:** Produktstand `d8cc94d` auf `codex/ui-connections`, gestapelt auf `875c83d` (Datenquellen/PR #62). Websuche mit gegliederten Einstellungen und gespeichertem Testzustand; MCP mit Verbindungsdialog, Werkzeug-/Bereichsfreigaben und Journal; Verzeichnis mit einheitlichen Listen und Anlage/Bearbeitung/Nachschlagen. Gemeinsame Status-, Fehler-, Busy-/Dirty- und Menüfokusverträge. Build grün, 34/34 gezielte Verbindungspunkte; erste drei nur echte QA-Lesezugriffe, übrige HTTP-Antworten simuliert. Basisregression 17/17 vor der letzten lokalen Umbruchkorrektur. Manuell alle drei Ansichten und zwei Anlagedialoge betrachtet. Keine echten Verbindungen/Zugangsdaten/Freigaben geändert. Evidence `docs/design/ui-connections-2026-11-15/README.md`, Spec `docs/tasks/UI-10-connections.md`. Vollständige CI, echte Connector-Gesamtabnahme und unabhängige Reviews offen. Zusätzlich Sitzungswiederherstellung nach Browser-Reload prüfen: generische Identität ohne Adminnavigation beobachtet, reguläres Wiederanmelden hilft. Kein Merge/Release/Deployment; nächster verbleibender UI-Umfang laut Inventar u. a. KI-Verwaltung, Betrieb, Team und Zugang/Onboarding.

**Datenquellen nach PO-Rückmeldung implementiert:** `codex/ui-datasources` auf `60eff80`. Neue Quellenübersicht mit getrenntem Betrieb/Abgleichsergebnis, Filtern, Zeilenmenüs und vollständigen Lade-/Fehler-/Busy-Zuständen. Assistent auf PO-Wunsch mit weniger Trennlinien; mobile Adminnavigation kompakt. Build grün, 27/27 gezielte Browserpunkte; nur der erste Lesezugriff echt, Schreibvorgänge und Probelauf ausdrücklich simuliert. Evidence `docs/design/ui-datasources-2026-11-15/README.md`, Spec `docs/tasks/UI-09-datasources-layout.md`. Kein echter Connector- oder DELETE-Nachweis, vollständige CI und unabhängige Gates offen. Neuer autorisierter Folgeauftrag: Websuche, MCP-Server und Verzeichnis im selben Katalogstil.

**Aktuell 15.11.2026: UI nach konkreter Browser-Rückmeldung.** `codex/ui-base@7e29b00` (Entwurf PR #59): gemeinsame Bedienelemente, echte Bestätigungsdialoge, konsolidierte Suche. Neue Basisprüfungen 17/17 auf dem ersten Produktzwischenstand, Assistent 34/34 und A11y 12/12 vor dem folgenden Chatumbau. Beide vollständigen CI-Anläufe wurden wegen Rechnerbelastung beim Spielen unterbrochen; KEIN vollständiges grünes CI für diesen Stand. Ein echter Chat-Modelllauf war zuvor rot („37 Tage“ fehlte) und wurde anschließend unterbrochen; keine vollständige neue Chat-Funktionsabnahme. Belege und Grenzen: `docs/design/ui-base-2026-11-15/README.md`.

**Wissensansicht nach PO-Browserkommentar umgesetzt:** `codex/ui-knowledge`, gestapelt auf Chat-Zwischenstand `df8e8ab` (PR #60). Basiswahl vertikal auf breiten Fenstern, kompakt darunter; Titel/Anzahl, Upload und breite Suche, echte Dokumenttabelle mit dauerhaften Aktionen; System-1.0-Tokens. Ladezustand und Request-/Scope-Schutz für verspätete Dokumentlisten im Frontend ergänzt. Build grün; Layout-/Zustandsprüfung 12/12, anschließende Chat-Regression 14/14 und Basisregression 17/17 gegen QA. Suchantworten und Stressdateien sind simuliert, echter Download/Listen und Dialog-Abbruch geprüft; kein echter Upload/Reindex/Endgültig-Löschen in diesem Lauf. Belege `docs/design/ui-knowledge-2026-11-15/README.md`, Spec `docs/tasks/UI-08-knowledge-layout.md`. Gesamte lokale CI sowie unabhängige Reviews und native Prüfung offen. Nächste noch nicht vollständig gestaltete Bereiche: Team, Administration und Zugang/Onboarding gemäß Gesamtinventar. Der vollständige Redesign-Auftrag ist noch nicht abgeschlossen.

**Neue bindende PO-Richtung:** ein einziger Einstieg „Neuer Chat“, allgemein nutzbar; Wissen, Datei und Web sind optionale Ergänzungen. `codex/ui-chat-layout`: kompakter Composer, Plusmenü, feste Hauptnavigation vor dem Verlauf, neutraler neuer Chat. Build grün, gezielte Browserprüfung 14/14, Senden/Stoppen ausdrücklich mit simulierter Antwort ohne Modellaufruf. Spec `docs/tasks/UI-06-chat-layout.md`, Screenshots `docs/design/ui-chat-layout-2026-11-15/`. Der Inhaber priorisiert jetzt die gedrängte Wissensansicht: strukturieren und dabei den Designkatalog anwenden. Dieser Folgeauftrag ist autorisiert; keine erneute Richtungsfreigabe erforderlich. Gesamtes Redesign bleibt offen. Kein Merge, Release oder Deployment; unabhängige Gates offen.

**Phase 4 vom Inhaber abgenommen (15.11.2026, „Ja, abnehmen“).** Abnahme betrifft das validierte Designsystem in Chat/Quelle/Assistent; sie ersetzt keine unabhängigen Gates und ist keine Merge-Freigabe. P05 wurde anschließend beauftragt. Chat, Quellenpanel, großer Datenquellen-Assistent und Primitive/Tokens real geprüft; 12 Produktfehler gefunden und behoben (u. a. zwei in der Übergabe nur behauptete Fixes, Doppel-Escape umging Dirty-Schutz, verdeckte Felder/Chat bei geringer Höhe, Zielgrößen). Browserläufe gegen QA mit echtem Modell: Assistent 34/34, Chat 31/31, A11y 12/12; Belege `docs/design/ui-validation-2026-11-15/README.md`, Prüfliste V01–V15 in `docs/tasks/UI-04-design-validation.md` (V03 teilweise: MCP-Rückfall nicht erneut geprüft; V08 SharePoint offen). Lokales CI `ci-local.sh --with-e2e` Exit 0 auf `7bd2304`, Evidence `ci/evidence/UI-04-20261115-144820/`. Neuer Befund S-1: Server-CORS `/api/v1/admin/**` ohne `DELETE` (Browser-Dev-Modus), eigene Aufgabe mit Security-Review. Unabhängige Reviewer-/QA-/Security-Gates offen. P05 implementiert, noch nicht vollständig abgenommen; kein Merge.

**Vorheriger Folgeauftrag: Phase 3 – Designsystem (15.11.2026).** Mit „Ja fang an mit Phase 3“ ausdrücklich beauftragt. Soll-System definiert: `docs/design/UI-DESIGN-SYSTEM-2026-11-15.md`, `UI-COMPONENT-CONTRACTS-2026-11-15.md`, `UI-MIGRATION-2026-11-15.md`. Eine maschinenlesbare Tokenquelle, acht Vertragsfamilien, alle 36 Vue-Dateien und 109 Inventar-IDs zugeordnet. 160 erlaubte Farbpaare berechnet; isolierter Katalog in Hell/Dunkel, schmal und bei geringer Dialoghöhe betrachtet, Fokus-Rückkehr und Root-Reflow dort geprüft. Evidenz/Grenzen: `docs/design/system-v1/README.md`. Branch `codex/ui-system`, eigener Diff gegen `codex/ui-research` reine Dokumentation/Referenz; Produktcode und ursprünglicher Umsetzungstracker unverändert. Keine Produkt-/CI-/A11y-/Review-Freigabe. Phasen 4–7 offen, nächster eigener Auftrag P04 (Chat/Assistent validieren), kein automatischer Beginn/Übertragen auf Fachseiten, kein Merge.

**Vorheriger Folgeauftrag: Phase 2 – Recherche und Richtung (15.11.2026).** Vom Inhaber mit „Starte mir Phase 2“ beauftragt. Recherche: `docs/design/UI-DESIGN-RESEARCH-2026-11-15.md` (16 Primärreferenzen, fünf veröffentlichte Abbildungen betrachtet). Richtung: `docs/design/UI-DESIGN-DIRECTION-2026-11-15.md`. Alle 109 Inventar-IDs zugeordnet, Umsetzungs-/Prüfstatus offen. Dokumentprüfungen/Grenzen: `docs/design/ui-research-2026-11-15/README.md`. Branch `codex/ui-research`, PR #56, reine Dokumentation. Keine Produkt-/CI-/QA-Freigabe.

**Abgeschlossene Bestandsaufnahme: Phase 1 und Planung (15.11.2026).** Vor der Recherche wurde die vollständige Bestandsaufnahme erstellt. Auditbasis ist `2de0a8a` (bisheriger UI-Entwurf, keine neue Designabnahme). 36 Vue-Dateien und 527 Elementstellen statisch erfasst, 32 Ansichten/Overlays ohne Datenänderung geöffnet. Inventar: `docs/design/UI-INVENTORY-2026-11-15.md`; Reihenfolge/Abnahmeschranken: `docs/design/UI-REDESIGN-PLAN-2026-11-15.md`; Tracker mit allen Einträgen offen: `docs/design/ui-audit-2026-11-15/coverage.json`. Kein Produktcode geändert. Die folgenden früheren UI-Aufträge sind historischer Bestand.

**Neuer PO-Auftrag: gesamtes UI modernisieren (15.11.2026).** Eigenes Worktree/Branch `codex/ui-modern`, Funktionsbasis `d541ce1` aus PR #53. Gemeinsame Gestaltung für Login, Chat, Navigation, Wissen, Team und Administration; MCP kompakt und aufklappbar. Elf Browseransichten inklusive Dark Mode und Handy sowie vier MCP-/Websuche-Funktionsschritte bestanden. Spezifikation: `docs/tasks/UI-01-modern-workspace.md`. Eigenes abschließendes CI-Gate im UI-PR; keine Übertragung der alten CI-Freigabe, kein Merge.

**Vorheriger Funktionsstand am 15.11.2026:** Die verfügbaren DS-, Chat-/Websuche- und
MCP-Funktionsprüfungen sind lokal bestanden; Einzelstatus und nicht prüfbare
Punkte stehen in den drei Aufgaben-Prüflisten.
Behoben sind Such-SQL, parallele Anhänge, Dateikontext trotz leerer Basis,
PDF-Überlauf, schlüssellose Websuche-Einstellungen und begrenztes Einlesen.
MCP ist gegen offiziellen Beispielserver + qwen2.5 geprüft; der normale
Chat-Rückfall funktioniert mit echtem TinyLlama ohne Werkzeugaufruf.
Werkzeugfreigaben, Journal und parallele Konfigurationsänderungen abgesichert.
449 Server-Tests in 66 Klassen, Frontend und Linux-Tauri sind bestanden.
Alle acht bestehenden E2E-Suiten bestehen nach Anpassung einzeln, einschließlich
nativer Anmeldung/Sitzungswiederherstellung und tatsächlicher Hell-/Dunkel-Farben.
Importierte Quelle mit zitierter KI-Antwort und Bewertungsstatistik im Browser grün.
Der verbindliche abschließende Gesamtlauf wird auf unverändertem PR-Head geprüft;
genaue SHA, Exitstatus, Matrix und Protokoll stehen im
[Draft-PR #53](https://github.com/marcello-conocchia/Keplura/pull/53).
Frühere/gezielt ausgeführte Läufe ersetzen diesen Nachweis nicht.
SharePoint/Entra-Testtenant, echte DFS-/NFS-Topologie sowie unabhängige
Security-/QA-/Reviewer-Freigaben bleiben offen. Keine Merge-Freigabe.

**Fortsetzung am 15.11.2026:** Der Inhaber hat mit „weiter“ fortgesetzt.
DS-Browser: Verzeichnis über Formular anlegen und Datenquellen-Assistent mit
Statusprüfung genau der neuen Quelle grün. Echte unlesbare SMB-ACL sperrt
bereits importierte Dateien. Laufender Abgleich per Prozessabbruch unterbrochen:
Neustart erkennt den Abbruch, erneuter Lauf grün. Gruppen-/Kontorechte bestehender
Sitzungen nach 265 Sekunden entzogen (Testintervall fünf Minuten), deaktiviertes
AD-Konto kann sich nicht anmelden. Evidence `ci/evidence/DS-01-20261115/`.
CHAT-01 und MCP-01 sind ebenfalls geprüft. Neue Übergabe:
`docs/HANDOFF-CODEX-2026-11-15.md`. Unabhängige Reviews bleiben offen.

**Historische ÜBERGABE / Pause am 14.11.2026:** Der Inhaber möchte schlafen; Arbeit bis
zu seiner Fortsetzung pausiert. Neuer ausdrücklicher Auftrag: den von Claude
erweiterten Branch prüfen, Reihenfolge CI → DS-01 → CHAT-01 → MCP-01 → E2E,
kleine Commits und Push, kein Merge. Diese Abnahme ersetzt die frühere lokale
UI-Arbeitspriorität; ältere Abschnitte unten sind historische Nachweise.

- Standard-CI auf `9a28e01`: vollständig grün, 416 Tests / 57 Klassen,
  Frontend und Linux-Tauri; Evidence `ci/evidence/20261114-221256/`.
  E2E war nicht ausgewählt. Nach den folgenden Produktfixes noch KEIN voller Lauf.
- DS-01 teilweise real abgenommen: OpenLDAP, Samba AD über LDAPS mit Test-CA,
  verschachtelte Gruppen, Linux/SMB-Rechte, zwei Konten, Rechteentzug nach
  Abgleich, PostgreSQL/hybride Suche, Qualität und Browser-Assistent.
- Sicherheitsfixes: lokale Quellwurzel und POSIX-Rechteklassen berücksichtigen,
  Symlink-Ausbruch verhindern, AD-Referrals ignorieren ohne ihnen zu folgen,
  kaputte SIDs verwerfen, unbekannte ACL ohne doppelte Datenbankzeile sperren,
  fehlenden SMB-Sicherheitsdeskriptor nicht als öffentlich behandeln.
  Zehn zusätzliche gezielte Tests bestanden. DS-Prüfliste enthält Restpunkte.
- CHAT-01, Websuche in IntraCore und MCP-Abnahme stehen noch aus; SearXNG
  liefert echte JSON-Treffer, offizieller Beispiel-MCP-Server ist vorbereitet.
- Kein fertiger Merge-Kandidat: finale CI/E2E sowie unabhängige Reviews offen.
  SharePoint benötigt einen Testtenant, der hier nicht eingerichtet ist.

Einstieg für die Fortsetzung: `docs/HANDOFF-CODEX-2026-11-14.md`, Abschnitt
„Codex-Zwischenstand und Pause“. Die ursprüngliche Pilotinstanz auf 18080
wurde nicht aktualisiert. Die isolierten QA-Container werden zur Pause gestoppt;
Daten und Volumes bleiben erhalten.

## Aktueller Auftrag des Inhabers — 14.11.2026

Diese Reihenfolge ersetzt die älteren Prioritäten und UI-Freeze-Vermerke weiter
unten; die historischen Nachweise bleiben erhalten:

1. Server-Paket: umgesetzt, echte Installation noch abzunehmen.
2. Browser-Version: umgesetzt (ADR-0011).
3. Grundlücken und Wissen-Seite: umgesetzt.
4. **Erster Pilotkunde mit Hand-Upload: jetzt.** Reihenfolge: echte Installation
   mit `bge-m3` und `qwen2.5:7b` → hybride Suche/lokaler Reranker/strukturiertes
   Chunking/keine unbelegten Antworten → Quellen-Vorschau → Team/Admin/Login,
   Wissensbasis im Composer und mobile Sidebar → Erst-Admin-Onboarding,
   Backup/Restore im Admin-Bereich und Antwortbewertungen.
5. Erst danach Datenquellen nach ADR-0012: AD/LDAP/Entra, LDAP-Login,
   SMB/NTFS/DFS und Linux/NFS/POSIX-ACL, anschließend SharePoint; nur lesend,
   Berechtigungsfilter in der Datenbankabfrage, Einrichtung mit Assistent.

Arbeitsweise: lokal im vorhandenen Repository, eigener Branch von `main` pro
Feature, Zwischencommits, deutsche verständliche Oberfläche, Build und Start
nach jedem Feature, kurze Meldung mit Screenshot. Ein laufender lokaler
Feature-Branch ist keine Merge-Freigabe; die Gates aus AGENTS.md bleiben offen.

### PILOT-01 — echte lokale Installation abgenommen, Merge-Gates offen

Spec: `docs/tasks/PILOT-01-installation.md`. Echter Installer mit GPU-Overlay
erfolgreich, eigener Compose-Stack mit persistenten Volumes auf Port 18080.
Die vorhandene Entwicklungsinstanz auf 8080 bleibt erhalten. `bge-m3` und
`qwen2.5:7b` lokal geladen; pgvector verwendet `vector(1024)`. Echter Hand-Upload,
asynchrone Indexierung, semantische Suche und korrekte Antwort mit Quelle sind
belegt, ebenso Browser-Anmeldung, Neustart und wiederholte Installation ohne
Änderung der Zugangsdaten oder Datenverlust.

Bereits geprüft: sieben Installer-Regressionsfälle grün (Argumente, gestoppte
Engine, Passwort nicht in Ausgabe, Wiederholbarkeit, Portquellen, fehlender und
ungültiger Port). Zusätzlicher echter Docker-Build bestätigt, dass `.git`,
`.devenv`, `.env.production`, Desktop-`.env`, Host-`node_modules` und Tauri-
`target` nicht im Build-Kontext stehen. Kontext nach Korrektur: 7,49 MB.
`deploy/pilot-check.cjs`: 11 echte Abnahmeschritte grün. 5/5 Tests des Prüflaufs
verhindern falsches Grün bei Memory-Fallback, fehlenden Quellen, unvollständiger
Übertragung und falschem Antwortfakt. Server-Basis im Linux-Build-Container:
399 Tests / 54 Klassen grün. Native Windows-Prüfung scheitert am Symlink-Recht;
der entsprechende Sicherheitsfall ist unter Linux grün. Evidence und Screenshot:
`ci/evidence/PILOT-01-20261114/`. Kein vollständiger `ci-local`-Lauf, keine
unabhängigen Freigaben und kein Merge; Kunden-DNS/HTTPS hier nicht eingerichtet.
Nächste Produktaufgabe: Schritt 4b, bessere belegte Antworten.

### PILOT-02 — bessere belegte Antworten lokal abgenommen, Merge-Gates offen

Eigener Worktree und Branch `codex/pilot-answer-quality` ab `main@43302d3`;
Spec: `docs/tasks/PILOT-02-answer-quality.md`. Abnahme mit dem Image
`intracore-server:pilot-quality`; anschließend durch PILOT-03 ergänzt.

- PostgreSQL: deutsche Volltextsuche + pgvector mit Reciprocal Rank Fusion.
  Echte SQL-Probe zeigt einen passenden Volltexttreffer trotz absichtlich
  irreführendem Vektor; deutsche Wortformen und alle vier Tenant-Pins geprüft.
- Lokaler CPU-Reranker: TEI 1.9.4 / `BAAI/bge-reranker-v2-m3` mit gepinnter
  Modellrevision, internem Port, persistentem Cache und begrenzten Antworten.
  Passender Testtext 0,9996, unpassender 0,000016; Ausfall ist ein Fehler.
- Feste Nichtfund-Antwort ohne Chat-Modellaufruf bei fehlenden relevanten
  Quellen; gespeichert mit leerer Quellenliste. Freie Chats bleiben erhalten.
- Abschnitts- und PDF-Seitenmetadaten in Index, Suche und Chat-Zitaten (V13).
  Echter PDF-Upload nennt Seite 2; keine erfundenen Seiten für DOCX/TXT/MD.
- Echte Abnahme 7/7 grün; Server vollständig unter Linux: 407 Tests / 55 Klassen,
  0 Fehler, 0 übersprungen; Frontend-Typecheck/Build und Server-Image grün.
  Screenshots und Protokolle: `ci/evidence/PILOT-02-20261114/`.

Vor dem Start wurden Datenbank und Uploads lokal gesichert. Kein Merge, kein
vollständiges `ci-local`-/Packaged-Gate, keine unabhängigen Freigaben behauptet.
Nächste Produktaufgabe: Schritt 4c, Dokumentvorschau rechts mit Quellen-Sprung.

### PILOT-03 — Quellen-Vorschau lokal abgenommen, Merge-Gates offen

Branch `codex/pilot-source-preview` ab main, Antwortqualität als Voraussetzung
auf dem Feature-Branch integriert. Spec: `docs/tasks/PILOT-03-source-preview.md`.
Die Pilotinstanz auf 18080 verwendet jetzt `intracore-server:pilot-preview`.

- Klick auf Chat-Quelle oder Suchtreffer öffnet rechts eine als Textansicht
  bezeichnete Dokumentvorschau; echte PDF-Seite/Abschnitt und zitierte Passage
  werden markiert. Originaldownload bleibt vorhanden. Index-Überlappung wird
  für die Lesedarstellung ohne Verlust der zitierten Passage entfernt.
- Neuer GET-Endpunkt mit Session, vollständigen SQL-Tenant-Pins, maximal 21
  Textblöcken, Positionsprüfung, no-store und nosniff. Kein Parser-/Modellaufruf.
  Alte Positionen, fehlende Rechte und Dokumente ohne Text werden ehrlich behandelt.
- Echte Browserprüfung: PDF-Seite 2, Escape mit Fokusrückgabe, literal dargestelltes
  Script-Beispiel ohne Script-Element, Handybreite 390 px mit Fokusbegrenzung.
- Frontend-Typecheck/Build, finales Server-Image und echte API grün. Vollständige
  Linux-Server-Suite: 412 Tests / 56 Klassen, 0 Fehler, 0 übersprungen.
  Evidence und Screenshots: `ci/evidence/PILOT-03-20261114/`.

Kein Merge und keine vollständigen ci-local-/Packaged-/unabhängigen Gates
behauptet. Nächste Produktaufgabe: Schritt 4d, Oberfläche fertigstellen.

## STATUS (was fertig ist — alles gemergt in main, PR-Gates auf GitHub)

| Meilenstein | Stand | Beweis (Commit / PR / Karte) |
|---|---|---|
| M0 Foundation (Desktop+Server+Contracts, Builds, E2E-Anker) | komplett 2026-09-03 | PRs #1/#2 (bd2d091, 2ffec4b), Evidenz 1efbdad |
| M1 Server-Basis (health, profiles, Test-Harness) | komplett 2026-09-03 | 0b17a2c, 38d532f, 4e23a40 (t_705c2c9e, t_0d040362, t_2debf12a) |
| M2 Identity & Tenancy (Argon2, Sessions, Orgs, Isolation) | komplett (OIDC-Zweitpfad 09.09. gemergt) | 6b22f86→3d29d7f (t_c6c1e325…t_05f43867); OIDC: d4b04a0 (Zeile OIDC-M2-Tail unten) |
| M2-POLISH + M3-HARDEN | komplett | a1b82bf (t_b39d57ae), 82d87e5 (t_c0235a04) |
| M3 Spaces & Knowledge (pgvector, KBs, Upload, RAG-Suche, UI) | komplett 2026-09-05 | 3dce57d, 25b5bbe, 8804e70, 4397385, ec628cd (t_89b63b4f…t_6bdfe8ae) |
| M4 Chat & Model-Gateway (M4a Gateway, M4b Chat/SSE, M4c Provider-presets) | komplett 2026-09-06 | 992a86c (t_7fa3b9c6), 8b5eeda (t_037a0960), f726cc0 (t_38dd77a9), PRs #24/#25/#27 |
| Design-System 'Soft Structure · Apple Refined' | verbindlich | 450fb9f (t_dd0c94e5), docs/DESIGN.md, PR #21 |
| UI-1 + UI-3 (Tokens/Komponenten, Knowledge-Views) | komplett | 032d3a0 (t_80c103f4, PR #22), 68e0c39 (t_8c504a0b, PR #23) |
| MVP-Audit (Doku auf echten Stand) | komplett | 6aa2b04 (t_37d48f0f, PR #26) |
| MVP-5 Parser: PDF+DOCX via Tika, Zip-Bomb/Timeout-Gates | komplett 2026-09-07 | 6b6407c (t_da9b5d07, PR #28) |
| SEC-2: CSRF double-submit + Org-Invites mit atomarem Redeem | komplett 2026-09-07, QA 24/24 am packaged Binary | 63fd971 (t_94554446, PR #29; QA t_ea740e1f) |
| CI-LOCAL: verbindliches lokales Gate scripts/ci-local.sh | komplett 2026-09-07 | 7e662f5 (t_71473c70, PR #30), ci/evidence/, docs/development.md |
| UI-POLISH-1 (letzter UI-Task): Create hinter '+'-Dialogen, gedeckelte Wissensspalte, ruhige Suche, Chat-Empty-State/Composer | komplett 2026-09-08 — **UI-FREEZE aktiv: nur noch echte UX-/Rendering-Bugs** | 10d5413 (t_596e3195, PR #32; Reviewer+QA auf 0510fcd, ci-local Evidence 20260907-194417) |
| DEPLOY-BUNDLE: Prod-Compose + Server-Dockerfile, getestetes Backup/Restore mit echter Integritaetspruefung, Monitoring-Doku, Release-Checkliste | komplett 2026-09-08 | fefe61d (t_bc138a04, PR #34 @a695ecb; Reviewer Stub-Runtime + Security-Negativprobe bestanden) |
| ADMIN-KI (PO-Spec 08.09.): Admin-Bereich nur fuer globale ADMIN-Rolle (Fresh-Read-Guard), KI-Provider-/Modell-CRUD in DB (.env nur noch Bootstrap), write-only Secrets (HKDF+GCM), RAG/Embedding-Wahl ohne Auto-Reindex, Systemstatus, SSRF-Guard default-dicht inkl. ULA/CGNAT, Start ohne Provider moeglich | komplett 2026-09-08 | d052e5e (t_0b34910a, PR #37 @ Produkt c18b6d0/Head 2fc81e4; ADR-0008 2cb839d; Reviewer R2 + Security empirisch, ci-local EXIT 0 Evidence 20260908-140621 — admin_flow_e2e 19/19 = QA-Nachweis laut PO-Spec ohne separates QA-Gate) |
| CHAT-POLISH (PO-go 09.09., UI-Freeze-Tauung NUR Chat-Panel): Markdown-Rendering hinter DOMPurify-XSS-Wand (http/https/mailto+, on* tot, Link-Interception), Copy/Retry-Aktionen, Code-Block-Copy, Quellen-Chips OHNE Roh-Score im Lesefluss, Empty-State-Chips, Stream-Caret | komplett 2026-09-09 | 82ebee7 (t_cfb8db22, PR #39 @ Produkt 48861a7/Head 067fd20; Reviewer+ci-local nachgebaut, Security-Gate vom Orchestrier direkt nach 3x Provider-400-Kaskade der Dispatcher-Laeufe (Protokoll auf t_d1f6a783), QA 12/12 Steps + XSS-Negativlegs an gebauter App) |
| DARK-FS (PO-Freigabe 09.09., UI-Freeze-Ausnahme): Dark-Mode-Token-Paar (html[data-ic-theme], alle Farb-/Schatten-Tokens dunkel ueberschrieben, keine Rohfarben ausserhalb theme.css) + theme-store (light/dark/system, localStorage, Pre-Paint-Skript gegen FOUC), Vollbild via Tauri setFullscreen (F11/Escape, Sidebar- + Login-Controls); Capability-Delta genau core:window:allow-set-fullscreen, Server-Diff 0 Dateien, Testids 172→174 ohne Loeschungen; Randfall dokumentiert: Theme-Persist ist geraetebasiert, nicht pro Nutzer | komplett 2026-09-09 | df270c6 (t_52b043aa, PR #41 @ Produkt 1b8cb7a/e419315, Head 9db23cb; Reviewer-Approval auf 9db23cb inkl. Nachbau dark 10/10 + dark-packaged 8/8; ci-local --with-e2e EXIT 0 Evidence 20260909-014006; QA t_3a6f26bc 49/49 Schritte unter echtem WM openbox an gepacktem Binary, 0 Findings) |
| RELEASE-GATE-1 (Linux-Anteile des Release-Gate-Durchlaufs, docs/RELEASE_CHECKLIST.md) | Linux-Gates abgearbeitet 2026-09-09 — KEIN Release-Schnitt; Restmenge ausstehend: echter compose-up auf Docker-Box, Win/mac-Builds auf PO-Build-Box, Signing/Notarisation, Gate 5 (PO-only) | a055642 (t_feb55c4f, PR #43 @ Produkt f4a8c81/Head 94ea4ad; Gate-1 ci-local --with-e2e EXIT 0 x2, Evidence 20260909-085544 auf 0fddde5 + 20260909-093000 auf f4a8c81, je 16 Stufen PASS; Versions-Beleg 0.1.0 via API + Sidebar-Pill im laufenden UI; deb SHA256 4fa337e0... QA 11/11 am entpackten Binary gegen geseedten Prod-Jar, t_c9c1c20d; G3-Komponentenlauf inkl. Backup/Restore + scratchdb-Gegenprobe; G4 secrets-frei; echter Befund gefixt: .env.production.example:97 Scrubber-Artefakt brach dotenv -> f4a8c81; Reviewer-Runde-2-Approval nach 17→16-Befund) |
| OIDC-M2-Tail (PO-Sequenz 09.09. Schritt 2): zweiter Login-Pfad OIDC auth code + PKCE — Server /api/v1/auth/oidc/* (providers/begin/callback/exchange, ADR-0009); Discovery-/Token-/JWKS-Dials durch SSRF-Guard; Client-Secret write-only nach ADMIN-KI-Muster; Rollen nur aus IntraCore-Membership, kein Auto-Provisioning; Fail-Closed ohne aktiven Provider ⇒ Button weg + Endpunkte 404; lokaler Argon2-Login unveraendert primaer | komplett 2026-09-09 | d4b04a0 (t_6e8e1a6c, PR #44 @ Produkt 355d55b/Head c47db30; ci-local --with-e2e EXIT 0 Evidence 20260909-164144, 18/18 Stufen inkl. e2e:oidc 28/28; Reviewer+Security APPROVED @ c47db30 — Security: 6 Checkpoints, 14/14 Negativlegs inkl. alg-Downgrade + SSRF-Verweigerung; QA GRUEN an echten Paketen, 6/6 Legs — Plattformgrenze WebKit-Handoff dokumentiert in ADR-0009 §7) |
| UI-REFRESH-1 Phase 1 (PO-'A' 09.09.): Tailwind v4 + shadcn-vue (reka-ui)-Primitive UEBER den bestehenden --ic-*-Tokens — Scope NUR Sidebar+Login; Koexistenz-Schicht ohne Preflight (source(none) + explizite @source-Zeilen auf die Migrationsflaeche), Theme-Bridge @theme inline auf var(--ic-*), data-ic-theme-Wechsel wirkt unveraendert; Altschicht (theme.css, AppButton/TextField) bleibt auf allen gefrorenen Flaeschen aktiv bis einer Phase-2-Migration; Belege: 26 Testids byte-identisch (Audit 14 Sidebar + 12 Login, keine Migration noetig), Vorher/Nachher-Screenshots 4/4 sha256-identisch + Geometrie 17/17 Controls ohne Befund, Bundle +3,19 kB gz selbst-gemessen (Reviewer; PR-Angabe +3,27 — Vite-Rundungstoleranz, Limit 60), Deps exakt tailwindcss/@tailwindcss/vite 4.3.3 + reka-ui 2.10.4 | komplett 2026-09-10 | 3310129 (t_6e87fabc, PR #47 @ Produkt cb4be94/Head f515b15; Binding-Gate ci-local --with-e2e EXIT 0, 8 e2e-Schrittsuiten 131/131 Steps (+ packaged-Build-Stufe ohne Steps, 18/18 Stufen gesamt), Evidence ci/evidence/20260910-025034, Lauf ueber inotify-Shim-Wrapper scripts/ci-local-e2e-gate.sh; Reviewer APPROVED @ f515b15 mit Execution-Lens selbst nachgebaut: Build, Testid-Audit, Screenshot-Recapture, gepacktes AT-SPI dark 8/8 + auth 7/7; QA t_f24171c9 Nutzerbrillen-Pass GRUEN 34/34 Schritte an zwei selbst gebauten Release-Binaries (base e38c859 vs. new f515b15) unter Xvfb+openbox+AT-SPI, Evidenz server/target/qa-t_f24171c9/) |
| UI-REFRESH-2 Phase 2 (PO-go 10.09. 'Mach 2'/'Ja'): Chat-Flaeche (ChatView/MarkdownAnswer) von scoped CSS auf Tailwind-Utilities/@utility ueber denselben --ic-Tokens migriert — Phase-1-Rezept, Button-Primitive wiederverwendet; ACs eigenverifiziert: 30 Chat-Testids maschinell alt==neu, 8/8 Vorher/Nachher-Screens PIXEL-IDENTICAL (hell+dark, empty/plain/rag/stream) + 110/110 Geometrie ohne Befund, DOMPurify-Wand unangetastet (Security-Browser-Probe), XSS-Negativlegs intact; Phase 3 (Knowledge-Flaeche) noch nicht angelegt — PO-Reihenfolge 'erst melden, dann nach Frage' | komplett 2026-09-10 | 28f57da (t_11f53ad9, PR #49 @ Produkt 83a9756/Head 42e8dd4; ci-local --with-e2e EXIT 0 Evidence 20260910-145537 18/18 Stufen, Reviewer APPROVED @ 42e8dd4 eigenverifiziert, Security t_fa5ade77 APPROVED alle 5 Checkpoints, QA t_b4a47f16 6/6 Legs an selbstgebauter Release-Binary) |
| OIDC-PFLEGE (PO-Karte 13.09.): nbf-Claim-Pruefung im ID-Token (OidcLoginService.validateClaims, dieselbe 60s-Leeway wie exp/iat, absent=gültig gemäss OIDC-Spec, fail-closed bei nicht-numerisch) + 6 adversarial Test-Legs; Doku-Pflege ROADMAP:72 OIDC-Häkchen (Ref d4b04a0) + ADR-0009 §4 | komplett 2026-09-13, gemergt 21.09. | 0fdbcf0 (t_d8fdaf51, PR #51 @ Produkt 885698f/Head daa74ce; ci-local --with-e2e EXIT 0 Evidence 20260913-111453; Reviewer APPROVED + Security-Gate t_dc5c86e1 APPROVED ohne Findings, je am finalen Head eigenverifiziert) |
| SERVER-PAKET (PO-Reihenfolge 14.11. Punkt 1) — IN ARBEIT auf Branch `claude/focused-goldberg-lfp1r0`, NICHT gemergt: pgvector-Dimension folgt `INTRACORE_EMBEDDING_DIM` bei leerem Index (PgVectorSchema, ADR-0010), Compose mit Ollama im Standard + `ollama-pull`-Job (qwen2.5:7b + bge-m3/1024), `compose.gpu.yml`, `deploy/install.sh`, Dockerfile-Fix (noble-User `ubuntu` belegte uid 1000 → Image-Build brach ab) | Teilbelege 14.11.: PgVectorSchemaTest + SearchStoreSelectionTest grün; echter Compose-Lauf (Docker) mit PG16+pgvector: Retype 64→1024 + `auto -> pgvector` + Health UP. OFFEN: Modell-Pull/echtes bge-m3 (Sandbox sperrt registry.ollama.ai), Upload+Suche bei 1024, ci-local-Gate, Reviewer/Security, Installation auf echter Kundenmaschine |
| GRUNDLUECKEN-SERVER (PO-Reihenfolge Punkt 3, Server-Teil) — auf Branch `claude/focused-goldberg-lfp1r0`, NICHT gemergt: DELETE Dokument (Uploader/OWNER), PATCH/DELETE Wissensbasis + Space + Organisation (OWNER, Kaskaden per SpaceDeletingEvent/OrgDeletingEvent ohne neue Paketzyklen), DELETE Mitglied (OWNER / Austritt, letzter OWNER 409), asynchrone Indexierung `INTRACORE_INDEXING_MODE=async` (PENDING/FAILED, V12, Prod-Compose async) + POST .../documents/{id}/reindex | nur kompiliert — Tests auf PO-Anweisung 14.11. ausgelassen. OFFEN: Tests/ci-local, Reviewer/Security, openapi.yaml-Nachtrag, Desktop-Bedienelemente (UI-Freeze, braucht PO-Wort), Paketzyklus admin<->gateway/chat (Umzug Provider-Domaene in eigenes Paket) |
| UI/UX-REDESIGN + BROWSER-FIRST (PO-Auftrag 14.11.: Redesign „Notion-inspiriert, eigenständig"; „auch für Browser, Browser first") — auf Branch `claude/focused-goldberg-lfp1r0`, NICHT gemergt. UI-FREEZE damit aufgehoben. ADR-0011 (Web-Bundle same-origin vom Server, Dockerfile-Node-Stufe). docs/design/REDESIGN-2026-11.md (Analyse, IA, Tokens). Umgesetzt: neue Tokens, Shell (Organisation/Arbeitsbereich-Umschalter, Chat · Wissen · Team · Administration), Wissen als Explorer (Tabelle, Klartext-Status, Upload Drag&Drop, Löschen/Umbenennen/Neu verarbeiten), Team (Org umbenennen/löschen, Mitglied entfernen, deutsche Rollen), Chat-Feinschliff | Typecheck (vue-tsc) grün; Browser-Sichtcheck per Playwright-Screenshots (hell, mobil). e2e-Suiten NICHT gelaufen und brauchen Anpassung (Start-Ansicht jetzt Chat, Org-Anlage unter Team, logout-button entfallen). OFFEN: Administration-Gruppierung, Login, Mobil-Navigation, Quellen-Sprung, CSP für Web-Auslieferung |

> **PO-AUSNAHME 14.11.2026:** Der PO hat den Squash-Merge dieses Branches nach `main` ausdrücklich
> angeordnet, OHNE dass die Gates aus AGENTS.md erfüllt waren: kein grüner vollständiger `ci-local`-Lauf
> auf dem finalen Head (der einzige Lauf war rot, die Ursachen wurden behoben und gezielt nachgetestet),
> keine Reviewer- und Security-Freigabe. Diese Gates sind auf `main` nachzuholen (Schritte 1–2 unten),
> bevor weitere Merges folgen.

### SCHRITT 5 begonnen (Claude, Cloud, 14.11.2026) — auf Wunsch des PO vor Abschluss der Pilotabnahme

Branch `codex/ds-datasources` (von main, 4b–4e übernommen): Verzeichnis (AD/LDAP) + Windows-Freigaben +
Linux-Ordner mit Rechte-Spiegelung und Einrichtungs-Assistent. Neue Abhängigkeit smbj 0.14.0 (Apache-2.0).
Migration V16. Nur Kompilieren/Typecheck geprüft. Prüfliste: `docs/tasks/DS-01-datasources.md`.
Nachgezogen am selben Tag: DS-5 Testfragen-Set (V17), Namen in der Rechte-Vorschau, Gruppen-Auffrischung
alle 15 min, DS-4 SharePoint Online (V18; PO-Entscheidung: Sites.Selected, Site-Gruppen → verbundene M365-Gruppe,
sonst gesperrt) inkl. Entra-Prinzipale beim OIDC-Login und `docs/howto/sharepoint.md`. Schritt 5 ist damit
als Code vollständig; offen sind ausschließlich Tests, Abnahme (Container-Fixtures, M365-Testtenant) und Security-Gate.

> **Übergabe an Codex:** `docs/HANDOFF-CODEX-2026-11-14.md` (Reihenfolge, Risiken, Prompt, nächste 10 Schritte).

### CHAT-PRODUKTIVITÄT (Claude, Cloud, 14.11.2026) — PO-Auftrag, Code fertig, Abnahme offen

Branch `codex/chat-productivity` (enthält `codex/ds-datasources`), Migrationen V19–V20: Antwort-Export (Word/PDF/E-Mail),
Vorlagen je Arbeitsbereich/Organisation, globale Suche (Strg+K), Antworten aus mehreren Wissensbasen, Datei im Chat.
Nur Kompilieren/Typecheck geprüft. Prüfliste: `docs/tasks/CHAT-01-productivity.md`.

**Nachgezogen 14.11.:** Websuche (CHAT-02, V21, Administration → Websuche) und MCP-Server Stufe 1 (V22,
Administration → MCP-Server; PO-Freigabe wörtlich „mcp", siehe `docs/tasks/MCP-01-mcp-server.md`). Stufe 2
(Werkzeuge im Chat, Freigabe je Arbeitsbereich, Protokoll; V23) als Code umgesetzt, Abnahme offen.

### STAND 4d/4e (Claude, Cloud, 14.11.2026) — Code fertig, lokale Abnahme durch Codex offen

- **4d** auf `codex/pilot-interface` (Head `454efed`): Team-Seite im Listenstil ergänzt. Prüfliste: `docs/tasks/PILOT-04-interface.md`.
- **4e** auf `codex/pilot-admin-ops` (von main, 4d per Merge übernommen): Daumen hoch/runter, Mitglied direkt
  hinzufügen mit Startpasswort + Pflicht-Passwortwechsel, Erste Schritte, Datensicherung in der Administration.
  Spec + Prüfliste: `docs/tasks/PILOT-05-admin-ops.md`. Migrationen V14, V15.
- In der Cloud nur Kompilieren/Typecheck geprüft — Tests, Build, Browser und echtes Backup macht Codex lokal.
- Danach: echte Pilotabnahme mit Kundendokumenten; erst dann Schritt 5 (ADR-0012).

### ÜBERGABE 14.11.2026 (Branch `claude/focused-goldberg-lfp1r0`, Head nach b751ada) — nächste Schritte

Stand: SERVER-PAKET, GRUNDLUECKEN-SERVER, BROWSER-FIRST (ADR-0011) und UI-Redesign v3 (Linear-inspiriert,
PO-Referenz) liegen auf dem Branch, NICHT gemergt, kein PR. Browser-e2e-Suiten an das Redesign angepasst und
lokal grün gelaufen (knowledge 11/11, chat 24/24, invite, admin, oidc, dark, mvp5). Ein `scripts/ci-local.sh`-Lauf
wurde gestartet, aber nicht abgeschlossen — es gibt dafür KEINEN gültigen Nachweis.

1. **CI-Gate:** `bash scripts/ci-local.sh --with-e2e` auf dem finalen Head komplett grün laufen lassen
   (Packaged-Suiten `auth`/`dark-packaged` brauchen `tauri build` + Xvfb; die suchen ggf. noch alte
   Bedienelemente — `logout-button`, `theme-toggle` als Button — und müssen wie die Browser-Suiten angepasst werden).
   Evidence unter `ci/evidence/<ts>/` committen.
2. **Reviewer- + Security-Gate** (Security nötig: neue DELETE/PATCH-Endpunkte, Org-Löschkaskade, async Indexing,
   Web-Auslieferung same-origin, Dockerfile-Node-Stufe). Danach Draft-PR und Merge nach AGENTS.md-Regeln.
3. **UI-Redesign Rest:** Team-, Administration- und Login-Seite im v3-Stil (Listen mit Status-Ringen wie Wissen);
   Mobil-Navigation (Sidebar als einblendbares Menü); Chat-Wissensbasis-Wahl in die Eingabekarte.
4. **Quellen-Vorschau:** Server-Endpunkt für Dokumentvorschau + Sprung zur zitierten Stelle (Alleinstellungsmerkmal).
5. **Echte Installation:** `deploy/install.sh` auf einer Maschine mit Internet (Modelle bge-m3 + qwen2.5:7b ziehen,
   Upload + Suche bei 1024 Dimensionen prüfen). In der Claude-Sandbox waren Modell-Registries gesperrt.
6. **Technik:** CSP für die Web-Auslieferung; Paketzyklus admin↔gateway/chat (Provider-Domäne in eigenes Paket);
   `contracts/openapi.yaml` um die neuen Endpunkte ergänzen; ci/evidence-Größe.
7. **PO-Entscheidung 14.11.2026 (Chat, Wortlaut):** auf die Frage nach schriftlicher Freigabe von
   „Fileserver/SMB- und SharePoint-Anbindung mit AD-Rechte-Spiegelung" antwortete der PO
   „ja und ldap auch linux biite mitdbedenken". Freigegeben ist damit NUR der Bereich **DS — Datenquellen**
   (SMB, Linux-Verzeichnisse/NFS, SharePoint; Rechte aus AD, generischem LDAP, Entra) gemäß ADR-0012 —
   NICHT die übrigen M5–M9-Ideen (Agents/MCP/Actions bleiben unfreigegeben). Windows-MSI/Signing seit ADR-0011 optional.
8. **CI-Befund 14.11.:** erster ci-local-Lauf rot (Server-Chunk 2: DocumentServiceTest NPE durch neue
   Indexing-Queue, FlywayBaselineMigrationTest zählte V12 nicht) — beides behoben und gezielt grün; der
   VOLLSTÄNDIGE Gate-Lauf auf dem finalen Head steht noch aus (Schritt 1).

Produkt-Code auf main = vollständiger Core-MVP: Login → Org → Space → KB →
Dokument (inkl. PDF/DOCX) → RAG-Suche mit Citations → Chat mit SSE-Streaming.

## NAECHSTE SCHRITTE (fixede PO-Reihenfolge, t_3b354559 §3)

1. ~~UI-POLISH-1 `t_596e3195`~~ KOMPLETT 2026-09-08 (10d5413, PR #32). **UI-FREEZE
   gilt weiter fuer ALLE anderen Flaechen (Wissen, Chat, Admin, Dokumente, Session):
   keine neuen UI-Flaechen, keine Polish-Tasks — nur echte UX-/Rendering-Bugs
   (PO-Regel 06.09.). Ausnahme 08.09.: ADMIN-KI-Flaeche wurde per PO-TASK
   ausdruecklich freigegeben (User-UI blieb unveraendert, nur additive Panels).
   Weitere Tauungen 09.09.: CHAT-POLISH (nur Chat-Panel, 82ebee7) und DARK-FS
   (Dark Mode + Full-Screen, PO-Instruktion 09.09.) — DARK-FS ist komplett und
   als df270c6 gemergt. UI-REFRESH-1 Phase 1 (PO-'A' 09.09.) war die dritte
   Tauung — NUR Sidebar+Login, gemergt 10.09. als 3310129. UI-REFRESH-2 Phase 2
   (PO-go 10.09. 'Mach 2') vierte Tauung — NUR Chat-Flaeche, pixel-identisch
   migriert, gemergt 10.09. als 28f57da (Statuszeile oben). Alles andere bleibt
   eingefroren; eine UI-REFRESH-Phase-3-Karte (Knowledge-Flaeche) ist NOCH NICHT
   angelegt und braucht zuerst das PO-Wort.**
2. Operator-Schritt AGENTS.md: ERFÜLLT 2026-09-07 (main@8043355, Grep=0).
3. ~~Deploy-Bundle~~ KOMPLETT 2026-09-08 (fefe61d, PR #34). Audit-Top-5 damit
   abgearbeitet (Parser ✅ CSRF/Invites ✅ lokales CI ✅ UI-Polish ✅ Deploy ✅).
4. **PO-Sequenz 09.09. (Board-Wahrheit, t_52b043aa-Kommentare + Folge-Karten):**
   [1] RELEASE-GATE (docs/RELEASE_CHECKLIST.md): Linux-Anteile erledigt 09.09.
   (a055642, Statuszeile oben). AUSSTEHEND und nicht erledigt: echter `compose up`
   auf einer Docker-Box (diese Box: nested rootless, Image-Extraktion blockiert —
   Beweis ci/evidence/RELEASE-GATE1-2026-09-09/g3-podman-pull-probe.log),
   Windows/macOS-Release-Builds auf PO-Build-Box (Achtung: tauri.conf.json
   bundle.targets=['deb'] — für Win/mac anpassen bzw. gezielt bauen), Code-Signing
   (Win) / Notarisation (mac) = PO-Punkt, Gate 5 (PO-Freigabe, Tag, Schnitt) =
   PO-only. Kleine technische Probleme im weiteren Gate-Durchlauf sind autonom
   durch den Developer fixbar (PO-Freigabe 09.09.), grosse nur mit PO.
   [2] ~~OIDC-M2-Tail~~ KOMPLETT 09.09. (Merge d4b04a0, PR #44 — Statuszeile
   oben). PO-Sequenz 09.09. Schritt 2 damit erledigt; Reviewer-, Security- und
   QA-Gate waren vor Merge gruen.
   Migration-Runbook (Dev-H2 -> PostgreSQL/pgvector auf Server): 'bald, nicht
   heute'. Objektstorage und Windows-Installer-Signing sind freigegeben als
   NICHT-Blocker (zur Wahl spaeter).
   UI-REFRESH (Tailwind + shadcn-vue ueber bestehenden --ic-*-Tokens, PO-Entscheid
   09.09. auf t_52b043aa): Phase 1 (Sidebar+Login) KOMPLETT — gemergt 10.09. als
   3310129 (PR #47, Statuszeile oben; Testid-Migration-AC erfuellt: 26/26 identisch,
   0 Suiten-Aenderungen). Phase 2 (Chat-Flaeche) KOMPLETT — gemergt 10.09. als
   28f57da (PR #49, Statuszeile oben; pixel-identisch, 30/30 Testids). Phase 3
   (Knowledge-Flaeche) ist NOCH NICHT angelegt — kommt erst als eigene
   PO-freigegebene Karte; nichts davon 'laeuft'.

## PO-ENTSCHEIDE (verbindlich, mit Datum + Anker)

- **18.11.2026, Chat „Angemeldet bleiben ergänzen“:** PO beauftragt Checkbox und Entfernung der 30-Minuten-Abmeldung, erlaubt Codex für diesen Auftrag und ausdrücklich Merge nach main. Angebot, den letzten nativen Check zu überspringen, wurde nicht benötigt: er war vor Abbruch grün fertig. Keine generelle Aufhebung der CI-/Review-Regeln. Umsetzung und Nachweise in PR #80.

- **18.11.2026, Chat – Merge PR #82:** PO weist nach dem Hinweis, dass Linux-CI und Reviewer/Security fehlen, an: „merge du“. Einmalige Ausnahme nur für PR #82; Gates sind damit nicht bestanden und werden nachgeholt.
- **18.11.2026, Chat – Vollständige Umbenennung:** Auf die Rückfrage, ob die technischen Kennungen trotz PR #78 ebenfalls umbenannt werden sollen (Option „Komplett“ mit Hinweis auf neue App-Kennung und Migrationsbedarf), antwortet der Inhaber „Ja ich will jetzt doch alles ändern“. Ersetzt den Satz „Technische Kennungen bleiben“ aus dem Eintrag zu PR #78; datentragende Namen bleiben laut ADR-0013.
- **18.11.2026, Chat – Produktname Keplura:** Nach der Beschreibung von PR #78 bestätigt der Inhaber „In Keplura passt durchuhren“. Auftrag zur Fertigstellung der Umbenennung; keine Ausnahme vom lokalen CI-Gate, kein Release/Deployment und keine Umbenennung des GitHub-Repositorys. Technische Kennungen bleiben zur Kompatibilität erhalten.

- **16.11.2026, Chat – einmalige Merge-Ausnahme PR #73, #74, #76:** Inhaber: „merge jetzt alles in main und lösche die branches ohne ci überspringe das jetzt“. Gemergt ohne unabhängige Reviewer-/Security-Prüfung (alle drei Klasse A) und ohne abschließenden `ci-local`-Lauf: #73 (Klartext-Gründe für gesperrte Linux-Dateien; letzter grüner Lauf `ci/evidence/PR73-20261116-170607/`), #74 (Rechte-Nachweis „Zugriff prüfen“; grüner Lauf `ci/evidence/ACCESS-01-20261116-172702/`), #76 (gehärteter Installer und Abnahmelauf; nur gezielte Tests: Installer 7/7, Abnahmeskript 5/5). Keine dauerhafte Regeländerung; die Prüfungen gelten als NICHT bestanden und sind nachzuholen, ebenso F-1 aus #70. Danach alle Branches außer `main` gelöscht; Codex-Nachweise als Tags `evidence/security-fixes` und `evidence/source-freshness` erhalten.

- **16.11.2026, Chat – schlanke Prüfung:** Inhaber: „wir testen einfach zu viel“, auf den Vorschlag (CI einmal pro PR am Ende, keine Wiederholung nach konfliktfreiem Angleichen, keine Demos ohne Auftrag, Nicht-root-CI-Umgebung, unabhängige Prüfung bei Klasse A bleibt): „Ja mache das und veranker das im Projekt das Codex auch weis“. Verankert in AGENTS.md § „Lean verification“ (gilt für alle Agenten inkl. Codex) und docs/development.md „Local CI gate“.

- **16.11.2026, Chat – Merge PR #70 (XLSX/PPTX) mit eingeschränkter Unabhängigkeit:** Codex-Produktcode unabhängig von Claude geprüft und freigegeben, `ci-local` grün auf `4274859` (520 Tests, Evidence `ci/evidence/PR70-20261116-163931/`). Die Review-Korrektur F-1 (Hyperlinks erlaubt, andere externe OPC-Ziele weiter abgelehnt, `dbaa5ea`) stammt vom Prüfer selbst und ist nicht unabhängig geprüft. Auf die Frage „Jetzt mergen“ oder „Auf Codex warten“ wählte der Inhaber „Jetzt mergen“. Offen: nachträgliche unabhängige Prüfung von F-1 (zusammen mit PR #73).
- **16.11.2026, Chat – NFS-Ordner vorerst gesperrt:** Nach Security-Befund S-3 (PR #71, hoch) liest PR #72 Linux-Ordner nur noch über lokale Dateisysteme ohne erweiterte ACL; NFS/CIFS/ZFS und Dateien mit erweiterter ACL bleiben fail-closed gesperrt. Auf die Empfehlung „Sperre akzeptieren, NFS später“ antwortete der Inhaber „okay fange an“. Bedingung: Admins sehen den Grund im Klartext (Folge-PR `claude/local-reader-reasons`). Sichere NFSv4-ACL-Auswertung erst auf Kundenbedarf als eigene Aufgabe. Windows-Freigaben (SMB) nicht betroffen.

- **15.11.2026, Chat – einmalige Merge-Ausnahme RISK-01 + OCR-01:** Der Inhaber: „mach den merge ohne nochmal zu testen“, auf Rückfrage „Beide“. Freigabe für die Branches `claude/oversharing-check` und `claude/ocr-scans` ohne Reviewer-/Security-/QA-Gate (beide Klasse A) und für OCR ohne erneuten `ci-local`-Lauf nach der Upload-Zeitlimit-Korrektur (letzter grüner Lauf: `ci/evidence/OCR-01-20261115-195447/`; RISK-01 grün auf dem finalen Code: `ci/evidence/RISK-01-20261115-193029/`). Keine dauerhafte Regeländerung; die Prüfungen gelten als NICHT bestanden und bleiben offen.

- **15.11.2026, Chat – risikobasierte Prüftiefe:** Auf den Vorschlag „Rechte, Security, Login, Datenquellen: CI grün plus unabhängige Prüfung; alles andere: CI grün plus kurzer Blick vom PO, dann mergen“ antwortete der Inhaber „Ja“. Klassen A (vertrauenskritisch, volle Gates) und B (Rest: `ci-local` grün + kurzer PO-Blick) sind in AGENTS.md § Hard prohibitions festgelegt; im Zweifel Klasse A. Lokales CI bleibt für jeden PR Pflicht. Ziel laut Inhaber: weniger Prüfrunden, mehr Features; kleine PRs zügig mergen.

- **15.11.2026, Chat / PR #64 – einmalige Merge-Ausnahme:** Der Inhaber beauftragte „push auf main“ und wählte anschließend ausdrücklich Option **2**: „Ausdrücklich eine einmalige Ausnahme von den fehlenden CI-/Review-Gates erteilen und den Gesamtstand übernehmen.“ Freigabe gilt für den konkret vorbereiteten Gesamtstand `a379c95` und dessen rein dokumentarischen Merge-Nachtrag. Übernahme auf `main@a6ae561` verifiziert. Kein grünes CI/Review behauptet, keine dauerhafte Regeländerung, keine Branch-Protection-Änderung, kein Release/Deployment. Offene Produktbefunde bleiben offen.


- Design: 'Soft Structure · Apple Refined' ist verbindlich, kein Redesign.
  (2026-09-05, docs/DESIGN.md, 450fb9f; bekräftigt 06.09. in t_596e3195)
- UI2-Chatspec: presentation-only, durfte M4 nicht vorziehen — erfüllt (M4b ist
  real gemergt). (PO 06.09., t_3b354559 §4)
- UI-Einfrieren nach UI-POLISH-1. (2026-09-06, t_596e3195 HARTE REGEL)
- Provider: Ollama, vLLM und gehostete OpenAI-kompatible APIs sind alle drei
  first-class; Produktions-Backend-Wahl bleibt PO-Entscheid.
  (2026-09-06, f726cc0/M4c t_38dd77a9, README "Current state")
- CI-Gate: lokales `scripts/ci-local.sh` ersetzt GitHub Actions als Merge-Pflicht;
  Actions = optionale Evidenz, Billing blockiert nie; Windows/macOS = Release-Gate,
  kein Per-PR-Gate. (PO-Entscheid 2026-09-06, t_71473c70, main@7e662f5,
  docs/development.md "Local CI gate (verbindlich)")
- Autonomie vor Eskalation: nicht blockieren bei Infra/Billing/wiederholbaren
  Fehlern; eskalieren nur bei Scope/Architektur/Geld/Irreversibel/Sicherheit.
  (PO 06.09., t_3b354559 §4)
- Kein zweiter Dev. (PO, geschlossen, t_3b354559 §4)
- M5–M9 (Connect/Connector/Integrations/Automation/Ecosystem): IDEA / NOT
  APPROVED — nichts davon darf ohne explizite PO-Freigabe gestartet, zerlegt
  oder getaskt werden. (PO-Direktive 2026-09-04, 521b71b, docs/ROADMAP.md)
- Merge/Release/Deploy: Auto-Squash-Merge nur durch Orchestrator `default` bei
  grüner Gate-Matrix auf finalem SHA; Release/Deploy/Force-Push PO-only.
  (AGENTS.md § Hard prohibitions + t_71473c70-Umschreibung)
- Open Source: Produktrepo `marcello-conocchia/Keplura` wird für eine spätere
  Veröffentlichung vorbereitet; Sichtbarkeit, Releases und Veröffentlichung nur
  nach ausdrücklicher PO-Freigabe. `keplura-web`/`keplura-website` bleiben privat
  und proprietär, kein Website-Code/-Design/-Asset ins Produktrepo. Software
  kostenlos ohne Paywall; kostenpflichtig nur optionale Dienstleistungen.
  (PO-Auftrag OPEN-SOURCE-01, 20.11.2026, PR #93)
- Lizenz: **Apache-2.0**. (PO, Chat 20.11.2026: „Apache-2.0“, PR #93)
- Öffentliches Repo mit sauberem Start: bisheriges privates Repo wird
  `marcello-conocchia/Keplura-intern` (Archiv), neues Repo
  `marcello-conocchia/Keplura`, Inhaber bleibt Owner/Maintainer;
  **Weiterentwicklung im neuen öffentlichen Repo**. Sicherheitsmeldungen nur
  über GitHub (keine E-Mail im Repo). Name „Keplura“ laut PO frei. App-Icon vom
  Website-Favicon — ausdrückliche Ausnahme von „keine Website-Assets“, nur für
  dieses Zeichen. (PO, Chat 20.11.2026; Plan `docs/OPEN_SOURCE_READINESS.md` §8–§9)
- PR #93 einmalig ohne vollen `ci-local.sh`-Lauf, Schnellprüfung der
  Build-Metadaten genügt (PO, Chat 20.11.2026: „Ja genau“). Keine generelle
  Änderung von Gate 1.

## BEKANNTE BLOCKER / GRENZEN

- Öffentliches Repo: Branch-Schutz für `main` noch nicht gesetzt (PO-Einstellung,
  `docs/OPEN_SOURCE_READINESS.md` §9). Nie aus einem Klon von `Keplura-intern`
  in dieses Repo pushen — die alte Historie darf hier nicht landen.

- Kein Produktblocker offen (Stand 10.09., verifiziert gegen offene Karten-Lane).
  Pflege-Watch-Items UI-REFRESH-1 (nicht-blockierend, aus Review t_6e87fabc +
  QA t_f24171c9): [a] Host fs.inotify.max_user_instances (derzeit 128, erschoepft)
  ist operatorseitig zu erhoehen — dann ist der Gate-Shim (ci/shims/no-inotify.c +
  scripts/ci-local-e2e-gate.sh) entbehrlich; bis dahin laeuft das bindende Gate auf
  diesem Host nur ueber den Wrapper. [b] ui/Label.vue ist in Phase 1 unbenutzt
  (Input.vue nutzt reka-ui Label direkt) — fuer Phase-2-Flaechen behalten, bewusst
  committet (Doku-Kommentar in der Datei). [c] .navidle/.navact sind generische
  Utility-Namen und damit moegliche Phase-2-Kollisionsquelle; die
  Konventionsregel (nie zwei Utilities fuer dieselbe Eigenschaft am selben Element,
  Zustands-Paare exclusiv) ist in desktop/src/styles/tailwind.css dokumentiert.
  [d] QA-Beobachtung nicht-blockierend: Kontrast des .env-Vorlagen-Buttons im
  dunklen Admin ~1.1-1.35 — Vor-Phase-1-Erbe (AdminPanel.vue byte-identisch base
  vs. new), gefrorene Flaeche; Kandidat fuer eine Kontrast-Karte in einer
  spaeteren PO-freigegebenen Phase-2-Runde, hier NICHT angelegt.
- GitHub-Billing: Actions können rot sein — blockiert nichts mehr (siehe CI-Gate-
  Entscheid oben; Bindung liegt bei ci-local-Evidenz unter ci/evidence/).
- Host: 2 CPU / 2048 pids → Server-Tests laufen nur im chunked Surefire-Modus von
  ci-local.sh (scripts/ci-local.sh Header, M4c-Diagnose); RAM knapp (QA-Apps >1 GB);
  .git/objects-Ownership-Drift nach Container-Recreation: Chown-Kur auf
  t_3b354559 §3 dokumentiert.
- OpenAPI-Contract: 23 Pfade in contracts/openapi.yaml@7e662f5 — am 07.09. gegen
  alle Server-Controller gemappt: pfad-level vollständig synchron (die "18 Pfade /
  28 Mappings"-Lücke aus der Kartennotiz 06.09. existiert nicht mehr; Zählung war
  Roh-Grep über Klassen-+Methoden-Prefixe). Offene Qualität: Contract wird nicht
  automatisch gegen Code geprüft (kein CI-Konsistenz-Check) — Kandidat für eine
  Folgekarte, kein Blocker.
- ~~OIDC (auth code + PKCE) als zweiter Login-Pfad~~ KOMPLETT 09.09. (PO-Sequenz
  Schritt 2) — Merge d4b04a0 (PR #44, alle Gates gruen; Statuszeile oben).
  (ADR-0002-slot gefuellt durch ADR-0009; ROADMAP M2-Tail-Haekchen "Login via
  OIDC" gesetzt 13.09. — Doku-Pflicht der ROADMAP-Pflegekarte erledigt.)
  Pflege-Watch-Items (nicht-blockierend, Folgekarte wert):
  ~~nbf-Claim-Pruefung ID-Token ergaenzen oder in ADR-0009 festhalten~~
  ERLEDIGT 13.09. (t_d8fdaf51: nbf-Pruefung mit derselben 60s-Leeway
  implementiert, optionaler Claim — absent bleibt gueltig; ADR-0009 §4
  nachgezogen; adversarial Legs in OidcLoginFlowIntegrationTest); optionaler
  Swap des Self-JWS-Verifiers -> nimbus-jose-jwt (ADR-0009 §4 autorisiert als
  kuenftige Vereinfachung); Deep-Link-Handoff-Manuelltest in GUI-DE wenn
  verfuegbar (Plattformgrenze WebKit, ADR-0009 §7).
- Objekt-Storage-Entscheidung offen, bevor Dateien den lokalen Disk sprengen
  (Richtung self-hosted, nicht SaaS — docs/ROADMAP.md M3-Hinweis); laut PO 09.09.
  kein Blocker fuer das naechste Release.
- Migration-Runbook Dev-H2 -> PostgreSQL/pgvector auf echtem Server: offen, laut
  PO 09.09. 'bald, nicht heute'.
- Deploy/Backup/Secrets/Monitoring/Signing fuer echte Kundenumgebungen: gebuendelt
  im DEPLOY-BUNDLE (fefe61d); Windows-Installer-Signing laut PO 09.09. kein
  Blocker. Rest = Release-Gate-Durchlauf (siehe NAECHSTE SCHRITTE).
- RBAC über OWNER/MEMBER hinaus und Self-Service-Registration: offen, braucht
  PO-Scope (ROADMAP M2-Tail). Registration wartete auf OIDC — OIDC ist seit
  09.09. komplett (d4b04a0), Registration ist damit entblockt, bleibt aber
  OFFEN und braucht eine eigene PO-Scope-Karte (NICHT fertig).

## REKONSTRUIERBARKEITS-SELBSTTEST (4 Fragen eines frischen Workers)

1. Was fertig ist? → STATUS-Tabelle oben, jede Zeile mit Commit/PR/Karten-Anker;
   Gegenprüfung: `git log --oneline origin/main` + gemergte PRs #1–#49.
2. Was kommt als Nächstes? → NAECHSTE SCHRITTE oben mit Karten-IDs; Gegenprüfung:
   Kanban `intracore` (RELEASE-GATE-1-Lane t_feb55c4f + Review + QA t_c9c1c20d
   done; OIDC t_6e8e1a6c done, gemergt als d4b04a0; UI-REFRESH-1-Lane t_6e87fabc
   + Review + QA t_f24171c9 done, gemergt als 3310129; UI-REFRESH-2-Lane
   t_11f53ad9 + Security t_fa5ade77 + QA t_b4a47f16 done, gemergt als 28f57da)
   + Abhängigkeitskanten.
3. Welche PO-Entscheidungen gelten? → PO-ENTSCHEIDE oben, je mit Datum + Anker.
4. Welche Blocker bestehen? → BLOCKER/GRENZEN oben; Gegenprüfung: offene Karten-
   Kommentare + `kanban_show` auf der aktuellen Orchestrier-Karte.
