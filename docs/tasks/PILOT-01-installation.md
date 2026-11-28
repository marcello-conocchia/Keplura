# PILOT-01 — Echte Installation und Hand-Upload

Status: in Arbeit, Branch `feat/pilot-installation`, Basis `main@43302d3`.

## Ziel

Den ausgelieferten Server mit PostgreSQL/pgvector, Ollama, `bge-m3` und
`qwen2.5:7b` auf einer Maschine mit Internet tatsächlich betreiben.

## Umfang

- `deploy/install.sh` ausführen und dabei gefundene Installationsfehler beheben.
- Wiederholbare Prüfung mit echten Modellen: Browser-Bundle, Anmeldung,
  Organisation, Arbeitsbereich, Wissensbasis, Hand-Upload, Indexierung,
  pgvector-Suche, Frage mit Quellen und gespeicherter Antwort.
- Zugangsdaten ausschließlich lokal halten; keine Passwörter in Ausgaben.
- Neustart und wiederholten Installationsaufruf ohne Datenverlust prüfen.

## Nicht enthalten

Hybride Suche, Reranker, Quellen-Vorschau, Oberflächenumbau und Connectoren
folgen in eigenen Features entsprechend der Inhaber-Roadmap.

## Akzeptanz und Nachweise

- Installation startet den echten Compose-Stack, Health meldet `UP`.
- Browser-Oberfläche wird vom Server ausgeliefert; Anmeldung funktioniert.
- Beide vorgegebenen Modelle sind lokal vorhanden. Upload wird durchsuchbar,
  Suche verwendet `pgvector` und `bge-m3` mit 1024 Dimensionen.
- Eine echte Modellantwort enthält den nachprüfbaren Fakt des Testdokuments;
  Quellen und Antwort bleiben nach erneutem Laden vorhanden.
- Negativprüfungen für Anmeldung/CSRF und fremde Organisationen bleiben wirksam.
- Screenshot des tatsächlichen Browser-Ablaufs und bereinigtes Prüfprotokoll.
- Build und relevante Tests nach Änderungen; vollständiges CI-Gate und
  unabhängige Freigaben vor einem Merge gemäß AGENTS.md.

## Arbeitsumgebung

Vorhandenes lokales Repository, eigener Feature-Branch. Die bisherige
Entwicklungsinstanz bleibt auf 8080; Compose erhält zunächst 18080 und eigene
persistente Volumes. Keine Änderungen an bestehenden Kundendaten.
