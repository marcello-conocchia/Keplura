# Keplura — UI/UX-Redesign (PO-Auftrag 14.11.2026)

> Historischer Plan vom 14.11.2026. Seine Phasennummern und Abschlussaussagen beziehen sich auf den damaligen Auftrag. Der neue vollständige Redesign-Auftrag folgt dem [aktuellen Arbeitsplan](UI-REDESIGN-PLAN-2026-11-15.md) und dem [Designsystem 1.0](UI-DESIGN-SYSTEM-2026-11-15.md). Phase 3 ist dort spezifiziert; Produktintegration und Phase-4-Validierung stehen aus.

Status: Phase 1–2 abgeschlossen; Phase 3 umgesetzt für Tokens, Shell, Wissen, Team, Chat (Feinschliff);
offen: Administration neu gruppieren, Login-Feinschliff, mobile Kompakt-Navigation, Quellen-Sprung an die
zitierte Stelle (braucht Dokumentvorschau — Server liefert heute nur Download), Phase 4 (e2e-Suiten auf
neue Navigation anpassen, UI-Tests ergänzen).
Auftrag: vollständiges Redesign „Notion-inspiriert, eigenständig", Deutsch, B2B-Mittelstand.

## Konflikte mit bestehenden Regeln (benannt, Vorschlag)

| Regel | Konflikt | Vorschlag |
|---|---|---|
| UI-FREEZE (PROJECT_STATE §Nächste Schritte 1) | Redesign ändert alle Flächen | Durch den PO-Auftrag vom 14.11. aufgehoben; in PROJECT_STATE als Entscheidung eintragen |
| DESIGN.md „Soft Structure · Apple Refined" | Palette (Apple-Blau, kühle Grautöne), Radien 10–14 px | DESIGN.md wird durch §3 dieses Dokuments abgelöst: gleiche Token-Namen (`--ic-*`), neue Werte; Prinzipien (ruhig, Flächen statt Rahmen, Akzent sparsam, kein Glas/Verlauf) bleiben |
| e2e-Harnesses (`desktop/e2e/*.cjs`) adressieren `data-testid` | Umbau der Struktur | Alle bestehenden testids bleiben erhalten (an neuer Stelle); neue Elemente bekommen neue testids |
| ADR-0001 (nur Desktop) | PO 14.11.: „auch für Browser, Browser first" | ADR-0011: Web-Bundle vom Server ausgeliefert, Gestaltung Browser-first, Tauri bleibt |

## 1. Analyse — größte UX-Probleme (Ist-Zustand)

1. **Eine Seite „Wissen" macht alles**: Organisationen, Mitglieder/Einladungen,
   Spaces, Wissensbasen, Dokumente, Upload und Suche untereinander auf einer
   Scrollfläche (`SessionView` + `MembersPanel` + `KnowledgePanel`). Keine Orientierung.
2. **Navigation bildet Aufgaben nicht ab**: Sidebar hat Wissen / Chat / Admin;
   Team-Verwaltung ist im Wissen-Bereich versteckt.
3. **Technikbegriffe**: „Knowledge Bases", „Chunk", „Score", Rollen „OWNER"/„MEMBER",
   Mischsprache („Du bist member…").
4. **Kontext unklar**: Organisation und Space werden per Select im Inhalt gewählt;
   Chat und Wissen wissen nichts voneinander sichtbar.
5. **Indexstatus** als Roh-Enum; kein verständlicher Fortschritt.
6. **Große Monolith-Dateien** (ChatView 893, KnowledgePanel 899, AdminPanel 852,
   api.ts 1043, auth-store 704 Zeilen) — Präsentation, Zustand, API vermischt.
7. **Fehlende Aktionen** in der UI, obwohl der Server sie jetzt kann
   (Löschen/Umbenennen, Mitglied entfernen, Re-Index).

## 2. Informationsarchitektur (Soll)

```
Sidebar
 ├─ Arbeitsbereich-Umschalter   (Organisation ▸ Space, oben, ein Klick)
 ├─ Chat                        (Verlauf des Space, neue Unterhaltung)
 ├─ Wissen                      (Explorer: Wissensbasen ▸ Dokumente)
 ├─ Team                        (Mitglieder, Einladungen, Rollen)
 ├─ ─────
 ├─ Administration              (nur Administratoren: Modelle, Anbieter, Systemstatus)
 └─ Fuß: Benutzer, Darstellung, Verbindungsstatus, Abmelden
```

Begriffe (durchgehend Deutsch):

| Technisch | Sichtbar |
|---|---|
| Organization | Organisation |
| Space | Arbeitsbereich |
| Knowledge Base | Wissensbasis |
| OWNER / MEMBER | Verwalter:in / Mitglied |
| INDEXED / PENDING / FAILED / NO_TEXT_LAYER / NOT_INDEXED | Durchsuchbar / Wird verarbeitet / Fehlgeschlagen / Kein Text erkannt / Nicht durchsuchbar |
| Score, Chunk | entfällt für Anwender (Quelle + Abschnitt) |

## 3. Designsystem (Tokens, Ablösung DESIGN.md §2)

- **Farben**: warme Neutraltöne (Papier statt Glas). Light: Canvas `#F7F7F5`,
  Content `#FFFFFF`, Text `#1F1F1D / #5F5E5A / #91908B`, Trenner `rgba(55,53,47,.09)`.
  Dark: `#191919 / #202020 / #2A2A2A`, Text `#EDEDEB / #A3A29E / #7B7A76`.
  Akzent gedecktes Tintenblau `#2E5BBA` (dark `#6B93E6`) — nur Primäraktion,
  Auswahl, Fokus. Statusfarben gedämpft (Grün `#2F7D4F`, Ocker `#9A6400`, Rot `#B42318`).
- **Typografie**: Inter/System, 14 px Basis, Skala 12 / 13 / 14 / 16 / 20 / 24,
  Gewichte 400/500/600, Zeilenhöhe 1.5 Fließtext, 1.25 Überschriften.
- **Abstände**: 4-px-Raster (4, 8, 12, 16, 24, 32, 48).
- **Radien**: 4 (Pills/Tags), 6 (Buttons, Inputs), 8 (Panels, Dialoge). Keine großen Karten.
- **Schatten**: nur für schwebende Ebenen (Menüs, Dialoge); Flächen trennen per Hintergrund + 1-px-Linie.
- **Bewegung**: 120–160 ms ease-out für Hover/Öffnen; `prefers-reduced-motion` respektiert.
- **Icons**: eine Linien-Icon-Familie (bestehendes `AppIcon`, 16 px, 1.5 px Strich), keine Emojis.

## 4. Kernansichten

- **Chat**: Verlaufsliste links (gruppiert Heute/Diese Woche/Älter), Gespräch mittig
  max. 760 px Lesebreite, Composer unten fixiert; Modell- und Wissensbasis-Wahl als
  kompakte Chips im Composer; Quellen als nummerierte Fußnoten → Klick öffnet Dokument.
  Streaming reserviert Platz (kein Layoutsprung).
- **Wissen**: Zweispalten-Explorer. Links Wissensbasen des Arbeitsbereichs; rechts
  Dokumenttabelle (Name, Typ, Größe, Status, Hochgeladen) mit Suche/Filter, Upload per
  Button + Drag&Drop, Statuschips in Klartext, Zeilenaktionen (Herunterladen,
  Neu verarbeiten, Löschen mit Bestätigung). Suche „Im Wissen suchen" mit Treffern als
  Dokument + Textauszug, ohne Score.
- **Team**: Organisationen, Mitgliederliste mit Rollen in Klartext, Einladungen; Aktionen
  nur für Verwalter:innen sichtbar.
- **Administration**: unverändert funktional, neu gruppiert (Modelle · Anbieter · System).

## 5. Umsetzungsreihenfolge (Phase 3)

1. Tokens + Basis-Komponenten (Button, Input, Dialog, Pill, Icon) auf neue Werte.
2. Application Shell: neue Sidebar mit Arbeitsbereich-Umschalter und Bereichen.
3. Team-Bereich aus `SessionView` herauslösen.
4. Wissen-Explorer (KnowledgePanel zerlegen).
5. Chat-Feinschliff, Administration-Gruppierung.

Phase 4 (QA): Typecheck/Build je Schritt; e2e-Suiten und neue UI-Tests sind
ausstehend — auf PO-Anweisung („Tests weglassen", 14.11.) derzeit nicht ausgeführt.
