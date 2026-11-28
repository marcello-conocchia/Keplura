# TU-06c — Isolierter MCP-stdio-Adapter (Neubau mit stärkerer Isolation)

Status: **Architekturvorschlag und Testplan zur Vorabprüfung vorbereitet.** Aus TU-06 (PR #39) herausgenommen (PO-Entscheidung 27.11.2026 im Chat: „Aus #39 herausnehmen“ auf Review-Befund 8, Major). TU-06 ist seit 28.11.2026 auf main (9364c77); diese Aufgabe ist die nächste. Rollen (PO 27.11.2026): **Codex implementiert, Claude prüft unabhängig** (Code + Security). Die erste Fassung steckt in Commit 19d01fa (zurückgenommen in b1206e0). Die Probe mit einem echten stdio-Server unter `ci/evidence/20261127-bridge-smoke/` bleibt als Nachweis der ersten Fassung erhalten.

Klasse A: Prozessisolation, Zugangsdaten, Netzgrenzen, neue Deploy-Dienste.

## Ziel

stdio-MCP-Server laufen wie in TU-06 festgelegt nie im Keplura-Serverprozess und werden nie über die Web-Oberfläche als Befehl eingetragen (PO 26.11.2026: „Bridge im Container“). Neu ist, dass ein Kindprozess weder das Bridge-Token noch andere Kindprozesse, noch den Keplura-Server oder Dienste des Hosts erreicht.

## Befund aus dem Review (Codex, 27.11.2026)

- Das Bridge-Token stand in der Umgebung des Elternprozesses. Kindprozesse liefen mit derselben UID im selben PID-Namensraum und konnten es unter `/proc/<pid>/environ` lesen.
- Alle Kindprozesse teilten `/work` und das Bridge-Netz. Darüber war der Keplura-Dienst erreichbar, je nach Docker-Gateway-Modus auch Dienste des Hosts.
- Der vorhandene Umgebungstest prüfte nur die Vererbung, nicht die Grenzen im echten Container.

## Scope

- **Token nicht lesbar für Kindprozesse:** Das Token kommt nur aus einer Datei, nie aus der Umgebung. Kindprozesse laufen unter einer anderen Identität als der Token-Halter und können weder die Datei noch Speicher oder Umgebung des Elternprozesses lesen.
- **Kindprozesse getrennt voneinander:** eigene Identität oder eigener Namensraum je Sitzung, privates beschreibbares Verzeichnis je Sitzung (wird am Sitzungsende gelöscht), kein gemeinsames `/work`.
- **Netz:** standardmäßig kein Netz für Kindprozesse. Ausgehende Ziele nur, wenn der Betreiber sie je Server ausdrücklich freigibt, und technisch durchgesetzt (nicht nur dokumentiert). Der Keplura-Server und der Host sind aus dem Kindprozess nie erreichbar.
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
| B5 | Freigegebenes Ziel als Sprungbrett: Weiterleitung oder zweite Adresse hinter einem freigegebenen Namen | Freigabe nur für das deklarierte Ziel (Host und Port). Durchsetzung außerhalb des Kindes | Container: freigegebenes Ziel erreichbar, ein anderer Port oder Host desselben Netzes nicht |
| B6 | Sitzung A liest, schreibt oder signalisiert Sitzung B: Dateien, `/proc`, `kill` | Eigene Identität oder eigener Namensraum je Sitzung, privates Arbeitsverzeichnis | Container: zwei gleichzeitige Sitzungen, A sieht weder Prozesse noch Dateien von B und kann B nicht beenden |
| B7 | Zurückgelassene Daten: Sitzung 1 legt Dateien ab, die Sitzung 2 (andere Person) liest | Arbeitsverzeichnis je Sitzung, am Ende gelöscht | Container: Datei aus Sitzung 1 fehlt in Sitzung 2 |
| B8 | Prozess überlebt das Sitzungsende: Doppel-Fork, `setsid`, Daemon | Ganzer Prozessbaum bzw. Namensraum wird beendet | Container: Testprogramm forkt einen Daemon; nach `DELETE` oder Zeitlimit läuft kein Prozess der Sitzung mehr |
| B9 | Rechteausweitung: setuid-Programme, Capabilities, Wechsel auf root | `no-new-privileges`, keine Capabilities im Kind, kein root | Container: `NoNewPrivs=1`, `CapEff=0` im Kind; ein setuid-Versuch scheitert |
| B10 | Ausbruch ins Dateisystem: Schreiben außerhalb des Arbeitsverzeichnisses, Lesen von Host-Mounts, Docker-Socket | Root-Dateisystem nur lesbar, keine Host-Mounts außer der Deklaration, nie der Docker-Socket | Container: Schreibversuche scheitern; Test der Compose-Konfiguration auf verbotene Mounts |
| B11 | Ressourcen erschöpfen: Fork-Bombe, Speicher, `/tmp` füllen, endlose Ausgabe, Hänger | Grenzen je Sitzung (Prozesse, Speicher, Dateigröße, Ausgabe, Zeit). Andere Sitzungen und die Bridge laufen weiter | Container: je ein Testprogramm pro Fall; danach antwortet eine zweite Sitzung normal |
| B12 | Befehlsinjektion: Werkzeug-Argumente landen in `argv`, Umgebung oder Shell | Argumente gehen nur als JSON-RPC über stdin. `argv` und Umgebung kommen nur aus der Deklaration, keine Shell | Unit und Container: Argumente mit `;`, `$( )`, Zeilenumbruch und `--flag` verändern den gestarteten Befehl nicht |
| B13 | Zugriff ohne oder mit falschem Token, Raten des Tokens | 401 ohne Prozessstart; zeitkonstanter Vergleich; Mindestlänge | Unit und Container: kein Kindprozess bei falschem Token |
| B14 | Fremde Sitzung übernehmen: `Mcp-Session-Id` einer anderen Sitzung raten oder wiederverwenden | Sitzungs-IDs zufällig und an die Verbindung gebunden. Unbekannte ID ergibt einen Fehler, keinen neuen Prozess | Unit: fremde und erfundene IDs werden abgelehnt |
| B15 | Missbrauch des Protokolls Richtung Keplura: übergroße oder kaputte Antworten, Antworten zu fremden Anfragen | Ausgabegrenze in der Bridge, bestehende Grenzen des Keplura-MCP-Clients | Unit: übergroße und kaputte Antworten enden mit fester Fehlermeldung |
| B16 | Geheimnisse in Logs oder Fehlertexten: Token, Umgebung, Pfade, Ausgabe des Kindes | Feste Fehlertexte, keine Ausgabe des Kindes im Log | Unit und Container: Logs und Antworten enthalten das Token nie |
| B17 | Keplura-Geheimnisse im Bridge-Container: Datenbank-Zugang, Master-Key, Provider-Schlüssel | Container bekommt nur Token-Datei und Deklaration | Test der Compose-Konfiguration: keine solchen Variablen oder Mounts |

**Ablauf:**
1. Codex legt zu Beginn die Bauweise mit einer kurzen Abwägung fest, entlang dieser Tabelle: Container je Server oder Sandbox je Sitzung.
2. Claude prüft die Abwägung und die geplanten Tests kurz, bevor der Hauptteil gebaut wird.
3. Was eine Bauweise nicht abwehren kann (z. B. B5 bei geteiltem Netz), geht mit Optionen an den PO.

## Architekturvorschlag für die Vorabprüfung

**Vorschlag: eigener OCI-Container je MCP-Sitzung.** Ein Container je dauerhaft laufendem Server würde zusätzlich eine Trennung seiner gleichzeitigen Sitzungen benötigen. Eine Sandbox im Bridge-Container könnte mit weniger Diensten auskommen, müsste aber verschachtelte Namespaces und pro Sitzung wirksame Ressourcenlimits auf den verschiedenen Zielhosts nachweisen. Das Standard-Seccomp-Profil von Docker schränkt das Anlegen verschachtelter Namespaces ein. Der OCI-Ansatz nutzt die vorhandene Container-Isolation für jede einzelne Sitzung. [Docker-Seccomp-Profil](https://docs.docker.com/engine/security/seccomp/) · [Docker-Netzwerkmodus none](https://docs.docker.com/engine/network/drivers/none/)

- **Bridge und Startdienst.** Keplura nutzt weiter den MCP-HTTP-Pfad. Die Bridge verwahrt ihr Token in einer nur für ihre eigene Identität lesbaren Datei. Ein separater, vom Betreiber bereitgestellter Startdienst besitzt die Runtime-Rechte und nimmt nur Sitzungsoperationen für fest deklarierte Servernamen an. Er bestimmt Image, Programm, Argumente, Umgebung und Limits aus seiner eigenen Deklaration. Werkzeugdaten erreichen den Worker ausschließlich als begrenztes JSON-RPC über stdin. Nur der Startdienst kommuniziert mit der Container-Runtime. Worker und Bridge erhalten deren Socket nicht. Die produktive Host-Integration dieses Startdiensts ist Teil der Vorabprüfung.
- **Lebensdauer und Dateisystem.** Jeder Start erhält eigene PID-, Mount- und Netzgrenzen, eine Nicht-root-Identität und einen privaten, größenbeschränkten Arbeitsbereich. Das Root-Dateisystem ist lesbar, Capabilities sind entzogen und `no-new-privileges` ist gesetzt. Sitzungsende, Ablauf oder Verlust der Bridge-Verbindung veranlassen die Runtime, den gesamten Worker zu beenden und seinen Arbeitsbereich zu verwerfen. Der Startdienst muss vor Freigabe nachweisen, dass Prozess-, Speicher- und Zeitlimits in der Zielumgebung durchgesetzt werden. Besonders im Rootless-Betrieb hängt das von der Cgroup-Konfiguration ab. [Docker Rootless und Cgroups](https://docs.docker.com/engine/security/rootless/tips/#limiting-resources)
- **Netzfreigabe.** Ohne Freigabe nutzt der Worker `network=none`. Für konfigurierte Ziele kommt ein separater, vom Betreiber kontrollierter Relay-Pfad infrage. Der Worker kann ausschließlich sein sitzungseigenes Relay erreichen. Das Relay wählt das Ziel aus der Deklaration und prüft Zieladresse und Port außerhalb des Workers. Direkte Routen zum Host und zu anderen Diensten bleiben gesperrt. B5 ist vor Umsetzung am vorgesehenen Produktionsnetz zu prüfen. Der Start mit einer Freigabe muss scheitern, wenn die Runtime diese Trennung nicht herstellen kann.
- **HTTP-Sitzung.** Bridge-Authentifizierung erfolgt vor dem Start. Eine zufällige Sitzungs-ID wird an den Servernamen und einen separaten, je MCP-Verbindung erzeugten Besitznachweis gebunden. Der MCP-HTTP-Client hält ihn bis zum Sitzungsende und sendet ihn über den geschützten Bridge-Transport. Für B14 muss selbst eine kopierte Sitzungs-ID mit gültigem Bridge-Token ohne den zugehörigen Besitznachweis abgewiesen werden.

**Testplan für die Vorabprüfung.** Die Pflichttests bleiben B1–B17 aus der Tabelle.

- **B1–B4, B17.** Einen synthetischen, zufälligen Testwert verwenden und Pfade, Mounts, die Identität sowie die Netzkonfiguration aus Sicht des Workers prüfen. Für Netzprüfungen ausschließlich eigens dafür angelegte Testendpunkte und kurze, begrenzte Verbindungsversuche verwenden. Ein Positivtest weist nach, dass die Testendpunkte von der vorgesehenen Seite erreichbar sind.
- **B5.** Ein Relay mit einem freigegebenen lokalen Testdienst und einem zweiten lokalen Testdienst betreiben. Erlaubtes Ziel erreicht, anderer Host und anderer Port gesperrt. Eine synthetische Weiterleitung und ein Wechsel des Test-DNS dürfen die Freigabe nicht erweitern. Mit einem fehlenden Durchsetzungsmechanismus darf keine Sitzung mit Netzfreigabe starten.
- **B6–B11.** Zwei gleichzeitige Worker mit getrennten Testdateien verwenden. Prozesssichtbarkeit, Signalrechte, frischen Arbeitsbereich, Ablauf und vollständiges Entfernen getrennt nachweisen. Limits mit begrenzten Testprozessen, kurzen Fristen und absichtlich niedrigen Grenzwerten prüfen. Eine unabhängige Kontrollsitzung muss währenddessen weiter antworten.
- **B12–B16.** Argumente als JSON-RPC-Text prüfen. Für Authentifizierung und Sitzungsbindung zählen, ob ein Worker gestartet wird und ob abgewiesene Anfragen den Container erreichen. Antworten für falsche IDs, zu große Nachrichten und Protokollfehler prüfen. Antwort- und Log-Ausgaben gegen den synthetischen Testwert prüfen.

**Stand der Vorprüfung.** Auf Docker Desktop wurde ein einzelner Linux-Testcontainer mit UID 65534 geprüft. Sichtbar waren nur Loopback, `NoNewPrivs=1`, keine effektiven Capabilities, ein privater beschreibbarer Arbeitsbereich und die erwarteten Speicher- und Prozessgrenzen. Das ist eine Machbarkeitsprobe der Runtime, kein Nachweis für B1–B17. Produktionsanbindung, Relay und der Besitznachweis sind die Schwerpunkte der unabhängigen Vorabprüfung.

## Acceptance Criteria

1. Jede Zeile von B1–B17 hat einen grünen Pflichttest. Die Container-Tests haben einen Nachweis unter `ci/evidence/<datum>-tu06c-container/`, mit Befehlen, Ausgaben und Image-Version.
2. Im **echten Container** nachgewiesen (Docker oder Podman, Nicht-root): Ein Kindprozess liest das Token weder aus Datei, Umgebung noch `/proc`, sieht keine anderen Kindprozesse und deren Dateien und erreicht weder den Keplura-Dienst noch den Host noch das Internet, solange nichts freigegeben ist.
3. Ein freigegebenes Ziel ist erreichbar, ein nicht freigegebenes nicht.
4. Alle Kriterien aus TU-06c der TU-06-Spezifikation bleiben erfüllt (nur deklarierte Befehle, Nicht-root, Zeitlimit, nur mit Token erreichbar, kein Prozessstart im Keplura-Server).
5. Einmal lokale Linux-CI am Ende als Nicht-root mit acl. Unabhängiges Code- und Security-Review durch Claude gemäß AGENTS.md.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-tu06c` ab main **nach** dem Merge von TU-06. Draft-PR, kein Merge durch den Entwickler.
