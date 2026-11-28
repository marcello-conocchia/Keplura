# TU-06c — Isolierter MCP-stdio-Adapter (Neubau mit stärkerer Isolation)

Status: **spezifiziert, nicht gestartet.** Aus TU-06 (PR #39) herausgenommen (PO-Entscheidung 27.11.2026 im Chat: „Aus #39 herausnehmen“ auf Review-Befund 8, Major). TU-06 ist seit 28.11.2026 auf main (9364c77); diese Aufgabe ist die nächste. Rollen (PO 27.11.2026): **Entwicklung und unabhängige Prüfung (Code + Security) sind getrennt.** Die erste Fassung steckt in Commit 19d01fa (zurückgenommen in b1206e0). Die Probe mit einem echten stdio-Server unter `ci/evidence/20261127-bridge-smoke/` bleibt als Nachweis der ersten Fassung erhalten.

Klasse A: Prozessisolation, Zugangsdaten, Netzgrenzen, neue Deploy-Dienste.

## Ziel

stdio-MCP-Server laufen wie in TU-06 festgelegt nie im Keplura-Serverprozess und werden nie über die Web-Oberfläche als Befehl eingetragen (PO 26.11.2026: „Bridge im Container“). Neu ist, dass ein Kindprozess weder das Bridge-Token noch andere Kindprozesse, noch den Keplura-Server oder Dienste des Hosts erreicht.

## Befund aus dem Review (27.11.2026)

- Das Bridge-Token stand in der Umgebung des Elternprozesses. Kindprozesse liefen mit derselben UID im selben PID-Namensraum und konnten es unter `/proc/<pid>/environ` lesen.
- Alle Kindprozesse teilten `/work` und das Bridge-Netz. Darüber war der Keplura-Dienst erreichbar, je nach Docker-Gateway-Modus auch Dienste des Hosts.
- Der vorhandene Umgebungstest prüfte nur die Vererbung, nicht die Grenzen im echten Container.

## Scope

- **Token nicht lesbar für Kindprozesse:** Das Token kommt nur aus einer Datei, nie aus der Umgebung. Kindprozesse laufen unter einer anderen Identität als der Token-Halter und können weder die Datei noch Speicher oder Umgebung des Elternprozesses lesen.
- **Kindprozesse getrennt voneinander:** eigene Identität oder eigener Namensraum je Sitzung, privates beschreibbares Verzeichnis je Sitzung (wird am Sitzungsende gelöscht), kein gemeinsames `/work`.
- **Netz:** Kindprozesse haben in TU-06c **gar kein Netz** (`network=none`, PO 28.11.2026). Eine Deklaration mit Netzziel wird beim Start abgelehnt. Der Keplura-Server, der Host und andere Worker sind aus dem Kindprozess nie erreichbar. Ausgehende Ziele über ein Relay (B5) folgen als eigene Klasse-A-Aufgabe nach TU-06c.
- **Bauweise:** Bevorzugt wird ein Container je deklariertem stdio-Server, oder je Sitzung ein Sandbox-Werkzeug im Bridge-Container (z. B. bubblewrap mit eigenen Namensräumen). Der Weg wird zu Beginn der Aufgabe mit einer kurzen Abwägung festgelegt. Eine Sicherheitsabwägung mit Produktwirkung geht an den PO.
- Ansonsten gilt der Umfang aus TU-06c der TU-06-Spezifikation: Deklaration nur im Deployment, keine Shell, Zeitlimits, begrenzte Ausgabe, Anbindung über den vorhandenen MCP-HTTP-Adapter, standardmäßig deaktivierter Dienst.

## Bedrohungen und Pflichttests

Lehre aus TU-06: Die Angriffe werden **vor** dem Code festgelegt. Jede Zeile braucht mindestens einen Test, der ohne die Abwehr rot wäre und mit ihr grün ist. Die Tests mit „Container“ laufen im echten Container (Docker oder Podman, Nicht-root, Produktions-Compose bzw. dessen Härtung). Unit-Tests ersetzen sie nicht. Neue Angriffe, die beim Bauen auffallen, kommen in diese Tabelle, statt als Restrisiko im PR zu landen. Ein Restrisiko mit Produktwirkung entscheidet der PO.

**Angreifer, von denen wir ausgehen:**
- ein bösartiges oder kompromittiertes stdio-Programm, z. B. ein npm-Paket mit Schadcode oder ein Server, der über Werkzeug-Argumente aus einer Prompt-Injection gesteuert wird;
- eine Person, die bei einem freigegebenen Server Werkzeug-Argumente vorgibt;
- jemand im internen Netz ohne Bridge-Token.

| # | Angriff | Erwartete Abwehr | Pflichttest |
|---|---|---|---|
| B1 | Kindprozess liest das Bridge-Token: Umgebung, `/proc/<ppid>/environ`, `/proc/<ppid>/mem` oder `cmdline`, Token-Datei, Kommandozeile | Token nie in Umgebung oder Argumenten. Datei nur für den Token-Halter lesbar. Kind unter anderer Identität, ohne Zugriff auf `/proc` des Elternprozesses (eigener PID-Namensraum oder `hidepid`) | Container: Testprogramm versucht alle Wege, Ergebnis enthält das Token nie |
| B2 | Kindprozess ruft die Bridge selbst auf (Loopback oder Bridge-Adresse), mit oder ohne gestohlenes Token, und startet weitere Server | Das Kind erreicht den Listener der Bridge nicht | Container: Verbindung zu Bridge-Port und Bridge-IP aus dem Kind scheitert |
| B3 | Kindprozess erreicht den Keplura-Server, die Datenbank, das Docker-Gateway, `host.docker.internal`, die Metadatenadresse `169.254.169.254` oder das Internet | Standardmäßig kein Netz im Kind, technisch durchgesetzt | Container: jedes Ziel einzeln scheitert, solange nichts freigegeben ist |
| B4 | Daten fließen über DNS ab | Ohne Freigabe kein DNS im Kind | Container: Auflösung eines externen Namens scheitert |
| B5 | Freigegebenes Ziel als Sprungbrett: Weiterleitung oder zweite Adresse hinter einem freigegebenen Namen | **Nicht Teil von TU-06c** (PO 28.11.2026: erst ohne Netz). In TU-06c gilt: keine Freigabe möglich | Unit und Container: Eine Deklaration mit Netzziel startet keinen Worker. Relay und Pflichttests zu B5 in der Folgeaufgabe |
| B6 | Sitzung A liest, schreibt oder signalisiert Sitzung B: Dateien, `/proc`, `kill` | Eigene Identität oder eigener Namensraum je Sitzung, privates Arbeitsverzeichnis | Container: zwei gleichzeitige Sitzungen, A sieht weder Prozesse noch Dateien von B und kann B nicht beenden |
| B7 | Zurückgelassene Daten: Sitzung 1 legt Dateien ab, die Sitzung 2 (andere Person) liest | Arbeitsverzeichnis je Sitzung, am Ende gelöscht | Container: Datei aus Sitzung 1 fehlt in Sitzung 2 |
| B8 | Prozess überlebt Sitzungsende, Zeitlimit oder Absturz des Startdiensts | Die Runtime beendet den ganzen Worker. Der Startdienst entfernt beim Neustart alle verwaisten Worker seiner Instanz anhand von Instanz- und Sitzungslabels | Container: Prozessende und Entfernung nach `DELETE`, Zeitlimit und Neustart nach abruptem Dienstabbruch prüfen. Worker anderer Instanzen bleiben unberührt |
| B9 | Rechteausweitung: setuid-Programme, Capabilities, Wechsel auf root | `no-new-privileges`, keine Capabilities im Kind, kein root | Container: `NoNewPrivs=1`, `CapEff=0` im Kind; ein setuid-Versuch scheitert |
| B10 | Ausbruch ins Dateisystem: Schreiben außerhalb des Arbeitsverzeichnisses, Lesen von Host-Mounts, Docker-Socket | Root-Dateisystem nur lesbar, keine Host-Mounts außer der Deklaration, nie der Docker-Socket | Container: Schreibversuche scheitern; Test der Compose-Konfiguration auf verbotene Mounts |
| B11 | Einzelne Worker oder viele gleichzeitige Aufrufe erschöpfen Ressourcen | Limits je Sitzung und atomare Obergrenzen für Worker je Server und insgesamt | Container: pro Sitzung begrenzte Prüflasten einsetzen. Beide Startobergrenzen einzeln erreichen und feste Fehler sowie null weitere Starts prüfen. Eine Kontrollsitzung antwortet nach Freigabe eines Platzes |
| B12 | Befehlsinjektion: Werkzeug-Argumente landen in `argv`, Umgebung oder Shell | Argumente gehen nur als JSON-RPC über stdin. `argv` und Umgebung kommen nur aus der Deklaration, keine Shell | Unit und Container: Argumente mit `;`, `$( )`, Zeilenumbruch und `--flag` verändern den gestarteten Befehl nicht |
| B13 | Zugriff ohne oder mit falschem Token, Raten des Tokens | 401 ohne Prozessstart; zeitkonstanter Vergleich; Mindestlänge | Unit und Container: kein Kindprozess bei falschem Token |
| B14 | Ein interner Client mit gültigem Bridge-Token kennt die kopierte Sitzungs-ID einer anderen Verbindung | Zufällige Sitzungs-ID mit mindestens 128 Bit, gebunden an Servername und einen separaten, zufälligen Besitznachweis der initiierenden MCP-Verbindung | Unit: gültiges Token und kopierte ID ohne oder mit fremdem Besitznachweis werden abgewiesen. Ebenso unbekannte ID, falscher Server und Wiederverwendung nach Sitzungsende. Nur der passende Besitznachweis erlaubt Zugriff. Abweisungen starten keinen Worker |
| B15 | Missbrauch des Protokolls Richtung Keplura: übergroße oder kaputte Antworten, Antworten zu fremden Anfragen | Ausgabegrenze in der Bridge, bestehende Grenzen des Keplura-MCP-Clients | Unit: übergroße und kaputte Antworten enden mit fester Fehlermeldung |
| B16 | Geheimnisse in Logs oder Fehlertexten: Token, Umgebung, Pfade, Ausgabe des Kindes | Feste Fehlertexte, keine Ausgabe des Kindes im Log | Unit und Container: Logs und Antworten enthalten das Token nie |
| B17 | Keplura-Geheimnisse im Bridge-Container: Datenbank-Zugang, Master-Key, Provider-Schlüssel | Container bekommt nur Token-Datei und Deklaration | Test der Compose-Konfiguration: keine solchen Variablen oder Mounts |
| B18 | Manipulierter Startauftrag versucht, Image, Mounts, Netz oder Privilegien zu ändern | Der Startdienst akzeptiert zur Auswahl nur Servername und Sitzungs-ID. Die gesamte Worker-Spezifikation stammt aus seiner eigenen Deklaration | Unit und Container: jedes zusätzliche Feld und unbekannte Servernamen ablehnen. Runtime-Aufrufe zählen und bei Ablehnung null Starts prüfen. Beim Positivfall festes Image und effektive Härtung nachweisen |
| B19 | Fremder Zugriff auf den Startdienst oder Missbrauch seines stdio-Kanals | Eigener Zugangsschlüssel und sitzungsgebundener, begrenzter Datenstrom | Unit und Container: fehlender oder falscher Startdienst-Schlüssel sowie das gültige Bridge-Token werden abgelehnt. Die erlaubte Sitzung kann stdio austauschen. Eine fremde Sitzung erhält keine Daten. Größen- und Zeitgrenzen greifen und ein Testgeheimnis erscheint weder in Logs noch Fehlern |

**Ablauf:**
1. Die Entwicklung legt zu Beginn die Bauweise mit einer kurzen Abwägung fest. Maßgeblich sind die Bedrohungen und Pflichttests.
2. Die unabhängige Prüfung bewertet die Abwägung und den Testplan vor Beginn des Hauptteils.
3. Offen gebliebene Sicherheitsabwägungen mit Produktwirkung gehen mit Optionen an den PO.

## Architekturvorschlag für die Vorabprüfung

Ein eigener OCI-Container pro MCP-Sitzung trennt Prozesse, Dateisystem und Lebensdauer auch bei gleichzeitigen Sitzungen desselben Servers. Die Entwicklung bevorzugt diesen Ansatz gegenüber verschachtelten Sandbox-Namespaces im Bridge-Container, weil die gleichen Containergrenzen pro Sitzung einzeln geprüft werden können.

- **Startdienst und Runtime.** Ein separater Startdienst nutzt ausschließlich die rootless Runtime eines dedizierten Betriebssystemkontos. Der Socket des Haupt-Daemons ist für diesen Dienst, die Bridge und die Worker unzugänglich. Der Startdienst akzeptiert einen authentifizierten Startauftrag mit Servername und Sitzungs-ID. Unbekannte Felder führen zur Ablehnung. Seine eigene Deklaration bestimmt ein vorab installiertes Image per Digest, das Programm, Argumente und Umgebung. Laufzeit-Pulls sind abgeschaltet. Jeder Worker läuft als Nicht-root mit `network=none`, Read-only-Root-Dateisystem, `cap-drop ALL`, `no-new-privileges`, festen Ressourcenlimits und privatem, begrenztem Arbeitsbereich. Worker erhalten keine Host-Mounts, Runtime-Sockets oder privilegierten Optionen. Die Produktionsumgebung muss die konfigurierten Limits nachweislich durchsetzen. Siehe B9–B12 und B18.
- **stdio und Authentifizierung.** Die Bridge verbindet sich über einen nur für Bridge und Startdienst zugänglichen Unix-Socket mit dem Startdienst. Dieser Kanal verwendet einen eigenen Zugangsschlüssel aus einer geschützt lesbaren Datei. Der Schlüssel ist vom Bridge-Token unabhängig. Der Startdienst hängt über seine Runtime an stdin und stdout des Workers und vermittelt den gesamten JSON-RPC-Datenstrom über die sitzungsgebundene Verbindung zur Bridge. Startfelder und stdio-Daten haben getrennte, typisierte Frames. Für beide Richtungen gelten Größen- und Zeitgrenzen. Stderr wird verworfen. Bridge- und Startdienst-Logs enthalten weder Nutzdaten noch Sitzungsschlüssel oder Tokens. Siehe B12–B16 und B19.
- **Netz und Lebensdauer.** PO-Entscheidung vom 28.11.2026. Jeder Worker nutzt `network=none`. Konfigurationen mit Netzwerkzielen werden vor dem Start abgewiesen. Das Relay und B5 sind eine eigene Klasse-A-Folgeaufgabe. Positive, konfigurierbare Obergrenzen gelten je Server und insgesamt. Bei Auslastung gibt der Startdienst eine feste Fehlermeldung zurück. Reservierung und Freigabe der Plätze liegen beim Startdienst. Sitzungsende, Timeout und Kanalverlust beenden den Worker. Instanz- und Sitzungslabels erlauben es, nach einem Neustart verwaiste Worker derselben Instanz zu entfernen, bevor neue Starts angenommen werden. Siehe B3–B8 und B11.
- **Besitznachweis (B14).** Das Schutzziel umfasst die kopierte Sitzungs-ID zusammen mit einem gültigen Bridge-Token. Der angenommene Angreifer hat keinen Zugriff auf den separaten Nachweis der initiierenden Verbindung. Keplura erzeugt diesen Nachweis vor `initialize` aus 256 Zufallsbits und nutzt ihn nur im Bridge-Modus. Die Bridge speichert ausschließlich seinen Hash zusammen mit Sitzungs-ID und Servername. Keplura sendet den Nachweis auf dem geschützten Transport bei jedem Post und Delete der MCP-Sitzung. Der HTTP-Client sendet das Feld ausschließlich im Bridge-Modus. Der Negativtest verwendet eine kopierte ID und ein gültiges Token mit fehlendem oder anderem Nachweis. Der Positivtest weist Zugriff mit allen drei Bindungswerten nach.

Die erste Machbarkeitsprobe auf Docker Desktop bestätigte einen Linux-Container als UID 65534, Loopback als einzige Schnittstelle, `NoNewPrivs=1`, keine effektiven Capabilities und wirksame Speicher- und Prozesslimits. Die Probe testete die Runtime-Grundlagen. Die rootless Produktionsanbindung und die Pflichttests stehen aus.

## Non-Goals

- keine Netzfreigabe für Worker, kein Relay (B5): eigene Klasse-A-Aufgabe nach TU-06c (PO 28.11.2026);
- kein Release/Deployment.

## Acceptance Criteria

1. B1–B4 und B6–B19 haben grüne Pflichttests. Für B5 ist die Ablehnung von Netzzielen nachgewiesen. Die Container-Tests haben einen Nachweis unter `ci/evidence/<datum>-tu06c-container/`, mit Befehlen, Ausgaben und Image-Version.
2. Im **echten Container** nachgewiesen (Docker oder Podman, Nicht-root): Ein Kindprozess liest das Token weder aus Datei, Umgebung noch `/proc`, sieht keine anderen Kindprozesse und deren Dateien und erreicht weder den Keplura-Dienst noch den Host noch das Internet, solange nichts freigegeben ist.
3. Es gibt keine Netzfreigabe: Der Worker hat nur `network=none`, und eine Deklaration mit Netzziel startet keinen Worker (fail closed). B5 ist ausdrücklich nicht abgenommen und folgt als eigene Aufgabe.
4. Alle Kriterien aus TU-06c der TU-06-Spezifikation bleiben erfüllt (nur deklarierte Befehle, Nicht-root, Zeitlimit, nur mit Token erreichbar, kein Prozessstart im Keplura-Server).
5. Einmal lokale Linux-CI am Ende als Nicht-root mit acl. Unabhängiges Code- und Security-Review gemäß AGENTS.md.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-tu06c` ab main **nach** dem Merge von TU-06. Draft-PR, kein Merge durch den Entwickler.
