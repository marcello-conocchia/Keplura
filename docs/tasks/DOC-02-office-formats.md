# DOC-02 — Excel und PowerPoint als Dokumenttypen

PO-Auftrag 15.11.2026. Klasse A: Upload und Parser. Draft-PR, kein Merge,
kein Push auf main, kein Release. Unabhängige Reviews und QA bleiben Merge-Gates.

## Ziel

XLSX/PPTX werden wie PDF/DOCX/TXT/MD/CSV hochgeladen, aus Datenquellen
abgeglichen und als Chat-Anhang gelesen. Suchtreffer aus Präsentationen nennen
die physische Folie; Tabellen nennen den Blattnamen.

## Scope

- Zentrale Upload-Policy und Parser-Routing um XLSX/PPTX erweitern. Der bestehende
  Datenquellen-Abgleich übernimmt die Policy ohne Änderungen unter datasource/.
- Apache Tika mit dem vorhandenen Microsoft-Modul; keine neue Runtime-Abhängigkeit.
- XLSX: Blattüberschriften, Zeilen und Tabulatoren zwischen Zellen, gespeicherte
  berechnete Formelwerte. Keine Neuberechnung oder Auflösung externer Daten.
- PPTX: Präsentationsreihenfolge, Abschnitt „Folie n“, echte Seitentrenner,
  Sprechernotizen auf derselben Folie. Keine Mastertexte.
- Strukturelle Vorprüfung im Parser-Zeitbudget: höchstens 200 Folien,
  50 Blätter und insgesamt 200.000 gespeicherte Zellen (auch leere formatierte).
- Dieselben Zeichen-, Zeit-, Pool- und ZIP-Grenzen wie DOCX; eingebettete
  Dokumente nicht verfolgen. Makro-Pakete und externe OPC-Beziehungen ablehnen.
- Folienzuordnung bleibt über Index, Suchtreffer, Chat-Zitat, Textvorschau und
  Antwortexport erhalten; das bestehende Wire-Feld pageNumber wird weiterbenutzt.

## Non-Goals / Dateigrenzen

Keine Binärformate XLS/PPT/DOC, keine XLSM/PPTM, keine Makroausführung,
kein Rendern aktiver Office-Dateien, keine Formel-Neuberechnung, keine neuen
Rechte- oder Synchronisationsregeln. Unverändert: server/.../datasource/**,
server/.../access/**, search/ocr/** und ExposureDialog.vue.

## Acceptance Criteria

1. Kleine echte POI-Dateien liefern Blatt-/Folientext, Formelwerte und Notizen.
2. Foliennummern stimmen auch bei leerer erster und umsortierter Folie.
3. Übergrenzen und ZIP-Bomben werden abgelehnt; kein stiller Teilindex.
4. Alte Binärformate und falsch umbenannte Office-Pakete werden abgelehnt.
5. Gemeinsame Policy gilt für Upload und Datenquellen; Chat-Anhänge routen
   XLSX/PPTX durch denselben Extraktor.
6. Doku und tatsächlich ausgeführte Prüfungen stehen im PR; CI-Lücken offen benennen.

## Tests

OfficeFormatsTest erzeugt XLSX/PPTX per POI: Inhalte, Formelcache, Notizen,
Präsentationsreihenfolge, Strukturgrenzen inklusive zulässiger Grenzwerte,
Zeichen-/Entry-/Inflate-Gates, eingebettete Dateien, Makros und externe Beziehungen.
Vorhandene PDF/DOCX-, Chunker-, Upload- und Exporttests bleiben Regressionstests.
API-Tests für Office-Upload und Chat-Anhang sowie tatsächliche Chunk-Persistenz.
`bash scripts/ci-local.sh` soweit die Umgebung es erlaubt. Keine ungeprüften
Behauptungen über Browser-/native QA oder unabhängige Security-Freigaben.

## Workspace

/workspace/Keplura, Branch codex/office-formats von origin/main@530a3a5.
