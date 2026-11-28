# TU-06c — Isolierter MCP-stdio-Adapter (Neubau mit stärkerer Isolation)

Status: **spezifiziert, nicht gestartet.** Aus TU-06 (PR #39) herausgenommen (PO-Entscheidung 27.11.2026 im Chat: „Aus #39 herausnehmen“ auf Review-Befund 8, Major). Start erst, wenn TU-06 auf main ist (genau eine aktive Implementierung). Die erste Fassung steckt in Commit 19d01fa (zurückgenommen in b1206e0). Die Probe mit einem echten stdio-Server unter `ci/evidence/20261127-bridge-smoke/` bleibt als Nachweis der ersten Fassung erhalten.

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

## Acceptance Criteria

1. Im **echten Container** nachgewiesen (Docker oder Podman, Nicht-root): Ein Kindprozess liest das Token weder aus Datei, Umgebung noch `/proc`, sieht keine anderen Kindprozesse und deren Dateien und erreicht weder den Keplura-Dienst noch den Host noch das Internet, solange nichts freigegeben ist.
2. Ein freigegebenes Ziel ist erreichbar, ein nicht freigegebenes nicht.
3. Alle Kriterien aus TU-06c der TU-06-Spezifikation bleiben erfüllt (nur deklarierte Befehle, Nicht-root, Zeitlimit, nur mit Token erreichbar, kein Prozessstart im Keplura-Server).
4. Einmal lokale Linux-CI am Ende als Nicht-root mit acl. Unabhängiges Code- und Security-Review gemäß AGENTS.md.

## Workspace

Eigener Worktree auf Branch `feat/tool-use-tu06c` ab main **nach** dem Merge von TU-06. Draft-PR, kein Merge durch den Entwickler.
