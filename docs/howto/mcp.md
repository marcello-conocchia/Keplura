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
4. Die Verbindung lädt nur die Werkzeugliste. Jedes neue Werkzeug ist
   **nicht klassifiziert und gesperrt**. Für jedes benötigte Werkzeug zuerst
   eine Risikoklasse wählen, dann ausdrücklich anhaken und die erlaubten
   Arbeitsbereiche auswählen:
   - **Lesen** – liest nur. Läuft nach Freigabe.
   - **Schreiben** – kann Daten im angebundenen System ändern. Standardmäßig
     aus; nur bewusst freigeben.
   - **Kritisch** – folgenreiche Aktion. Nach Freigabe plant das Modell den
     Aufruf nur; er erscheint im Chat als Karte „Wartet auf Bestätigung“ mit
     den genauen Eingaben. Ausgeführt wird erst, wenn die Person genau diesen
     Aufruf bestätigt (Frist 10 Minuten, einmalig). Ablehnen, Fristablauf
     oder eine neue Nachricht im Chat beenden ihn ohne Aufruf.
   Die Klasse setzt nur ein Admin. Angaben des MCP-Servers (z. B.
   `readOnlyHint`) werden dafür nicht übernommen.
5. Eine geänderte Risikoklasse hebt die Freigabe dieses Werkzeugs auf. Ändert
   der Server beim Neu-Laden Beschreibung oder Eingabeschema eines Werkzeugs,
   entfallen Klasse und Freigabe ebenfalls. Nach Änderung von Adresse oder
   Token entfallen alle Klassen und Freigaben. Erneut laden, Liste prüfen und
   gezielt neu klassifizieren und freigeben.
6. Werkzeuge, deren Eingabeschema Keplura nicht vollständig prüfen kann
   (z. B. `$ref`, `pattern`, `if`/`then`, Tupel-Listen), bleiben gesperrt und
   sind entsprechend markiert. Argumente des Modells werden vor jedem Aufruf
   gegen das Schema geprüft; passen sie nicht, findet kein Aufruf statt.

**Umstellung bestehender Installationen (TU-01):** Werkzeuge, die vor dem
Update freigegeben waren, haben noch keine Risikoklasse. Sie werden nicht
automatisch als „Lesen“ eingestuft und laufen nach dem Update **nicht mehr**,
bis ein Admin sie klassifiziert und erneut anhakt. Die Oberfläche zeigt sie
als „War vor der Umstellung freigegeben“. Das bisherige Aufrufprotokoll wird
in das neue zentrale Protokoll übernommen.

Keplura spricht Streamable HTTP mit Protokollversion `2025-06-18`:
JSON- und SSE-Antworten, Sitzungskennung und `202` für Benachrichtigungen.
SSE endet für den Client mit der passenden Antwort-ID; eine offene Verbindung
blockiert die fertige Antwort nicht. Größenlimit zwei Millionen Bytes,
Zeitlimit 20 Sekunden je Antwort. Keine Wiederholung ausgeführter Werkzeuge.
Protokollgrundlage: [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).

Werkzeuge laufen mit dem Dienst-Token und den Rechten des MCP-Servers.
Die Freigabe gilt pro Arbeitsbereich, nicht pro Mitarbeiter. Deshalb nur
passende Werkzeuge und ein möglichst eingeschränktes Dienstkonto verwenden.
Alle Werkzeugaufrufe laufen über einen gemeinsamen Ausführungsweg im Server.
Vor jedem Aufruf prüft Keplura erneut: Chat gehört der Person in ihrer
Organisation, sie ist noch Mitglied, Arbeitsbereich passt, Werkzeug ist noch
klassifiziert, freigegeben und unverändert, Verbindungsziel unverändert.
Der Beginn wird vor der Ausführung protokolliert; ohne verfügbare
Protokollierung erfolgt kein Aufruf. Das Protokoll enthält keine Argumente
oder Ergebnisse.

Je Antwort gibt es **eine Aufrufrunde** mit **höchstens acht Aufrufen**
(auch abgelehnte Versuche zählen mit). Danach antwortet das Modell ohne
Werkzeuge; weitere Aufrufwünsche werden nicht ausgeführt. Weitere Grenzen:
Argumente höchstens 16 000 Zeichen, Ergebnisse werden nach 8 000 Zeichen
gekürzt und als fremder Inhalt gekennzeichnet, nach insgesamt 120 Sekunden
Werkzeugzeit startet in dieser Antwort kein weiterer Aufruf.

Modelle ohne Tool-Calling können den normalen Chat weiterverwenden. Nur eine
ausdrückliche Ablehnung dieser Fähigkeit vor dem ersten Werkzeugaufruf löst
den Rückfall aus. Verbindungsfehler, Überlastung und Fehler nach bereits
ausgeführten Werkzeugen lösen keine automatische Wiederholung aus.

**Aufrufkarten (TU-02):** Jeder Werkzeugaufruf erscheint im Chat unter der
Antwort als Karte mit Werkzeug, Verbindung, Risikoklasse, Eingaben und Status
(wartet auf Bestätigung, läuft, erfolgreich, fehlgeschlagen, abgelehnt, nicht
ausgeführt, abgelaufen, abgebrochen, Ausgang unklar). Ergebnisse sind
Fremdinhalt und werden nur als Text angezeigt. Nur die Person des Chats sieht
ihre Karten und kann entscheiden. Eine Bestätigung prüft vor der Ausführung
erneut Zugriff, Freigabe, Risikoklasse, Werkzeugdefinition, Verbindungsziel
und Eingaben; jede Änderung macht sie ungültig. Ein bestätigter Aufruf läuft
höchstens einmal; ist sein Ausgang unklar, wird er nicht wiederholt. Nach der
Bestätigung entsteht keine neue Modellantwort.
