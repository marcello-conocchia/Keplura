# Deploy -- Keplura ausliefern und betreiben

Dieses Verzeichnis enthaelt die Auslieferungsarte f **Server + Desktop** fuer
einen echten Betrieb (DEPLOY-BUNDLE, t_bc138a04):

| Datei | Zweck |
|---|---|
| `compose.prod.yml` | **Produktions-Stapel**: PostgreSQL 16+pgvector persistent, Server-Image (Build aus diesem Repo), HEALTHCHECKS, Restart-Policies, Volumes, Ports nur loopback |
| `Dockerfile.server` | Server-Image: MVN-Build im Image-Container (Stage 1), JRE-Runtime + Nicht-Root-User (Stage 2) |
| `.env.production.example` | Vorlage mit ALLE noetigen Variablen (Provider-Presets, DB-Creds, Limits) -- nur ASCII-Platzhalter, niemals echte Secrets |
| `docker-compose.yml` | **unveraendert**: Dev-Infrastruktur (nur DB + Provider-Profile) fuer einen lokal gestarteten Server-Prozess -- kein Deployment-Stapel |
| `tools/` | Operator-Werkzeuge (Konto-Seed/Verify, Argon2-Hash) -- laufen gegen das Produkt-JAR, keine Produktaenderung |
| `smoke_api.cjs` | Akzeptanz-Smoke Login->Org->Space->KB->Upload->RAG->Chat->Historie gegen einen laufenden Server (hermetischer Mock-Upstream inklusive) |

Backup und Restore gehoeren zur Auslieferung, liegen aber wie gewohnt unter
`scripts/backup.sh` und `scripts/restore.sh` (nahe Bedienung unten in Schritt 6).

---

## 1. Vollstaendiger Installationspfad

### 1.1 Server (ein Mittelstands-Host, Linux x86_64, 4+ GB RAM)

Voraussetzungen: Docker **oder** Podman mit Compose-Erweiterung, git, openssl.
Empfohlen: 16 GB RAM (CPU-Betrieb mit `qwen2.5:7b`), ca. 15 GB Platz fuer Modelle.

**Ein-Befehl-Installation (SERVER-PAKET, ADR-0010):**

```bash
git clone <repo-url> /opt/keplura && cd /opt/keplura/deploy
./install.sh --admin-email admin@firma.example      # mit GPU: zusaetzlich --gpu
```

Das Skript legt beim ersten Lauf `.env.production` (Modus 600) mit zufaelligem
DB-Passwort, Secrets-Master-Key und Erst-Admin-Passwort an (Zugangsdaten stehen
nur in `.env.production`, das Passwort wird nicht im Terminal ausgegeben), baut den Server, startet PostgreSQL+pgvector, Ollama,
zieht Chat- (`qwen2.5:7b`) und Embedding-Modell (`bge-m3`, mehrsprachig, 1024 dim)
und wartet auf `/api/v1/health`. Erneuter Aufruf ist sicher (vorhandene
`.env.production` bleibt). Die pgvector-Spalte passt sich der Embedding-Dimension
an, solange noch kein Dokument indexiert ist; spaeterer Modellwechsel braucht Re-Index.

Wenn Port 8080 bereits belegt ist: `./install.sh --port 18080`. Der Parameter
gilt für diesen Aufruf; für den dauerhaft gleichen Port
`KEPLURA_SERVER_HOST_PORT=18080` in `.env.production` setzen. Ohne Parameter
verwendet das Skript die Umgebung, anschließend die Datei und sonst 8080.
Eine vorhandene Konfiguration und ihre Zugangsdaten werden nie überschrieben.
Docker bzw. Podman muss vor dem Aufruf laufen.

**Pilotprüfung mit echten Modellen** (Node.js 22+, nach der Installation):

```bash
cd /opt/keplura
node deploy/pilot-check.cjs --base http://localhost:8080 \
  --env-file deploy/.env.production --out /tmp/keplura-pilot-check.json
```

Die Prüfung meldet sich mit dem Erst-Admin an und legt eine eigene Organisation
„Pilotprüfung …“ samt Arbeitsbereich, Wissensbasis und Testdokument an. Sie prüft
den anonymen Zugriff, CSRF, asynchrone Indexierung, echte `bge-m3`-/pgvector-Suche,
eine vollständige `qwen2.5`-Antwort mit Quellen sowie das erneute Laden. Es wird
kein Mock gestartet. Die Beispieldaten bleiben für die anschließende Sichtprüfung
im Browser erhalten. Das Protokoll enthält keine Zugangsdaten. Die Modellantwort
kann auf CPU mehrere Minuten dauern. Dieser Prüflauf gehört auf die Pilot- oder
Testinstanz, da er absichtlich Beispieldaten erzeugt.

Mit `bash deploy/test-install.sh` lässt sich die Argumentprüfung, Konfiguration,
Portwahl und Geheimhaltung der Installer-Ausgabe ohne Container reproduzieren.

Manuell (gleiches Ergebnis):

```bash
cp .env.production.example .env.production && chmod 600 .env.production
#    -> POSTGRES_PASSWORD: openssl rand -hex 24; Master-Key + Bootstrap-Admin setzen
docker compose -f compose.prod.yml --env-file .env.production up -d --build
curl -fsS http://127.0.0.1:8080/api/v1/health   # {"status":"UP"}
```

**Erstes Konto anlegen** (der Prod-Profil-Server hat bewusst keinen
Dev-Seeder -- kein Seed-Overlay in einer Live-Umgebung):

Variante A -- Compose-Stapel (Werkzeuge laufen auf dem Host, INSERT im
DB-Container; braucht ein JDK 21+ auf dem Host, z. B. den Build-Rechner):

```bash
cd /opt/keplura
jar xf server/target/keplura-server-0.1.0.jar BOOT-INF/lib   # libs aus dem Fat-JAR
javac -cp 'BOOT-INF/lib/*' -d /tmp/ic-tools deploy/tools/*.java
read -rs PW                                                    # Passwort nie als Argument
HASH=$(printf '%s' "$PW" | java -cp "/tmp/ic-tools:BOOT-INF/lib/*" deploy.tools.PasswordHashTool 2>/dev/null)
cd deploy
docker compose -f compose.prod.yml --env-file .env.production exec -T db \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "INSERT INTO app_user (email, password_hash, display_name, created_at)
      VALUES ('admin@firma.example','$HASH','Admin',now()) ON CONFLICT (email) DO NOTHING;"
```

Variante B -- Bare-Metal/Server-Netz: dasselbe Werkzeug mit `seed`-Kommando
(setzt den Hash selbst, idempotent -- ein bestehendes Konto wird NIEMALS
ueberschrieben, keine Credential-Reset-Hintertuer):

```bash
cd /opt/keplura/deploy
set -a; . ./.env.production; set +a      # POSTGRES_PASSWORD dient als DB-Zugang
read -rs PW
printf '%s' "$PW" | java -cp "/tmp/ic-tools:../BOOT-INF/lib/*" deploy.tools.AccountTool \
  --url "jdbc:postgresql://127.0.0.1:5432/$POSTGRES_DB" --user "$POSTGRES_USER" \
  --password-env POSTGRES_PASSWORD seed --email admin@firma.example --display-name "Admin"
# dasselbe Werkzeug verified nach jedem Restore:  ... verify --email admin@firma.example
```

Beide Wege nutzen exakt denselben Argon2-Encoder wie der Login-Pfad
(`PasswordHasher` des Servers); das Passwort laeuft ausschliesslich ueber
stdin -- niemals als Prozess-Argument (landet in `ps` und Shell-History).

**TLS-Reverse-Proxy (Produktion Pflicht):** der Server publishet nur auf
`127.0.0.1`, und das Prod-Profil flaggt das Session-Cookie `Secure` -- ohne
TLS-Proxy gibt es keinen Login (die Desktop-App landet zurueck beim
Login-Formular). Sessions sind bewusst In-Memory im Serverprozess gehalten
(kein Session-Store, kein Session-Secret in der ENV): jeder Server-Neustart
bedeutet Re-Login fuer alle Nutzer -- kein Datenverlust, nur ein erneuter
Anmelde-Klick. Minimalbeispiel Caddy auf demselben Host:

```
intranet.firma.example {
    reverse_proxy 127.0.0.1:8080
}
```

### 1.2 Desktop-App installieren

Die Desktop-App ist ein natives Installationspaket; sie spricht HTTPS gegen
den Reverse-Proxy-Host von 1.1.

- **Linux**: `Keplura_0.1.0_amd64.deb` bauen und verteilen
  (`cd desktop && npm ci && npm run tauri build`), installieren:
  `sudo dpkg -i Keplura_0.1.0_amd64.deb`
- **Windows/macOS**: dieselben Befehle auf der jeweiligen Plattform
  (`npm run tauri build` erzeugt .msi/.exe bzw. .app/.dmg). Windows-
  Code Signing und macOS-Notarisation sind PO-Punkte (Kosten/Account);
  Details in `docs/RELEASE_CHECKLIST.md`.

Die Server-URL wird zur BUILD-Zeit eingesetzt (Vite-Env `VITE_SERVER_URL`,
Standard `http://localhost:8080`). Fuer eine Kundeninstallation vor dem
Bauen setzen, z. B.:

```bash
cd desktop
VITE_SERVER_URL=https://intranet.firma.example npm run tauri build
```

### 1.3 Login -> Organisation -> erstes Knowledge-Base

1. App starten -> Login-Maske -> die in 1.1 erzeugten Zugangsdaten.
2. Organisation anlegen (Sidebar-Dialog), z. B. "Firma Nord".
3. Space anlegen (z. B. "Produktion"), darin ein Knowledge-Base
   (z. B. "Betriebsanleitungen").
4. Dokumente hochladen (txt/md/csv/pdf/docx; pdf/docx via Tika-Parser).
5. Chat: Unterhaltung in der Knowledge-Base anlegen -> Fragen werden mit
   Quellenangaben (Citations) beantwortet; Suche liefert pgvector-KNN-Treffer.

## 2. Triage: was loggt wo?

| Was | Wo | Wie |
|---|---|---|
| HTTP-Zugriffe (Pfad, Status, ms -- keine Query-Parameter) | `server`-Container-Log | `docker compose -f compose.prod.yml logs -f server` |
| App-Meldungen (Boot, Flyway, Gateway-Statuswarnungen) | dito (Profil prod = `com.keplura` auf INFO) | dito |
| PostgreSQL (Slow Queries, Verbindungen) | `db`-Container-Log; Standardkonfig des pgvector-Images | `logs -f db` |
| Modell-Provider Ollama | `ollama`-Container-Log | `logs -f ollama` |
| Container-Lifecycle (Restarts, OOM-Kills) | journald des Hosts | `journalctl -u docker` bzw. `-u podman` / `journalctl -t conmon` |
| Desktop-App (Transport-/Renderer-Fehler) | lokal auf dem Client-Geraet (Tauri/webview-Console); kein zentrales Logging | Nutzer:in gefordert -> DevTools im Debug-Build |

Konvention: Logs enthalten keine Passwoerter/Keys und keine Dokumentinhalte;
Request-Logging nennt nur Pfad+Status (M2d-Regel). Fehlermeldungen nennen aus
Sicherheitsgruenden nur Klassennamen, nie Upstream-Details -- fuer tieferes
Debugging kurz `LOGGING_LEVEL_COM_KEPLURA=DEBUG` im Server-Container setzen
und `restart server`.

## 3. Ressourcenbedarf (realistisch)

| Komponente | CPU | RAM | Disk |
|---|---|---|---|
| server (JVM 21, Spring Boot) | 1-2 Kerne | ~0,8-1,5 GB Heap + ~300 MB Overhead | <1 GB Image + Upload-Volumen |
| db (PostgreSQL 16 + pgvector) | 1 Kern | 256 MB shared_buffers Default; pro Verbindung ~10 MB | DB-Daten + WAL; Chunks sind klein (64-dim Vektoren) |
| ollama (optional, CPU) | 2+ Kerne waehrend Generierung | **Modellgroesse + ~0,5 GB**; qwen2.5:0.5b laeuft mit ~1 GB, 7B-Modelle brauchen 8 GB+ | Modellgewichte (~400 MB ... 5 GB+) |

Faustregel: **4 GB RAM / 2 Kerne** reichen fuer einen Testbetrieb mit
Ollama-Modellklasse 0,5-1B; ab ~7B-Modellen 16 GB einplanen oder vLLM-GPU
(preset `vllm`, Hardware ist PO-/Kundenseite). Die Upload-Groesse (Default
20 MiB) begrenzt den Dokument-Pipeline-Speicher; Tika-Parser laufen mit
max. 2 parallelen Extraktionen.

## 4. Was ueberwachen (Monitoring-Mindestsatz)

Kein Monitoring-Stack noetig -- der Stack bringt alles selbst mit:

1. **Liveness**: `/api/v1/health` (200 + `"UP"`). Bereits als Docker-
   HEALTHCHECK im Image UND in `compose.prod.yml` verdrahtet; der
   Restart-Policy-Effekt ist damit automatisch: crun/PATH:container stirbt,
   Healthcheck fails 3x -> Neustart. Externe Pruefung (Statusseite,
   Cron, Icinga -- was auch immer schon da ist):
   `curl -fsS http://127.0.0.1:8080/api/v1/health`
2. **DB-Connectivity**: healthcheck des `db`-Containers
   (`pg_isready`); zusaetzlich zeigt der Server-Boot jede DB-Drift sofort
   (Flyway-Validate failt laut).
3. **Disk auf storage-Volumen**: Uploads wachsen mit dem Dokumentbestand.
   `df -h` auf dem Mount des volumes (`/var/lib/docker/volumes/intracore-prod_storage`) --
   Schwelle ~80 %: Backup/Media-Regel pruefen (Schritt 6). Gleiches gilt
   fuer `pgdata` und (Ollama-)`ollamamodels`.
4. **Logs**: `docker compose ... logs --since 1h server | grep -E 'ERROR|WARN'`
   (cron-faehig) bzw. journald fuer Container-Neustarts.
5. **Modell-Gateway-Status**: `GET /api/v1/system/info` zeigt nur
   `model: configured|mock|none` -- genug fuer die Frage "antwortet der
   Provider ueberhaupt?"; Details nie nach aussen (produktseitige Regel).

## 5. Bare-Metal-Betrieb ohne Container-Runtime

Wo Container nicht konnen (z. B. verschachtelte Rootless-Umgebungen ohne
UID-Mapping, wie unsere CI-Runner -- siehe Evidence-Log des Akzeptanzlaufs),
ist der Komponenten-Pfad dokumentiert und real getestet:

1. PostgreSQL 16 paketiert installieren (+ pgvector), Schema uebernimmt
   Flyway beim Server-Boot (keine Handarbeit).
2. Server: `mvn -B -f server/pom.xml package -DskipTests` ->
   `SPRING_PROFILES_ACTIVE=prod KEPLURA_DB_URL=... java -jar
   server/target/keplura-server-0.1.0.jar` (alle ENVs wie in
   `.env.production.example`).
3. systemd-unit fuer den Java-Prozess ist Operator-Routine; Logs dann ueber
   journald (Triage-Tabelle gilt sinngemass).
4. Desktop/Installer bleiben identisch (1.2).

## 6. Backup & Restore (PFLICHT vor dem ersten Produktivtag einzurichten)

```bash
# taglich (cron), newest 7 behalten:
30 2 * * * /opt/keplura/scripts/backup.sh --out /var/backups/keplura --keep 7 >> /var/log/keplura-backup.log 2>&1
# Test-Restore eines aktuellen Backups in eine Kratz-DB:
scripts/restore.sh /var/backups/keplura/keplura-XXXX --database scratchdb
# Voll-Wiederanlauf (ACHTUNG --force ersetzt die Live-Schema):
scripts/restore.sh /var/backups/keplura/keplura-XXXX --force
```

- backup.sh schreibt pro Lauf EINEN consistenten Ordner:
  `database.dump` (pg_dump -Fc) + `documents.tar.gz` (Upload-Baum) +
  `MANIFEST.txt` (sha256, Version, flags); Rechte 0700/0600 ab dem ersten
  Write (Mandantendaten!). Default stoppt kurz den Server fuer eine
  widerspruchsfreie Kopie; `--online` ist der Kompromiss (erklaert im
  Script-Kopf).
- restore.sh prueft zuerst Integritaet (sha256 + lesbarer Dump/Tar),
  verweigert ohne `--force` das Ueberschreiben einer nicht-leeren DB,
  spielt beide Haelften ein und post-checkt Zeilenanzahlen + Dateien.
- **Medienregel**: Kopien mindestens auf ein zweites Geraet/NAS -- Rotation
  (`--keep`) schuetzt nur vor Platznot, nicht vor einem toten Host.
- Real getestet (Beweis-Log committed):
  `ci/evidence/DEPLOY-BUNDLE-2026-09-08/backup-restore-proof.log` --
  backup -> DROP DATABASE + Uploads loeschen -> restore -> Login,
  pgvector-Suche und Chat-Historie wiederkommen.

## 7. Smoke-Lauf (Akzeptanz selbst wiederholen)

Gegen einen laufenden Prod-Stapel (Provider-ENV zeigt auf den eingebauten
Mock -- nur fuer diesen Lauf!):

```bash
# Server mit Gateway-ENV auf den Mock hochfahren (compose: KEPLURA_GATEWAY_BASE_URL=http://127.0.0.1:19923/v1 temporarily)
node deploy/smoke_api.cjs --base http://127.0.0.1:8080 \
  --email SMOKE-ADRESSE --password 'SMOKE-PASSWORT'
```

Exit 0 und 10 Zeilen `"ok":true` = Org/KB/Upload/RAG/pgvector/Chat komplett
durch. Der Smoke erzeugt eigene Testobjekte (Org "Smoke Org") -- in einer
Live-Umgebung nur auf einer Staging-Instanz fahren.

## 8. Versions- und Update-Politik

SemVer-light: `0.x.y` bis der PO den Beta-Schnitt freigibt
(RELEASE_CHECKLIST). Server und Desktop tragen beide die semantische
Version (pom.xml, tauri.conf.json/package.json); der Smoke prueft
`/api/v1/system/info` gegen die gleiche Ziffer. Aktualisieren:

```bash
cd /opt/keplura && git pull      # Installationsordner; Altinstallationen: /opt/intracore
cd deploy && ./install.sh        # baut, startet, prüft Health; Flyway-Migrationen laufen beim Boot
```

Update immer über `./install.sh` (nicht direkt `docker compose ... up`).
Beim ersten Update nach der Umbenennung IntraCore → Keplura (ADR-0013)
benennt `install.sh` alte `INTRACORE_*`-Einträge in `.env.production` auf `KEPLURA_*` um
(Sicherung: `.env.production.vor-keplura`), Werte und Datenbank bleiben gleich.
Volumes und Compose-Projektname (`intracore-prod`) bleiben bewusst unverändert,
damit Datenbank und Dokumente erhalten bleiben. **Wichtig:** Ein direktes
`docker compose ... up` mit noch nicht umgestellter `.env.production` bricht
den Server-Start bewusst ab (Meldung im Log: alte und neue Umgebungsvariablen
widersprechen sich). Ohne diese Sperre liefe er ohne Master-Key (gespeicherte
Anbieter-Schlüssel unlesbar) und mit Standard-Anbietereinstellungen. Darum zuerst
`./install.sh` oder die Datei von Hand umstellen. Bare-Metal ohne Compose: der Server liest alte
`INTRACORE_*`-Variablen weiter und warnt beim Start (stehen alter und neuer
Name mit verschiedenen Werten nebeneinander, bricht er ab); das JAR heißt jetzt
`keplura-server-<Version>.jar`, Startskripte/systemd-Units entsprechend anpassen
(ein altes `intracore-server-*.jar` in `target/` bleibt sonst liegen und läuft weiter). Neue
Sicherungen heißen `keplura-<Zeit>`; alte `intracore-*`-Sicherungen rotiert
`backup.sh` nicht mehr und können von Hand gelöscht werden. Die Desktop-App hat
eine neue Kennung (`com.keplura.desktop`): alte IntraCore-App deinstallieren,
Keplura installieren, neu anmelden.

```bash
scripts/backup.sh --out /var/backups/keplura   # VOR jedem Update -- Standard!
```

## 9. Pilot: Antwortqualität und lokale Neubewertung

Der Compose-Stack startet zusätzlich die lokale Modell-Laufzeit Hugging Face
Text Embeddings Inference mit `BAAI/bge-reranker-v2-m3` (Apache-2.0,
[Modellkarte](https://huggingface.co/BAAI/bge-reranker-v2-m3)). Der Cache liegt
im persistenten Volume `rerankmodels`. Beim ersten Start werden etwa 2,3 GB
Modellgewichte geladen. Der Dienst hat keinen veröffentlichten Port. Fragen
und Textstellen bleiben im internen Compose-Netz; im Betrieb wird kein
öffentlicher Inferenzdienst aufgerufen. Auf CPU benötigt die Modell-Laufzeit
etwa 3–4 GB zusätzlichen Arbeitsspeicher. Die CPU-Konfiguration lässt die GPU
für Chat und Embeddings frei.

PostgreSQL kombiniert deutsche Volltextsuche und Vektorsuche per Reciprocal
Rank Fusion. Beide Kandidatenlisten sind bereits in SQL auf Mitgliedschaft,
Organisation, Arbeitsbereich und Wissensbasis begrenzt. Bis zu 50 Kandidaten
werden lokal neu bewertet; nur Ergebnisse ab `KEPLURA_RERANKER_MIN_SCORE`
(Standard 0,5) gelangen in den Wissens-Chat. Dieser Modellwert ist ein
Relevanzfilter und keine Garantie, dass jede Aussage einer Antwort korrekt ist.
Ein Ausfall des Rerankers wird als Suchfehler gemeldet. Er wird niemals als
„nichts gefunden“ ausgegeben. Ohne passende Fundstelle liefert der Server
eine feste Nichtfund-Antwort mit leerer Quellenliste, ohne das Chat-Modell
aufzurufen. Freie Chats ohne Wissensbasis verwenden weiter das Sprachmodell.

PDF-Seiten und Abschnittsüberschriften werden beim Indexieren gespeichert.
Bestehende Dokumente erhalten diese Metadaten nach „Erneut verarbeiten“;
alte Chat-Zitate bleiben lesbar. Bei TXT/MD/DOCX werden keine unzuverlässigen
Seitenzahlen erfunden. Die Änderung verwendet die neue Migration V13.

Abnahme gegen die tatsächlich laufende Installation (erzeugt eigene
Beispieldaten, keine Mocks; Node.js 22+):

```bash
node deploy/quality-check.cjs --base http://localhost:8080 \
  --env-file deploy/.env.production --out /tmp/keplura-quality.json
# Optional: mit einem zweitseitigen Test-PDF, dessen Seite 2 die
# Rauchwarnschwelle der Leitwarte von 42 Prozent enthält:
# zusätzlich --pdf /pfad/zum/test.pdf
```

Der Lauf prüft relevante und irrelevante Fragen, Nichtfund bei leerer
Wissensbasis, falsche Organisationspfade und gespeicherte Abschnittsquellen.
`deploy/tools/HybridSearchProbe.java` prüft zusätzlich direkt auf PostgreSQL,
dass ein Volltexttreffer einen absichtlich irreführenden Vektor ausgleichen
kann und alle vier SQL-Berechtigungsgrenzen greifen. Dazu im Build-Container
auf dem Compose-Netz mit dem Runtime-Klassenpfad kompilieren und als normale
Java-Klasse starten (nicht im Java-Source-Launcher, dessen eigener Classloader
den paketinternen Store-Konstruktor nicht verwenden kann). Zugangsdaten kommen
aus der lokalen ENV-Datei; Argumente sind nur Organisations-, Arbeitsbereichs-
und Wissensbasis-ID aus dem Qualitätsprotokoll. Das Werkzeug schreibt keine Daten.
