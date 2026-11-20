# UI-01 — modernes Gesamtbild

PO-Auftrag 15.11.2026: gesamte Oberfläche modernisieren, orientiert an
Open WebUI und LM Studio Bionic. Vue 3 bleibt: der Auftrag betrifft die
Gestaltung und Informationshierarchie, keinen Wechsel des festen Stacks.

## Ziel / Scope

Einheitliches Workspace-Design für Navigation, Chat, Wissen, Team, Login
und alle Administrationsbereiche. Ruhige neutrale Flächen, großzügigere
Typografie, abgestimmte Radien und Abstände, schwebende Chat-Eingabe.
Administration mit gruppierter Seitennavigation. MCP-Verbindungen als
kompakte Karten mit aufklappbaren Werkzeugen und Arbeitsbereich-Freigaben.
Leere neu angelegte Chats zeigen dieselbe hilfreiche Orientierung wie der
erste Start. Widersprüchliche ältere Composer-CSS-Regeln entfernen.

## Non-Goals

Keine neuen Backend-Funktionen, keine Rechteänderungen, keine Datenmigration,
kein Frameworkwechsel, kein Merge/Release/Deployment.

## Akzeptanz / Tests

- Alle sechs Hauptflächen nutzen dieselbe Gestaltung und bleiben bedienbar.
- Heller und dunkler Modus, Desktop und 390-Pixel-Handy ohne Seitenüberlauf.
- Eingabetext und Aktionen überlappen auch auf dem Handy nicht.
- Bestehende Testids und Funktionen erhalten; MCP nach Neuanlage geöffnet,
  nach erneutem Aufrufen bewusst kompakt. Freigaben bleiben gespeichert.
- Tatsächlicher Vue-/TypeScript-/Vite-Build und lokales CI-Gate.
- Browser-Nachweise mit bestehendem isoliertem PostgreSQL-/Ollama-/MCP-Testsystem.

## Workspace

Eigenes Worktree `.worktrees/ui-modern`, Branch `codex/ui-modern` von main,
mit dem geprüften Funktionsstand `d541ce1` als Fast-forward-Voraussetzung.
Der UI-Diff wird getrennt gegenüber `codex/chat-productivity` geprüft;
PR #53 und main werden nicht verändert.

## Stand

Erste Browserprüfung: Login, Chat, Wissen, Team, Administration, MCP,
Werkzeugauswahl, Dark Mode und mobile Navigation bestanden; Screenshots
werden als Evidence gesichert. Abschließendes CI-Ergebnis im UI-PR.
Unabhängige Review-/QA-Freigabe bleibt erforderlich.
