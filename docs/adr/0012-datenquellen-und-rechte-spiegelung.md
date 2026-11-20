# ADR-0012: Datenquellen (Fileserver, SharePoint) mit Rechte-Spiegelung aus AD/LDAP

Date: 2026-11-14
Status: Proposed (Scope vom PO freigegeben am 14.11.2026, Chat: „ja und ldap auch linux bitte mitbedenken";
technisches Design wartet auf Reviewer- und Security-Gate)
Bezug: ROADMAP „DS — Datenquellen" (neu), ADR-0007 (Vektor-Store), ADR-0009 (OIDC), ADR-0010 (Embedding),
ADR-0011 (Browser-first). Ersetzt NICHT die nicht freigegebenen M5–M9 (Agents/MCP/Actions bleiben IDEA).

## Kontext

Keplura soll sich von Open WebUI u. a. dadurch abheben, dass vorhandene Firmenablagen automatisch
eingelesen werden und Antworten nur aus Dokumenten entstehen, die der fragende Mitarbeiter auch im
Explorer öffnen dürfte. Ein durchschnittlicher Sysadmin muss das ohne KI-Wissen einrichten können.
Kundenumgebungen sind gemischt: Windows (AD, SMB/NTFS), Linux (OpenLDAP/FreeIPA/Samba-AD, NFS/POSIX),
Microsoft 365 (Entra ID, SharePoint Online).

## Entscheidung

### 1. Quellentypen (Reihenfolge der Umsetzung)

| # | Quelle | Rechtemodell | Zugriff |
|---|---|---|---|
| 1 | Windows-Freigabe SMB2/3 (Windows Server 2016–2025, Samba), inkl. **DFS-Namespaces** (`\\firma.local\dfs\…`, Referral-Auflösung auf das Ziel) | NTFS-DACL → SIDs (inkl. Share-Rechte: effektiv = Schnittmenge Share ∩ NTFS) | SMB-Client in Java (smbj), Dienstkonto read-only, Kerberos oder NTLMv2 |
| 2 | Linux-Verzeichnis (NFS-Mount, lokaler Pfad, Samba ohne AD) | POSIX owner/group/mode + POSIX-ACL (getfacl), optional NFSv4-ACL | Mount im Server-Container (read-only), Rechte über uid/gid → LDAP |
| 3 | SharePoint Online / OneDrive | SharePoint-Berechtigungen → Entra-Gruppen/Benutzer | Microsoft Graph, App-Registrierung mit Sites.Selected |

### 2. Identitäten: ein Verzeichnis-Adapter, drei Ausprägungen

`DirectoryProvider`-Abstraktion liefert für einen angemeldeten Benutzer die Menge seiner
**Prinzipale** (Benutzer-ID + alle transitiven Gruppen) in einer einheitlichen Form:

- **Active Directory (LDAP/LDAPS):** objectSid + tokenGroups (transitiv, inkl. verschachtelter Gruppen).
- **Generisches LDAP (OpenLDAP, FreeIPA, 389-DS):** uid/uidNumber, memberOf bzw. groupOfNames/posixGroup
  (gidNumber); verschachtelte Gruppen per konfigurierbarer Tiefe.
- **Entra ID:** Gruppen-IDs aus dem OIDC-Token bzw. Graph (Overage-Fall).

Kanonische Prinzipal-Schlüssel: `sid:S-1-5-…`, `posix-uid:1001`, `posix-gid:2001`, `ldap-dn:cn=…`,
`entra:<object-id>`. Zuordnung Keplura-Konto ↔ Verzeichniskonto über Login (OIDC-Subject bzw.
LDAP-Bind) — kein Auto-Provisioning ohne Admin-Freigabe (ADR-0009-Regel bleibt).
Login per LDAP-Bind wird als dritter Login-Pfad ergänzt (lokal Argon2 bleibt primär).

### 3. Rechte-Spiegelung

- Beim Abgleich wird pro Datei die **effektive Lese-Menge** berechnet (Vererbung aufgelöst,
  explizites Verweigern hat Vorrang, POSIX: owner/group/other + ACL-Mask) und als Prinzipal-Liste
  gespeichert (`kb_document_acl(document_id, principal, allow)`).
- Die Suche filtert **in der Datenbankabfrage vor dem Ranking** (gleiche Regel wie die heutigen
  Tenant-Pins, ADR-0007 §4): `EXISTS (acl allow ∩ user_principals) AND NOT EXISTS (acl deny ∩ …)`.
  Niemals Nachfilterung in Java.
- Fail-closed: Datei ohne ermittelbare Rechte ist für niemanden sichtbar; Fehler erscheint im Quellstatus.
- Rechteänderungen werden beim nächsten Delta-Lauf übernommen (Ziel ≤ 15 min); Benutzer-Gruppen werden
  bei Login und spätestens alle 15 min aufgefrischt.

### 4. Synchronisation

- Delta über Änderungszeit + Größe + Hash (SMB/Linux), Graph-Delta-Token (SharePoint); nächtlicher Vollabgleich.
- Gelöschte/verschobene Dateien werden aus dem Index entfernt (nutzt den neuen Lösch-Pfad).
- Indexierung über die asynchrone Warteschlange (`KEPLURA_INDEXING_MODE=async`), Drosselung je Quelle.
- Quelle ist immer **read-only**; Keplura schreibt nie in Kundenablagen.

### 5. Admin-Erlebnis (Pflicht-Akzeptanzkriterium)

Assistent „Datenquelle hinzufügen": Typ → Verbindung + Test (Klartext-Ergebnis, Lösungshinweis) →
Ordnerauswahl/Ausschlüsse → Rechte-Vorschau („Gruppe Vertrieb sieht 1.240 Dateien") → Zeitplan →
Probelauf → Aktivieren. Statusseite je Quelle (letzter Lauf, Anzahl, Fehler in Klartext).
Verzeichnis-Anbindung (AD/LDAP/Entra) als eigener Assistent mit „Verbindung testen" und
„Benutzer nachschlagen" (zeigt aufgelöste Gruppen). Geheimnisse write-only wie ADR-0008.

### 6. RAG-Qualität (gilt auch für Hand-Upload)

Hybride Suche (PostgreSQL-Volltext `german` + pgvector), lokaler Reranker (bge-reranker-v2-m3 über das
Modell-Gateway), strukturbewusstes Zerlegen (Überschriften, Tabellen, Seiten), OCR für Scans,
Quellen-Vorschau mit Sprung zur Stelle, „keine Quelle → keine Behauptung", Test-Fragen-Set mit
Trefferquote für Admins.

## Sicherheit (Pflichtpunkte für das Security-Gate)

- Dienstkonten nur lesend; LDAPS/StartTLS Pflicht (Klartext-LDAP nur mit expliziter Admin-Option).
- Zugangsdaten verschlüsselt (SecretCipher), nie in Logs; SSRF-Guard für alle Ziel-Hosts (ADR-0008).
- Pfad-Normalisierung gegen Traversal, Symlinks außerhalb der Quelle werden ignoriert.
- Rechte-Tests: negative Legs pro Quellentyp (Verweigern-Eintrag, verschachtelte Gruppe, entzogenes Recht,
  Benutzer ohne Gruppe) — CI mit Samba-AD + OpenLDAP + NFS/POSIX-ACL-Fixtures in Containern.
- Audit-Log: wer hat welche Quelle wann eingerichtet/geändert.

## Konsequenzen

- Neues Server-Modul `datasource` (+ `directory`), neue Tabellen per Flyway, keine Änderung an V1–V12.
- CI braucht Container-Fixtures (Samba-AD, OpenLDAP, NFS). Echte Windows-AD- und SharePoint-Tests
  laufen als Release-Gate auf PO-Infrastruktur (Testtenant nötig).
- Neue Abhängigkeiten (smbj, UnboundID-LDAP-SDK, Graph-Client) brauchen Lizenz- und Security-Check.

## Umsetzungsschritte

1. RAG-Qualität (hybride Suche, Reranker, Quellen-Vorschau) — wirkt sofort, auch ohne Connector.
2. `directory`: LDAP/AD-Anbindung + LDAP-Login + Gruppenauflösung, Admin-Assistent.
3. `datasource`: Linux-Verzeichnis (einfachster Testpfad) + SMB, ACL-Speicher, Abfragefilter, Assistent.
4. SharePoint/Entra.
5. Test-Fragen-Set + Qualitätsanzeige.

## Umsetzungsstand 14.11.2026 (Branch `codex/ds-datasources`, nicht gemergt)

Erste Ausbaustufe programmiert (Details und Prüfliste: `docs/tasks/DS-01-datasources.md`):
Rechte-Filter in allen Lesepfaden, AD/LDAP-Anbindung mit Login und Gruppenauflösung, Windows-Freigaben
(SMB/DFS/NTFS) und Linux-Ordner (POSIX), Abgleich, Einrichtungs-Assistent. Bewusste Grenzen dieser
Version: keine Share-ACL-Auswertung (Empfehlung „Jeder: Lesen“ + NTFS), keine erweiterten POSIX/NFSv4-ACLs,
Prinzipale nur beim Login aufgefrischt, SharePoint und Testfragen-Set folgen. Ungeprüft bis zur lokalen
Abnahme (Tests, Container-Fixtures, Security-Gate).
