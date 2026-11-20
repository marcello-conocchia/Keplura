# Security-Nachreview PR #72 – finaler Produktstand

**Geprüfter SHA: `9feb4e28283c5f637886faae10db950257cbb26f`**, Branch `codex/security-fixes`, Basis `530a3a5edb2e3b777e6c64cef14709d3e85b2f2d`. Datum: 16.11.2026.

**Status: SECURITY APPROVED für diesen SHA durch den unabhängigen Security-Review-Agenten. Keine offenen Security-Befunde im beauftragten Prüfumfang. R-1 ist geschlossen.** Dies ist eine Agentenprüfung und keine menschliche GitHub-Freigabe des Repository-Inhabers. Andere Merge-Gates, insbesondere finales vollständiges lokales CI und QA, werden hierdurch nicht ersetzt.

## Prüfumfang und Befundabschluss

AGENTS.md, docs/tasks/SEC-REVIEW-FIXES.md, ursprünglicher SECURITY-2026-11-16.md sowie echter Produktdiff gegen die Basis gelesen. Erster Review auf d695ff0, anschließendes eigenständiges Review der OCR- und R-1-Nachbesserungen und Zuordnung zum finalen Commit. Die Produktdateien entsprechen während dieses Abschlussreviews exakt 9feb4e2; nur neu erzeugte CI-Evidence-Verzeichnisse waren untracked. Keine Produktänderung, kein Push/Merge, keine externen Kommentare.

- **S-1/S-2/S-4 geschlossen:** gezielte CORS-Mappings für me/password/Admin-DELETE; Restore ohne bestätigtes Profil verwirft den Anmeldestatus; einheitlicher Sessionaufbau für lokalen/LDAP-/OIDC-Login. Schreibgate liest den aktuellen Kontozustand, sodass ein Adminreset bestehende Sessions einschränkt. Keine Erweiterung der Originliste oder Aufhebung von CSRF/Rollengates im Diff.
- **S-3 geschlossen:** Gruppenrechte stammen nicht mehr aus einer möglicherweise als Gruppenbits erscheinenden ACL-Maske. Der native Leser verweigert vorhandene POSIX-Access-ACL-xattrs, unzulässige Dateisysteme und unerwartete Abfragefehler. Datei und alle Verzeichnisse bis einschließlich Quellwurzel werden über gehaltene Handles geprüft (`server/native/local-reader/src/main.rs:20`, `:44`). NFS/CIFS/ZFS werden durch die Dateisystem-Allowlist nicht als unterstützte lokale Rechtequelle freigegeben.
- **S-5 geschlossen:** SearXNG validiert Basis-URI und tatsächliches Ziel mit derselben SsrfGuard-Shape-/Dial-Policy wie MCP, einschließlich Betreiber-CIDRs. Keine Credentials, Query oder Fragmente in Basis-URLs; Redirect.NEVER bleibt wirksam. DNS-Pinning ist unverändert ausdrücklich außerhalb des Auftrags.
- **S-6 einschließlich Nachbefund geschlossen:** gemeinsames Tika-/OCR-Gesamtbudget zählt Seitentrenner und Ersatztext. Zusätzlich wirft PdfOcr.readCapped bei Seitenüberschreitung statt still zu kürzen. Der Fehler wird aus Prozessoutput und OCR-Worker als TruncatedExtractionException bis zum Dokumentpfad weitergereicht. Eigene Proben bestätigen Grenzwert und sofortige Reaktion auf weiterlaufenden Prozess mit zu großer Ausgabe.
- **S-7-Technikgrenze geschlossen:** Planer sieht ausschließlich den aktuellen direkten Nutzerprompt und freigegebene Werkzeugdefinitionen; Verlauf, Anhänge, RAG, Web und Ergebnisse fehlen im Aktionskontext. Nach einem Batch hat die Antwort keine Werkzeuge; ein weiterer vom Modell ausgegebener Toolcall wird nicht ausgeführt. Maximal acht Aufrufe bleiben bestehen. Das ist eine technisch geprüfte Begrenzung der Aktionsautorität, keine Garantie gegen jede semantische Fehlinterpretation eines direkten Nutzerauftrags oder einer Werkzeugbeschreibung.

## R-1: konkreter Nachweis und Grenzen

Der zuvor reproduzierte Ablauf `acl(0644-Datei) → atomarer Austausch durch private 0600-Datei → open()` wird im produktiven lokalen Rechte-Mirrorpfad nicht mehr verwendet. `DataSourceSyncService.java:287` verzweigt zu `syncLocalSnapshot`; `:340` erhält eine gemeinsame Aufnahme und `:356` verwendet deren ACL.

Der Rust-Leser öffnet jeden relativen Pfadteil mit openat/O_NOFOLLOW und hält Datei-/Verzeichnis-FDs. Die Zugriffsrechte, UID/GID und ACL-/Dateisystemprüfung beziehen sich auf diese Objekte; der Inhalt wird vom selben Datei-FD gelesen. Vor und nach der Kopie werden dev/ino/mode/uid/gid/size/mtime/ctime einschließlich Nanosekunden geprüft (`main.rs:36`, `:72`, `:101`). Ein ausgetauschtes Blatt oder Elternverzeichnis kann den offenen Datenstrom daher nicht auf einen anderen privaten Inode umleiten. Eine während der Ausgabe geänderte Berechtigung lässt den Helper fehlschlagen, auch wenn bereits Bytes ausgegeben wurden.

Java gibt die Aufnahme erst nach EOF, exakter Bytezahl, erfolgreichem Prozessende und Prüfung des Timeoutzustands zurück (`NativeLocalReader.java:33–65`). Ungültige oder teilweise Ausgaben werden gelöscht. Eigene End-to-End-Probe mit finalem echten Helper: erst öffentliches ACL-Ergebnis, danach Austausch durch PRIVATE_SYNTHETIC_CANARY mit 0600; die neue snapshot-Aufnahme lieferte private Bytes **und private ACL ohne everyone**. Der alte everyone-Grant wurde nicht wiederverwendet.

Die Bestandsynchronisation vergleicht für lokale Rechte-Mirrors immer den aufgenommenen SHA mit dem gespeicherten Dokument (`DataSourceSyncService.java:348`). Identische alte Listing-Größe/mtime überspringen die Aufnahme nicht. Unterschiedlicher Inhalt wird ersetzt; gleicher Inhalt erhält die aktuelle Snapshot-ACL. Aufnahmefehler führen für bekannte alte Dokumente zu deny-everyone (`:364–368`); Erfolg schreibt Dokumentreferenz, Zustand und ACL gemeinsam in einer Transaktion. Die beiden neuen Java-Regressionsfälle zum alten Listing und zur Failclosed-Revokation wurden statisch geprüft; deren vom Implementierer ausgeführte Tests werden hier nicht als eigene Ausführung angerechnet.

Die geschlossene Race ist die Vermischung verschiedener Objekte durch getrennte Pfadoperationen. Die Aufnahme ist keine Zusage eines zeitlich unveränderlichen Filesystemsnapshots beliebiger aktiv schreibender Quellen. Spätere Änderungen nach abgeschlossener Aufnahme unterliegen weiterhin dem Sync-Modell. Fehler bereits beim Verbinden oder gesamten Auflisten behalten gemäß ausdrücklich aktualisierter Betriebsdoku die bisherige Offline-/Altindexpolitik; sie sind keine allgemeine sofortige Rechte-Revokation. Diese Grenzen sind keine offenen Befunde dieses freigegebenen Auftrags.

## Docker, CI und Betriebsdokumentation

Docker baut den Leser in einer separaten Rust-Stufe mit --locked und kopiert ihn explizit ausführbar (755) nach /usr/local/bin vor dem Wechsel zum unprivilegierten Servernutzer. Kein setuid, keine zusätzliche Schreibberechtigung für den Servernutzer, kein Shell-/Netzwerkpfad im Helper. libc=0.2.189 ist exakt gepinnt und durch Cargo.lock-Prüfsumme gebunden. .dockerignore schließt vorhandene target-Artefakte aus dem Buildkontext aus.

scripts/ci-local.sh baut und testet den nativen Helfer vor Java, setzt für den Testlauf dessen absoluten Binarypfad und schlägt bei fehlendem Cargo oder Build-/Testfehler fehl. Keine Abschwächung des CI-Gates im Diff. Der Runtime-Fallbackname benötigt eine korrekt installierte vertrauenswürdige Binärdatei; die Betreiber-ENV KEPLURA_LOCAL_READER ist keine untrusted API-Eingabe.

docs/development.md beschreibt native Build-/Runtimevoraussetzung, unterstützte/gesperrte Dateisysteme, volle SHA-Lesearbeit pro Sync, Snapshotfehler vs. früher Offlinefehler sowie notwendige Bestandsnachprüfung im Wartungsbetrieb. Die Codeänderung allein repariert keine alten ACL-Zeilen; dies wird ausdrücklich nicht behauptet. Kein offener Security-Befund in Packaging oder Dokumentationsvertrag nachgewiesen.

## Tatsächlich ausgeführte eigene Prüfungen

**Auf exakt 9feb4e2, isoliert vom laufenden gemeinsamen Voll-CI:**

1. Rust `cargo test --manifest-path server/native/local-reader/Cargo.toml --target-dir /workspace/scratch/security72-native-target --locked` und anschließender `cargo build` mit gleichem Ziel: **Exit 0, 6 Tests bestanden, 0 Fehler, 0 ignoriert**. Rohprotokoll `/workspace/scratch/security72-native-final-tests.log`. Tests: Blattaustausch, Elternaustausch, Inodeänderung vor Ausgabe, Änderung während der Kopie, Symlink/Parent-Traversal, echte erweiterte ACL und Leselimit.
2. NativeLocalReader/LocalSourceConnector aus finalem Quelltext ausschließlich nach Scratch kompiliert. Eigene Java-End-to-End-Probe mit tatsächlich gebautem Helper für den oben beschriebenen Austausch: PASS.
3. Finaler Java-Protokollbridge mit isolierter Helperfixture: gültige dreibyteige Kontrolle akzeptiert; vollständige Bytes + Exit1, zusätzliche Bytes, zu wenige Bytes, ungültiger DIR-Modus und ungültige Nanosekunden jeweils verweigert. Nach jedem Fall temporäre Kopie auf Entfernung geprüft: PASS.
4. Finales PdfOcr.java ausschließlich nach Scratch kompiliert: exakt 100000 ASCII- bzw. Unicode-Zeichen akzeptiert; 100001 ASCII-/Unicodezeichen und 400001 Bytes verweigert. Echter synthetischer Prozess schreibt 400001 Bytes und würde anschließend zehn Sekunden weiterlaufen: Limitfehler nach rund **0,09 Sekunden** propagiert: PASS. Kein echter OCR-Inhalt oder externer Anbieter dafür verwendet.

**Aus dem vorangegangenen eigenen Review auf d695ff0, keine Behauptung einer Wiederholung auf finalem SHA:**

- Maven: SecurityFixesIntegrationTest 4, OidcLoginFlowIntegrationTest 16, ChatApiIntegrationTest 19; **39 bestanden, 0 Fehler, 0 Skips**, Exit 0. Die relevanten Auth-/CORS-/MCP-Produktpfade sind zwischen diesem Stand und 9feb4e2 unverändert. Rohprotokoll `/workspace/scratch/security-review-72-tests.log`.
- Damalige reale ACL-Race-Probe zeigte den Fehler; der abschließende echte Native-Helper-Test oben prüft den reparierten Aufnahmepfad. Frühere getfacl-Fehlerproben gehören zum inzwischen ersetzten Guard und werden nicht als Tests des neuen Lesers ausgegeben.

## Prüfgrenzen

Kein eigener Dockerimage-Build, Browser-/Tauri-Lauf, vollständiger Maven-/CI-Lauf auf dem finalen SHA, realer LDAP-/OIDC-Anbieter, echter MCP-/Suchanbieter, NFS/CIFS/ZFS-Mount, DNS-Rebinding- oder Lasttest in diesem Teilreview. Root führt das vollständige lokale CI separat aus; dessen Ergebnis ist hier nicht vorweggenommen. Die 60-Sekunden-Timeoutgrenze der neuen Java-Bridge wurde statisch geprüft, nicht in einem eigenen Dauertest ausgereizt. Bestehende Non-Goals wie DNS-Pinning, vollständige Sessionrevokation, OCR-Isolation und sofortiger Oversharing-Purge bleiben getrennte Themen.

**Abschließender Securitystatus: APPROVED für 9feb4e28283c5f637886faae10db950257cbb26f; keine offenen Befunde aus diesem Review.**
