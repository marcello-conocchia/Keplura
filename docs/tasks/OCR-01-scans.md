# OCR-01 — Texterkennung für gescannte Dokumente

Ursprünglich für Codex vorgesehen; PO 15.11.2026: „codex läuft nicht du musst ocr auch mit übernehmen“ → von Claude umgesetzt.

PO-Freigabe 15.11.2026: „was sind die nächsten schritte du kannst dran arbeiten und parallel kann ich
codex auch arbeiten lassen“ — auf Basis der Produktstrategie (Must-have „OCR für Scans“).

## Ziel

Gescannte PDFs und Bilder liefern heute `NO_TEXT_LAYER` und sind für Suche und Chat unsichtbar. Auf echten
Fileservern ist das ein großer Anteil (Verträge, Lieferscheine, alte Handbücher). Nach OCR-01 werden sie
lokal per Texterkennung indexiert, mit Seitenangabe zitierbar und in der Quellen-Vorschau lesbar.

## Scope

- Lokale OCR, keine Cloud: Tesseract (`tesseract-ocr` + `tesseract-ocr-deu` + `-eng`) im Server-Image,
  angesteuert über Tika (`TesseractOCRParser`) oder direkt per Prozessaufruf mit festen Argumenten.
- Auslöser: PDF ohne Textschicht (heutiger `NO_TEXT_LAYER`-Pfad) und gemischte PDFs (Seiten ohne Text).
  Optional zusätzlich Bildformate PNG/JPG/TIFF als neue erlaubte Upload-Typen.
- Seitengenau: OCR-Text behält die PDF-Seitennummer (V13-Metadaten), damit Zitate „Seite 3“ nennen.
- Grenzen wie beim bestehenden Parser: Zeit-Limit je Dokument, maximale Seitenzahl (z. B. 200),
  CPU-Begrenzung (Warteschlange `KEPLURA_INDEXING_MODE=async` nutzen, max. 1 OCR-Job parallel).
- Ehrlicher Status: neuer Indexstatus bzw. Hinweis „per Texterkennung gelesen“; ist OCR abgeschaltet oder
  fehlt Tesseract, bleibt `NO_TEXT_LAYER` mit verständlicher Meldung.
- Schalter `KEPLURA_OCR_ENABLED` (Standard: an, wenn Tesseract vorhanden), Sprachen `KEPLURA_OCR_LANGS=deu+eng`.

## Non-Goals

- Keine Handschrifterkennung, keine Layout-/Tabellenrekonstruktion, kein Cloud-OCR, kein GPU-Zwang.
- Keine Änderungen an Datenquellen-, Rechte- oder Admin-Datenquellen-Code (dort arbeitet Claude, s. u.).

## Dateigrenzen (wichtig, paralleles Arbeiten)

Codex darf ändern: `server/src/main/java/com/keplura/server/search/TikaDocumentTextExtractor.java`,
`search/DocumentIndexingService.java`, `knowledge/DocumentUploadPolicy.java` (nur neue Bildtypen),
neue Klassen unter `search/ocr/`, `deploy/Dockerfile.server`, `deploy/README.md`, `docs/development.md`
(Abschnitt OCR), zugehörige Tests, Desktop nur Statustexte in `desktop/src/components/knowledge/*`.

Codex ändert NICHT: `server/.../datasource/**`, `server/.../access/**`, `desktop/src/components/admin/DataSourcesPanel.vue`,
`desktop/src/datasource-api.ts`. Falls eine Flyway-Migration nötig ist: **V25** verwenden (V24 ist für RISK-01 reserviert,
auch wenn RISK-01 sie voraussichtlich nicht braucht). `docs/PROJECT_STATE.md` nur mit einem eigenen Absatz ergänzen.

## Acceptance Criteria

1. Ein gescanntes deutsches PDF (2+ Seiten) wird indexiert; Suche nach einem Wort von Seite 2 findet es;
   Chat-Antwort zitiert „Seite 2“; Quellen-Vorschau zeigt den erkannten Text.
2. Gemischtes PDF: Textseiten unverändert über die Textschicht, Bildseiten per OCR.
3. Tesseract fehlt / OCR aus → Status `NO_TEXT_LAYER` wie bisher, kein Absturz, verständliche Meldung.
4. Zeit- und Seitenlimit greifen (Test mit künstlich großem PDF), Indexierung anderer Dokumente läuft weiter.
5. Kein Prozessaufruf mit Benutzer-/Dateinamen in der Kommandozeile (nur temporäre Datei mit festem Namen).
6. Server-Image baut; Imagegröße vorher/nachher dokumentiert.

## Tests

Unit-Tests für Erkennung „Seite ohne Text“, Limits und Abschaltung; Integrationstest mit kleinem eingecheckten
Scan-PDF (nur synthetischer Text, keine echten Daten); `bash scripts/ci-local.sh` auf dem finalen Commit.

## Workspace

Eigener Worktree + Branch `claude/ocr-scans` von `origin/main`. Draft-PR, kein Merge ohne Gates (AGENTS.md).

## Umsetzung und Prüfstand (ehrlich)

- `search/ocr/PdfOcr` (Rendern + Tesseract-Prozess, Limits) und `TikaDocumentTextExtractor.withOcr`
  (nur leere Seiten, Seitentrennung erhalten, Chat-Anhänge max. 10 Seiten). Tika selbst bleibt `NO_OCR`.
- Bildtypen (PNG/JPG/TIFF) und ein eigener Status „per Texterkennung gelesen“ sind NICHT umgesetzt
  (würden Upload-Policy bzw. Status-Constraint ändern); `ExtractedText.ocrPages` liefert die Zahl intern.
  Keine Migration nötig (V25 bleibt frei).
- Geprüft: `PdfOcrTest` 7/7 unter Linux mit echtem Tesseract 5.3.4 (deu+eng), u. a. deutscher Scan mit
  Umlauten; `TikaDocumentTextExtractorTest` 18/18 und `DocumentIndexingStatusTest` 4/4 im selben Lauf;
  unter Windows ohne Tesseract dieselben Suiten grün, echter OCR-Test übersprungen.
- `ci-local.sh` (Standardstufen, ohne E2E) im frischen Container: Exit 0, 456 Server-Tests / 67 Klassen,
  1 übersprungen (der Echt-Tesseract-Test, CI-Image ohne Tesseract), Desktop-Build und `cargo check` grün.
  Evidence `ci/evidence/OCR-01-20261115-195447/`.
- Server-Image `deploy/Dockerfile.server` gebaut (`keplura-server:ocr01`): **722 MB statt 606 MB (+116 MB)**;
  enthält Tesseract 5.3.4 mit `deu`, `eng`, `osd`; läuft weiterhin als Benutzer `keplura` (uid 1000).
- Laufende App (15.11.2026, isolierter Demo-Container mit RISK-01 + OCR-01, Tesseract per apt, H2, lokale
  Hash-Embeddings, kein Chat-Modell): zweiseitiges Bild-PDF ohne Textschicht (schräg, verrauscht) hochgeladen →
  `INDEXED` in 2,4 s; Suche „Ölwechsel Betriebsstunden“ → erster Treffer „Seite 2 · Abschnitt 2“ mit
  fehlerfrei erkanntem Text inkl. Umlauten; Quellen-Vorschau zeigt beide Seiten und markiert die Passage auf Seite 2.
- OFFEN: Chat-Antwort mit Zitat „Seite 2“ gegen ein echtes Modell; OCR innerhalb des gebauten Produktions-Images
  (die Demo lief im CI-Image mit nachinstalliertem Tesseract); unabhängige Gates (Klasse A).
- Befund aus PO-Test (15.11.2026): eigener mehrseitiger Scan, synchrone Indexierung (Dev-Standard) →
  Server speicherte nach 61,5 s erfolgreich (201), die Oberfläche brach den Upload nach 60 s ab und meldete
  fälschlich einen Fehler. Behoben: Upload-Zeitlimit der Oberfläche 16 min (über dem OCR-Gesamtlimit 900 s);
  Chat-Anhänge bekommen serverseitig ein OCR-Budget von 90 s (Oberfläche wartet 120 s). Produktion indexiert
  asynchron und war nicht betroffen. Der CI-Nachweis oben gilt für den Stand VOR dieser Korrektur;
  vor dem Merge `ci-local.sh` auf dem finalen Commit erneut ausführen.
