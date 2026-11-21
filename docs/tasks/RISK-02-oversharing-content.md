# RISK-02 — Oversharing-Check Stufe 2: Inhaltsprüfung

PO-Auftrag 21.11.2026: Oversharing-Check nach Inhalt als nächstes Alleinstellungsmerkmal. Baut auf
RISK-01 (Name/Rechte) und der Textextraktion (MVP-5, OCR-01) auf. Branch `feat/oversharing-content`
von `main`. Klasse A (Datenquellen, Parsing von Dateiinhalten).

## Ziel

Für Dateien, die Stufe 1 als **breit lesbar** findet, prüft Keplura zusätzlich den extrahierten Text
lokal auf sensible Muster. Der Admin sieht im selben Check z. B. „enthält 3 IBANs“ — nie die Werte
selbst. So fallen auch Dateien mit unauffälligem Namen auf („Liste.xlsx“ mit Kontodaten).

## Scope

- `ContentPatterns` (rein, ohne Zustand): zählt je Kategorie **verschiedene** Treffer in einem Text.
  - **IBAN**: Länderlänge (Tabelle SEPA + gängige Länder) + Prüfsumme ISO 13616 (mod 97), mit/ohne
    Leerzeichen-Gruppen.
  - **Kreditkarte**: 13–19 Ziffern (Leerzeichen/Bindestrich zwischen Gruppen erlaubt), bekannte
    Präfixe (Visa, Mastercard, Amex, Discover, JCB, Diners) + Luhn.
  - **Steuer-ID** (DE): 11 Ziffern, erste ≠ 0, Ziffernregel (genau eine Ziffer 2- oder 3-mal) +
    Prüfziffer ISO 7064 MOD 11,10.
  - **Sozialversicherungsnummer**: DE-Rentenversicherungsnummer (Bereich, Geburtsdatum, Buchstabe,
    Seriennummer, Prüfziffer) und US-SSN nur im Format `123-45-6789` mit Gültigkeitsregeln.
  - **Personal/Gehalt** und **Gesundheit**: Schlüsselwörter DE + EN (z. B. Bruttogehalt, Payroll,
    Personalnummer; Diagnose, Krankmeldung, sick leave). Erst ab 3 Treffern gemeldet (Rauschen).
  - Ziffernfolgen, die Teil längerer Zahlen sind, zählen nicht.
- `ExposureContentInspector`: liest je breit lesbarer Datei den Inhalt über denselben Konnektor
  (`open()`), begrenzt auf **max. Bytes** (Standard 20 MB), extrahiert pdf/docx/xlsx/pptx mit dem
  bestehenden Tika-Extraktor (gleiche Zip-Bomben-, Zeichen- und Pool-Grenzen, eigene **Seitengrenze**
  Standard 200 und **Zeitlimit** Standard 20 s, **ohne OCR**), txt/md/csv als UTF-8. Temporäre Kopie wird
  immer gelöscht. Fehler jeder Art → Zustand **„nicht prüfbar“** mit kurzem Grund (zu groß, kein Text,
  Zeitlimit, beschädigt/verschlüsselt, nicht lesbar) — nie „unauffällig“.
- Höchstens `max-files` (Standard 2000) Inhaltsprüfungen je Lauf; danach Hinweis im Bericht.
- Bericht (`GET …/exposure`): je Fund `contentState` (`CHECKED` / `NOT_CHECKABLE` / `null` = nicht
  geprüft), `contentReason`, `contentHits` [{category, label, count}]; Zusammenfassung `content`
  {enabled, checked, sensitive, notCheckable, skipped}. Funde sind jetzt breit lesbare Dateien mit
  sensiblem Namen **oder** sensiblem Inhalt **oder** nicht prüfbarem Inhalt.
- Konfiguration `keplura.exposure.content.{enabled,max-bytes,max-pages,timeout-seconds,max-files}`.
- Oberfläche: Kennzahl „mit sensiblem Inhalt“, je Datei Inhalts-Etiketten („enthält 3 IBANs“) bzw.
  „Inhalt nicht prüfbar: …“; Texte erklären Name- vs. Inhaltsprüfung.

## Non-Goals

- Keine Werte anzeigen, loggen, speichern oder an Dienste schicken; kein LLM, kein externer Dienst.
- Keine Prüfung eng berechtigter Dateien (dort ist das Risiko nicht Oversharing).
- Kein OCR für Scans in dieser Stufe (Scans ohne Textschicht = „nicht prüfbar“).
- Keine persistente Historie, keine automatische Sperre — der Admin entscheidet wie in Stufe 1.
- Keine weiteren Länderformate (z. B. AT/CH-Sozialversicherung) und keine Namens-/Adresserkennung.

## Acceptance Criteria

1. Erkennung: gültige IBAN/Kreditkarte/Steuer-ID/SV-Nummer werden gezählt, ungültige Prüfsummen und
   Teilstücke längerer Zahlen nicht; Wiederholungen desselben Werts zählen einmal.
2. Bericht und Logs enthalten nur Kategorie + Anzahl (Test: serialisierter Bericht enthält keinen Wert).
3. Inhalte werden nur für breit lesbare Dateien gelesen (Test: `open()` einer eng berechtigten Datei wirft).
4. Zu große, unlesbare, leere (kein Text) und zeitüberschreitende Dateien sind „nicht prüfbar“ und
   erscheinen als Fund.
5. Ohne Inspector (Konfiguration aus) verhält sich der Check exakt wie Stufe 1 (liest keine Inhalte).
6. API-Schutz unverändert (401/403/404 wie RISK-01).

## Tests

- `ContentPatternsTest` (Prüfsummen, Formate, Fehltreffer, Schlüsselwörter, Deduplizierung).
- `ExposureContentInspectorTest` (Text, Byte-Grenze, Lesefehler, leerer Text, Office-Datei).
- `ExposureScanServiceTest` (Inhalt nur bei breit lesbaren Dateien, nicht prüfbar als Fund, keine Werte im JSON).
- Bestehende RISK-01-Tests bleiben grün. Desktop: `npm run build`.

## Prüfstand (ehrlich)

- Unit: `ContentPatternsTest` 12, `ExposureContentInspectorTest` 9 (Text, Byte-Grenze, falsche Größe in der
  Liste, Lesefehler, leerer Text, kaputtes PDF, DOCX über Tika, unbekannter Typ),
  `ExposureScanServiceTest` +3 (Inhalt nur bei breit lesbaren Dateien, Dateigrenze je Lauf, ausgeschaltet
  = Stufe 1). Serialisierter Bericht enthält keine Werte. `ExposureAdminIntegrationTest`,
  `TikaDocumentTextExtractorTest` unverändert grün.
- Lauf gegen echten Server (Dev-Profil, H2) mit Linux-Ordner-Quelle (5 Testdateien, Rechte-Übernahme aus)
  und gebauter Oberfläche, Deutsch und Englisch: 2 IBANs + 4 Gehaltsbegriffe, 1 Kartennummer, 1 kaputtes
  PDF als „nicht prüfbar“, 1 Datei nur nach Namen. Bericht-JSON und Server-Log ohne Werte.
- NICHT geprüft: SMB/SharePoint mit echten Inhalten, große Ablagen (Laufzeit), echte Scans.
