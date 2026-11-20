# Unabhängiger Code-Review PR #72 — finaler Nachtrag

Geprüfter SHA: **9feb4e28283c5f637886faae10db950257cbb26f**.
Rust-/Snapshot-Nachtrag vollständig gegen 846dea28f0ec35991787d55be3dc46ad2f9e3779 geprüft.
Basis des ursprünglichen Reviews: origin/main@530a3a5.
OCR-Nachtrag separat geprüft gegen d695ff0649b38be1dc0d3ff61234776797b6ecad.
Rolle reviewer; keine Produktänderungen, kein Push/Merge, keine externen Kommentare.

## Ergebnis

Keine konkreten neuen funktionalen Fehler oder Sicherheits-/Abnahmeverletzungen im Produktdiff gefunden. Die reviewer-Code-Freigabe gilt für **9feb4e28283c5f637886faae10db950257cbb26f**. Sie ersetzt keine Security-/QA-Freigabe oder den finalen lokalen CI-Gate. Der ursprüngliche R-1-Pfad ist nach PO-Zustimmung durch den unten geprüften Snapshot-Nachtrag ersetzt. Eine unabhängige Security-Freigabe erteilt weiterhin der Security-Reviewer selbst.

## OCR-Nachtrag

PdfOcr.java und die drei neuen Regressionstests vollständig statisch geprüft. readCapped verwirft Überlängen jetzt mit TruncatedExtractionException statt einer erfolgreichen Teilzeichenfolge. Die Exception bleibt sowohl über den stdout-Leser und exec als auch über den OCR-Worker/Future und recognize bis zu beiden Extractor-Einstiegen erhalten. Damit wird der im ursprünglichen Review vermerkte Altbestand der stillen OCR-Seitenkürzung behoben. Byte- und Zeichengrenzen sind begrenzt; exakte Grenzwerte bleiben zulässig. Der stdout-Leser wird vor dem Prozessende beobachtet und beide Wartephasen teilen dieselbe Deadline. Der Prozess wird im finally auch bei Limitfehler beendet. Keine neuen Befunde in diesem Nachtrag.

Die Regressionen prüfen exakten Unicode-Grenzwert, Zeichen- und Byteüberschreitung, den echten Child-JVM-Ausgabepfad sowie Fehlerweitergabe für Upload- und Chat-Anhang-Extractor. Wegen parallel laufendem Voll-CI wurde auf diesem Nachtrags-SHA ausdrücklich kein eigener Maven-Lauf gestartet. Die vom Orchestrator gemeldeten Ergebnisse sind keine selbst ausgeführte Testevidenz dieses Reviews.

## Ursprüngliche Prüfung

AGENTS.md, docs/tasks/SEC-REVIEW-FIXES.md, gesamter Produktdiff und relevante bestehende Aufrufer gelesen. Schwerpunkt: ACL-Auswertung einschließlich Eltern, Fehlerpfade des Syncs, gemeinsame Session-Erzeugung und aktueller DB-Passwortstatus, CORS-Pfade/-Verben, SSRF-Dial-Policy und Redirects, OCR-Merge-Gesamtbudget, Trennung des MCP-Aktionskontexts vom Antwortkontext und Begrenzung auf acht Aufrufe.

Selbst auf d695ff0649b38be1dc0d3ff61234776797b6ecad ausgeführt: Maven LocalSourceConnectorTest,PdfOcrTest,WebSearchSsrfTest, exit 0. Zusammen 15 Tests, 14 bestanden, 1 übersprungen: realTesseractReadsAGermanScan (deu-Sprachpaket fehlte zu diesem Zeitpunkt). Kein Testfehler. Produktdiff und OCR-Nachtrag ohne Whitespace-Fehler; Evidence-Logs wurden nicht als Reviewgegenstand bewertet.

## Grenzen

Kein eigener Browserlauf, kein eigener OIDC-/Auth-/MCP-Integrationstest und kein kompletter ci-local-Lauf. Keine Behauptung einer Prüfung jedes denkbaren ACL-Rennens oder DNS-Pinnings. Kein eigener Docker-Image-Build. Die finale Java-/CI-Ausführung und echtes OCR laufen beim Orchestrator; ihr Ergebnis wird hier nicht vorweggenommen.


## Rust-/Snapshot-Nachtrag auf 9feb4e2

Vollständig gelesen: Rust-Helfer einschließlich Cargo.toml/Lockfile und sechs Tests, Java-Protokollbrücke NativeLocalReader, LocalSourceConnector, syncLocalSnapshot, sourceContentMatches, alle geänderten Java-Tests, CI-Voraussetzung, Docker-Buildstufe und Betriebs-/Aufgabendokumentation.

Die native Erweiterung bindet ACL/Metadaten und Bytes an dieselben offenen Handles. Jeder Pfadteil wird mit NOFOLLOW traversiert; ACL- und Dateisystemprüfung verwenden FDs. Die offenen Datei-/Verzeichnisobjekte werden vor und nach der Kopie mit Modus, Eigentümer, Größe und Änderungsmarkern verglichen. Java gibt die temporäre Kopie und daraus abgeleitete ACL erst nach validiertem Protokoll und erfolgreichem Helferexit frei.

Der Sync verwendet die Snapshot-SHA statt Listing-Größe/mtime zur Inhaltsentscheidung; ACL und Dokumentzuordnung werden in einer Transaktion aktualisiert. Ein Snapshot-/Verarbeitungsfehler setzt für eine bekannte Datei eine eigene Deny-all-ACL. Der ursprüngliche R-1-Pfad, bei dem ACL(path) und open(path) verschiedene Inodes kombinieren konnten, wird im lokalen Spiegelungspfad nicht mehr verwendet. Kein konkreter neuer oder verbliebener Befund zu diesem Nachtrag aus reviewer-Sicht.

Selbst auf diesem SHA ausgeführt: cargo test --manifest-path server/native/local-reader/Cargo.toml --target-dir /workspace/scratch/code-review-72-native --locked --offline mit der bereitgestellten Scratch-Toolchain. **6 bestanden, 0 fehlgeschlagen, 0 ignoriert; exit 0.** Isoliertes Target außerhalb des Repos; kein Maven während des parallelen Voll-CI gestartet. git diff --check 846dea2 9feb4e2 ebenfalls exit 0.

Grenze: Keine allgemeine sofortige Rechte-Revokation bei Verbindungs-/Listingausfall; dieser separate Altbestand und die notwendige Wartungsphase zum Bestandsabgleich sind in der Betriebsdokumentation ausdrücklich behandelt.
