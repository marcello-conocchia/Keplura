# Quellen-Frische im Zitat

PO-Auftrag 15.11.2026, Klasse B gemäß ausdrücklich beauftragter risikobasierter Prüftiefe (PR #66): additive Quellenmetadaten und UI, keine neuen Rechte/externen Aufrufe/Abhängigkeiten. Branch `codex/source-freshness` von `origin/main@ff18bd9`, eigener Worktree `source-freshness`.

Ziel: Chat-Zitat, Wissenssuchtreffer und Textvorschau zeigen „Stand: <Datum>“. `sourceUpdatedAt` verwendet die Original-Änderungszeit einer importierten Datei, sonst das Upload-Datum. Datum kommt mit den bestehenden tenant-/ACL-gefilterten Abfragen; keine Migration/Tabelle. Älter als drei Kalenderjahre: neutraler Zusatz „älteres Dokument“, Tooltip „Stand vom …, bitte prüfen, ob es aktuell ist“. Historische Zitate ohne Metadaten zeigen ehrlich „Stand: unbekannt“; keine Datenerfindung oder Umschreibung alter Antworten.

Gesperrte Pfade unverändert: datasource/**, access/**, TikaDocumentTextExtractor, search/ocr/**, DataSourcesPanel.vue, ExposureDialog.vue und AGENTS.md. Kein Merge ohne PO-Blick, keine Wiederverwendung der einmaligen früheren Merge-Ausnahme.

Abnahme: Original- vs. Upload-Datum und Metadatenerhalt nach Ranking/Chat-Persistenz geprüft; Altersgrenze einschließlich genau drei Jahren und fehlender Werte; sichtbare frische und alte Quelle in einem Screenshot; Build und `bash scripts/ci-local.sh` auf finalem PR-SHA grün. Evidence wird im PR verlinkt, ohne den geprüften Produkt-SHA nachträglich zu verändern. Kein separates UI-Inventar oder neue Browser-Prüfkampagne.

PO-Feinschliff nach Screenshot: Quellen im Chat ohne Rahmen, Fläche oder Positionsmetadaten. Dateiname führt, Stand/Alter klein darunter; eine kleine Quellennummer erhält die Zuordnung zu Verweisen wie [1] in der Antwort; Seiten-/Abschnittsangaben in der Vorschau. Dieselbe Darstellung gilt während des Streamings. Alterszusatz als neutraler Text statt zusätzlichem Badge.
