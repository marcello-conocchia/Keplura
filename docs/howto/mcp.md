# MCP-Server sicher anbinden

1. Den HTTP-Endpunkt des MCP-Servers und ein Dienst-Token bereitstellen.
   Keplura startet keine externen Programme (`stdio` wird nicht unterstützt).
2. Für Server im Firmennetz muss der Betrieb das Ziel in
   `KEPLURA_ADMIN_SSRF_ALLOW_CIDRS` freigeben. Möglichst eine einzelne
   IPv4-Adresse als `/32` eintragen. Keine pauschale Freigabe aller Netze.
   Standardmäßig sind interne, lokale und Metadaten-Adressen gesperrt;
   Weiterleitungen werden nicht verfolgt. Dies ist dieselbe Richtlinie wie
   bei den Verbindungstests für KI-Anbieter.
3. Administration → MCP-Server: Name, HTTP(S)-Adresse und gegebenenfalls
   Token eintragen. Zugangsdaten gehören in das Token-Feld, nicht in die URL.
4. Die Verbindung lädt nur die Werkzeugliste. Jedes benötigte Werkzeug
   ausdrücklich anhaken und die erlaubten Arbeitsbereiche auswählen.
5. Nach Änderung von Adresse oder Token bleiben alte Werkzeugfreigaben
   gesperrt. Erneut laden, Liste prüfen und gezielt neu freigeben.

Keplura spricht Streamable HTTP mit Protokollversion `2025-06-18`:
JSON- und SSE-Antworten, Sitzungskennung und `202` für Benachrichtigungen.
SSE endet für den Client mit der passenden Antwort-ID; eine offene Verbindung
blockiert die fertige Antwort nicht. Größenlimit zwei Millionen Bytes,
Zeitlimit 20 Sekunden je Antwort. Keine Wiederholung ausgeführter Werkzeuge.
Protokollgrundlage: [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).

Werkzeuge laufen mit dem Dienst-Token und den Rechten des MCP-Servers.
Die Freigabe gilt pro Arbeitsbereich, nicht pro Mitarbeiter. Deshalb nur
passende Werkzeuge und ein möglichst eingeschränktes Dienstkonto verwenden.
Keplura prüft Freigaben vor jedem Aufruf erneut und protokolliert den Beginn
vor der Ausführung. Ohne verfügbare Protokollierung erfolgt kein Aufruf.
Bis zu drei Modellrunden und acht Werkzeugaufrufe je Antwort; Ergebnisse
werden gekürzt und als fremder Inhalt gekennzeichnet.

Modelle ohne Tool-Calling können den normalen Chat weiterverwenden. Nur eine
ausdrückliche Ablehnung dieser Fähigkeit vor dem ersten Werkzeugaufruf löst
den Rückfall aus. Verbindungsfehler, Überlastung und Fehler nach bereits
ausgeführten Werkzeugen lösen keine automatische Wiederholung aus.
