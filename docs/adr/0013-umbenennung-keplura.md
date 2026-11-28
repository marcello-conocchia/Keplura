# ADR-0013: Vollständige Umbenennung IntraCore → Keplura

Date: 2026-11-18
Status: Accepted (PO, Chat 18.11.2026: „Ja ich will jetzt doch alles ändern“)
Bezug: PR #78 (sichtbarer Produktname), ADR-0003 (Sitzungs-Cookies), ADR-0008 (SecretCipher), ADR-0010 (Server-Paket)

## Kontext

PR #78 hat nur den sichtbaren Produktnamen umgestellt und alle technischen
Kennungen behalten. Der PO will die Umbenennung vollständig. Einige Namen
tragen aber Daten oder verweisen auf Dinge außerhalb des Repositorys; deren
Änderung würde Daten unerreichbar machen.

## Entscheidung

Umbenannt auf Keplura:

- Java-Pakete `com.keplura.server`, Hauptklasse `KepluraServerApplication`,
  Maven-Artefakt `keplura-server`, Spring-Name, Logger-Kategorie `com.keplura`.
- Properties `keplura.*`, Umgebungsvariablen `KEPLURA_*`.
- Sitzungs-Cookies `KEPLURASESSION`/`KEPLURASESSION_DEV`, CSRF-Cookie
  `keplura_csrf`, Header `X-Keplura-Org`, Sitzungsattribute.
- Desktop: App-Kennung `com.keplura.desktop`, Crate/Paket `keplura-desktop`,
  Browser-Speicherschlüssel `keplura.*`; nativer Leser `keplura-local-reader`.
- Container-Benutzer und Pfad `/var/lib/keplura`, Image `keplura-server`,
  Containernamen, Sicherungsordner `keplura-<Zeit>`, Vorgaben für neue
  Installationen (`POSTGRES_DB/USER=keplura`).

Bewusst unverändert:

| Kennung | Grund |
|---|---|
| HKDF-Salz `intracore:secret-cipher:v1` (SecretCipher) | gespeicherte Anbieter-Geheimnisse wären sonst nicht mehr entschlüsselbar |
| Compose-Projekte `intracore-prod` / `intracore-dev` | bestimmen die Volume-Namen; neuer Name = leere Datenbank und leerer Dokumentenspeicher |
| Bestehende DB-Namen/-Benutzer in `.env.production` | werden nicht angefasst; nur neue Vorlagen nutzen `keplura` |
| Angewandte Flyway-Migrationen | Prüfsummen; Änderung bricht `validate` beim Start |
| Kanban-Board `intracore`, Image `intracore-dev:latest`, Host-Pfad des Repository-Checkouts | liegen außerhalb des Repositorys, Umbenennung ist ein eigener Verwaltungsschritt (das GitHub-Repo heißt bereits `marcello-conocchia/Keplura`) |
| `ci/evidence/**`, `desktop/e2e/evidence/**`, `docs/HANDOFF-*`, Verlauf in PROJECT_STATE | historische Nachweise |

Übergang für bestehende Installationen:

- Der Server übernimmt jede gesetzte `INTRACORE_*`-Variable als `KEPLURA_*`,
  wenn die neue nicht gesetzt ist (`LegacyEnvironmentPostProcessor`), und
  warnt beim Start mit den Namen (nie den Werten).
- `deploy/install.sh` benennt in einer vorhandenen `.env.production` alle
  `INTRACORE_*`-Schlüssel um und legt `.env.production.vor-keplura` an.
  Der Rückfall im Server greift nur bei Bare-Metal: Compose reicht nur die
  `KEPLURA_*`-Namen in den Container (die DB-Zugangsdaten kommen aus den
  unveränderten `POSTGRES_*`, der Server würde also starten). Damit eine nicht
  umgestellte Datei nicht still ohne Master-Key und mit Standardwerten läuft,
  reicht `compose.prod.yml` die alten Namen `INTRACORE_SECRETS_MASTER_KEY` und
  `INTRACORE_SERVER_HOST_PORT`, `INTRACORE_PROVIDER` als Erkennung durch; ist ein alter Name gesetzt
  und der neue mit anderem Wert vorhanden, bricht der Server den Start ab
  (nur Namen im Log). Update daher immer über `install.sh`. Die Sicherungen
  sind per `.gitignore` ausgeschlossen; eine vorhandene wird nie überschrieben.
- Der Dokumentenpfad im Container wechselt auf `/var/lib/keplura`; das Volume
  bleibt dasselbe, gespeicherte Pfade sind relativ.

## Folgen

- Alle Desktop-Nutzer installieren die App neu (neue Kennung = neue App,
  eigener Webview-Speicher) und melden sich neu an; alte Sitzungs- und
  CSRF-Cookies gelten nicht mehr. Eine alte Desktop-App spricht mit dem neuen
  Server nicht mehr (Org-Header).
- Die Rückfalllogik für `INTRACORE_*` kann nach dem Beta-Schnitt entfernt
  werden, wenn keine Altinstallation mehr existiert.
