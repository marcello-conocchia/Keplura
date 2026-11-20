# ADR-0010: Server-Paket — gebündeltes Ollama, Auto-Modell-Pull, Vektor-Dimension folgt dem Embedding-Modell

Date: 2026-11-14
Status: Accepted (PO-Reihenfolge Punkt 1 „Server-Paket")
Amends: ADR-0007 §6 („Dimension ändern = neue Migration")

## Kontext

V5 legt `kb_chunk_embedding.embedding` fest als `vector(64)` an (Hash-Embedding).
Ein echtes Modell (bge-m3, 1024) fiel deshalb still auf die In-Memory-Suche
zurück. Ziel des Inhabers: wiederholbare Kundeninstallation mit einem Befehl.

## Entscheidung

1. **Dimension zur Laufzeit abgleichen** (`PgVectorSchema`, vor der Store-Probe):
   Weicht die Spaltendimension von `keplura.search.embedding-dim` ab und sind
   `kb_chunk` UND `kb_chunk_embedding` leer, wird die Spalte per
   `ALTER TABLE … TYPE vector(N)` umgetypt (eine Transaktion, ACCESS-EXCLUSIVE-Lock
   auf beide Tabellen, danach Recount). Ein befüllter Index wird NIE umgetypt —
   Vektoren zweier Modelle sind nicht vergleichbar; dann gilt weiter ADR-0007
   (auto ⇒ protokollierter Memory-Fallback, `pgvector` ⇒ Boot bricht ab).
   Abschaltbar: `KEPLURA_SEARCH_AUTO_ADAPT_DIMENSION=false`.
2. **Keine Flyway-Placeholder-Migration:** eine versionierte Migration läuft nur
   einmal und Placeholder-Änderungen kollidieren mit der Checksum-Validierung.
   V5 bleibt eingefroren; die Twin-Tabelle ist keine JPA-Entity (`validate` unberührt).
3. **Grenze 1..4095 Dimensionen** — passt worst case in `kb_chunk.embedding VARCHAR(65536)`.
4. **Compose-Standard:** Ollama ist Teil des Stacks (kein Profil, kein Port nach
   außen); Job `ollama-pull` zieht Chat-Modell (Default `qwen2.5:7b`) und
   Embedding-Modell (Default `bge-m3`, 1024) bevor der Server startet.
   Defaults: `EMBEDDING_BACKEND=gateway`. GPU per `compose.gpu.yml`.
   Air-gapped: `KEPLURA_SKIP_MODEL_PULL=1` + vorbefülltes Volume.
5. **`deploy/install.sh`:** erzeugt `.env.production` mit Zufalls-Secrets
   (DB-Passwort, Master-Key, Erst-Admin), startet, wartet auf Health.

## Konsequenzen

- Frische Installation: semantische, mehrsprachige Suche ohne Handarbeit.
- Modellwechsel bei befülltem Index braucht Re-Index (Roadmap Punkt 3, noch nicht da).
- Ein Admin-DB-Embedding-Modell (ADR-0008) mit anderer Dimension als ENV
  scheitert weiterhin laut beim Indexieren; die Spalte folgt nur der ENV-Dimension.
- Erstinstallation lädt mehrere GB Modelle (bge-m3 ≈ 1,2 GB, qwen2.5:7b ≈ 4,7 GB).
