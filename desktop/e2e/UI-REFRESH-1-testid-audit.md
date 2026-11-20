# UI-REFRESH-1 — Testid-Audit alt vs. neu (Phase 1: Sidebar + Login)

Vergleichsbasis: `main@e38c859` (Alt-Baum) gegen den finalen HEAD dieses
PRs. Regel der Karte: alle `data-testid` in Sidebar/Login bleiben erhalten
oder werden mit Mapping-Tabelle migriert. Ergebnis: **volle Identitaet —
keine Migration, kein Wegfall, keine Umbenennung.**

## Sidebar (`src/components/AppSidebar.vue`)

| data-testid | alt (e38c859) | neu (Phase 1) | Selector-Intention |
|---|---|---|---|
| `sidebar` | `<nav>` | `<nav>` (Tailwind-Utilities) | gleich |
| `theme-toggle` | `<button class="icon-btn">` | `<Button variant="icon">` → `<button>` | gleich (aria-label identisch) |
| `fullscreen-toggle` | `<button class="icon-btn">` | `<Button variant="icon">` → `<button>` | gleich (aria-label identisch) |
| `sidebar-reload` | `<button class="icon-btn">` | `<Button variant="icon">` → `<button>` | gleich |
| `sidebar-nav-wissen` | `<button class="ni navbtn">` | `<Primitive as="button" class="nav-row …">` | gleich |
| `sidebar-nav-chat` | dito | dito | gleich |
| `sidebar-nav-admin` | dito (v-if isAdmin) | dito (v-if isAdmin) | gleich |
| `sidebar-kb-count` | `<span class="ct2 ic-num">` | `<span class="ic-num …">` | gleich |
| `sidebar-org` | `<div class="ni">` | `<div class="nav-row …">` | gleich |
| `sidebar-org-empty` | `<p class="ni-empty">` | `<p …>` (Utilities) | gleich |
| `sidebar-logout` | `<button class="icon-btn">` | `<Button variant="icon">` → `<button>` | gleich |
| `sidebar-status` | `<p class="sconn">` | `<p …>` (Utilities) | gleich |
| `sidebar-version` | `<span class="model" role="note">` | `<span role="note">` (Utilities) | gleich |
| `sidebar-model` | dito | dito | gleich |

## Login (`src/components/LoginView.vue`)

| data-testid | alt (e38c859) | neu (Phase 1) | Selector-Intention |
|---|---|---|---|
| `login-card` | `<div class="login">` | `<div>` (Utilities) | gleich |
| `login-form` | `<form>` | `<form>` | gleich |
| `login-email` | `<TextField>` → `<input id="login-email">` | `<Input>` → `<input id="login-email">` | gleich; `<label for>` "E-Mail" bleibt (AT-SPI) |
| `login-password` | dito (`login-password`) | dito | gleich; Label "Passwort" |
| `login-message` | `<p class="message">` | `<p>` (Utilities) | gleich |
| `login-submit` | `<AppButton type="submit">` | `<Button type="submit">` → `<button>` | gleich; Name "Anmelden" (AT-SPI-Anker) |
| `login-oidc-message` | `<p class="message error">` | `<p>` (Utilities) | gleich |
| `login-oidc-button` | `<button class="oidc-submit">` | `<Button variant="outline">` → `<button>` | gleich |
| `connection-status` | `<span>` | `<span>` | gleich (EN-Worte unveraendert) |
| `connection-retry` | `<button class="retry">` | `<button>` (Utilities) | gleich |
| `theme-toggle` | `<button class="lbtn">` | `<Button variant="icon">` → `<button>` | gleich (aria-label identisch) |
| `fullscreen-toggle` | dito | dito | gleich |

## Rollen-/Labels-Anker der gepackten Suiten

- `packaged_login_e2e.py`: Push-Button-Name "Anmelden" — Button rendert als
  native `<button>` mit gleichem Slot-Text; Entries heissen weiterhin
  "E-Mail"/"Passwort" ueber `<label for>` (reka-ui Label = natives `<label>`).
- `packaged_dark_e2e.py`: "Darstellung wechseln, aktuell …" und
  "Vollbild aktivieren/verlassen" — aria-labels zeichengenau unveraendert.
- `packaged_chat_e2e.py`: "Abmelden" (Name + title) und "Wissen"-Zeile mit
  Anzahld-badge — unveraendert.

## E2E-Suiten: Aenderungsstatus

Keine Suite wurde an Selector-INTENTIONEN geaendert (testids, Rollen,
Labels). Wartezeit-Tweaks: keine noetig (evidence: ci-local-Lauf im
PR-Body / `ci/evidence/<run>/`).

## Programmatischer Abgleich ( reproduzierbar )

    cd desktop && bash e2e/ui-refresh-1-testid-audit.sh

Das Skript vergleicht die testid-Mengen aus `git show e38c859` gegen den
Arbeitsbaum und meldet jede Differenz (Soll: keine).
