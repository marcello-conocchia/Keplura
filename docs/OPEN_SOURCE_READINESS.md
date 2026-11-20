# Open-Source-Bereitschaft — Keplura (OPEN-SOURCE-01)

Stand: 20.11.2026, Prüfbasis `main@ef37690` (#91) plus alle über GitHub
erreichbaren Referenzen (Branches, Tags, `refs/pull/*`).
Auftrag: Repository `marcello-conocchia/Keplura` für eine spätere
Veröffentlichung vorbereiten. **Nichts wurde veröffentlicht**, die
Sichtbarkeit ist unverändert privat, keine Historie umgeschrieben, kein Release,
kein Deployment.

## Abschließende Einschätzung: **READY — veröffentlicht am 20.11.2026** (ursprünglich NOT READY; alle Blocker entschieden und Umstellungsplan §9 umgesetzt, offen nur Branch-Schutz durch den PO)

Der Code selbst ist veröffentlichungsfähig: Im gesamten erreichbaren
Git-Bestand wurden **keine Zugangsdaten, Schlüssel oder Kundendaten** gefunden,
und alle Abhängigkeiten sind mit einer freien Lizenz vereinbar. Es fehlen aber
Entscheidungen des Product Owners, ohne die eine Veröffentlichung nicht
sinnvoll oder rechtlich unklar wäre (Blocker B2–B4; B1 Lizenz ist seit
20.11.2026 entschieden: Apache-2.0). Nach diesen Entscheidungen und den
Bereinigungsschritten in Abschnitt 5 ist das Repository bereit.

## 1. Blocker für eine Veröffentlichung

| Nr. | Blocker | Warum | Lösung | Wer |
|---|---|---|---|---|
| B1 | ~~Keine Lizenz~~ **erledigt** | PO-Entscheidung 20.11.2026 (Chat: „Apache-2.0“). | `LICENSE` (ASF-Originaltext), `NOTICE`, Lizenzfelder in `server/pom.xml`, `desktop/package.json`/`package-lock.json`, beiden `Cargo.toml`; README/CONTRIBUTING/CHANGELOG angepasst (dieser PR). | — |
| B2 | **entschieden 20.11.2026** (neues Repo mit sauberem Start, §8/§9) — ursprünglich: **Private E-Mail-Adresse in GitHub-PR-Referenzen** | 197 Commits in `refs/pull/*/head` (PR-Köpfe der Feature-Branches) tragen die private Absenderadresse des Inhabers als Autor, 2 weitere eine `@intracore.io`-Adresse im Co-Author-Trailer. Auf `main` sind alle Autor-/Committer-Adressen noreply; **eine** Commit-Nachricht (`6b6407c`, 07.09.2026) enthält im Co-Author-Trailer die `@intracore.io`-Adresse. Bei öffentlichem Repo sind alle PR-Köpfe für jeden abrufbar; GitHub lässt `refs/pull/*` nicht löschen. Der Remote-Branch `codex/remember-login` enthält ebenfalls 2 solche Commits. | Siehe Abschnitt 5, Option A/B/C. | PO |
| B3 | **erledigt 20.11.2026** (Name frei laut PO, Icon von der Website, siehe §8) — ursprünglich: **Rechte an Name und App-Icon ungeklärt** | Das App-Icon (`desktop/src-tauri/icons/`) stammt aus dem Gerüst vom 03.09.2026, die Herkunft ist nicht dokumentiert. Für den Namen „Keplura“ liegt im Repo keine Marken-/Kollisionsprüfung vor. Ein Open-Source-Produkt mit Dienstleistungsmodell lebt vom geschützten Namen. | Urheberschaft des Icons bestätigen oder Icon ersetzen; Markenrecherche/-anmeldung „Keplura“ prüfen; kurze Markenrichtlinie (Name darf für unveränderte Weitergabe, nicht für Forks/eigene Angebote genutzt werden). | PO |
| B4 | **entschieden 20.11.2026** (nur GitHub-Meldeweg; Einschalten beim Veröffentlichen, §9) — ursprünglich: **Meldeweg für Sicherheitslücken nicht aktiv** | `SECURITY.md` verweist auf GitHubs „Private vulnerability reporting“. Das muss in den Repo-Einstellungen eingeschaltet werden (geht erst sinnvoll beim Veröffentlichen); eine Rollen-Adresse (z. B. security@…) fehlt. | Beim Veröffentlichen aktivieren; optional Rollen-Postfach in `SECURITY.md` eintragen. | PO |

Keine weiteren harten Blocker. Die Punkte in Abschnitt 5 sind Empfehlungen,
die die Veröffentlichung professioneller machen, aber nicht verhindern.

## 2. Lizenzempfehlung und Abhängigkeitsprüfung

### 2.1 Empfehlung: **Apache License 2.0**

Vergleich für das Geschäftsmodell „Software kostenlos, Geld mit Installation,
Integration, Wartung und Support“:

| Kriterium | Apache-2.0 | MIT | AGPLv3 |
|---|---|---|---|
| Unternehmen dürfen frei installieren und anpassen | ja | ja | ja |
| Akzeptanz in Rechtsabteilungen von Unternehmen | sehr hoch | sehr hoch | **niedrig** — viele Unternehmen verbieten AGPL intern |
| Ausdrückliche Patentlizenz + Patent-Rückfallklausel | **ja** | nein | ja |
| Pflicht, eigene Änderungen offenzulegen | nein | nein | ja, auch bei Nutzung übers Netz |
| Schutz vor einem Wettbewerber, der Keplura als gehosteten Dienst anbietet, ohne Änderungen zu teilen | nein (Schutz über Marke) | nein | **ja** |
| Vereinbar mit allen geprüften Abhängigkeiten | ja | ja | ja (Apache-2.0/MIT/MPL/EPL-Komponenten sind mit GPLv3-Familie kombinierbar; EPL-2.0 nur dank Secondary-License-Klausel bzw. getrennter Bibliothek — vor Wahl gesondert prüfen) |
| Passt zu „keine künstliche Einschränkung“ | ja | ja | ja |

Begründung:

1. Kunden sind Unternehmen, die Keplura **selbst betreiben** (on-premise). Für
   sie ist eine Lizenz entscheidend, die ihre Rechtsabteilung ohne Rückfragen
   freigibt. Das leistet Apache-2.0 am besten; AGPL würde gerade die
   Zielgruppe abschrecken und den Vertrieb der Dienstleistungen bremsen.
2. Apache-2.0 enthält eine ausdrückliche Patentlizenz. Das schafft Vertrauen bei
   Unternehmen und schützt Beitragende; MIT bietet das nicht.
3. Die Hauptsorge bei permissiven Lizenzen — ein Dritter bietet das Produkt als
   eigenen Dienst an — trifft ein On-Premise-Produkt weniger. Wirksamer
   Schutz ist hier die **Marke** „Keplura“ (B3): Ein Dritter darf den Code
   nutzen, aber nicht unter diesem Namen auftreten.
4. Das Dienstleistungsmodell braucht keine exklusiven Rechte am Code. Wer
   später eine zusätzliche kommerzielle Edition plant, müsste dafür
   Beitragende-Vereinbarungen (CLA) einführen — das ist **nicht** empfohlen und
   widerspräche „keine versteckten Paywalls“.

Alternative, falls Schutz vor gehosteten Fremdangeboten wichtiger ist als
Unternehmensakzeptanz: AGPLv3. MIT nur, wenn maximale Einfachheit gewünscht
und auf die Patentklausel verzichtet wird.

**PO-Entscheidung 20.11.2026: Apache-2.0.** Umgesetzt in diesem PR:
`LICENSE` (ASF-Originaltext, gegen zwei unabhängige lokale Kopien
abgeglichen), `NOTICE` (Copyright „The Keplura Authors“, Hinweis auf Inter
OFL-1.1 und die Abhängigkeitsliste), Lizenzfelder in `server/pom.xml`,
`desktop/package.json` + `package-lock.json` (`"private": true` bleibt) und
den beiden `Cargo.toml`. Beiträge stehen nach Abschnitt 5 der Lizenz unter
Apache-2.0.

Noch offen: Beitragsregel — Empfehlung **DCO-Sign-off** (`git commit -s`)
statt CLA; Copyright-Inhaber in `NOTICE` ggf. auf eine Firma ändern, falls die
Rechte dort liegen sollen.

### 2.2 Abhängigkeiten (Laufzeit)

Vollständige Liste: [docs/legal/THIRD_PARTY_LICENSES.md](legal/THIRD_PARTY_LICENSES.md),
reproduzierbar mit `node scripts/third-party-licenses.cjs`.

| Komponente | Pakete | Lizenzen (deklariert) | Bewertung |
|---|---|---|---|
| Web/Desktop-UI (npm, ohne devDependencies) | 154 | MIT 128, MPL-2.0 12 (lightningcss, nur Build-Werkzeug), Apache-2.0 5, ISC 2, BSD-2/3, 0BSD, `MPL-2.0 OR Apache-2.0` (DOMPurify), OFL-1.1 (Inter-Schrift) | unkritisch |
| Desktop-Hülle (Rust, alle Zielplattformen) | 471 | überwiegend `MIT OR Apache-2.0`; MPL-2.0 (cssparser, selectors, option-ext …), Unicode-3.0, Zlib, ISC, BSD-3, CDLA-Permissive-2.0 (webpki-roots), `Apache-2.0 AND ISC` (ring), BSL-1.0-Option | unkritisch; MPL-2.0 ist dateibezogen und gilt nur für Änderungen an diesen Dateien |
| Lokaler Rechteleser (Rust) | 1 | `MIT OR Apache-2.0` | unkritisch |
| Server (Maven, Laufzeit) | 124 | Apache-2.0 (~92), EPL-2.0/EDL-1.0 (Jakarta, JAXB, AspectJ), `EPL-2.0 OR LGPL-2.1` (Logback), `EPL-2.0 OR GPL-2.0 WITH Classpath-Exception` (Jakarta-APIs), Bouncy Castle (MIT-artig), MIT, BSD-2/3, `MPL-2.0 OR EPL-1.0` (H2), `MPL-1.1 OR GPL-3 OR LGPL-3` (juniversalchardet), PostgreSQL-JDBC BSD-2 | unkritisch bei unveränderter Nutzung; bei Dual-Lizenzen gilt jeweils die freie, nicht-GPL-Option |

Keine Abhängigkeit steht nur unter GPL/AGPL, keine ist proprietär, kein Eintrag
„UNKNOWN“. Hibernate ORM 7.x ist Apache-2.0 (nicht mehr LGPL).

### 2.3 Schriften, Icons, Bilder, Fremdkomponenten

| Bestandteil | Herkunft | Lizenz | Befund |
|---|---|---|---|
| Schrift Inter | `@fontsource-variable/inter` (npm) | SIL OFL-1.1 | erlaubt; beim Ausliefern Lizenztext beilegen (NOTICE) |
| UI-Icons (`desktop/src/components/ui/AppIcon.vue`) | selbst gezeichnete Inline-SVG-Pfade | eigene | `logout` entspricht geometrisch dem Feather/Lucide-Icon „log-out“ (MIT/ISC). Sehr einfache Form, Risiko gering; vorsorglich Lucide/Feather in NOTICE nennen **oder** Pfad leicht ändern. |
| App-Icon (`desktop/src-tauri/icons/*`) | Gerüst-Commit 03.09.2026 | ungeklärt | **Blocker B3** |
| Screenshots in `docs/`, `ci/evidence/`, `desktop/e2e/evidence/` | eigene Testläufe | eigene | Stichprobe zeigt nur synthetische QA-Daten (Testorganisationen, `*.test`-Hosts), keine personenbezogenen Daten; keine Metadaten (PNG-Textblöcke/EXIF) in 910 Bildern |
| PDF/DOCX in `ci/evidence/CHAT-01-20261115/` | Export-Testergebnisse | eigene | Ersteller „Apache POI“, keine Personennamen |
| Container-Images (Compose) | pgvector/PostgreSQL, Ollama, HF text-embeddings-inference, vLLM, Eclipse Temurin, Ubuntu | PostgreSQL-Lizenz, MIT, Apache-2.0, Apache-2.0, GPLv2+CPE, diverse | Repo verteilt nur Dockerfiles/Compose, keine Images. **Falls später Images veröffentlicht werden**, enthalten sie GPL-Pakete (Temurin, Ubuntu, Tesseract-Abhängigkeiten) → Quelltext-Hinweise nötig. |
| Standardmodelle | `qwen2.5:7b`, `bge-m3`, `BAAI/bge-reranker-v2-m3` | Apache-2.0, MIT, Apache-2.0 | werden beim Kunden geladen, nicht verteilt. Hinweis: einige andere Qwen-2.5-Größen (3B, 72B) haben eine eigene Qwen-Lizenz — bei Modellwechsel prüfen. |
| Website-Code/-Assets (`keplura-web`, `keplura-website`) | — | — | **nicht enthalten**: keine Verweise, keine Website-Dateien, keine Marketing-Assets im Produktrepo gefunden. |

### 2.4 KI-generierter und übernommener Code

- Ein großer Teil des Codes entstand mit KI-Agenten (Claude, Codex, Hermes mit
  Qwen); auf `main` tragen 24 Commits einen Claude-, 17 einen
  Hermes-/Entwickler-Agent-Co-Author-Trailer; alle Commits sind unter dem
  Inhaber gemergt.
- Die Nutzungsbedingungen der kommerziellen Anbieter (Anthropic, OpenAI)
  übertragen Rechte an den Ausgaben auf den Nutzer; für den Hermes-Betrieb über
  Nous Portal und das offene Qwen-Modell sollte der PO das kurz bestätigen
  (Qwen-Lizenzen erheben keine Rechte an Ausgaben).
- Rechtlich offen (je nach Land): Rein maschinell erzeugte Teile sind ggf.
  nicht urheberrechtlich geschützt. Das schadet einer Open-Source-Freigabe
  nicht — die Lizenz deckt alles ab, woran Rechte bestehen —, ist aber bei
  Marken- und Haftungsfragen zu berücksichtigen. Keine Rechtsberatung;
  bei Bedarf Anwalt hinzuziehen.
- **Übernahme aus anderen Projekten:** Die Suche nach Herkunftshinweisen
  („copied from“, „adapted from“, Stack Overflow, bekannte Icon-Sets) ergab
  keinen übernommenen Fremdcode außer dem oben genannten Icon-Pfad. Ein
  automatisierter Abgleich gegen öffentliche Codebasen (Snippet-Scan) wurde
  **nicht** durchgeführt.

## 3. Sicherheitsbefunde

### 3.1 Vorgehen (wirklich ausgeführt)

1. Spiegel-Klon des GitHub-Repos inklusive aller Branches, Tags und 92
   PR-Referenzen (474 Commits, 3 923 einzigartige Dateiversionen, davon 3 100
   Text, 823 binär).
2. Mustersuche über **jede** Dateiversion der Historie: private Schlüssel,
   GitHub-/OpenAI-/Anthropic-/AWS-/Slack-/Google-/Hugging-Face-/Tailscale-Token,
   JWT, Bearer-Token, Discord-Webhooks und -Bot-Token, Zugangsdaten in URLs,
   Zuweisungen `password=`/`token=`/`api_key=`, persönliche Pfade, E-Mail-
   Adressen, IP-Adressen, interne Hostnamen, IBAN, Name des Inhabers.
3. Entropie-Suche nach zufällig wirkenden Zeichenketten ≥ 32 Zeichen
   (ohne Lockfiles und Hashes).
4. Prüfung aller jemals eingecheckten Dateinamen (`.env`, Schlüssel-,
   Zertifikats-, Datenbank-, Dump-Dateien).
5. Commit-Metadaten (Autoren, Co-Autoren) und Commit-Nachrichten.
6. Texte aller 92 Pull Requests inklusive Kommentare und Reviews (Issues: keine).
7. Bild-Metadaten aller 910 PNG/JPG, Dokument-Metadaten der PDF/DOCX,
   Sichtprüfung von 6 Screenshots aus Admin-, Team-, Verzeichnis- und
   Datenquellen-Ansichten.

Werte wurden dabei nie ausgegeben, nur maskiert (erste 4 Zeichen + Länge).

### 3.2 Ergebnis

| Nr. | Befund | Schwere | Ort | Empfehlung |
|---|---|---|---|---|
| S1 | **Keine echten Zugangsdaten gefunden.** Alle Treffer sind Platzhalter (`change-me-before-use`, `replace…`), Test-Fixtures (`sekret…` gegen Mock-Server) oder Variablennamen. Nie eingecheckt: `.env`, `.env.production`, Schlüssel, Zertifikate, Datenbanken. `desktop/.env` enthält nur `VITE_SERVER_URL=http://localhost:8080`. | — | — | Keine Rotation nötig. Vorsorglich gilt: Sollte je ein Schlüssel außerhalb von Git (z. B. Chat, Discord, Agent-Logs) geteilt worden sein, diesen rotieren. |
| S2 | Private E-Mail-Adresse des Inhabers als Commit-Autor (197×) und eine `@intracore.io`-Adresse als Co-Author (2×) | mittel (Datenschutz) | Autor: nur `refs/pull/*`, Remote-Branch `codex/remember-login`, lokale Feature-Branches. Co-Author: auch 1 Commit-Nachricht auf `main` (`6b6407c`) | siehe B2 / Abschnitt 5 (Option A entfernt beides) |
| S3 | Interne Infrastrukturdetails: absoluter Host-Pfad des Agenten-Servers (`/home/<agent-user>/workspaces/…`), Agenten-Setup (Hermes, Nous-Modell, Discord, Kanban-Board) | niedrig | `AGENTS.md`, `docs/adr/0013…`, 9 Dateien in `desktop/e2e/`, ~400 Logdateien in `ci/evidence/` | Kein Geheimnis, aber Angriffsfläche-Information und unprofessionell; Pfade in Skripten durch Variablen ersetzen, AGENTS.md für die Öffentlichkeit neutralisieren (Abschnitt 5). |
| S4 | Name des Inhabers in internen Dokumenten (Handoffs, ADR-0002, `docs/design/apple-refined.html` mit Beispieladresse `marcello@acme.de`) | niedrig | `docs/HANDOFF-*.md`, `docs/design/` | Vertretbar (Projektinhaber); Mock-Adresse auf `@example.com` ändern. |
| S5 | Test-Fixtures mit echten Domains statt reservierter: `ilkay@istanbul.com` (Locale-Test), `acme.de` | niedrig | `UserRepositoryTest`, `apple-refined.html` | Auf `example.com`/`.test` umstellen (Folgeaufgabe, Testcode → eigener PR). |
| S6 | Lokale, nicht ignorierte Ordner `.claude/`, `.playwright-mcp/`, `.worktrees/` mit Agenten-Zustand und Browser-Mitschnitten | mittel (Risiko versehentlicher Commit) | Arbeitskopie | **behoben in diesem PR**: in `.gitignore` aufgenommen. |
| S7 | GitHub-Actions-Workflow ohne explizite Token-Rechte | niedrig | `.github/workflows/ci.yml` | **behoben in diesem PR**: `permissions: contents: read`. Trigger ist `pull_request` (nicht `pull_request_target`), Fork-PRs bekommen also keine Secrets — passt für ein öffentliches Repo. |
| S8 | Web-Oberfläche ohne globale Content-Security-Policy, Desktop-CSP `null` | mittel (Produkt) | ADR-0011-Folgepunkt | Bekannt; als offene Grenze in `docs/OPERATIONS.md` und README dokumentiert. Eigene Klasse-A-Aufgabe vor einer Beta. |
| S9 | Dev-Profil (H2): Wissen an Chat anhängen schlägt fehl (`BadSqlGrammarException`, HTTP 500) — beim Erstellen der Screenshots beobachtet; mit PostgreSQL funktioniert es | niedrig (nur Entwicklung) | `/api/v1/orgs/*/chat/conversations/*/context` | Externe Entwickler stoßen beim Ausprobieren darauf → eigene Aufgabe; bis dahin in `docs/OPERATIONS.md` dokumentiert. |

Grenzen der Prüfung: Bilder wurden nicht vollständig per Texterkennung
untersucht (Stichprobe 6 von 910, alle Metadaten geprüft); Mustersuche findet
nur bekannte Formate; Inhalte außerhalb von GitHub (Discord, Kanban,
Agent-Speicher) waren nicht Teil der Prüfung.

## 4. Geprüfte Installations- und Build-Prozesse

| Prozess | Geprüft | Ergebnis |
|---|---|---|
| Server bauen `mvn -B package -DskipTests` (Windows, JDK 21.0.12, Maven 3.9.16) | ja, 20.11.2026 | Exit 0, `keplura-server-0.1.0.jar` |
| Server starten (dev-Profil, H2) + Web-UI `npm run dev` + Anmeldung | ja | funktioniert; Fehler S9 beim Wissen-Anhängen |
| Server gegen PostgreSQL 16 + pgvector mit Flyway-Migrationen (`ddl-auto=validate`), Upload von 4 Dokumenten, Indexierung, Chat mit Quellen über einen lokalen Modell-Ersatz | ja | funktioniert; Screenshots in `docs/assets/screenshots/` stammen aus diesem Lauf |
| Lizenzinventar `node scripts/third-party-licenses.cjs` (npm, `cargo metadata --locked` für beide Rust-Projekte, `mvn dependency:list`) | ja | Exit 0, Ergebnis eingecheckt |
| `deploy/install.sh` auf frischem Linux-Host | **nein** (dieser Lauf auf Windows) | zuletzt belegt durch Update-Probe 18.11.2026 und Pilot-Läufe laut `docs/PROJECT_STATE.md`; vor der Veröffentlichung einmal auf frischer VM mit der neuen `docs/INSTALL.md` durchspielen |
| `scripts/ci-local.sh` | **nein** (PO-Entscheidung 20.11.2026, Chat: „Ja genau“ auf den Vorschlag, den vollen Lauf durch eine Schnellprüfung zu ersetzen) | Der PR ändert keine Produktlogik, nur Texte, Lizenz-Metadaten in Build-Dateien, ein eigenständiges Skript und die Workflow-Rechte. Stattdessen auf `a94b64c` geprüft: `mvn validate` ok; `cargo metadata --locked` für beide Rust-Projekte ok (Lizenz Apache-2.0); `npm ci --dry-run` ok, Lizenz in `package.json` und Lockfile gleich; Workflow-`permissions` vorhanden; `node --check` für das Skript ok. Ein voller Lauf wurde zweimal gestartet und jeweils abgebrochen, er liefert **keinen** Nachweis. |
| Desktop-Pakete (`npm run tauri build`) Windows/macOS/Linux | **nein** | Release-Gate laut `docs/RELEASE_CHECKLIST.md` |
| GitHub Actions | nicht ausgelöst | Bei öffentlichem Repo sind Actions-Minuten für Standard-Runner kostenlos — der frühere Abrechnungsgrund für das lokale Gate entfällt dann. Der Workflow ist für Fork-PRs geeignet. |

Release-Prozess: neu beschrieben in [docs/RELEASING.md](RELEASING.md)
(Versionsstellen, Tag-Schema, Gates, Prüfsummen, empfohlene SBOM, Pinning der
Basis-Images).

## 5. Erforderliche und empfohlene Bereinigungsschritte

Keine dieser Aktionen wurde ausgeführt, wenn sie Historie, Remote oder
Nachweise verändert. Reihenfolge = Empfehlung.

1. ~~Lizenz entscheiden (B1)~~ — erledigt: Apache-2.0, `LICENSE`/`NOTICE` in diesem PR.
2. **Umgang mit der Historie** (B2). Optionen:
   - **A (empfohlen): Neues öffentliches Repository mit sauberem Start.**
     Den freigegebenen Stand von `main` als ersten Commit (oder `main` ohne
     PR-Referenzen per frischem Push) in ein neues Repo, z. B.
     `keplura/keplura`, übernehmen. Das private Repo bleibt als Archiv mit
     allen Nachweisen. Vorteil: keine PR-Referenzen, keine internen
     Diskussionen, keine Historie mit privaten Adressen; schnellster und
     sicherster Weg. Nachteil: öffentliche Historie beginnt neu.
   - **B: `main`-Historie öffentlich, ohne PRs.** `main` (87 Commits,
     Autoren nur noreply) in ein neues Repo pushen. Behält die Historie, lässt
     PR-Köpfe und Diskussionen privat. Die `@intracore.io`-Adresse in der
     Nachricht von `6b6407c` bliebe sichtbar (Entfernen nur per
     Historien-Umschreibung — PO-Entscheidung).
   - **C: Dieses Repo öffentlich schalten.** Alle 92 PR-Texte, Reviews,
     PR-Köpfe (mit privater Adresse) und Branches werden öffentlich.
     Private Adressen ließen sich nur durch Löschen und Neuanlage des Repos
     entfernen. **Nicht empfohlen.**
   Bei A oder B zusätzlich: in GitHub „Keep my email addresses private“ und
   „Block command line pushes that expose my email“ aktivieren.
3. **Marke und Icon klären** (B3).
4. **Interne Agenten-Dokumentation trennen.** `AGENTS.md`, `docs/HANDOFF-*.md`,
   `docs/tasks/`, `docs/reviews/` und `docs/PROJECT_STATE.md` beschreiben den
   internen Agentenbetrieb (Hermes, Discord, Kanban, PO-Entscheidungen).
   Empfehlung: im öffentlichen Repo eine neutrale `AGENTS.md` (Regeln für
   Beitragende/Coding-Agenten ohne Host-Pfade, Modell- und Discord-Angaben);
   Handoffs und Agenten-Betriebsregeln im privaten Repo belassen. Reviews und
   Aufgaben-Specs dürfen öffentlich sein (sie zeigen Qualität), wenn
   gewünscht.
5. **Nachweise verschlanken.** `ci/evidence/` (1 495 Dateien, 66 MB),
   `desktop/e2e/evidence/` (4,8 MB) und ~330 Screenshots in `docs/design/`
   (24 MB) sind interne Prüfbelege. Empfehlung für das öffentliche Repo:
   nicht übernehmen, sondern im privaten Archiv behalten bzw. künftig als
   CI-Artefakte oder Release-Anhänge ablegen; im öffentlichen Repo nur die
   aktuellen Design-Referenzen (`docs/design/system-v1/`) und
   `docs/assets/screenshots/`. Nichts davon wurde gelöscht.
6. **Kleinigkeiten (eigene kleine PRs, Klasse B):** persönliche Host-Pfade
   in `desktop/e2e/*.sh`/`*.py` durch Variablen ersetzen; Beispieladressen in
   `apple-refined.html` und `UserRepositoryTest` auf reservierte Domains;
   `logout`-Icon-Herkunft klären; S9 (H2-Fehler) beheben.
7. **Beim Veröffentlichen in GitHub einschalten:** Private vulnerability
   reporting, Dependabot-Sicherheitswarnungen, Secret Scanning mit Push
   Protection, Branch-Schutz für `main` (Review-Pflicht, Status-Checks),
   Issue-Vorlagen, Discussions (optional).
8. **Optional:** `CODE_OF_CONDUCT.md` (z. B. Contributor Covenant 2.1),
   englische Fassung der wichtigsten ADRs.

## 6. Öffentliche Dokumentationsstruktur (in diesem PR)

| Datei | Inhalt | Status |
|---|---|---|
| `README.md` | Produktbeschreibung, Funktionen, Screenshots im Dunkelmodus, Architektur (Mermaid), Schnellstart Server und Entwicklung, Grenzen, Dienstleistungsmodell, Lizenzhinweis | neu geschrieben (Englisch) |
| `docs/INSTALL.md` | Voraussetzungen, Installer, TLS, erste Schritte, Desktop-App, Modellwahl, Prüfung, **Update**, Deinstallation | neu |
| `docs/OPERATIONS.md` | **Hardwareanforderungen**, Dienste und Logs, Monitoring, Backup/Restore, Sicherheitshinweise, **bekannte Einschränkungen** | neu |
| `CONTRIBUTING.md` | Aufbau, Einrichtung, Richtlinien, Tests, `ci-local.sh`, PR-Prozess mit Risikoklassen | neu |
| `SECURITY.md` | vertraulicher Meldeweg, Reaktionsziele, Geltungsbereich | neu (Meldeweg muss aktiviert werden, B4) |
| `CHANGELOG.md` | Keep-a-Changelog, Unreleased + Vorabhistorie | neu |
| `.env.example` | alle für Entwicklung relevanten Variablen mit sicheren Platzhaltern | neu |
| `docs/RELEASING.md` | reproduzierbarer Release-Ablauf | neu |
| `.github/pull_request_template.md` | PR-Vorlage mit Risikoklasse und Prüfliste | neu |
| `docs/legal/THIRD_PARTY_LICENSES.md` + `scripts/third-party-licenses.cjs` | Lizenzinventar, reproduzierbar | neu |
| `LICENSE`, `NOTICE` | Apache License 2.0, Copyright- und Drittanbieter-Hinweis | neu (PO-Entscheidung 20.11.2026) |
| `deploy/README.md`, `docs/development.md`, ADRs | bestehende ausführliche (deutsche) Doku | unverändert, verlinkt |

Eine fremde Person kann mit `README.md` → `docs/INSTALL.md` einen Server
aufsetzen und mit `CONTRIBUTING.md` → `docs/development.md` lokal entwickeln.
Die Sprachmischung (öffentliche Einstiegsdoku Englisch, Detail-Doku und UI
Deutsch) ist in README genannt.

## 7. Open-Source-Strategie (Umsetzung im Text)

- README: „free to use, no paid edition, feature gates or usage limits“;
  bezahlte Installation, Integration, Wartung und Support sind optional und
  von unabhängigen Anbietern inklusive des Maintainers möglich.
- Keine Funktion ist im Code oder in der Doku als „kostenpflichtig“ markiert;
  keine Lizenzschlüssel-/Feature-Flag-Mechanik vorhanden (geprüft).
- Website (`keplura-web`, `keplura-website`) bleibt privat; README verlinkt
  bewusst noch keine Website. Nach Freigabe kann ein Satz mit Link zur
  Anfrage von Dienstleistungen ergänzt werden.

## 8. PO-Entscheidungen erforderlich

1. ~~Lizenz~~ — entschieden 20.11.2026: **Apache-2.0**.
2. ~~Historie/Repository~~ — entschieden 20.11.2026 (Chat): **neues
   öffentliches Repo mit sauberem Start**. Das bisherige private Repo wird in
   `marcello-conocchia/Keplura-intern` umbenannt (Archiv mit voller Historie,
   PRs und Nachweisen); das neue Repo heißt `marcello-conocchia/Keplura`, der
   Inhaber bleibt Owner und Maintainer. **Weiterentwickelt wird im neuen
   öffentlichen Repo.**
3. ~~Marke und App-Icon~~ — entschieden 20.11.2026 (Chat): Name „Keplura“ ist
   nach eigener Prüfung des PO frei; keine Markenanmeldung beauftragt. App-Icon
   wird vom Website-Favicon (`keplura-web/public/favicon.svg`, eigenes Werk des
   Inhabers) übernommen — **ausdrückliche PO-Ausnahme** von der Regel „keine
   Website-Assets im Produktrepo“, gilt nur für dieses Zeichen. Quelle liegt als
   `desktop/src-tauri/icons/app-icon.svg` bei.
4. Interne Agenten-Doku und Nachweise — abgeleitet aus Punkt 2 (Entwicklung
   öffentlich): `AGENTS.md`, `docs/PROJECT_STATE.md`, Aufgaben-Specs, Reviews
   und Handoffs gehen mit, weil sie im neuen Hauptrepo verbindlich bleiben.
   Nicht übernommen werden nur die historischen Prüfprotokolle
   `ci/evidence/` und `desktop/e2e/evidence/` (≈ 71 MB, ~400 Logs mit
   Host-Pfaden); sie bleiben in `Keplura-intern`. Verweise darauf in älteren
   Dokumenten zeigen ab dann ins Archiv.
5. ~~Sicherheitskontakt~~ — entschieden 20.11.2026 (Chat): **nur
   GitHub-Meldeweg**, keine E-Mail-Adresse im Repo.
6. Beitragsregel: **DCO (Empfehlung)** oder CLA — offen, blockiert nicht.

## 9. Umstellungsplan (Stand 20.11.2026)

| Schritt | Wer | Status |
|---|---|---|
| App-Icon ersetzen, Entscheidungen dokumentieren (PR im privaten Repo) | Claude | erledigt (Archiv #95, `0c6e1c4`) |
| Hermes-Agenten pausieren | PO | erledigt (PO-Bestätigung 20.11.2026) |
| Privates Repo in `Keplura-intern` umbenennen | Claude nach PO-Go | erledigt 20.11.2026 |
| Neues Repo `Keplura` **privat** anlegen, bereinigten Stand von `main` als ersten Commit pushen (Autor und Committer: Inhaber, noreply-Adresse) | Claude | erledigt: ein Commit, Inhalt und Dateirechte identisch mit Archiv-`main` ohne `ci/evidence/` und `desktop/e2e/evidence/` |
| Lokaler Klon des Windows-Rechners auf `Keplura-intern` umgestellt; neue Arbeit in frischem Klon des neuen Repos | Claude | erledigt |
| Hermes-Host: alten Arbeitsordner beiseitelegen und das neue Repo **frisch klonen** (nicht nur Remote ändern — ein alter Klon würde beim Push die alte Historie ins neue Repo laden); danach Agenten fortsetzen | PO / Plattform | erledigt (PO-Bestätigung 20.11.2026) |
| Offener Archiv-PR #92 übertragen | Claude | erledigt: neuer Draft-PR #1, Archiv-#92 geschlossen |
| Sichtbarkeit auf **öffentlich**; Private vulnerability reporting, Secret Scanning mit Push Protection, Dependabot-Warnungen und -Sicherheitsupdates einschalten | Claude nach PO-Go („Yes, go public“) | erledigt 20.11.2026: vorher alle Referenzen erneut geprüft (5 Commits, nur Inhaber/noreply, keine Zugangsdaten); ohne Login erreichbar, GitHub erkennt Apache-2.0; Archiv bleibt privat |
| Branch-Schutz für `main` | PO (AGENTS.md: Agenten schalten keinen Branch-Schutz) | offen — Empfehlung: nur per PR, kein Force-Push, kein Löschen; keine Pflicht-Freigabe (Inhaber kann eigene PRs nicht freigeben) |
