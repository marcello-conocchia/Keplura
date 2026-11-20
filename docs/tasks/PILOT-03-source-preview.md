# PILOT-03 — Dokumentvorschau rechts an der Quelle

Branch `codex/pilot-source-preview`, von `main@43302d3` angelegt; die fertige
lokale Antwortqualität ist als ausdrückliche technische Voraussetzung auf
diesem Feature-Branch integriert, ohne Merge nach main.

Ziel: Ein Klick auf eine Chat-Quelle öffnet rechts eine sichere Textansicht des
Dokuments, springt zur echten PDF-Seite bzw. zum Abschnitt und markiert die
zitierte Textstelle. Das Original bleibt herunterladbar. Die Textansicht wird
klar benannt, sie behauptet kein originalgetreues PDF-/Word-Seitenlayout.

Umfang: sessiongeschützter, begrenzter Preview-Endpunkt mit allen Tenant-Pins;
Quellenposition gegen aktuelle Indexposition prüfen; keine erneute Parser- oder
Modellausführung pro Klick; reine Textdarstellung ohne HTML-Ausführung; Laden,
Fehler, alte Quellen und Dokumente ohne Text verständlich behandeln; Tastatur,
Escape und kleine Bildschirme unterstützen.

Akzeptanz: echte PDF-Seite 2 und Markdown-Abschnitt werden markiert; falsche
Org-/Space-/KB-/Dokumentpfade und anonyme Anfragen verraten keine Inhalte;
keine gespeicherten Dateipfade im JSON, Cache-Control no-store; kein XSS durch
Dokumenttexte; Build und Start, relevante Tests, Browser-Screenshot.
Merge-Gates nach AGENTS.md bleiben vor einem Merge offen.
