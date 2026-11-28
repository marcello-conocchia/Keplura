# DS-01 — Schritt 5, erste Ausbaustufe: Verzeichnis + Fileserver mit Rechte-Spiegelung

Branch `feat/ds-datasources` von `main@43302d3`; übernimmt `feat/pilot-admin-ops` (4b–4e) per
Merge-Commit als Voraussetzung (Suche, Administration). Kein Merge nach main.
Spec: `docs/adr/0012-datenquellen-und-rechte-spiegelung.md`.

## Umgesetzt (14.11.2026) — nur Kompilieren/Typecheck geprüft

| Etappe | Inhalt |
|---|---|
| Rechte-Filter | V16 (`user_principal`, `kb_document_acl`, `directory_connection`, `data_source`, `data_source_file`); `AccessFilter` als SQL/JPQL-Baustein IN Dokumentliste, Download, Vorschau, pgvector-, hybrider und In-JVM-Suche; Dokumente ohne ACL (Hand-Upload) unverändert sichtbar |
| Verzeichnis | `LdapDirectoryClient` (JDK/JNDI): ldaps/StartTLS, RFC-4515, leere Passwörter abgelehnt; AD: objectSid + tokenGroups; LDAP: uid/gidNumber + Gruppen (member/uniqueMember/memberUid, Tiefe 5); Login: lokal zuerst, dann Verzeichnis, Kontoanlage nur mit Auto-Provisionierung; `/api/v1/admin/directories` (CRUD, test, lookup) |
| Fileserver | `SmbSourceConnector` (smbj 0.14.0, DFS, NTFS-DACL → sid:/everyone, Allow/Deny, INHERIT_ONLY ignoriert); `LocalSourceConnector` (nur unter `KEPLURA_DATASOURCE_LOCAL_ROOTS`, POSIX u/g/o, Elternordner konservativ); `DataSourceSyncService` (Delta, Rechte-Fingerprint, Löschungen, Dokument+ACL in einer Transaktion, fail-closed, Probelauf); `/api/v1/admin/datasources` |
| Oberfläche | Administration → „Datenquellen“ (Statusliste + 6-Schritte-Assistent mit Probelauf und Rechte-Vorschau) und „Verzeichnis“ (Verbindung testen, Benutzer nachschlagen); Login-Feld „E-Mail oder Benutzername“ |

## Bewusste Grenzen dieser Version (in ADR-0012 vermerkt)

- SMB: Freigabe-Berechtigungen (Share-ACL) werden nicht ausgewertet — Empfehlung „Jeder: Lesen“ auf der
  Freigabe, Steuerung über NTFS.
- Linux: erweiterte POSIX-/NFSv4-ACLs werden nicht ausgewertet; Zugriff nur über Zusatzgruppen auf
  Elternordner geht konservativ verloren (fail-closed).
- SharePoint (DS-4) folgt (Entscheidungen offen, siehe unten).

## Abnahme-Checkliste (lokal)

1. `mvn verify`; neue Unit-Tests: `LdapDirectoryClient.sidToString/escapeFilter`, `SmbSourceConnector.principal`,
   `DataSourceSyncService.fingerprint/Filter`, `LocalSourceConnector.acl` (Tempdir mit chmod: privater Elternordner,
   o+r-Datei, Gruppe), `AccessFilter` über H2 (ohne ACL sichtbar, Allow trifft, Deny gewinnt, everyone,
   fremder Benutzer sieht nichts — für Liste, Download, Vorschau, Suche), `SearchStoreSelectionTest` Platzhalterzahl.
2. Container-Fixtures: Samba-AD (z. B. `nowsci/samba-domain` oder eigenes Image) mit Freigabe + NTFS-Rechten,
   OpenLDAP mit posixAccount/groupOfNames, ein NFS- oder Bind-Mount-Ordner mit chmod-Varianten.
3. Negativtests je Quellentyp: Verweigern-Eintrag, verschachtelte Gruppe, entzogenes Recht (nach Abgleich
   verschwindet das Dokument für die Person), Benutzer ohne Gruppe, unlesbare Rechte → für niemanden sichtbar.
4. Browser: Verzeichnis anlegen/testen/nachschlagen; Datenquelle per Assistent bis Aktivieren; Abgleich-Status;
   Anmeldung mit Verzeichniskonto; Chat-Antwort zitiert nur erlaubte Dateien; zweites Konto ohne Recht sieht nichts.
5. Security-Review (Pflicht): LDAP-Injection, anonymer Bind, TLS/Hostname, SMB-Zugangsdaten, Pfad-/Symlink-Ausbruch
   aus den lokalen Wurzeln, Datenleck über Dateinamen/Vorschau/Download, Lauf-Abbruch/Neustart.
6. Screenshots unter `ci/evidence/DS-01-<datum>/`.


## Nachtrag (gleicher Tag, gleicher Branch)

- **DS-5 Testfragen-Set** (`2e02372` davor `e2e796a`): V17, `/api/v1/admin/quality`, Administration → „Antwortqualität“
  (Trefferquote, Verlauf, Daumen-Statistik nur als Zahlen, Fragen einzeln oder als Liste aus Excel).
- **Namen statt SIDs** in der Rechte-Vorschau (Verzeichnis-Abfrage objectSid/uidNumber/gidNumber, Windows-Standardkonten).
- **Gruppen-Auffrischung alle 15 min** (`96fd5e2`): nicht gefunden/deaktiviert → Prinzipale entfernt; AD-, OpenLDAP-,
  389-DS-Sperrmerkmale; deaktivierte Konten können sich nicht mehr per Verzeichnis anmelden.

Zusätzliche Prüfpunkte: Prüflauf mit echten Fragen (Trefferquote plausibel, Rechte des Prüfenden wirken),
CSV mit Semikolon und Tab, Daumen-Statistik nach Bewertungen; Auffrischung: Benutzer aus AD-Gruppe entfernen →
nach ≤ 15 min verschwindet die Datei aus seiner Suche; Konto deaktivieren → keine Anmeldung, keine Quelldokumente.

## SharePoint (DS-4) — Entscheidungen (PO 14.11.2026: Sites.Selected; Site-Gruppen → M365-Gruppe, sonst gesperrt)

1. Graph-Berechtigung: `Sites.Selected` (nur ausdrücklich freigegebene Sites, empfohlen) oder `Sites.Read.All`/`Files.Read.All`
   (alle Sites, einfacher einzurichten). Zusätzlich `GroupMember.Read.All` für Gruppen der Benutzer.
2. SharePoint-Gruppen (Besitzer/Mitglieder/Besucher einer Site) liefert Graph nicht als Entra-Gruppen. Optionen: (a) bei
   Microsoft-365-Gruppen-Sites auf die verbundene Gruppe abbilden, sonst fail-closed; (b) zusätzlich SharePoint-REST mit
   eigenem Zertifikat; (c) Zuordnung im Assistenten von Hand.
3. Anmeldung der Benutzer über Entra ID (bestehender OIDC-Weg) muss die `oid` übernehmen und Gruppen per Graph auflösen.
4. Testtenant (Microsoft 365 Developer/Trial) wird für die Abnahme benötigt.


## SharePoint umgesetzt (`b56d360`, `5bb5b25` und UI-Commit)

- Entra-Prinzipale beim OIDC-Login (oid, groups-Claim, Tenant) — nur aus Microsoft-ausgestellten Tokens.
- `SharePointSourceConnector` (Graph, Sites.Selected, Download ohne Bearer, Drosselung), V18.
- Assistent: dritte Wahl „SharePoint Online“ mit Einrichtungshilfe; Anleitung `docs/howto/sharepoint.md`.

Prüfpunkte (braucht einen Microsoft-365-Testtenant): App mit Sites.Selected, eine Site freigegeben,
eine nicht → nur die freigegebene ist erreichbar; Datei nur für Gruppe A → Person in A sieht sie, Person in B nicht;
„Besucher“-Recht → nicht sichtbar; Organisationslink → alle des Tenants; OIDC-Login mit groups-Claim und mit
Überlauf; Token eines fremden IdP mit gefälschter oid → keine Entra-Prinzipale.
Grenze: Entra-Gruppen werden nur bei der Anmeldung aktualisiert (kein Graph-Recht GroupMember.Read.All).

## Abnahme-Zwischenstand — 14.11.2026, auf Wunsch pausiert

| Prüfpunkt | Ergebnis | Beleg / Rest |
|---|---|---|
| 1: CI, Migrationen, SQL-Bindings | bestanden auf `9a28e01` | 416 Tests / 57 Klassen, Frontend, Linux-Tauri; `ci/evidence/20261114-221256/`. Finale Wiederholung nach Produktfixes offen. |
| 1: neue gezielte Tests | bestanden | Local 3, LDAP 4, Sync 1, SMB 2; gezielte Maven-Läufe. Kein vollständiges `mvn verify` nach diesen Fixes behauptet. |
| 2: Container-Fixtures | bestanden für Samba AD/SMB, OpenLDAP und Linux-Dateien | Echte Dienste und POSIX-Dateirechte; kein echter NFS-Server und keine DFS-Topologie geprüft. |
| 3: Deny, verschachtelte Gruppe, zweites Konto | bestanden | LDAP-Gruppen, AD-tokenGroups, SMB-DACL; private Linux-Elternordner und Gruppen; fremde Dateien fehlen in Liste/Download/Vorschau/pgvector-Suche. |
| 3: Rechteentzug nach Abgleich | bestanden | SMB-Deny hinzugefügt, Abgleich, Datei verschwindet aus Liste/Download/Vorschau/Suche. Unlesbare Rechte bisher nur gezielt im Code getestet, Laufzeitfall offen. |
| 4: Browser | teilweise bestanden | Verzeichnis testen/nachschlagen, Datenquellen-Assistent bis Aktivieren, Status sichtbar; `datasources_flow_e2e.cjs`, vier Schritte grün. Verzeichnis über Formular anlegen und zitierte Chat-Antwort offen. Status-Wartebedingung des neuen Harness noch präzisieren. |
| 5: gezielte Sicherheitsprüfungen | teilweise bestanden | Injection, leeres Passwort abgewiesen; LDAPS mit Test-CA funktioniert, falscher Hostname scheitert. Lokale Quellwurzel, POSIX-Klassen und Symlink-Eltern abgesichert. Abbruch/Neustart und unabhängiger Security-Review offen. |
| 6: Screenshots | bestanden für geprüfte Oberflächen | `ci/evidence/DS-01-20261114/`, insbesondere `browser/`. |
| DS-5 Qualität | bestanden für Suche/CSV/Rechte | Semikolon und Tab importiert; zwei Fragen, eine erlaubte Quelle, korrekt 50 %; Verlauf vorhanden. Daumen-Statistik nach Bewertungen offen. |
| Gruppenauffrischung / deaktiviertes Konto | noch nicht geprüft | 15-Minuten-Scheduler und bestehende Sitzung gezielt prüfen. |
| SharePoint / Entra-Testtenant | nicht prüfbar | Hier kein Microsoft-365-Testtenant eingerichtet. Sites.Selected, fremde Site, Besucher-/Gruppenrechte und echter Entra-Login bleiben offen. |

Erste rote Diagnoseergebnisse bleiben in der Evidence erhalten: AD ohne TLS
wurde abgewiesen, danach LDAPS korrekt geprüft; eine negative Passwortprüfung
erwartete zunächst 401 statt ebenfalls korrektem 400 und wurde entsprechend
korrigiert. Keine dieser alten roten Zeilen ist als Produktfreigabe zu lesen.
Noch kein abgeschlossener DS-01-Gate und kein Merge-Kandidat.

### Fortsetzung am 15.11.2026

- **Bestanden:** Verzeichnis per Browserformular anlegen und verbinden. Neuer
  Browserlauf mit fünf Schritten; Statusprüfung jetzt auf genau die neu
  angelegte Quelle begrenzt.
- **Bestanden:** Datei zuerst über SMB eingelesen, dann dem Dienstkonto
  `READ_CONTROL` entzogen. Probelauf meldet unlesbare ACL; Folgeabgleich sperrt
  das vorhandene Dokument, kein SQL-Duplikat und kein Download möglich.
- **Bestanden:** Java-Prozess während `RUNNING` absichtlich abgebrochen;
  Neustart meldet unterbrochenen Lauf, erneuter Abgleich übernimmt 40 Dateien.
- **Bestanden:** Zwei bestehende AD-Sitzungen: Gruppe entfernt bzw. Konto
  deaktiviert; nach 265 Sekunden verschwinden die betreffenden Dateien ohne
  Neuanmeldung. Testkonfiguration: Auffrischung alle fünf Minuten (Standard 15).
  Anmeldung des deaktivierten Kontos scheitert. Teständerungen danach zurückgesetzt.
- Evidence: `ci/evidence/DS-01-20261115/`. SharePoint/Entra mangels Testtenant,
  echte DFS-/NFS-Topologie und unabhängiger Security-Review bleiben offen.

### Abschließende verfügbare Funktionsprüfungen am 15.11.2026

- **Bestanden:** importierte Datei `oeffentlich.txt` liefert im Browser eine
  echte qwen2.5-Antwort mit „37 Tage“ und Quellenverweis.
- **Bestanden:** Daumen hoch, Wechsel auf runter und Zurücknehmen aktualisieren
  die echte Statistik jeweils korrekt. Antwortqualität zeigt im Browser den
  tatsächlich neu ausgeführten Prüflauf mit 50 % (eine von zwei Quellen für
  den Prüfenden sichtbar) sowie die Bewertungszahlen.
- `datasources_quality_e2e.cjs`, drei Schritte; Evidence
  `ci/evidence/DS-01-20261115/quality-browser/`. Der erste lokale Harness-Versuch
  wartete die Chat-Anlage nicht ab und fragte ohne Wissensbasis; nach expliziter
  Prüfung von Anlage und gewählter Wissensbasis grün. Kein Produktfehler behauptet.
- Bestehende E2E-Suiten einzeln bestanden. Verbindlichen Gesamtlauf auf dem
  finalen Commit samt Matrix/Protokoll im
  [Draft-PR #53](https://github.com/marcello-conocchia/Keplura/pull/53) prüfen.
  Fehlende externe Infrastruktur und unabhängige Freigaben bleiben offen.
