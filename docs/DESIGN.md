# Keplura Design System — „Soft Structure · Apple Refined"

> Historischer Entwurf. Für den neuen vollständigen Redesign-Auftrag gilt seit Phase 3 das [Designsystem 1.0](design/UI-DESIGN-SYSTEM-2026-11-15.md) mit [Komponentenverträgen](design/UI-COMPONENT-CONTRACTS-2026-11-15.md). Der folgende Text dokumentiert den früheren Stand, keine aktuelle Produkt-/Designabnahme. Produktmigration und Phase-4-Validierung stehen noch aus.

Status: Designrichtung vom PO freigegeben am 2026-09-05. Versionierte Basis für alle
UI-Redesign-Tasks (Desktop: Tauri 2 + Vue 3 + TS). Reine Spezifikation — kein Theme-Code,
keine Vue-Änderungen in diesem Dokument. Dark-Mode-Tokens waren ursprünglich ausgeklammert;
sie sind seit der PO-Instruktion 09.09. (DARK-FS) in §2.1 spezifiziert.
Referenz-Mockup: `docs/design/apple-refined.html` (Light-Modus).

## 1. Grundprinzipien (inkl. PO-Feinjustierung 2026-09-05)

1. Ruhig, leicht, präzis — das Interface nimmt sich zurück, der Inhalt führt.
2. Hierarchie über Typografie und Abstand, **nicht** über Boxen und Rahmen.
3. Sidebar minimal und ruhiger als der Content-Bereich.
4. Möglichst wenige sichtbare Borders: Flächen (unterschiedliche Hintergründe) plus
   dünne Separatoren ersetzen Kartengerüste und Umrandungen.
5. Buttons klein und kompakt — Primary Buttons akzentuiert, aber nie dominant.
6. Status-Badges klein und zurückhaltend (getönte Pills, nie bunte Flächen).
7. Hover/Transitions: subtil und weich (wird später umgesetzt; keine Motion-Lib).
8. KEIN Glassmorphismus, keine dekorativen Effekte (keine Verläufe als Deko, keine
   Glow/Schatten-Spielereien, keine Animationen ohne Funktionszweck).
9. Kein Web-Dashboard-Feeling — Keplura ist eine native Produktiv-App.

## 2. Farb-Tokens (Light-Modus, CSS-Variablen)

| Token | Wert | Verwendung |
|---|---|---|
| `--ic-canvas` | `#F5F5F7` | Fenster-/App-Hintergrund (Folie hinter allem) |
| `--ic-content` | `#FBFBFD` | Content-Fläche (Hauptbereich, Sidebar) |
| `--ic-surface` | `#FFFFFF` | gehobene Flächen: Panels, Inputs, aktive Zeilen |
| `--ic-text` | `#1D1D1F` | Primärtext |
| `--ic-text-2` | `#6E6E73` | Sekundärtext, Labels |
| `--ic-text-3` | `#8E8E93` | mutierter Text, Gruppen-Header, Captions |
| `--ic-separator` | `rgba(0,0,0,.06)` | Standard-Separator (Listen, Panels) |
| `--ic-separator-faint` | `rgba(0,0,0,.035)` | schwacher Separator (nur wo nötig) |
| `--ic-accent` | `#0071E3` | Akzent — ausschließlich Primary-Button & Selection |
| `--ic-hover-tint` | `#EAF3FC` | Hover-Tönung (Fläche) |
| `--ic-hover-ink` | `#1A66B8` | Hover-Tönung (Text/Link) |
| `--ic-selection` | `rgba(0,0,0,.045)` | Selektions-Hintergrund (Textauswahl, Auswahl) |
| `--ic-status-ok` | `#34A853` | Status „ok" — Textfarbe der Pills |
| `--ic-status-wait` | `#B26A00` | Status „wartend/in Arbeit" — Textfarbe der Pills |

Regeln:
- Akzent **nur** für Primary-Buttons und Selection/Fokus — nie für Deko, Icons oder Flächen.
- Status-Badges: Text in `--ic-status-*`, Hintergrund dieselbe Farbe mit **10 % Alpha**
  (`color-mix(in srgb, var(--ic-status-ok) 10%, transparent)`), keine deckenden Flächen.
- Weitere Statusfarben erst spezifizieren, wenn ein Use-Case existiert (keine Farbvorräte).
- Keine reinen Graustufen-Ketten neben den drei Textstufen; Flächen über
  canvas/content/surface unterscheiden, nicht über Borders.

### 2.1 Dark-Modus (DARK-FS, PO-Instruktion 09.09.2026)

Gleiche Token-Namen, invertierte Flächen; Views kennen ausschließlich `--ic-*`,
der Modus (`light|dark|system`, Speicherung + Auflösung in `theme-store.ts`)
schaltet nur `html[data-ic-theme]`. Apple-Refined bleibt Leitlinie: gleiche
Rollen, keine neuen Akzent-Verwendungen.

| Token | Light | Dark |
|---|---|---|
| `--ic-canvas` | `#F5F5F7` | `#17171A` |
| `--ic-content` | `#FBFBFD` | `#1D1D20` |
| `--ic-surface` | `#FFFFFF` | `#26262A` |
| `--ic-text` | `#1D1D1F` | `#F5F5F7` |
| `--ic-text-2` | `#6E6E73` | `#A7A7AC` |
| `--ic-text-3` | `#8E8E93` | `#86868B` |
| `--ic-separator` | `rgba(0,0,0,.06)` | `rgba(255,255,255,.09)` |
| `--ic-separator-faint` | `rgba(0,0,0,.035)` | `rgba(255,255,255,.05)` |
| `--ic-accent` | `#0071E3` | `#0A84FF` (Apple-Dark-Stufe desselben Blaus) |
| `--ic-hover-tint` | `#EAF3FC` | `#1E3247` |
| `--ic-hover-ink` | `#1A66B8` | `#4DA3FF` |
| `--ic-selection` | `rgba(0,0,0,.045)` | `rgba(255,255,255,.08)` |
| `--ic-status-ok` | `#34A853` | `#4CCB68` |
| `--ic-status-wait` | `#B26A00` | `#D99A2B` |
| `--ic-status-error` | `#B91C1C` | `#FF6961` |

Zusätzliche modusgleiche Helfer-Tokens (DARK-FS, ersetzen Rohwerte in Views):
`--ic-on-accent` (Text auf Accent-Füllung, beide Modi `#FFFFFF`),
`--ic-input-edge` (Input-Hairline), `--ic-scrim` (Dialog-Backdrop), plus
`color-scheme` damit native Scrollbars folgen. Schatten: auf dunklem Grund
trägt das weisse Hairline (`--ic-shadow-hairline`), Schwarztöne bleiben für
die Höhen-Stufen. Vollbild (derselbe Task): F11/Escape + Sidebar-Toggle,
Tauri `setFullscreen` bzw. Fullscreen-API im Browser — kein Farb-Token.

## 3. Typografie

Charakter: Inter / SF-Pro-Text-artig (System-Stack mit Inter als Referenzschnit).
`font-feature-settings` cv-Alternates sind optional (ease optical alignment), kein Muss.

| Rolle | Größe/Schnitt | Details |
|---|---|---|
| Display | 21 px / 600 | Seitentitel (ein einziger pro View) |
| Headline | 17 px / 600 | Panel-/Abschnittsüberschriften |
| Body | 13.5 px / 400 | Zeilenhöhe 1.6 — Grundtext, Chat, Listen |
| Caption | 12 px | Sekundärinfo, Zeitstempel, Metadaten |
| Micro | 10.5 px / 600 | Gruppen-Header (uppercase, `letter-spacing: .04em`), Pills |

- Tabular-Zahlen (`font-variant-numeric: tabular-nums`) für Größen, Scores, Tabellenwerte.
- Textgewichte nur 400/500/600; keine 700+-Schnitte, kein Kunst-Grey durch Opacity.
- Zeilenlängen im Lesetext max. ~72 Zeichen (Breite des Content-Bereichs begrenzen).

## 4. Radien, Schatten, Spacing

Radien (feste Stufen, aufwärts): **7 / 8 / 10 / 12 / 13 / 14 px**
- 7 px Pills/Chips · 8 px Buttons · 10–11 px Inputs · 12–14 px Panels/Modals.

Schatten-Stufen (nur zur Flächenhebung, Opacity max. **.09**):

| Stufe | Beispiel | Verwendung |
|---|---|---|
| hairline | `0 0 0 1px rgba(0,0,0,.04)` | feinste Kantenzeichnung statt Border |
| raised | `0 1px 2px rgba(0,0,0,.05)` | aktive Sidebar-Zeile, kleine Panels |
| float | `0 4px 12px rgba(0,0,0,.07)` | Popovers, Menüs, Drag-Overlays |
| panel | `0 12px 32px rgba(0,0,0,.09)` | Modals (Obergrenze — nie darüber) |

Spacing: 8er-Raster; erlaubte Abstufungen **4 / 6 / 10 / 12 / 14 / 16 / 24**.
Innerer Element-Abstand klein (4–10), Gruppen-Abstand groß (16–24) — Rhythmus statt Enge.

## 5. Komponenten-Specs

### Button
- Text 13 px / weight 500; Padding 6 × 13 px; Radius 8 px; Height ≈ 30 px.
- Primary: Hintergrund `--ic-accent`, weiße Schrift — aber **nicht dominant** (kein
  Full-Width, nie mehrere Primaries nebeneinander).
- Secondary/Ghost: Fläche `--ic-surface` + hairline, bzw. nur Text in `--ic-text-2`.
- Hover: sanfte Tönung (`--ic-hover-tint`), Transition subtil-weich (≈ 120–160 ms ease).

### Input
- Radius 10–11 px; Hintergrund `--ic-surface`; keine harten Borders (hairline genügt).
- Fokus: weicher Ring 3.5 px in `--ic-accent` bei 14 % Alpha, ohne Layout-Shift.
- Placeholder `--ic-text-3`; Icon-in-Inputs links, 15 px Stroke-Icon, `--ic-text-2`.

### Sidebar (224 px)
- Fläche `--ic-content`, ruhiger als Content; Separatoren nur `--ic-separator-faint`.
- Gruppen-Header: Micro 10.5 px / 600, uppercase, `--ic-text-3`.
- Aktive Zeile: **weiße Fläche (`--ic-surface`) mit hairline shadow statt Farbbalken**;
  Text `--ic-text`, weight 500. Hover: `--ic-hover-tint`.
- Profil als dezente Fußzeile (Avatar klein, Name Body-Zeit, Role Caption) — kein Kartenblock.

### Liste / Row
- Zeilen trennen **nur** über Separatoren — kein Kartenstapel, kein Row-Rahmen.
- Row-Höhe großzügig (≥ 40 px), Inhalt linksbündig, Metad rechts in Caption/`--ic-text-3`.
- Tabular-Zahlen bei Größen/Datum/Score-Spalten.

### Chat
- AI-Antwort: natürlicher Textblock **ohne Bubble**, volle Breite des Lesefensters.
- Nutzer-Nachricht: dezente Tönung — `--ic-accent` bei **7 %** Alpha, Radius 12, kompakt.
- Quellen: dünne Accent-Linie (2 px `--ic-accent`) links am Quellenblock; Eintrag zeigt
  Dokument + Abschnitt + klickbaren Treffer; Score als kleiner Chip (Micro, Pill Radius 7).
- Quellen-Treffer **müssen** klickbar sein: `cursor: pointer`, Hover-Underline in
  `--ic-hover-ink`, stabiles `data-testid` pro Treffer (QA/E2E-Anker).
- Streaming: Text erscheint ohne Springen; Caret dezent, kein Blink-Counter.

### Status-Pill
- Micro 10.5 px / 600, Padding 2 × 8 px, Radius 7 px; Farbe siehe §2 (Text + 10 % Alpha-Grund).

## 6. Icons

- **Ein** Stroke-System durchgehend: Strichstärke 1.7 px, runde Caps/Joins.
- Größen nur 15 px (Inline/UI) und 13 px (dicht, Pills/Captions).
- Farbe `--ic-text-2` ( Ruheszustand), `--ic-text` wenn führend; Akzent nur bei Selection.
- Keine Emojis, keine gemischten Icon-Sets, keine filled/stroke-Mischungen.

## 7. Do / Don't

| Do | Don't |
|---|---|
| Flächen + Separatoren zur Gliederung | Boxen, Rahmen, Kartenstapel als Struktur |
| Ein Akzent, sparsam (Primary/Selection) | Buntes Dashboard, Status-Farbfeuerwerk |
| Typo-Skala einhalten, Tabular-Zahlen | Ad-hoc-Größen, proportionale Zahlenkolonnen |
| Kleine kompakte Buttons/Pills | Große CTA-Flächen, Balken-Optik |
| Aktive Zeile = weiße Fläche + hairline | Farbiger Balken/Full-Swatch als Active-Marker |
| Quellen klickbar + `data-testid` | Quellen als toter Text |
| Native App-Anmutung, 13.5 px Ruhe | Web-Dashboard-Look, Glassmorphismus, Deko-Effekte |

## 8. Referenz-Mockup

`docs/design/apple-refined.html` ist das vom PO abgenommene Referenz-Mockup dieser
Richtung (Sidebar 224 px, Liste/Rows, Chat mit Quellenlinie, Status-Pills). Abweichungen
zwischen Mockup und Spec werden über dieses Dokument entschieden — DESIGN.md führt.
Alle UI-Redesign-Tasks referenzieren dieses Dokument; Token-Namen (`--ic-*`) sind
verbindlich für die spätere Theme-Umsetzung.
