# SEC-REVIEW-FIXES — Befunde aus dem Main-Review schließen

PO-Auftrag 16.11.2026: „Löse diese Probleme“. Klasse A (Auth, Rechte, Parser,
Netzwerk und MCP). Basis origin/main@530a3a5, separater Worktree und Branch
feat/security-fixes. Draft-PR; kein Merge, kein Push auf main, kein Release.

## Ziel / Scope

S-1 bis S-6 aus SECURITY-2026-11-16 sowie die technische Vertrauensgrenze aus S-7
beheben. Die Beschränkungen des früheren Office-Auftrags betrafen DOC-02; diese
separate Aufgabe benötigt Änderungen am Rechte- und OCR-Pfad selbst.

- Gezielte CORS-Freigaben für Profil, Passwortwechsel und Admin-DELETE;
  bestehende Session-/CSRF-/Rollengates unverändert wirksam.
- Gemeinsame Session-Erzeugung für lokalen/LDAP- und OIDC-Login. Pflichtwechsel
  im Response und in der Session; bei jedem Schreiben aktuellen Konto-Zustand prüfen.
  Restore ohne bestätigtes Profil zeigt den Login statt einer Ersatzidentität.
- Lokale Rechte: FD-gebundener Linux-Leser in Rust (PO-Zustimmung 16.11.2026)
  ermittelt Datei-/Elternrechte und Inhalt aus denselben offenen Handles.
  NOFOLLOW-Traversal, fstat/fgetxattr/fstatfs, vor/nach Änderungen prüfen;
  keine Shell, kein Netzwerk, begrenztes Protokoll mit Erfolg vor Freigabe.
  Erweiterte/unbekannte ACLs/Dateisysteme verweigern. Snapshot-Hash statt
  Listing-Metadaten entscheidet über erneute Indexierung; Fehler sperrt alte ACL.
- SearXNG verwendet dieselbe Shape-/Dial-Policy einschließlich Betreiber-CIDRs
  wie MCP. Keine Credentials/Query/Fragmente in Basis-URLs, keine Redirects.
- Zeichenlimit umfasst Tika und OCR zusammen, einschließlich Seitentrenner;
  Überschreiten ablehnen, kein erfolgreicher Teilindex.
- MCP plant höchstens einen Batch ausschließlich anhand des aktuellen direkten
  Nutzerauftrags. Verlauf/Dateien/RAG/Web/Tool-Ergebnisse fehlen im Aktionskontext.
  Antwort mit allen Belegen hat keine Werkzeuge; weitere Modell-Toolcalls werden
  serverseitig nicht ausgeführt. Maximal acht Aufrufe bleiben bestehen.

## Non-Goals

Keine Office-Änderungen, keine Änderungen am Bericht-Branch, kein neues ACL-Modell,
keine erweiterte POSIX-/NFSv4-ACL-Unterstützung, kein allgemeiner Schutz vor jeder
Modell-Fehlinterpretation. DNS-IP-Pinnung, vollständige Session-Revokation,
OCR-Worker-Isolation und sofortige Oversharing-Purges sind separate Themen.

## Acceptance Criteria / Tests

- Tatsächliches Browser-Login und Reload bewahren Profil/Adminnavigation;
  Admin-DELETE und Startpasswortwechsel funktionieren über Cross-Origin-CORS.
  Fremde Origins, fehlendes CSRF und normale Nutzer bleiben ausgeschlossen.
- Echter OIDC-Stub-IdP: Pflichtwechsel bleibt nach Exchange aktiv, Schreiben ist
  bis zum Wechsel gesperrt. Admin-Reset sperrt schon vorhandene Sessions.
- Echte POSIX-ACL mit group::--- und lesbarer Maske an Datei oder Elternordner
  führt zur Ablehnung. Fehlerhafte ACL-Ausgabe ebenfalls.
- Loopback-Canary erhält ohne Betreiberfreigabe keinen SearXNG-Request; mit
  expliziter Freigabe funktioniert die Suche. Redirect bleibt unangetastet.
- OCR-Stub mit zwei Seiten überschreitet Zeichenbudget und wird auf Upload- und
  Chat-Anhangpfad abgelehnt.
- Bösartiges Ergebnis und ein zweiter Modell-Toolcall lösen keine Folgeaktion aus;
  Fremdinhalte erreichen ausschließlich den Antwortkontext.
- Gezielte Tests, Desktop-Build und bash scripts/ci-local.sh wirklich ausführen.
  Lücken/Skips/Umgebungsblocker ehrlich im PR nennen. Unabhängige Reviews und QA
  bleiben Merge-Gates; selbst ausgeführte Tests sind keine unabhängige Freigabe.

## Unabhängiger Review-Nachtrag

Die Prüfung von d695ff0 fand keine neue Regression, zeigte aber eine vorhandene
stille Kürzung in PdfOcr.readCapped. Auch dieses Seitenlimit muss den kompletten
Upload/Anhang ablehnen; Regressionen prüfen den exakten Grenzwert, echten
Prozessoutput und die Weitergabe durch den OCR-Worker. Der getrennte Altbefund
R-1 (Dateiaustausch zwischen ACL(path) und open(path)) wird nach expliziter
PO-Zustimmung durch den Rust-Leser geschlossen. Regressionen prüfen Austausch
von Blatt/Elternverzeichnis, In-place-Änderung während Kopie, FD-gebundene ACLs,
Fehlerprotokolle und das Ersetzen von Inhalt bei unveränderten Listing-Metadaten.
