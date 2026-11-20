# PILOT-02 — bessere belegte Antworten

In Arbeit auf `codex/pilot-answer-quality`, eigener Worktree ab `main@43302d3`.
Die Build-Kontext- und Zeilenenden-Korrekturen aus PILOT-01 sind als technische
Voraussetzung übernommen. Die installierte PILOT-01-Instanz bleibt erreichbar.

## Ziel und Umfang

- PostgreSQL-Volltextsuche mit Sprachkonfiguration `german` und pgvector
  kombinieren; Berechtigungen in beiden Kandidatenabfragen vor dem Ranking.
- Kandidaten mit dem lokalen mehrsprachigen Modell `BAAI/bge-reranker-v2-m3`
  neu bewerten. Keine Dokumenttexte an öffentliche Inferenzdienste schicken.
- Dokumente an Absätzen/Überschriften zerlegen; PDF-Seiten und genaue Abschnitte
  speichern, damit die folgende Quellen-Vorschau auf belastbaren Positionen beruht.
- Ohne hinreichend passende Quelle eine feste verständliche Antwort liefern,
  anstatt das Sprachmodell raten zu lassen. Betriebsfehler bleiben Betriebsfehler.

Nicht enthalten: Vorschau-Oberfläche (PILOT-03), weitere Oberflächen, Connectoren.

## Akzeptanz und Prüfung

- Echte PostgreSQL-Prüfung mit Synonymen, deutschen Wortformen und exakten
  Kennungen; fremde Organisationen gelangen nicht in die Kandidatenmenge.
- Echter lokaler Reranker unterscheidet passende und unpassende Textstellen.
- Dokumentierte Relevanzschwelle; keine Treffer führt ohne Modellaufruf zu
  „Dazu habe ich nichts gefunden.“; Antwort und leere Quellenliste bleiben
  nach Laden erhalten. Ausfall des Rerankers wird nicht als Nichtfund ausgegeben.
- Abschnitts-/Seitenmetadaten reisen durch Index, Suche und Chat-Zitate.
  Für Formate ohne verlässliche Seitengrenzen werden keine Seiten erfunden.
- Relevante Unit-/Integrationstests, echte Modelle, Build, laufende App und
  Screenshot; Zwischencommits. Merge-Gates nach AGENTS.md bleiben verbindlich.

## Modell-Laufzeit

Hugging Face Text Embeddings Inference dient als lokale Modell-Laufzeit
(CPU, internes Compose-Netz, persistenter Modellcache); die Java-Anwendung
bleibt der autoritative modulare Monolith. Kein neuer öffentlicher Produktdienst.
Modell und Protokoll: [BAAI-Modellkarte](https://huggingface.co/BAAI/bge-reranker-v2-m3),
[TEI-Dokumentation](https://huggingface.co/docs/text-embeddings-inference/en/quick_tour).
