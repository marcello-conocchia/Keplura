# Nachgeholte Reviewer- und Security-Prüfung (Review-Schuld 15./16.11.2026)

Auftrag: unabhängige Code- und Security-Prüfung der sechs Klasse-A-Änderungen, die per
PO-Ausnahme am 15./16.11.2026 ohne diese Gates nach `main` gemergt wurden
(`docs/PROJECT_STATE.md`, PO-ENTSCHEIDE 15.11. und 16.11.2026). UI-11 bis UI-13 gehören nicht dazu.

- **Geprüfter Stand:** `main@6a9a10a`. Bewertet wird der Code, wie er heute auf `main` liegt.
  Die ursprünglichen Merge-Commits dienten als Diff-Grundlage. Seit den Merges umbenannt
  (`com.intracore` → `com.keplura`, #82). Zeilenangaben beziehen sich auf `6a9a10a`.
- **Prüfer:** Claude, eigene Sitzung ohne Beteiligung an der Umsetzung. **Grenze der
  Unabhängigkeit:** RISK-01, OCR-01, #73, #74 und #76 stammen laut PROJECT_STATE ebenfalls von
  Claude-Sitzungen, also aus derselben Modellfamilie. Ob das als unabhängige Prüfung im Sinne
  von AGENTS.md gilt, entscheidet der PO. F-1 (#70) wurde von einer anderen Claude-Sitzung
  geschrieben; Codex hat es nicht geprüft.
- **Vorarbeit berücksichtigt:** Der Codex-Security-Review `docs/reviews/SECURITY-2026-11-16.md`
  (Stand `530a3a5`) deckte RISK-01 und OCR-01 bereits teilweise ab. S-6 (OCR-Zeichenbudget)
  wurde mit #72 geschlossen. Die dort genannte Grenze „PDFBox-Rendering nicht hart killbar“
  ist hier als O-1 konkretisiert und nachgewiesen.

## Ergebnis je Punkt

| # | Änderung | Merge | Urteil |
|---|---|---|---|
| 1 | Oversharing-Check RISK-01 inkl. Migration V24 | `fc2e0c1` (#68) | **FREIGEGEBEN** (2 niedrige Hinweise) |
| 2 | OCR für Scans OCR-01 (Tesseract) | `530a3a5` (#69) | **BEFUNDE**: O-1 mittel (Fix-PR), 2 niedrige |
| 3 | Klartext-Gründe gesperrte Linux-Dateien | `52da40f` (#73) | **FREIGEGEBEN** |
| 4 | Rechte-Nachweis für Admins | `fcba135` (#74) | **FREIGEGEBEN** (1 niedriger Hinweis) |
| 5 | Gehärteter Installer und Abnahmelauf | `05d2524` (#76) | **FREIGEGEBEN** (2 niedrige Hinweise) |
| 6 | F-1 aus #70: Hyperlinks erlaubt, andere externe OPC-Ziele abgelehnt | `617ad1f` (#70) | **FREIGEGEBEN** |

Es gibt keine hohen Befunde. Der einzige mittlere Befund ist O-1, die Korrektur liegt als
Draft-PR vor: **[marcello-conocchia/Keplura-intern#91](https://github.com/marcello-conocchia/Keplura-intern/pull/91) (privates Archiv)**
(Branch `claude/review-debt-ocr-worker`).

## Geprüft und in Ordnung (Kurzfassung)

1. **RISK-01:** Alle Endpunkte liegen unter `/api/v1/admin/**` (Session, CSRF, globale ADMIN-Rolle).
   Die globale Admin-Rolle ist laut ADR-0008 bewusst mandantenübergreifend. Der Scan liest nur
   die Dateiliste und die Rechte, mit demselben Konnektor und `Filter` wie der Abgleich. Für
   lokale Quellen läuft er über den FD-gebundenen nativen Leser (#72). Ein Verweigern hat
   Vorrang (`ExposureScanService.java:201`), unlesbare Rechte werden fail-closed gezählt.
   `/exclude` lehnt Traversal und absolute Pfade ab, begrenzt Anzahl und Länge und maskiert
   Glob-Sonderzeichen (Java-Glob mit `\`-Escape, geprüft). Die Ablage wird nie verändert.
   V24 verbreitert nur die Spalte, ohne Datenänderung (portabel H2/PG). Entfernt wird erst beim
   nächsten erfolgreichen Abgleich; das ist bereits in #71 dokumentiert.
2. **OCR-01:** `ProcessBuilder` ohne Shell, feste Argumente, Sprachen per Regex validiert,
   temporäre Dateien generisch benannt (Modus 0600), stdin `/dev/null`, stderr verworfen.
   Ausgabe je Seite und Gesamtbudget sind begrenzt (S-6, #72), der Prozess wird bei
   Zeitablauf hart beendet. Tesseract sieht nur das von PDFBox gerenderte Graustufen-PNG,
   nie die hochgeladenen Bytes. Echter Tesseract-Lauf (deu+eng) in dieser Umgebung grün.
3. **#73:** Exit-Codes 3/4 des Lesers werden nur übernommen, wenn der Prozess regulär endet
   (`NativeLocalReader.java:104`). Jede Ablehnung bleibt eine IOException, also gesperrt
   (fail-closed unverändert, `syncLocalSnapshot` widerruft weiterhin alte ACLs). Gründe sind
   feste Texte ohne Pfade.
4. **#74:** Das Urteil „sichtbar“ kommt aus derselben Abfrage wie Suche, Liste und Vorschau
   (`AccessExplainService.java:133`, `findByIdInKbForMember`). Die abgeleitete Begründung
   (`:187`) entspricht der Logik von `AccessFilter` (Allow nötig, Deny hat Vorrang, ohne
   ACL-Zeilen sehen es alle Mitglieder). Abweichungen werden gemeldet statt verdeckt. Nur
   GET-Aufrufe, nur Admins. Die LIKE-Suche escaped Spring Data selbst.
5. **#76:** Argumente werden vor jeder Dateiänderung validiert (E-Mail-Regex ohne
   Zeilenumbruch, Port 1–65535). Neue `.env.production` entsteht mit `umask 077`, Symlinks
   werden abgelehnt, das Admin-Passwort landet nicht im Terminal, ein zweiter Lauf lässt die
   Datei byte-gleich. Der Abnahmelauf sendet Zugangsdaten nur an `--base` und gibt im Fehlerfall
   nur eigene Texte aus.
6. **F-1:** `OfficePackagePolicy.java:86` lässt nur externe Beziehungen exakt vom Typ
   `…/2006/relationships/hyperlink` zu. Alle anderen externen Ziele (attachedTemplate, OLE,
   Bilder, Datenverbindungen) werden auf Paket- und Teilebene weiter abgelehnt. POI und Tika
   rufen Hyperlinks nie ab. Der Test deckt xlsx und pptx ab, positiv wie negativ. Strict-OOXML
   (`purl.oclc.org`-Typ) wird weiter abgelehnt; das ist fail-closed, eine Produktgrenze und
   kein Sicherheitsbefund.

## Befunde

### O-1 (mittel, OCR-01): Hängendes Rendering blockiert die OCR-Warteschlange — Fix-PR offen

- **Ort:** `server/src/main/java/com/keplura/server/search/ocr/PdfOcr.java:170` (Seitenaufruf auf
  dem einzigen Worker `:74`). Das Rendering in `recognizePage` (`:205–211`) läuft im Serverprozess.
- **Szenario:** Ein angemeldetes Mitglied lädt ein PDF hoch, dessen Seite beim Rendern länger
  als das Seitenlimit (60 s) braucht, etwa durch pathologischen Vektorinhalt oder eine
  PDFBox-Schleife. `f.cancel(true)` stoppt PDFBox nicht. Jede weitere Seite, auch die späterer
  Dokumente, wartet dann das volle Seitenlimit hinter der hängenden Seite: bis zu 900 s je
  gescanntem PDF. Das geschieht auf dem einzigen Indexierungs-Thread (`KEPLURA_INDEXING_WORKERS=1`),
  also staut sich die gesamte Indexierung. Chat-Anhänge warten jeweils 90 s.
- **Nachweis:** Der Regressionstest `PdfOcrTest.stuckPageDoesNotMakeLaterPagesWaitBehindIt`
  simuliert ein nicht unterbrechbares Rendering. Auf `main` braucht das zweite Dokument
  **4002 ms** (4 Seiten × 1 s Limit), mit Korrektur unter 900 ms. Danach läuft OCR wieder normal.
- **Fix (PR):** `PdfOcr` merkt sich eine Seite, deren Aufrufer aufgegeben hat, die aber noch
  läuft. Solange sie läuft, werden weitere Seiten sofort als fehlgeschlagen übersprungen
  (sie bleiben ohne Text, wie bisher bei Zeitablauf). Kein Warten, keine neue Abhängigkeit.
- **Restrisiko:** Die hängende Seite belegt weiter einen CPU-Kern, und OCR ruht, bis sie fertig
  ist. Die vollständige Lösung wäre, das Rendering in einen eigenen, killbaren Prozess zu
  verlegen. Das wäre eine eigene Klasse-A-Aufgabe, falls der PO das will.

### O-2 (niedrig, OCR-01): Tesseract erbt die gesamte Server-Umgebung

- **Ort:** `PdfOcr.java:227–230`. `ProcessBuilder` übernimmt alle Umgebungsvariablen, darunter
  `KEPLURA_DB_PASSWORD` und `KEPLURA_SECRETS_MASTER_KEY`.
- **Szenario:** Eine Schwachstelle in Tesseract oder ein falsch gesetztes `KEPLURA_OCR_COMMAND`
  sähe die Geheimnisse direkt. Der Nutzen ist begrenzt, denn derselbe Benutzer könnte
  `/proc/<pid>/environ` lesen. Es ist reine Härtung.
- **Fix:** `pb.environment().clear()`, danach nur `PATH`, `OMP_THREAD_LIMIT` und, falls gesetzt,
  `TESSDATA_PREFIX` übernehmen.

### O-3 (niedrig, OCR-01): PDF wird je Seite neu geladen

- **Ort:** `PdfOcr.java:205`. `Loader.loadPDF` läuft für jede Seite erneut, bei 100 Seiten also
  100-mal das ganze Dokument. Das kostet nur Leistung, schwächt aber die Grenze des
  Gesamtzeitlimits ab.
- **Fix:** Das Dokument einmal je `recognize`-Aufruf auf dem Worker laden (zusammen mit einer
  Umstellung auf einen eigenen Prozess, siehe O-1).

### R-1 (niedrig, RISK-01): Ausschluss wirkt nicht bei Pfaden mit Leerzeichen am Rand

- **Ort:** `ExposureRules.java:118` maskiert keine Leerzeichen, `DataSourceSyncService.java:472`
  trimmt jedes Muster. `ExposureScanService.java:112` entfernt den Treffer trotzdem aus dem Bericht.
- **Szenario:** Auf einem Linux-Ordner liegt `personal/gehalt.xlsx ` (mit abschließendem
  Leerzeichen). Der Admin klickt „Ausschließen“. Das gespeicherte Muster passt nicht mehr,
  die Datei bleibt übernommen und verschwindet trotzdem aus der Trefferliste. Geprüft mit
  `PathMatcher`: kein Treffer. Unter Windows/SMB praktisch ausgeschlossen.
- **Fix:** Leerzeichen am Anfang und Ende in `excludePatternFor` als `[ ]` maskieren, oder
  solche Pfade in `/exclude` mit einer klaren Meldung ablehnen.

### R-2 (niedrig, RISK-01): Ausschluss kann bei gleichzeitiger Bearbeitung verloren gehen

- **Ort:** `DataSourceAdminController.java:194–212`. Die Muster werden gelesen, ergänzt und
  geschrieben; `DataSource` hat kein `@Version`.
- **Szenario:** Zwei Admins speichern gleichzeitig, einer per `/exclude`, der andere per PATCH
  der Muster. Der spätere Schreibvorgang überschreibt den Ausschluss still.
- **Fix:** Optimistische Sperre (`@Version`, braucht eine Migration) oder `SELECT … FOR UPDATE`
  in `exclude`.

### A-1 (niedrig, #74): Übersicht mit zwei Zählabfragen je Wissensbasis

- **Ort:** `AccessExplainService.java:218–224`. Je Mitgliedschaft, Arbeitsbereich und
  Wissensbasis laufen zwei COUNT-Abfragen. Das betrifft nur die Leistung bei vielen
  Wissensbasen, nicht die Korrektheit.
- **Fix:** Eine gruppierte Abfrage je Organisation.

### I-1 (niedrig, #76): Erstinstallation schreibt `.env.production` nicht atomar

- **Ort:** `deploy/install.sh:51–62`. Die Datei entsteht in zwei Schritten (`sed >`, dann `>>`).
- **Szenario:** Bricht der Lauf zwischen den Schritten ab (Strg+C), bleibt eine Datei ohne
  Master-Key und ohne Admin-Zugang liegen. Der nächste Lauf meldet „Bestehende Zugangsdaten
  bleiben erhalten“ und startet einen Server ohne Admin.
- **Fix:** Die Datei vollständig in eine `mktemp`-Datei schreiben und erst dann per `mv` umbenennen.

### I-2 (niedrig, #76): Abnahmelauf erlaubt Klartext-HTTP zu entfernten Hosts

- **Ort:** `deploy/pilot-check.cjs:30`.
- **Szenario:** `--base http://server.firma.example:8080` überträgt das Bootstrap-Admin-Passwort
  unverschlüsselt im Netz.
- **Fix:** Für Hosts außer `localhost`, `127.0.0.1` und `::1` nur `https:` zulassen.

## Tatsächlich ausgeführte Prüfungen

Alle Läufe am 19.11.2026 in dieser Cloud-Umgebung, Java-Tests als **Nicht-root-Benutzer**
mit installiertem `acl`, gebautem nativem Leser (`KEPLURA_LOCAL_READER`), echtem Tesseract
5 (deu+eng) und UTF-8-Locale:

- `main@6a9a10a`: `OfficeFormatsTest` 17, `PdfOcrTest` 11 (inkl. echtem Tesseract),
  `ExposureRulesTest` 7, `ExposureScanServiceTest` 6 (inkl. echter POSIX-Rechte),
  `ExposureAdminIntegrationTest` 6, `AccessExplainIntegrationTest` 4,
  `NativeLocalReaderReasonTest` 3, `LocalSourceConnectorTest` 7: **alle grün, 0 übersprungen**.
  Ein erster Lauf als root ohne Leser, `setfacl` und UTF-8 hatte 8 Umgebungsfehler; das ist
  wie in AGENTS.md beschrieben ein Umgebungsfehler, kein Produktfehler.
- Nativer Leser: `cargo test --locked` 7/7 grün (als root ausgeführt).
- Installer: `deploy/test-install.sh` 11/11 PASS, `node deploy/pilot-check.test.cjs` 5/5.
- O-1: Der Regressionstest schlägt auf `main` fehl (4002 ms) und ist mit Korrektur grün.
  Alle Server-Tests unter `com.keplura.server.search` (12 Klassen) sind mit Korrektur grün.
  `ci-local.sh` für den Fix einmal vollständig grün auf `12d4cd6` (Nicht-root, 540 Tests), Evidence im Fix-PR unter `ci/evidence/O-1-20261119-214304/`.
- **Nicht geprüft:** echter SMB/AD-Lauf des Oversharing-Checks, Browser/E2E, echter
  Installer-Lauf mit Docker (kein Docker-Daemon in dieser Umgebung), PostgreSQL statt H2,
  Abnahmelauf gegen echte Modelle.

Diese PR ändert nur Dokumentation (docs-only), deshalb ist kein CI-Lauf nötig (AGENTS.md,
Lean verification 2). Merge entscheidet der PO.
