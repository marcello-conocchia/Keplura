# Designsystem 1.0 – Referenz und Prüfprotokoll

Stand: 15.11.2026. **Phase-3-Spezifikation mit isoliertem Komponentenkatalog. Keine Produktimplementierung oder vollständige Design-/Accessibility-Abnahme.**

## Einstieg und Reproduktion

- [Systemdefinition](../UI-DESIGN-SYSTEM-2026-11-15.md): Werte, Hierarchie, Navigation, Scope, Responsive und Qualitätsziele.
- [Komponentenverträge](../UI-COMPONENT-CONTRACTS-2026-11-15.md): Verhalten aller Grundbausteine einschließlich Fehler/Busy/Dirty/Fokus.
- [Migration](../UI-MIGRATION-2026-11-15.md): alle 36 Vue-Dateien, CSS-Konsolidierung und P04-Abnahme.
- [tokens.json](tokens.json): autoritative Wertetabelle, Hell/Dunkel und gemeinsame Maße; [contracts.json](contracts.json): acht Vertragsfamilien und alle 109 ID-Zuordnungen.
- [catalogue.html](catalogue.html): daraus erzeugte Designreferenz. [tokens.css](tokens.css) ist ebenfalls generiert und wird vom Produkt **nicht** importiert.

Vom Repository aus, mit vorhandenem Node:

```text
node docs/design/system-v1/build-catalogue.cjs
node docs/design/system-v1/build-catalogue.cjs --check
node docs/design/system-v1/verify-system.cjs
node docs/design/system-v1/serve-catalogue.cjs
```

Der letzte Befehl stellt ausschließlich die Referenz auf Loopback-Port1423 bereit. Er nutzt den vorhandenen Inter-Font aus den installierten Desktop-Abhängigkeiten; ohne ihn fällt die Referenz auf die Systemschrift zurück. Kein neuer Download/Installationsschritt. `KEPLURA_DESIGN_PORT` kann den Port ändern. Der Server erlaubt nur die Referenzdateien und den Font; keine API, Verzeichnisauflistung oder Schreiboperationen. Die bestehende App-/Pilotinstanz wird nicht verändert.

## Tatsächlich ausgeführte Prüfungen

| Prüfung | Ergebnis / Beleg |
|---|---|
| Systemquelle und generierte Referenz | Generator ausgeführt; `--check` vergleicht tatsächliche Ausgaben mit tokens.json |
| Farbkontraste | 160 definierte Paare, vor Rundung gegen Schwelle geprüft; Mindestwert Text4.5781:1, notwendige Control-/Fokuskontraste3.3080:1. [Einzelpaare](contrast-pairs.json) |
| Hell/Dunkel-Rollen | Beide28 Rollen gleich benannt;59 gemeinsame Werte, benannte Kompatibilitätsaliasse. Keine unzugeordneten aktiven --ic-Tokenverweise im Bestand |
| Inventar-/Dateiabgleich | Alle109 IDs mit gültigem Vertrag, alle36 Vue-Dateien zugeordnet; ursprünglicher Tracker unverändert/offen; Quelltext-Hashes unverändert zur Auditbasis |
| Dokumentprüfung | Lokale Verweise, syntaktisch gültige externe URLs, reiner Docs-Diff, Whitespace und offensichtliche Secret-/persönliche Pfadmuster; aktuelles Ergebnis in [checks.json](checks.json) |
| Katalog1440×1000 | Hell und Dunkel visuell betrachtet; Inter geladen, genau ein H1, kein Root-Überlauf. [Hell](screenshots/catalogue-light.jpg), [Dunkel](screenshots/catalogue-dark.jpg) |
| Katalog390×844 | Einspaltige Ansicht betrachtet, Felder16px/min44px, kein horizontaler Root-Überlauf; breite Tabelle bewusst eigener Scrollbereich. [Gesamtaufnahme](screenshots/catalogue-mobile.jpg) |
| Dialogmuster480×400 | Name initial fokussiert, Tab→Abbrechen, Escape schließt, Fokus zurück am Auslöser; Aktionen sichtbar. [Aufnahme](screenshots/dialog-low-height.jpg) |
| Katalog320×800 | DOM-Messung: Root-Scrollbreite entspricht Clientbreite, kein horizontaler App-Root-Scroll. Kein echter400%-Browserzoom durchgeführt |

Browserbeobachtungen mit Messwerten/Grenzen: [browser-observations.json](browser-observations.json). Die vier JPGs zeigen ausschließlich die isolierte Referenz mit Beispieldaten, keine neue Keplura-Produktansicht.

Die Kontrastprüfung belegt ausdrücklich nur die aufgelisteten deckenden Tokenpaare. Transparenz, Überlagerungen, Bildhintergründe, weitere Kombinationen und tatsächliche Produkt-CSS-Kaskaden müssen später am Renderzustand geprüft werden. Dekorative Trenner sind kein Ersatz für notwendige Controlgrenzen. Der Katalog zeigt nicht jede spezifizierte Variante; insbesondere Dirty-/Busyguard, Secretänderung, Portalstapel und komplexer Assistent sind definierte spätere Produktverträge.

## Offen und nicht behauptet

Keine Produkt-Builds, E2Es oder erneutes `scripts/ci-local.sh` in dieser reinen Spezifikationsphase. Keine Übertragung früherer CI-Ergebnisse. Kein unabhängiger Reviewer-/QA-/Security-Review, keine vollständige Screenreader-/Forced-colors-/Reduced-Motion-/Zoom-/Touchgeräte-/Tauri-Prüfung. Keine Backend-/Rechte-/Auth-/MCP-/Upload-Funktionsprüfung durch den Referenzkatalog.

Phase 4 muss das System zuerst in **Chat mit echter Unterhaltung und komplexem Datenquellen-Assistenten** validieren. Dazu Datenzeile, Secret und Quelle, alle Normal-/Problemzustände, mobile/kleine Höhen und Hell/Dunkel. Erst anschließend folgt die vollständige Migration. Alle109 Inventarstatus bleiben offen; „definiert“ ist kein zusätzlicher Produktabschlussstatus. Kein Merge, Release oder Deployment.
