# SharePoint Online an Keplura anbinden

Für Administratorinnen und Administratoren des Kunden. Dauer: ca. 15 Minuten.
Keplura liest nur; es verändert in SharePoint nichts. Zugriff bekommt Keplura nur auf Sites,
die du ausdrücklich freigibst (Berechtigung **Sites.Selected**).

## 1. App-Registrierung anlegen

1. [Entra Admin Center](https://entra.microsoft.com) → *Identität → Anwendungen → App-Registrierungen* → **Neue Registrierung**.
2. Name: `Keplura`, Kontotypen: *Nur Konten in diesem Organisationsverzeichnis*. Registrieren.
3. Notiere **Anwendungs-ID (Client-ID)** und **Verzeichnis-ID (Tenant-ID)** von der Übersichtsseite.
4. *API-Berechtigungen → Berechtigung hinzufügen → Microsoft Graph → Anwendungsberechtigungen* →
   **Sites.Selected** → hinzufügen → **Administratorzustimmung für … erteilen**.
5. *Zertifikate & Geheimnisse → Neuer geheimer Clientschlüssel* (z. B. 24 Monate). Den **Wert** sofort kopieren.

## 2. Site für Keplura freigeben (Leserecht)

`Sites.Selected` gibt zunächst Zugriff auf **keine** Site. Freigabe je Site, z. B. mit PnP PowerShell
(als SharePoint-Administrator):

```powershell
Connect-PnPOnline -Url https://firma-admin.sharepoint.com -Interactive
Grant-PnPAzureADAppSitePermission -AppId <Client-ID> -DisplayName "Keplura" `
  -Site https://firma.sharepoint.com/sites/Vertrieb -Permissions Read
```

Alternativ per Microsoft Graph (Graph Explorer, angemeldet mit `Sites.FullControl.All`):

```http
POST https://graph.microsoft.com/v1.0/sites/{site-id}/permissions
{ "roles": ["read"], "grantedToIdentities": [ { "application": { "id": "<Client-ID>", "displayName": "Keplura" } } ] }
```

## 3. Anmeldung der Mitarbeiter mit Microsoft 365

Damit Keplura weiß, welche Dateien eine Person sehen darf, melden sich Mitarbeiter mit ihrem
Microsoft-365-Konto an (Administration → Anmeldung/OIDC):

1. Eine zweite App-Registrierung (oder dieselbe) für die Anmeldung mit Umleitungs-URI laut Keplura-Administration.
2. *Tokenkonfiguration → Gruppenanspruch hinzufügen* → **Sicherheitsgruppen** (bzw. *Alle Gruppen*), im ID-Token.
   Bei mehr als 200 Gruppen pro Person liefert Microsoft keine Gruppenliste; Keplura nutzt dann nur die
   direkte Berechtigung der Person (Gruppenrechte greifen nicht).

## 4. In Keplura einrichten

Administration → **Datenquellen → Datenquelle hinzufügen → SharePoint Online**:
Adresse der Site (optional mit Bibliothek und Ordner), Tenant-ID, Client-ID, geheimer Clientschlüssel.
Der Assistent verbindet sich probeweise, zeigt Anzahl und Rechte-Vorschau und wird erst danach aktiviert.

## Wie Rechte übernommen werden

| In SharePoint | In Keplura |
|---|---|
| Person oder Entra-Gruppe direkt berechtigt | genau diese Person/Gruppe |
| SharePoint-Gruppe „Mitglieder“ / „Besitzer“ einer Team-/Gruppen-Site | die verbundene Microsoft-365-Gruppe |
| andere SharePoint-Gruppen (z. B. „Besucher“) | **nicht übernommen** – nur direkt Berechtigte sehen die Datei |
| Freigabelink „Personen in der Organisation“ oder „Jeder“ | alle angemeldeten Personen der eigenen Organisation |

Änderungen an Dateien und Rechten übernimmt Keplura beim nächsten Abgleich (Standard alle 15 Minuten).
Gruppenmitgliedschaften der Mitarbeiter werden bei jeder Anmeldung aktualisiert.
