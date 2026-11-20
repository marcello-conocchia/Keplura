# Übergabe an Claude Code — IntraCore, 14.11.2026

## Auftrag und Stopp

Der Inhaber hat wegen des Tokenbudgets gestoppt und diese Übergabe samt Push
verlangt. **Nicht ungefragt die gesamte Roadmap weiterlaufen lassen.** Bei
Fortsetzung zuerst den begonnenen Schritt **4d** fertigstellen, dann 4e.
Schritt 5 beginnt erst nach dem Hand-Upload-Piloten aus Schritt 4.

Basis bleibt `main@43302d3`. Es wurde **nichts nach main gemergt**. Alle neuen
Commits verwenden die vorhandene Git-Identität **Marcello Conocchia**.

## Branches und nachgewiesener Stand

| Branch | Stand | Letzter geprüfter Feature-Commit |
|---|---|---|
| `codex/pilot-installation` | 4a lokal abgenommen | `c55e9fe` |
| `codex/pilot-answer-quality` | 4b lokal abgenommen | `e8efb24` |
| `codex/pilot-source-preview` | 4c lokal abgenommen | `fce09e3` |
| `codex/pilot-interface` | **4d begonnen, WIP; nur Typecheck/Build geprüft** | siehe Branch-Head |

Jeder Branch wurde von main angelegt. B übernimmt zwei technische Fixes aus A
per Cherry-pick (`.dockerignore`, Shell-Zeilenenden). C integriert B als
Voraussetzung, D integriert C. **Die vollständigen Installer-Änderungen aus A
sind damit noch nicht in D enthalten.** Bei einer späteren Zusammenführung A
berücksichtigen; PROJECT_STATE-Konflikte nach dem aktuellen Sachstand auflösen.
Die ursprünglichen Commit-Hashes und getrennten Branches bleiben erhalten.

Specs: `docs/tasks/PILOT-01-installation.md` bis `PILOT-04-interface.md`.
Evidence mit Screenshots: `ci/evidence/PILOT-01-20261114/` bis `PILOT-03-20261114/`.
`docs/PROJECT_STATE.md` enthält den aktuellen Produktstand; diese Datei ergänzt
den konkreten Arbeits- und Laufzeitkontext.

## Was tatsächlich erledigt wurde

### 4a — echte lokale Installation

- Docker Desktop gestartet; echte Installation mit PostgreSQL 16/pgvector,
  Ollama, `bge-m3` (1024 Dimensionen) und `qwen2.5:7b` durchgeführt.
- NVIDIA RTX 3060 Ti wird verwendet. Modelle sind tatsächlich heruntergeladen.
- Server liefert das Browser-Bundle aus. Browser-Anmeldung, Upload, Indexierung,
  pgvector-Suche, echte korrekte Antwort mit Quelle, erneutes Laden, Neustart und
  erneuter Installer-Aufruf geprüft. Zugangsdaten und Daten bleiben erhalten.
- Installer auf Branch A: deutsche Fehlermeldungen, Engine-Prüfung, Portwahl,
  kein Admin-Passwort in Terminalausgaben, vorhandene Konfiguration erhalten.
- Fehlende `.dockerignore` ergänzt: lokale Secrets, node_modules, native Builds,
  Git und Laufzeitdaten gelangen nicht in den Build-Kontext. Kontrollbuild grün.
- 7 Installer-Regressionsfälle, 5 Tests des Abnahmeprüfers, 399 Server-Tests unter
  Linux grün. Windows-Symlink-Test scheitert an fehlendem OS-Recht; unter Linux grün.

### 4b — belegte Antworten

- Deutsche PostgreSQL-Volltextsuche (`german`) + pgvector per Reciprocal Rank
  Fusion, vollständige Tenant-Pins vor beiden Ranglisten.
- Lokaler CPU-Reranker: Hugging Face TEI `cpu-1.9.4`, Modell
  `BAAI/bge-reranker-v2-m3`, Revision gepinnt, internes Compose-Netz, persistenter
  Cache, kein veröffentlichter Port. Etwa 3–4 GB zusätzlicher RAM.
- Kandidaten mit Relevanz unter 0,5 werden verworfen; ein Reranker-Ausfall ist
  ein Fehler und wird nicht als Nichtfund ausgegeben.
- Bei fehlenden passenden Quellen: feste Antwort „Dazu habe ich nichts gefunden…“,
  leere Quellenliste, **kein Chat-Modellaufruf**, normale gespeicherte Unterhaltung.
- Abschnitte/Überschriften, echte PDF-Seiten und Quelltext-Offsets gespeichert
  (V13), durch Suche und Chat-Zitate weitergereicht. DOCX/TXT erhalten keine
  erfundenen Seitenzahlen. Alte Dokumente brauchen für neue Metadaten Re-Index.
- Echter Modelllauf 7/7 grün; direkte PostgreSQL-Probe mit irreführendem Vektor,
  deutschen Wortformen und allen vier Tenant-Pins grün; 407 Server-Tests grün.

### 4c — rechte Quellen-Vorschau

- Quellenklick im Chat oder Suchtreffer öffnet rechts die **Textansicht** des
  Dokuments, springt zur Seite/Passage und markiert die zitierte Stelle.
  Originaldownload bleibt erhalten; kein originalgetreues PDF-Layout behauptet.
- Begrenzter GET-Preview-Endpunkt mit Session und SQL-Tenant-Pins, maximal 21
  Textblöcken, Prüfung alter Positionen, no-store/nosniff, ohne Parser/Modellaufruf.
- Überlappung der Indexabschnitte wird in der Lesedarstellung entfernt, während
  die markierte Passage vollständig bleibt.
- Echte Browserprüfung: PDF-Seite 2, Escape und Fokusrückgabe, 390-px-Ansicht und
  Fokusbegrenzung. Script-Beispiel als Text sichtbar, 0 Script-Elemente im DOM.
- Frontend-Typecheck/Build, reales finales Server-Image und API geprüft;
  **412 Server-Tests in 56 Klassen, 0 Fehler, 0 übersprungen, unter Linux grün.**

## 4d — aktueller WIP, hier fortsetzen

Bereits geändert, mit `npm ci` und `VITE_SERVER_URL=same-origin npm run build`
erfolgreich geprüft; **noch nicht im Browser geprüft oder als Image gestartet**:

- `desktop/src/App.vue`: mobile Kopfleiste und einblendbare Sidebar, Schließen,
  Escape, Fokusführung und Schließen bei Navigation/Kontextwechsel.
- `ChatView.vue`: Wissensbasis-Select in der Eingabe. Wechsel bei offenem Chat
  erzeugt neue Unterhaltung und bewahrt Entwurf; Auswahl ohne offenen Chat gilt
  für die nächste Frage. Senden kann eine erste Unterhaltung anlegen.
- `LoginView.vue`: ruhigere Anmeldeseite, deutsche Verbindungszustände;
  englischen Offline-Anker im nativen E2E-Harness entsprechend angepasst.
- `AdminPanel.vue`: gemeinsame PageHeader/PageContainer, flachere Listen,
  Statusringe, deutsche Begriffe, zutreffender Hinweis auf aktive Installations-
  konfiguration statt falscher Behauptung, ohne DB-Provider funktioniere keine KI.

**Noch offen in 4d:**

1. Team-/Mitgliederseite im Listenstil fertigstellen; wurde in D noch nicht geändert.
2. D im Browser starten und tatsächlich prüfen: erste Frage ohne offenen Chat,
   Auswahl „Ohne Wissensbasis“, Wechsel zwischen zwei Wissensbasen bei offenem
   Chat **mit vorhandenem Entwurf**, Streaming-Sperre und Fehlerpfade.
3. Mobile Sidebar bei 390 px, Org-/Space-Wechsel, Navigation, Portal-Menüs,
   Escape/Fokus, Login, Team und Admin auf horizontales Überlaufen prüfen.
4. Admin-Formulare und weiterhin sichtbare Aktionen/Testids prüfen, sprachliche
   Reste in MembersPanel/Admin ergänzen. Native E2Es wurden NICHT ausgeführt.
5. Screenshots, Evidence, PROJECT_STATE aktualisieren und committen.

Den WIP nicht als fertig oder bereits ausgeliefert bezeichnen. Insbesondere
Composer-Zustandswechsel und neue mobile Navigation haben bisher nur den
Compiler-/Build-Check, keine Verhaltensabnahme.

## Laufende lokale Umgebung

- **Pilot: `http://localhost:18080/`**, aktuell Image
  **`intracore-server:pilot-preview`**, also der geprüfte Stand von 4c.
- Ältere Entwicklungsinstanz auf `http://localhost:8080/` läuft separat weiter
  (Java/H2); nicht mit der PostgreSQL-Pilotinstanz verwechseln.
- Compose-Projekt `intracore-prod`: `intracore-server`, `intracore-pg-prod`,
  `intracore-ollama-prod`, `intracore-prod-reranker-1`; Pull-Job beendet mit Exit 0.
- Volumes: `pgdata`, `storage`, `ollamamodels`, `rerankmodels` unter dem Projekt.
  Nicht löschen. Der separate Versuchscontainer `intracore-pilot-reranker` ist gestoppt.
- Lokale Zugangsdaten sind nur in ignorierten Dateien: `.devenv/local-login.json`
  und `deploy/.env.production` im ursprünglichen Checkout. Niemals ausgeben,
  committen oder in Handoff/Logs kopieren. Es wurden keine realen Secrets gepusht.
- Vor V13 wurden DB-Dump und Upload-Archiv im ignorierten `.devenv` des
  Antwortqualitäts-Worktrees gesichert. Es gibt noch kein UI-Backup/Restore.
- Es bestehen mehrere lokale Worktrees; **`git worktree list`** zeigt die Pfade
  und Zuordnung. Nicht blind einen schon in einem anderen Worktree belegten
  Branch auschecken. Keine uncommitteten Produktänderungen sollen zurückbleiben.

Für D ein neues Image bauen und bewusst die Pilotinstanz aktualisieren, z. B.
in PowerShell im Checkout mit der lokalen ENV-Datei:

```powershell
docker build -f deploy/Dockerfile.server -t intracore-server:pilot-interface .
$env:INTRACORE_SERVER_IMAGE = 'intracore-server:pilot-interface'
docker compose -f deploy/compose.prod.yml -f deploy/compose.gpu.yml --env-file deploy/.env.production up -d --no-build server
```

Die vorhandenen Live-Prüfer sind `deploy/quality-check.cjs`,
`deploy/source-preview-check.cjs` und `deploy/tools/HybridSearchProbe.java`.
Sie arbeiten mit echten Modellen; die Node-Prüfer erzeugen ausdrücklich eigene
Beispieldaten. Die Evidence enthält IDs der hier vorhandenen Pilot-Testdaten.

## Danach: 4e und Grenzen

- Erst-Admin-Onboarding: Organisation, Arbeitsbereich, erste Wissensbasis,
  Mitarbeiter einladen. Bestehende Einladung benötigt bisher ein existierendes
  Benutzerkonto; für einen realen ersten Mitarbeiter ist das bewusst zu klären
  bzw. mit einer sicheren Admin-Anlage zu lösen, nicht zu übersehen.
- Backup/Restore in der Admin-Oberfläche sichtbar und verständlich machen.
- Daumen hoch/runter unter Antworten, serverseitig gespeichert und autorisiert.
- Danach echte Pilotabnahme mit Kundendokumenten und Kundenumgebung. DNS/HTTPS
  und eine Installation auf einem Kundenhost wurden hier NICHT eingerichtet.
- Erst dann Schritt 5 nach ADR-0012 (AD/LDAP/Entra, SMB/DFS/NTFS, Linux/NFS/POSIX,
  später SharePoint; nur lesend, ACL-Filter in SQL, Einrichtungsassistent).

## Gates und Zusammenführung

AGENTS.md bleibt maßgeblich: keine Arbeit direkt auf main, kein Force-Push,
kein Release/Produktiv-Deploy. Die lokalen Serverläufe sind **kein vollständiger
`scripts/ci-local.sh --with-e2e`-Lauf**. Tauri/Packaged, unabhängige Reviewer- und
Security-Freigaben sind vor einem Merge noch offen. Der geerbte main-Stand aus
PR #52 hatte diese offenen Gates bereits. GitHub Actions ist nur Zusatzbeleg.

Keine Freigaben oder Tests erfinden. Bei Fortsetzung pro Feature committen,
deutsche verständliche Texte, bauen und starten und dem Inhaber jeweils einen
kurzen Bericht mit Screenshot geben. Jetzt ist auf Wunsch des Inhabers Stopp.
