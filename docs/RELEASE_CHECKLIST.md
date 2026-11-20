# Release-Checkliste (Beta / Release) -- Keplura

Verbindlich vor jedem Beta-/Release-Schnitt (PO-Entscheid 2026-09-06:
Cross-Platform-Builds sind RELEASE-Gate, nicht per-PR-Gate). Diese Liste
automatisiert nichts -- sie wird bei jedem Schnitt manuell abgearbeitet und
die Ergebnisse werden hier unten im Laufwerksblock dokumentiert.

Versionierung (Versionspolicy 0.1.0):
- SemVer-light `MAJOR.MINOR.PATCH`; vor 1.0.0 ist MINOR das
  Milestone-Baendel (M3=M3.x ...), PATCH sind Hotfixes.
- Eine Version, drei Orte, IMMER gleichziehen und commiten:
  `server/pom.xml` `<version>`, `desktop/package.json` `"version"`,
  `desktop/src-tauri/tauri.conf.json` `"version"`. Prueflauf:
  `GET /api/v1/system/info` liefert `serverVersion` aus dem gebauten Jar
  (Maven-Filter), der Desktop zeigt sie in der Session-Ansicht -- muss
  exakt der Checklisten-Version entsprechen.

## Gate 1 -- Bindendes CI-Gate (Linux)

- [ ] `bash scripts/ci-local.sh` Exit 0 auf dem FINAL-Commit-SHA des
      Release-Zweigs (Evidence unter `ci/evidence/<timestamp>/`, MATRIX.md
      im Release-Tag-PR verlinkt).
- [ ] `--with-e2e` (alle Suites: knowledge, chat, invite, auth/packaged)
      ist beim RELEASE-Schnitt Pflicht, nicht nur docs/compose-Diffs.

## Gate 2 -- Cross-Platform-Builds (PO-Release-Gate; pro Merge nicht noetig)

- [ ] **Linux**: `cd desktop && npm ci && npm run tauri build` ->
      `src-tauri/target/release/bundle/deb/Keplura_<version>_amd64.deb`
      (Release-Build mit LTO -- Zeit einplanen; debug-Buendel zaehlt nicht).
- [ ] **Windows**: dasselbe auf einem Windows-Host (WSL reicht NICHT fuer
      das .msi/.exe-Bundle; nativer Node + Rust required). Build-Box: PO.
      - [ ]Artefakte PRUEFEN: Installer startet, Login gegen Test-Server, ein
            Chat-Turn, Upload. Known-Quirk: origin `http://tauri.localhost`
            ist CORS-seitig bereits erlaubt (DesktopCorsConfig F2).
      - [ ] **Windows Code Signing: PO-PUNKT** (Zertifikat/Kosten offen;
            unsigned Builds nur an explizit informierte Tester ausliefern --
            SmartScreen-Warnung).
- [ ] **macOS**: nativer Build auf Apple-Hardware (`npm run tauri build` ->
      .app/.dmg). - [ ] **Notarisation/Developer-ID: PO-PUNKT** (Apple-
      Developer-Account noetig).
- [ ] Jede Plattform: Installationspfad aus `deploy/README.md` Abschnitt1.2
      durchgespielt, Ergebnis in den Laufwerksblock.

## Gate 3 -- Deployment-Bundle-Verifikation

- [ ] `deploy/compose.prod.yml` bootet auf einer SAUBEREN Maschine aus
      frischem Volume (`up -d --build`, /health 200) -- Host-Kategorie
      docker ODER podman-compose, aber nicht beide: verifiziert haben wir bisher nur
      CI-seitig. (Diese CI-Box konnte Image-Extraktion wegen verschachtelter
      Rootless-UID-Maps nicht -- dokumentiert im Evidence-Log; der Stack lief
      dort Komponenten-weise real: echtes PG16+pgvector, Prod-Jar, Smoke,
      Backup/Restore. Auf einer echten Docker-Box ist diese Luecke zu
      schliessen und hier abgehakt.)
- [ ] `scripts/backup.sh` + Gegenprobe `scripts/restore.sh --database
      scratchdb` auf einer Staging-Instanz lauffaehig; Backup-Kopie auf
      zweitem Medium.
- [ ] Reverse-Proxy + DNS + gueltiges TLS-Zertifikat fuer den echten Host
      konfiguriert (ohne TLS keine Anmeldung moeglich -- Pflicht: Secure-Cookie im Prod-Profil), Login
      der Desktop-App darueber getestet.
- [ ] Provider-Preset der Zielumgebung gesetzt + Modell gepullt
      (`--profile ollama` bzw. vLLM-Base-URL), Chat-Smoke gegen echten
      Upstream einmal von Hand.

## Gate 4 -- Sicherheit / Qualitaet

- [ ] security-review auf dem finalen SHA (Pflicht bei Aenderung an
      auth/secrets/inputs/network/deps -- sonst dokumentiert uebersprungen).
- [ ] qa-flow auf dem finalen SHA (Hauptflows: Login, Org/Space/KB,
      Upload->Suche, Chat mit Citations).
- [ ] Keine Secrets im Repo: `git log -p <range> | grep -iE
      'api[_-]?key|password.*[:=]'` manuell sichten (Scrubbing-Quirks der
      CI-Box dokumentieren, nicht verschweigen).
- [ ] Aktualisierte `.env.production.example` = echte Var-Menge des Servers
      (Diff gegen `application*.properties` ENV-Referenzen).

## Gate 5 -- Freigabe + Schnitt

- [ ] PO-Freigabe im Discord/Task-Kommentar (Beta vs. Release explizit).
- [ ] Tag `v<version>` auf dem finalen SHA pushen; GitHub-Release mit den
      Installer-Artefakten (deb jetzt; win/mac je nach Signing-Status).
- [ ] Update in `docs/PROJECT_STATE.md` (Statuszeile + PO-Entscheid-
      Liste, Pflegepflicht) -- niemals Agent-Memory als Source of Truth.
- [ ] Rollback-Pfad notiert: letzter guter Tag + `restore.sh --force` auf
      dem Backup vor dem Update (Steps in deploy/README.md Abschnitt6).

---

## Laufwerksblock (pro Schnitt ausfuellen; aeltere Eintraege bleiben stehen)

### 2026-09-09 -- RELEASE-GATE-1: erste Volllauf-Evidenz auf main@89338d3 + Branch feat/release-gate (NOCH KEIN Release-Schnitt; Tag/Gate 5 = PO)

Schnitt-Basis: main@89338d3; Branch feat/release-gate; Produkt-SHA-Strang
def6969 (Versions-Anzeige) -> 0fddde5 (.env-Diff) -> f4a8c81 (.env-Zeile-97-Fix).

- Gate 1 (bindend): `bash scripts/ci-local.sh --with-e2e` -> **Exit 0, alle
  16 Stufen PASS** (Zaehlung nach results.json/MATRIX.md; 52 Server-Klassen in
  5 Chunks, je genau einmal; desktop
  build; cargo check; e2e knowledge 15/15, dark 10/10, dark-packaged 8/8,
  chat 24/24, invite 20/20, admin 19/19, auth 7/7). Zwei Laeufe:
  `ci/evidence/20260909-085544/` auf 0fddde5 und `ci/evidence/20260909-093000/`
  auf f4a8c81 (finaler Code-SHA; .env-Zeile ist zur Skriptlaufzeit irrelevant,
  aber gruen ist gruen auf dem finalen Stand). MATRIX.md im PR-Body.
- Versionierung ("eine Version, drei Orte"): pom.xml=0.1.0, package.json=0.1.0,
  tauri.conf.json=0.1.0 — kein Drift, keine Aenderung. BELEG:
  `GET /api/v1/system/info` liefert `serverVersion 0.1.0` gegen das frisch
  gebaute Prod-Jar (e2e-dark-API-Bein + G3-Komponentenlauf 08:34/08:35), und
  die Sidebar-Pill zeigt `· v0.1.0` im laufenden UI
  (`e2e-dark/run.log: {"step":"sidebar-dark",...,"pill":"· v0.1.0"}`,
  Screenshots 04-dark-session.png). Desktop-Seite der Checkliste damit
  erfuellt (Session-Ansicht = Sidebar-Fusszeile).
- Gate 2 Linux: Release-Build (LTO=true, codegen-units=1, strip; kein debug)
  im intracore-dev-Container: `npm ci && npm run tauri build`. Artefakt:
  `desktop/src-tauri/target/release/bundle/deb/Keplura_0.1.0_amd64.deb`
  (4.251.038 B) SHA256
  `4fa337e04909fce60731a535308c4effa30fe4314f8da08dc0ec81244304e82f`.
  Integritaet: alle 4 md5sums des Pakets verifiziert OK; eingebettetes
  Frontend-Bundle = `index-ea3icpCT.js` = exakt das final gebaute Asset MIT
  Versions-Pill (Hash-Vergleich gegen dist/, sha256 des Bundle-Binaries
  5273a64b..., Abweichung zum Release-Binary nur 3 Build-ID-Bytes).
  dpkg-Installationslauf auf DIESE Box nicht pruefbar (kein root/sudo im
  Container, Installationspfad-Abschnitt 1.2 laeuft auf der Zielumgebung/
  QA-Karte).
- Gate 2 Windows/macOS: WARTEST AUF PO-BUILD-BOX (unangehakt, kein Fehler).
  Anleitung im PR-Kommentar "Windows/macOS Build-Beleg".
- Gate 3: podman-compose 1.0.6 + podman 4.9.3 diesmal installationsfrei aus
  /cache/debs extrahiert (`/tmp/rg1-pc`) — compose-CONFIG-RENDER gruen
  (22-Vars-Env, Fail-Fast-Fehlermeldungen intakt; Vorher-/Nachher-Diff:
  `g3-compose-config.out` zeigte dotenv-Warnung Zeile 96/97 ->
  `g3-compose-config-after-envfix.out` clean). Image-PULL/RUN erneut
  hart blockiert: nested rootless, `newuidmap: write to uid_map failed:
  Operation not permitted` (Beweis `g3-podman-pull-probe.log`) — eine echte
  Docker-Box steht AUS; das ist die ehrliche Restmenge, hier nicht
  beschoenigt. Komponenten-Nachweis real (wie DEPLOY-BUNDLE-Lauf, diesmal
  am Release-Zweig-Jar): PG 16.15 + pgvector 0.6.0 frisch initdb, Prod-Jar
  bootet dagegen (/health 200, system/info 0.1.0, Flyway 10/10 Migrationen),
  smoke_api.cjs 10/10 zweimal (fresh + nach Restore), backup.sh -> DROP
  DATABASE + Uploads wipe -> restore.sh stellt exakt-Zaehlungen 1/1/1/2 +
  Dokumentdatei wieder her, Gegenprobe restore.sh --database scratchdb aus
  demselben Archiv OK. Evidence: `ci/evidence/RELEASE-GATE1-2026-09-09/`.
  Reverse-Proxy/TLS + Provider-Preset-Zielumgebung bleiben PO-Umgebungs-
  leg (kein Ziel-Host auf dieser Box).
- Gate 4: Secret-Scan `git log -p df270c6..f4a8c81` (3.514 Zeilen, manuell
  gesichtet + Muster-Grep): keine Secrets — Fundstellen sind ausschliesslich
  ENV-NAMEN und Boot-Logs ohne Werte; e2e-Passwoerter nur via ENV, nie
  literal (Scrubber-Hinweis: diese Box ersetzt passwordartige Literale durch
  '***' — siehe Gate-4-BEFUND unten). `.env.production.example` gegen alle
  application*.properties-ENV-Hooks gediffed: 22 compose-reichte Vars waren
  dokumentiert, 28 Properties-only-Tuning-Hooks ergaenzt (Abschnitt 7).
  BEFUND (echtes Produkt-Problem, gefixt in f4a8c81): Zeile 97 der Vorlage
  war seit d052e5e ein Scrubber-Artefakt ('*** First-boot...' statt
  '# First-boot...') und brachte python-dotenv/podman-compose bei
  `--env-file`-Konsum zu Fall ('could not parse statement starting at line
  96'); Fix + sauberer Re-Render als Evidenz. Security-Review/QA-Review auf
  finalem SHA = Gate der Karten-Reviewphase (Reviewer/QA-Profile), nicht
  Gegenstand dieses Laufwerksblocks.
- Gate 5: NICHT bearbeitet — PO-only (Freigabe, Tag, GitHub-Release,
  Rollback-Notiz).

### 2026-09-08 -- DEPLOY-BUNDLE Erstfassung (noch kein Release-Schnitt)

- Gate 1: ci-local.sh (docs/compose-only Diff, ohne e2e) -> siehe PR
- Gate 3 compose-leg: diese CI-Box blockiert Container-Image-Extraktion
  (nested rootless, 1 UID) -- Komponenten-Pfad real getestet (PG16.15 +
  pgvector 0.6.0 + Prod-Jar + smoke_api.cjs 10/10 + backup->drop->restore
  Proof) in `ci/evidence/DEPLOY-BUNDLE-2026-09-08/`. Docker-Boot einer
  sauberen Maschine steht aus.
- Gates 2/4/5: offen, erst beim ersten Beta-Schnitt faellig.
