# Desktop E2E harnesses (evidence flows)

## Migrationen und neue Funktionsprüfungen (15.11.2026)

Die aktiven Harnesses starten ihre **frischen, wegwerfbaren H2-Datenbanken**
mit Flyway und `ddl-auto=validate`. Hibernate allein erzeugt die neuen
JDBC-Tabellen für Anhänge/Websuche/MCP nicht. Bestehende Entwickler-Datenbanken
werden dadurch weder gelöscht noch automatisch baselined. Für manuelle Läufe
dieselben Flyway-Flags verwenden und einen neuen Datenbankpfad wählen.

Zusätzliche echte Browserläufe mit vorbereiteten, isolierten Testdiensten:

- `datasources_flow_e2e.cjs`: `E2E_EMAIL`, `E2E_PASSWORD`, `E2E_OUT`,
  `E2E_SOURCE_ROOT`; optional `E2E_DIRECTORY_URL`, `_BASE_DN`, `_BIND_DN`,
  `_PASSWORD` für die Formularanlage (LDAPS muss vertrauenswürdig eingerichtet sein).
- `datasources_quality_e2e.cjs`: Login-/Ausgabevariablen sowie `E2E_API`,
  `E2E_ORG_ID`, `E2E_KB_ID`; DS-Fixture mit öffentlicher Inspektionsfrist
  37 Tage und Testfragen-Set mit einer sichtbaren und einer gesperrten Quelle.
  Prüft die belegte Chat-Antwort und Wechsel/Zurücknehmen der Bewertungen
  gegen die echte Statistik sowie den neuen Qualitätslauf im Browser.
- `productivity_flow_e2e.cjs`: dieselben Login-/Ausgabevariablen, `E2E_PDF`,
  optional `E2E_SCAN`; `E2E_SPACE_TERM`, `E2E_KB_TERM`, `E2E_DOCUMENT_TERM`,
  `E2E_SEARCH_TERM` für gezielte Navigationsfälle und `E2E_WEB=1` bei realer Websuche.
- `mcp_flow_e2e.cjs`: Login-/Ausgabevariablen plus `E2E_MCP_URL`,
  `E2E_MCP_TOKEN`, `E2E_SEARX_URL`, optional `E2E_MCP_SPACE` (vollständiger
  Name „Organisation / Arbeitsbereich“). Vorher erfolgreichen Werkzeugaufruf
  für die Protokollprüfung ausführen. MCP-Ziel in der Betrieb-Freigabeliste erlauben.

Für alle: `NODE_PATH` mit `playwright-core`, optional `E2E_CHROME`,
`E2E_BASE` standardmäßig `http://localhost:1420`. Nur Testzugänge verwenden.
Diese vier Läufe benötigen externe Fixtures und ergänzen die bestehenden
hermetischen Suiten des verbindlichen CI-Gates.

## Packaged-app E2E (M2-POLISH, ADR-0005 evidence)

> Gate note (CI-LOCAL, 2026-09-06): `scripts/ci-local.sh --suite <name>`
> wraps these harnesses as stage [4/4] without changing them. Wrapper
> contract worth knowing when editing a flow: browser suites share ONE
> wrapper-started vite on :1420 (`VITE_SERVER_URL=http://localhost:8080`);
> `knowledge` gets an externally booted seeded jar (`E2E_JAR` enables its stale-session leg), while
> `chat`/`invite` keep owning the jar lifecycle themselves and are booted
> only after the previous suite's jar was reaped — do not rely on a server
> surviving between suites. Credentials come from `CI_E2E_EMAIL`/
> `CI_E2E_PASSWORD` (never literals, per the scrubber note below).


`packaged_login_e2e.py` drives the **real built Tauri binary** (native GTK
window + WebKitGTK webview) under Xvfb against the **real Spring Boot jar**,
using only AT-SPI2 (the accessibility tree) plus XTEST key events — no test
hooks in app or webview code.

Covered (PO mandate): launch fresh → login → org list → app restart with
session restore (proves the native persistent cookie jar) → logout →
server restart with stale-cookie restore rejection (proven from the server
log: 401 on /api/v1/orgs replayed from the persisted cookie) → re-login →
server unreachable.

Requirements (met by the intracore-dev container): `tauri build` artifacts
(`desktop/src-tauri/target/release/keplura-desktop`), `mvn package` jar,
Xvfb, dbus-run-session, at-spi2-core, python3-gi + WebKit2/Atspi typelibs,
libXtst.

Run:

```bash
cd server && mvn -B package -DskipTests          # jar
cd desktop && npm run tauri build                # app binary (deb too)
cd desktop/e2e
dbus-run-session -- xvfb-run -a -s "-screen 0 1280x800x24" \
  python3 packaged_login_e2e.py \
    --bin ../src-tauri/target/release/keplura-desktop \
    --jar ../../server/target/keplura-server-0.1.0.jar \
    --email qa@e2e.example --password 'E2e-Pass-123'
```

Output is JSON lines (one `{"step":…, "ok":true}` per step); exit 0 only if
all steps passed. Windows/macOS: GUI execution on CI runners is unreliable
for this stack, so CI runs the headless Rust-side cookie-persistence test
(`src-tauri/tests/cookie_persistence.rs`, all platforms) instead — documented
honestly in ADR-0005 and the PR.

## Knowledge-flow E2E (M3e, browser QA flow)

`knowledge_flow_e2e.cjs` (Node + playwright-core against the container's
headless chromium) drives the DOCUMENTED browser flow — `npm run dev` on
http://localhost:1420 against the real Spring Boot jar with a dev-seeded
account — and proves the M3e user journey: login → org → space → KB create →
txt upload (indexStatus badge INDEXED) → search with cited hits → reload
persistence → logout. JSON-line output per step, screenshots to `E2E_OUT`;
exit 0 only when every step passed. This is the same flow the QA gate walks,
script-automatable; the packaged (native-transport) leg stays covered by
`packaged_login_e2e.py` + the Rust cookie test above.

**Stale-session leg (review Finding 1 regression cover):** when `E2E_JAR`
points at the jar, the script additionally SIGTERMs the running server,
restarts it on a FRESH H2 db seeded with a SECOND account, lets the panel's
next call hit the dead session (401 → login view) and then re-logs in as
account B **without a page reload** (the pinia store survives view
mount/unmount, so any tenant ref the expired branch missed would still be
renderable). It asserts zero `knowledge-kb-item`/`knowledge-doc-item` rows
and none of account A's names/snippets appear for B. This is the browser
equivalent of the packaged e2e's "server restart with stale-cookie restore
rejection" step, extended across two accounts.

Prereqs: seeded jar booted (dev profile + keplura.dev.seed.* CLI props, see
docs/development.md), `npm run dev` serving :1420, and playwright-core
installed in a scratch dir OUTSIDE this repo (deliberately not a package
dependency — it is a test tool, not product code):

```bash
cd desktop/e2e
E2E_EMAIL=<seed-email> E2E_OUT=/tmp/m3e-evidence \
  [E2E_JAR=../../server/target/keplura-server-0.1.0.jar \
   E2E_EMAIL_B=<second-seed-email> E2E_PASSWORD_B=<second-password>] \
  NODE_PATH=<scratch>/node_modules node knowledge_flow_e2e.cjs <seed-password>
```

Without `E2E_JAR` the stale-session leg is skipped (externally managed jar,
plain evidence mode). With it, the script OWNS the server lifecycle — boot
the first jar on a disposable db, it will be killed and replaced.

## Chat-flow E2E (M4b, browser QA flow)

`chat_flow_e2e.cjs` extends the same harness to the M4b chat journey and is
the only e2e that also runs the REAL streaming path end to end: it boots a
local mock OpenAI-compatible upstream (:19921) that answers SSE deltas, then
owns the jar lifecycle with `KEPLURA_GATEWAY_*` env pointing at that mock
(so `E2E_JAR` is REQUIRED — the gateway config must be injected at boot).

Proven journey: login → org/space/KB + indexed .txt fixture → sidebar Chat →
plain chat streams token-by-token and persists → RAG chat renders citation
chips (filename + Abschnitt + score) → reload keeps list/transcript/citations
→ delete conversation → STOP mid-stream (slow mock mode; aborted row persists
honestly as "gestoppt" with its partial text) → logout clears the client-side
list → re-login refetches from the server → stale-session leg: jar restarts
on a FRESH db without a page reload, the next chat call 401s into the login
view and re-login shows an empty (foreign-free) list. JSON-line steps +
screenshots to `E2E_OUT` (default /tmp/m4b-evidence), exit 0 only when every
step passed. M4c adds one assertion at login: the sidebar's `sidebar-model`
pill reads exactly "· Modell: konfiguriert" — the closed-vocabulary provider
word from /api/v1/system/info (the ONLY provider fact the desktop ever sees;
provider name, base URL and key are not reachable from any client surface).

Prereqs: `npm run dev` on :1420, a `mvn package` jar, playwright-core in a
scratch dir outside the repo (same as M3e above). Credentials are positional
(never literal):

```bash
cd desktop/e2e
E2E_OUT=/tmp/m4b-evidence \
  E2E_JAR=../../server/target/keplura-server-0.1.0.jar \
  NODE_PATH=<scratch>/node_modules \
  node chat_flow_e2e.cjs <seed-email> <seed-secret>
```

Note: the jar boot kills any other running `java -jar` — do not run `mvn
verify` concurrently (the script excludes surefire forks, but the dev-profile
H2 file lock in `server/target` still collides). The credential-identifier
style inside the script is deliberate: the dev container's file pipeline
scrubs credential-shaped literals from agent-written files.

## MVP-5 parser-flow E2E

`mvp5_flow_e2e.cjs` starts its OWN jar (fresh temp H2 + upload dir under
`E2E_DB_DIR`, dev-seeded account) + its OWN vite on :1420 (the only CORS
origin the server allows credentialed — see DesktopCorsConfig; `VITE_SERVER_URL`
must name `localhost`, not `127.0.0.1`, or Chrome drops the session cookie as
cross-site) and proves the parser loop in the real UI with fixtures generated
by `mvp5_fixtures.py` (hand-written valid PDF/DOCX bytes — the e2e box has no
PDFBox/POI): docx → INDEXED badge + cited search hit; pdf → same; scan-like
pdf → NO_TEXT_LAYER badge + never cited; then a jar restart at
`pdf-max-pages=30` uploading a 60-page pdf → honest rejection toast, NO row
(full transaction rollback), and the A–C badges survive the restart (proof
`NO_TEXT_LAYER` is persisted db state). JSON-line steps, screenshots to
`E2E_OUT`, exit 0 only if all passed.

```bash
cd desktop/e2e
E2E_JAR=../../server/target/keplura-server-0.1.0.jar \
  E2E_EMAIL=<seed-email> NODE_PATH=<scratch>/node_modules \
  node mvp5_flow_e2e.cjs <seed-password>
```


## Admin-flow E2E (ADMIN-KI, browser QA flow)

`admin_flow_e2e.cjs` drives the ADMIN panel against a real jar (own vite,
own H2 file db — invite_flow harness shape). Covers: bootstrap-admin
promotion + sidebar entry visibility, the one-click .env seed offer, SSRF
refusal of a metadata-address dial (`Ziel blockiert`, policy runs before the
socket), mask-only secret display, dead-port test pill, EMBEDDING default
switch WITH the no-reindex confirm dialog, read-only RAG board, status
board, and the boot-2 negative half: USER account sees NO admin entry and
every direct /api/v1/admin/** call is server-gated (GET 403, POST-without-
CSRF 403 CSRF_INVALID, POST-with-CSRF 403 ADMIN_REQUIRED — gate ORDER per
SessionAuthConfig). A/B accounts ride one DB across jar restarts.

```bash
cd desktop/e2e
E2E_JAR=../../server/target/keplura-server-0.1.0.jar \
  NODE_PATH=<scratch>/node_modules \
  node admin_flow_e2e.cjs <mailA> <secretA> [mailB] [secretB]
```

## OIDC-flow E2E (OIDC-M2-Tail, second login path, browser QA flow)

`oidc_flow_e2e.cjs` drives the OIDC login through the REAL desktop login
screen against a real jar AND a REAL stub OpenID Provider
(`oidc_stub_idp.java`: own process, pure JDK, boot-generated RSA-2048,
RS256-signed ID tokens, an HTML authorize form, and a token endpoint that
demands the correct S256 PKCE verifier + Basic client auth — the full
begin -> form -> callback -> code+verifier exchange -> session chain runs
for real, no contract mocking). Four boots share one H2 file db; the dev
seeder trio (`keplura.dev.seed.oidc.*`) materializes provider + link,
and `127.0.0.0/8` joins the SSRF allow-list for the loopback dial (the
documented single-machine posture, ADR-0009 §8). Legs: no-provider -> no
button; seeded -> button + coarse-only capabilities shape; local Argon2
login + org create untouched; headline browser OIDC success -> session +
orgs + reload; negatives (IdP error param, unverified stranger, forged
state on a live binding, cookie-less callback, exchange flood 401->429);
admin deactivate -> button gone AND begin 404 -> re-activate -> button
back. Local login is re-gated after every phase.

```bash
cd desktop/e2e
E2E_JAR=../../server/target/keplura-server-0.1.0.jar \
  NODE_PATH=<scratch>/node_modules \
  node oidc_flow_e2e.cjs <seed-email> <seed-secret>
# extra env: E2E_IDP_PORT (default 15432), E2E_DB, E2E_OUT (/tmp/oidc-evidence)
```

## DARK-FS E2E (Dark Mode + Full-Screen, browser + native legs)

`dark_flow_e2e.cjs` (browser leg) proves the appearance system against the
real vite build with a seeded jar on :8080 (externally booted — same wrapper
shape as `knowledge`): system-preference resolution LIVE-flipping with
emulated `prefers-color-scheme`, explicit dark overriding a contradicting OS
setting, localStorage persistence + the index.html pre-paint script applying
dark BEFORE the app boots, dark tokens on the logged-in sidebar, the account
menu selecting the app-wide palette, and the fullscreen menu item on
the browser fallback path (document.fullscreen + fullscreenchange-mirrored
label, Escape exits natively). Positional credentials like every flow.

`packaged_dark_e2e.py` (native leg, AT-SPI + XTEST on the packaged binary
like packaged_login/chat) proves what only the packaged runtime exercises:
the account menu in the real accessibility tree, the rendered dark/light
surface colours in native screenshots, and F11 completing the Tauri
`set_fullscreen` call (verified through the resulting menu label),
and Escape (our Tauri-path handler — no browser fullscreen here to steal it)
leaving fullscreen again. It expects a seeded jar already answering on
:8080 (the ci-local wrapper guarantees that ordering). Xvfb has no window
manager: this checks native IPC completion, not a window manager honouring
the request or setting `_NET_WM_STATE_FULLSCREEN`.

```bash
# browser leg (wrapper-equivalent manual run)
cd desktop/e2e
E2E_OUT=/tmp/dark-fs-evidence NODE_PATH=<scratch>/node_modules \
  node dark_flow_e2e.cjs <seed-email> <seed-secret>

# native leg
dbus-run-session -- xvfb-run -a -s "-screen 0 1280x800x24" \
  python3 packaged_dark_e2e.py \
    --bin ../src-tauri/target/release/keplura-desktop \
    --email <seed-email> --password '<seed-secret>'
```

