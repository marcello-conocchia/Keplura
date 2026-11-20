# AUTH-REMEMBER — Angemeldet bleiben

Klasse A: Anmeldung, Sitzungslaufzeit und Cookie-Persistenz.
PO-Auftrag vom 18.11.2026: „Angemeldet bleiben“ beim Login ergänzen und die
Abmeldung nach 30 Minuten entfernen; Codex-Nutzung ausdrücklich bestätigt.

## Ziel

Eine sichtbare Auswahl bestimmt, ob die Anmeldung das Schließen der Anwendung
überlebt. Inaktivität von 30 Minuten meldet niemanden mehr ab.

## Scope

- Checkbox „Angemeldet bleiben“, standardmäßig aus, für lokale/Verzeichnis- und
  Firmenkonto-Anmeldung.
- Optionales API-Feld `rememberMe` (Standard `false`). Aus: Sitzungscookie ohne
  Max-Age; an: persistentes HttpOnly-Sitzungscookie.
- Authentifizierte Sitzungen gelten absolut bis zu 30 Tage, unabhängig von
  Aktivität. Betreiberparameter `keplura.auth.session-lifetime-seconds`
  steuert Cookie und serverseitige Obergrenze gemeinsam. Zugriffe verlängern
  diese Frist nicht. Der Container räumt verlassene Sitzungen spätestens nach
  diesem Inaktivitätsintervall auf; die absolute Frist wird vorher geprüft.
- Noch nicht authentifizierte Sitzungen behalten ihr kurzes Timeout (OIDC).

## Non-Goals

Keine serverseitige Sitzungspersistenz, neue Abhängigkeiten oder Migrationen.
Ein Serverneustart beendet die im Speicher gehaltenen Sitzungen weiterhin.
Browser können Sitzungscookies bei aktivierter Sitzungswiederherstellung selbst
über einen Neustart behalten; der native Client speichert nur persistente Cookies.
Kein Deployment, keine Speicherung von Passwörtern oder Sitzungsschlüsseln in JS.

## Acceptance Criteria

- Auswahl erreichbar per Label und Tastatur, während Anmeldung deaktiviert.
- Beide Loginpfade senden die Wahl; nur die nicht sensible OIDC-Präferenz wird
  vorübergehend im SessionStorage über die IdP-Navigation getragen.
- Genau ein Sitzungscookie; HttpOnly, SameSite und Secure in Produktion erhalten.
- Keine 30-Minuten-Abmeldung; feste serverseitige Obergrenze; Logout und
  Sitzungsrotation bleiben wirksam. Alte Cookies reaktivieren keine Sitzung.
- Vor Merge unabhängiger Reviewer, Security und gezielte QA sowie Linux-CI.
- PROJECT_STATE nach Merge aktualisieren.

## Tests

Gezielte reale HTTP-Tests: AuthApiIntegrationTest und OidcLoginFlowIntegrationTest
(Cookie-Flags, beide Laufzeiten, absoluter Ablauf auf /auth/me, unveränderte
Ablauffrist, Fixation/Logout/CSRF, OIDC-IdP-Ablauf). Desktop-Build und gezielte
Browser-QA für Checkbox und Request-Payloads; vollständige ci-local.sh einmal
am finalen Produktstand. Ergebnisse werden erst nach Ausführung berichtet.

## Workspace

Eigener Worktree `Keplura-remember-login`, Branch `codex/remember-login`,
Ausgangspunkt `main@9535c93`. Der ältere Worktree `Keplura-persist-login`
enthält separate uncommittete Arbeit und wird nicht verändert.

## Ergebnis (18.11.2026, PR #80)

Produktstand `7dcc00c5e50bfa1ea561e92f2538e48ae13cdceb`: 27 gezielte reale
Linux-HTTP-Tests und Desktop-Build bestanden. Unabhängiger Reviewer und Security
haben diesen Stand ohne offene Befunde freigegeben. Gezielte Browser-QA 4/4:
echte Vue-Oberfläche, HTTP vollständig simuliert; keine native Tauri- oder echte
IdP-Wiederherstellungsprüfung. Beide Loginwege, beide Auswahlwerte, Label und
Tastatur sowie OIDC-Präferenzbereinigung geprüft.

Verbindliche Linux-CI als Benutzer ci (uid 1500), Java 21, acl installiert:
`ci/evidence/20261118-200407/`, Exit 0. 76 Server-Testklassen, 532 Tests bestanden,
ein OCR-Test mangels Tesseract übersprungen; nativer Rechte-Helfer, Desktop-Build
und Linux-Tauri-Check grün. Erster Anlauf `ci/evidence/20261118-200001/` stoppte
wegen fehlender Schreibrechte im NPM-Cache des Testcontainers; nach Korrektur der
Testumgebung gleicher Produktstand vollständig grün. Beide Nachweise erhalten.

Der PO bot während des letzten Checks ausdrücklich Überspringen und Merge an;
der Check war jedoch vor einem Abbruch bereits vollständig grün fertig. Es
wurde keine Prüfung fälschlich abgehakt. Release und Deployment sind nicht Teil
dieses Auftrags. PROJECT_STATE wird unmittelbar nach dem verifizierten Merge
aktualisiert.