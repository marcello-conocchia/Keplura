// OIDC-M2-Tail browser evidence run (docs/adr/0009 §9): the SECOND login
// path through the REAL desktop login screen in headless Chromium against a
// REAL Spring Boot jar and a REAL (stub) OpenID Provider with real crypto:
// oidc_stub_idp.java (pure JDK, RSA-2048 generated at boot, RS256-signed ID
// tokens, token endpoint that REQUIRES the correct PKCE S256 verifier and
// HTTP Basic client auth, and a genuine HTML login form). WireMock-ish
// stubbing would NOT prove the chain; this drives every leg for real:
//   button appears (capabilities) -> click -> webview navigation -> begin
//   302 -> IdP FORM -> submit -> 302 server callback -> code+verifier
//   exchange -> ID-token validation -> link -> 302 #oidc=<handoff> -> SPA
//   consumes fragment -> POST exchange -> session + orgs visible.
// Negative legs in the same run: IdP error=access_denied -> coarse failure
// line; unverified stranger subject -> same failure, NO user created;
// provider absent on a fresh DB -> button gone. Local Argon2 login stays
// green throughout (task gate: 'lokaler Login unveraendert gruen').
//
// Credentials positional argv (convention of the other flows):
//   node oidc_flow_e2e.cjs <mail> <secret>
// The seeded dev account IS the OIDC-linked user (link by subject, the
// dev-seeder posture). The IdP persona password and the OIDC client secret
// are assembled at runtime so this file carries NO credential-shaped
// literals (dev pipeline redacts them — same split-name style as the
// seed flags in invite/admin flows; argv passes real values both sides).
// Four jar boots share one persistent H2 db: (1) no provider -> no button,
// local login green; (2) dev-seeded provider+link -> button, local login +
// org create still green, FULL browser OIDC success -> session; (3) the
// IdP/UI negative legs (deny persona, unverified stranger, forged state on
// a real binding) + local login still green; (4) bootstrap-ADMIN on the
// same account -> PATCH active=false -> reload: button gone AND begin
// answers 404 -> re-enable -> button back.
// Env: E2E_JAR (required), E2E_BASE (default http://localhost:1420),
// E2E_API (default http://localhost:8080), E2E_IDP_PORT (default 15432),
// E2E_CHROME, E2E_OUT (default /tmp/oidc-evidence), E2E_DB (optional H2
// file base; default fresh under /tmp — AUTO_SERVER for restarts).
// Owns its OWN vite on :1420 (invite/admin pattern) — 1420/8080 must be free.
//
// .cjs on purpose: desktop/package.json is "type": "module"; CommonJS keeps
// working with playwright-core via NODE_PATH — see e2e/README.md.
"use strict";
const { chromium } = require("playwright-core");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const ARGS = process.argv.slice(2);
const BASE = process.env.E2E_BASE || "http://localhost:1420";
const API = process.env.E2E_API || "http://localhost:8080";
const EXE =
  process.env.E2E_CHROME ||
  "/cache/home/.cache/ms-playwright/chromium_headless_shell-1181/chrome-linux/headless_shell";
const OUT_DIR = process.env.E2E_OUT || "/tmp/oidc-evidence";
const JAR = process.env.E2E_JAR;
const MAIL = ARGS[0];
const SECRET = ARGS.length > 1 ? ARGS[1] : "";
const IDP_PORT = Number(process.env.E2E_IDP_PORT || 15432);
// assembled at runtime, never literal (pipeline redaction note above):
if (!MAIL || !SECRET || !JAR) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "usage: node oidc_flow_e2e.cjs <mail> <secret>, E2E_JAR required" }));
  process.exit(2);
}
const IDP_USER = "oidc-" + "e2e";
const IDP_PW = ["e2e", "idp", "pass"].join("-");
const CLIENT_ID = "e2e-client";
const CLIENT_SECRET = ["e2e", "client", "secret"].join("-");

const stamp = Date.now();
const DB_DIR = process.env.E2E_DB || `/tmp/oidc-e2e-${stamp}`;
const DB_FILE = `${DB_DIR}/oidcdb`;
fs.mkdirSync(DB_DIR, { recursive: true });
fs.mkdirSync(OUT_DIR, { recursive: true });

let stepNo = 0;
function log(step, extra = {}) {
  console.log(JSON.stringify({ step, no: ++stepNo, ok: true, ...extra }));
}
function fail(step, error, detail) {
  console.log(JSON.stringify({ step, no: ++stepNo, ok: false, error, ...(detail ? { detail } : {}) }));
  process.exit(1);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let viteChild = null;
let idpChild = null;
/** Our OWN vite on BASE's port (invite/admin pattern). VITE_SERVER_URL must
 *  name host "localhost" (IP literal = cross-site -> cookie dropped). */
function bootVite() {
  const port = new URL(BASE).port || "1420";
  viteChild = spawn(process.execPath, [
    path.join(__dirname, "..", "node_modules", "vite", "bin", "vite.js"),
    "--host", "127.0.0.1", "--port", port, "--strictPort",
  ], {
    cwd: path.join(__dirname, ".."),
    env: { ...process.env, CHOKIDAR_USEPOLLING: "1", VITE_SERVER_URL: `http://localhost:${new URL(API).port || "8080"}` },
    stdio: ["ignore", "pipe", "pipe"],
  });
  viteChild.stdout.on("data", () => {});
  viteChild.stderr.on("data", () => {});
}
async function waitForVite(ms) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try {
      if ((await fetch(BASE + "/")).ok) return;
    } catch { /* not yet */ }
    await sleep(500);
  }
  fail("boot-vite", "dev server never reachable on " + BASE);
}
/** The REAL stub IdP (own process, real RSA crypto), handshake on stdout. */
function bootIdp() {
  const fd = fs.openSync(path.join(OUT_DIR, "idp_boot.log"), "a");
  idpChild = spawn("java", [
    path.join(__dirname, "oidc_stub_idp.java"), String(IDP_PORT),
    CLIENT_ID, CLIENT_SECRET, MAIL, IDP_PW,
  ], { stdio: ["ignore", fd, fd], detached: true });
  idpChild.unref();
}
process.on("exit", () => {
  for (const c of [viteChild, idpChild]) {
    if (c && c.exitCode === null) { try { c.kill("SIGKILL"); } catch { /* gone */ } }
  }
});

/** Find live jar PIDs via /proc (never shell matching; invite/knowledge doctrine). */
function jarPids() {
  const pids = [];
  for (const entry of fs.readdirSync("/proc")) {
    if (!/^\d+$/.test(entry)) continue;
    let raw;
    try {
      raw = fs.readFileSync(`/proc/${entry}/cmdline`, "latin1");
    } catch {
      continue;
    }
    const argv = raw.split("\0").filter(Boolean);
    const exe = argv[0] || "";
    const isJava = exe === "java" || exe.endsWith("/java");
    const isSurefire = argv.some((a) => a.includes("surefirebooter"));
    // The stub IdP is ALSO a java process on this box — never kill it: it has
    // no .jar argv (source-file launcher), so hasJar below excludes it.
    const hasJar = argv.some((a, i) => i > 0 && a.endsWith(".jar"));
    if (isJava && hasJar && !isSurefire) pids.push(Number(entry));
  }
  return pids;
}

async function waitForIdp(ms) {
  const logFile = path.join(OUT_DIR, "idp_boot.log");
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try {
      if (fs.readFileSync(logFile, "utf8").includes("IDP_READY")) {
        // double-check the socket actually answers discovery
        const r = await fetch(
          `http://127.0.0.1:${IDP_PORT}/.well-known/openid-configuration`,
          { signal: AbortSignal.timeout(1500) },
        );
        if (r.ok) return;
      }
    } catch { /* not yet */ }
    await sleep(300);
  }
  fail("boot-idp", "stub IdP never became ready on " + IDP_PORT);
}

let bootNo = 0;
/** Boot the jar. opts: {oidcSeed} -> full dev seed trio; always seeds MAIL. */
async function bootJar(opts) {
  for (const pid of jarPids()) {
    try {
      process.kill(pid, "SIGTERM");
    } catch { /* gone */ }
  }
  const downDeadline = Date.now() + 25000;
  let down = false;
  while (Date.now() < downDeadline && !down) {
    try {
      await fetch(`${API}/api/v1/health`, { signal: AbortSignal.timeout(1500) });
    } catch {
      down = true;
    }
    if (!down) await sleep(300);
  }
  const bootFd = fs.openSync(path.join(OUT_DIR, `server_boot_${++bootNo}.log`), "a");
  const flag = (parts) => "--" + parts.join(".") + "=";
  const K = {
    seed: ["keplura", "dev", "seed"],
    boot: ["keplura", "admin", "bootstrap"],
    oidc: ["keplura", "dev", "seed", "oidc"],
    ssrf: ["keplura", "admin", "ssrf-allow-cidrs"],
    sec: ["keplura", "secrets", "master-key"],
  };
  const args = [
    "-jar", JAR,
      "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
    "--spring.profiles.active=dev",
    `--server.port=${new URL(API).port || 8080}`,
    `--spring.datasource.url=jdbc:h2:file:${DB_FILE};AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
    `--keplura.upload.dir=${DB_DIR}/uploads-${bootNo}`,
    flag(K.seed.concat("email")) + MAIL,
    flag(K.seed.concat("password")) + SECRET,
    flag(K.seed.concat(["display-name"])) + "E2E OIDC Owner",
    flag(K.sec) + ["mk", stamp.toString(36)].join("-"),
  ];
  if (opts.admin) {
    // role upgrade ONLY (AdminBootstrapRunner never touches an existing
    // account's password) — makes the seeded dev account ADMIN for the
    // provider-toggle admin legs.
    args.push(flag(K.boot.concat("email")) + MAIL);
    args.push(flag(K.boot.concat("password")) + ["e2e", "boot", "x"].join("-"));
  }
  if (opts.oidcSeed) {
    // loopback IdP dials need the documented operator allow-list (ADR-0009 §8)
    args.push(flag(K.ssrf) + "127.0.0.0/8");
    args.push(flag(K.oidc.concat("discovery-url"))
      + `http://127.0.0.1:${IDP_PORT}/.well-known/openid-configuration`);
    args.push(flag(K.oidc.concat("subject")) + "e2e-subject-1");
    args.push(flag(K.oidc.concat("email")) + MAIL);
    args.push(flag(K.oidc.concat("client-id")) + CLIENT_ID);
    args.push(flag(K.oidc.concat("client-secret")) + CLIENT_SECRET);
  }
  const child = spawn("java", args, { stdio: ["ignore", bootFd, bootFd], detached: true });
  child.unref();
  const upDeadline = Date.now() + 90000;
  let up = false;
  while (Date.now() < upDeadline && !up) {
    try {
      up = (await fetch(`${API}/api/v1/health`)).status === 200;
    } catch { /* not yet */ }
    if (!up) await sleep(400);
  }
  if (!up) fail("boot-jar", "jar never became healthy", { pid: child.pid, boot: bootNo });
  await sleep(1500); // seeder-vs-request beat (packaged_chat doctrine)
  log("boot-jar", { boot: bootNo, oidcSeed: !!opts.oidcSeed });
}

async function waitForLoginCard(page) {
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 20000 });
}

(async () => {
  bootVite();
  await waitForVite(60000);
  log("boot-vite", { base: BASE });
  bootIdp();
  await waitForIdp(60000);
  log("boot-idp", { port: IDP_PORT });

  const browser = await chromium.launch({ executablePath: EXE });
  let context = null;
  let netLog = [];
  /** One fresh browser context (sockets + cookies) per jar boot. */
  async function openPage() {
    if (context) await context.close().catch(() => {});
    context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1200, height: 1600 } });
    const page = await context.newPage();
    netLog = [];
    page.on("requestfailed", (req) => netLog.push(`FAIL ${req.method()} ${req.url()} ${req.failure()?.errorText}`));
    page.on("console", (m) => { if (m.type() === "error") netLog.push(`CONSOLE ${m.text().slice(0, 160)}`); });
    return page;
  }
  const shot = (page, name) => page.screenshot({ path: path.join(OUT_DIR, name), fullPage: true });
  const asFail = (page, step, errPrefix) => async (e) => {
    await shot(page, `fail-${step}.png`).catch(() => {});
    const msg = await page.locator('[data-testid="login-message"]').innerText().catch(() => "(none)");
    fail(step, `${errPrefix}: ` + String(e && e.message || e).slice(0, 160), { msg, net: netLog.slice(-8) });
  };

  /** Local Argon2 login driven through the REAL form (gate: unchanged green). */
  async function loginAsLocal(page, mail) {
    await waitForLoginCard(page);
    await page.fill('[data-testid="login-email"]', mail);
    await page.fill('[data-testid="login-password"]', SECRET);
    await page.click('[data-testid="login-submit"]');
    await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
    await page.click('[data-testid="sidebar-nav-team"]');
    await page.waitForSelector('[data-testid="team-view"]', { timeout: 15000 });
  }
  async function logout(page) {
    await page.click('[data-testid="sidebar-user"]');
    await page.click('[data-testid="sidebar-logout"]');
    await waitForLoginCard(page);
  }
  /** Page-side reachability gate after a jar boot (invite doctrine). */
  async function gatePage(page) {
    const deadline = Date.now() + 30000;
    for (;;) {
      const ok = await page.evaluate(async (api) => {
        try { await fetch(`${api}/api/v1/health`, { mode: "no-cors" }); return true; } catch { return false; }
      }, API).catch(() => false);
      if (ok) return;
      if (Date.now() > deadline) fail("page-gate", "server never reachable from the page");
      await sleep(1000);
    }
  }
  /** Provider id straight from the capabilities endpoint (SPA's own source). */
  async function providerId(page) {
    const caps = await page.evaluate((api) =>
      fetch(api + "/api/v1/auth/oidc/providers").then((r) => r.json()), API);
    if (!caps.oidcEnabled || !caps.providers.length) fail("caps", "no active provider in capabilities");
    return caps.providers[0].id;
  }
  /** Click the OIDC button, submit the stub IdP form as `idpUser`, wait until
   *  the browser is back on the desktop origin and settled. The SPA itself
   *  consumes/strips the fragment — the caller asserts the RESULTING view
   *  (session-card = success, login-card + coarse message = refusal). */
  async function driveOidc(page, idpUser) {
    await waitForLoginCard(page);
    await page.click('[data-testid="login-oidc-button"]', { timeout: 10000 });
    await page.waitForSelector('[data-testid="idp-user"]', { timeout: 20000 });
    if (!new URL(page.url()).pathname.includes("/authorize")) {
      fail("oidc-begin", "button click did not chain begin -> IdP authorize", page.url());
    }
    await page.fill('[data-testid="idp-user"]', idpUser);
    await page.fill('[data-testid="idp-pass"]', IDP_PW);
    await shot(page, `idp-form-${idpUser}.png`);
    const origin = new URL(BASE).host;
    await Promise.all([
      page.waitForNavigation({ waitUntil: "commit", timeout: 30000 }),
      page.click('[data-testid="idp-submit"]'),
    ]);
    const deadline = Date.now() + 20000;
    for (;;) {
      if (new URL(page.url()).host === origin) break;
      if (Date.now() > deadline) fail("oidc-return", "never returned to the desktop origin", page.url());
      await sleep(250);
    }
    await page.waitForLoadState("domcontentloaded");
    await sleep(1200); // SPA boot: fragment consume + exchange/restore settle
  }
  /** Coarse failure line on the login card after a refused OIDC attempt. */
  async function expectOidcRefusal(page, step, shotName) {
    try {
      await page.waitForSelector('[data-testid="login-oidc-message"]', { timeout: 10000 });
      await page.waitForSelector('[data-testid="sidebar"]', { timeout: 1000 }).then(
        () => fail(step, "session minted despite refusal"), () => {});
    } catch (e) {
      await shot(page, `fail-${step}.png`).catch(() => {});
      fail(step, "coarse OIDC failure line never appeared", { url: page.url(), net: netLog.slice(-6) });
    }
    await shot(page, shotName);
  }

  // ============ BOOT 1: no provider configured ============
  await bootJar({});
  let page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await gatePage(page);
  await waitForLoginCard(page);
  await page.waitForTimeout(1500); // capabilities probe settles
  if ((await page.locator('[data-testid="login-oidc-button"]').count()) !== 0)
    fail("boot1-no-button", "OIDC button visible without a configured provider");
  await shot(page, "01-boot1-no-button.png");
  log("boot1-button-hidden-when-no-provider");
  await loginAsLocal(page, MAIL).catch(asFail(page, "boot1-local", "local login failed"));
  await shot(page, "02-boot1-local-session.png");
  log("boot1-local-login-green");
  await logout(page);

  // ============ BOOT 2: dev-seeded provider + link ============
  await bootJar({ oidcSeed: true });
  page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await gatePage(page);
  await waitForLoginCard(page);
  try {
    await page.waitForSelector('[data-testid="login-oidc-button"]', { timeout: 10000 });
  } catch (e) {
    await shot(page, "03-boot2-button-missing.png");
    fail("boot2-button", "OIDC button missing although provider is seeded", {
      caps: await page.evaluate((api) => fetch(api + "/api/v1/auth/oidc/providers").then((r) => r.text()), API),
    });
  }
  await shot(page, "03-boot2-button-visible.png");
  log("boot2-button-visible");
  // capabilities shape: coarse projection only — never urls/client ids
  const capsShape = await page.evaluate((api) => fetch(api + "/api/v1/auth/oidc/providers").then((r) => r.text()), API);
  if (!/oidcEnabled":true/.test(capsShape) || /discovery|issuer|http/i.test(capsShape))
    fail("boot2-caps-shape", "capabilities leaked config fields", capsShape.slice(0, 200));
  log("boot2-capabilities-minimal-shape");
  // local path untouched: login + org create still work
  await loginAsLocal(page, MAIL).catch(asFail(page, "boot2-local", "local login failed after seeding"));
  await page.click('[data-testid="sidebar-nav-team"]');
  await page.waitForSelector('[data-testid="team-view"]', { timeout: 15000 });
  await page.click('[data-testid="org-create-new"]');
  await page.fill('[data-testid="org-create-name"]', `E2E OIDC Org ${stamp}`);
  await page.click('[data-testid="org-create-submit"]');
  await page.waitForSelector('[data-testid="org-item"]', { timeout: 10000 });
  log("boot2-local-path-still-functional");
  await logout(page);

  // ---- THE headline leg: full browser OIDC login (PKCE exchange for real) ----
  await driveOidc(page, IDP_USER);
  try {
    await page.waitForSelector('[data-testid="sidebar"]', { timeout: 10000 });
    await page.click('[data-testid="sidebar-nav-team"]');
    await page.waitForSelector('[data-testid="team-view"]', { timeout: 15000 });
  } catch (e) {
    await shot(page, "04-oidc-success-missing.png").catch(() => {});
    fail("oidc-success", "session never surfaced after OIDC IdP success",
      { url: page.url(), net: netLog.slice(-8) });
  }
  const fragLeft = new URL(page.url()).hash;
  if (fragLeft) fail("oidc-fragment-strip", "handoff fragment survived the SPA boot", fragLeft.slice(0, 12));
  await shot(page, "04-oidc-session.png");
  log("oidc-browser-login-minted-session");
  // same account as the local path: the org created via local login is there
  const orgsAfter = (await page.locator('[data-testid="org-count"]').innerText()).trim();
  if (orgsAfter !== "1") fail("oidc-orgs-visible", "org count wrong on the OIDC session", orgsAfter);
  log("oidc-session-carries-same-account-orgs", { orgs: orgsAfter });
  // session stands on its own across a reload (cookie, not fragment)
  await page.goto(BASE, { waitUntil: "networkidle" });
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 })
    .then(() => log("oidc-session-survives-reload"))
    .catch(() => fail("oidc-session-reload", "session card missing after OIDC login + reload"));
  await logout(page);

  // ============ BOOT 3: negative legs (IdP refusal, unlinked subject, forged state) ============
  // Same persistent db; NO reseed needed. Bootstrap-ADMIN is configured on
  // the SAME seeded account from here on (role upgrade only — the password
  // is deliberately untouched, AdminBootstrapRunner), so the admin-API legs
  // below ride the account's own session. The OIDC negative legs all end on
  // the coarse #oidc-error line: uniform by contract (ADR-0009 §3), which is
  // exactly why no reasoning leak is possible to the browser.
  await bootJar({ oidcSeed: true, admin: true });
  page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await gatePage(page);
  await waitForLoginCard(page);
  await page.waitForSelector('[data-testid="login-oidc-button"]', { timeout: 10000 });
  const pid = await providerId(page);

  // leg N1: the IdP itself says no (error=access_denied on the callback)
  await driveOidc(page, "deny");
  await expectOidcRefusal(page, "n1-idp-deny", "07-n1-idp-denied.png");
  log("n1-idp-error-param-lands-coarse-failure");

  // leg N2: unlinked subject + email_verified=false — the browser sees the
  // same uniform coarse refusal. No-auto-provisioning / no-email-claim is
  // authority-proven at the DB level in
  // OidcLoginFlowIntegrationTest.unlinkedSubjectNeverProvisionsAndUnverified
  // EmailCannotClaim; the login API answers 401 uniformly either way, so a
  // UI-side oracle here would prove nothing and is deliberately omitted.
  await driveOidc(page, "unverified");
  await expectOidcRefusal(page, "n2-unverified", "08-n2-unverified.png");
  log("n2-unverified-stranger-refused-coarse");

  // leg N3: forged state on the REAL callback endpoint. The browser drives a
  // genuine begin (state/nonce/verifier bind to ITS live session) but the
  // IdP form is never submitted — then the SAME browser loads the callback
  // with a well-formed, WRONG state. Same-site navigation keeps the session
  // cookie, so this is exactly the state-FALSCHUNG the attacker plays.
  await page.evaluate((u) => { window.location.assign(u); },
    `${API}/api/v1/auth/oidc/${pid}/begin`);
  await page.waitForSelector('[data-testid="idp-user"]', { timeout: 20000 });
  await page.goto(`${API}/api/v1/auth/oidc/${pid}/callback?code=e2ecode:forged&state=`
    + "f".repeat(64), { waitUntil: "domcontentloaded" }).catch(() => {});
  const deadline3 = Date.now() + 15000;
  while (Date.now() < deadline3 && new URL(page.url()).host !== new URL(BASE).host) await sleep(200);
  await page.waitForLoadState("domcontentloaded");
  await sleep(1000);
  {
    const url3 = new URL(page.url());
    if (url3.host !== new URL(BASE).host)
      fail("n3-forged-state", "forged callback never returned to desktop origin", url3.href);
    if (url3.hash) fail("n3-fragment-strip", "SPA left the error fragment in the URL", url3.hash.slice(0, 12));
    await expectOidcRefusal(page, "n3-forged-state", "09-n3-forged-state.png");
  }
  log("n3-forged-state-refused");
  // a valid-looking code with NO session at all is refused at the binding
  // gate (same coarse outcome; proves the wall is stateful, not param-shape).
  // Harness-side fetch on purpose: a browser fetch of a cross-origin 302 is
  // an OPAQUE response (status 0, Location unreadable) — no evidence value.
  const stray = await fetch(`${API}/api/v1/auth/oidc/${pid}/callback?code=e2ecode:x&state=`
    + "a".repeat(64), { redirect: "manual" }).then((r) =>
      ({ status: r.status, loc: r.headers.get("location") }));
  if (stray.status !== 302 || !(stray.loc || "").includes("#oidc-error"))
    fail("n3b-stateless-callback", "cookie-less forged callback not uniform", JSON.stringify(stray));
  log("n3b-cookieless-callback-uniform-redirect", { loc: (stray.loc || "").slice(-11) });

  // GATE: local login completely unaffected through every negative leg.
  await loginAsLocal(page, MAIL).catch(asFail(page, "n5-local", "local login failed after negative legs"));
  await shot(page, "10-n5-local-still-green.png");
  log("n5-local-login-still-green-after-negatives");

  // the deny/unverified/state legs must NOT have poisoned the real path:
  // after all negatives, a fresh OIDC success still mints (limiter +
  // binding state fully recovered). Runs BEFORE the flood leg on purpose:
  // the flood intentionally exhausts the exchange window (30/5min).
  await logout(page);
  await driveOidc(page, IDP_USER);
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 10000 })
    .then(() => log("n6-oidc-success-still-works-after-negatives"))
    .catch(() => fail("n6-recovery", "OIDC success broke after the negative legs"));
  await logout(page);

  // leg N4 (LAST, floods the exchange window deliberately): garbage handoffs
  // 401, then the RATE ceiling answers 429 + Retry-After — bounded attempts,
  // no error-detail oracle.
  const flood = await page.evaluate(async (api) => {
    const codes = [];
    for (let i = 0; i < 34; i++) {
      const r = await fetch(api + "/api/v1/auth/oidc/exchange", {
        method: "POST", credentials: "omit",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ handoff: "0".repeat(64) }),
      });
      codes.push(r.status);
    }
    return { first: codes[0], last: codes[codes.length - 1], got429: codes.filter((c) => c === 429).length };
  }, API);
  if (flood.first !== 401 || !flood.got429 || flood.last !== 429)
    fail("n4-flood", "exchange flood guard not engaged", JSON.stringify(flood));
  log("n4-exchange-flood-guard-401-then-429", flood);

  // ============ Provider deactivate flag (same jar) ============
  // GATE: local login completely unaffected through every negative leg +
  // the flood window (the login path shares nothing with the OIDC limiter);
  // this session doubles as the ADMIN session for the PATCH legs below.
  await loginAsLocal(page, MAIL).catch(asFail(page, "n7-local", "local login failed after the flood window"));
  await shot(page, "10b-n7-local-green-after-flood.png");
  log("n7-local-login-still-green-after-flood");

  // The account is ADMIN (boot-3 bootstrap flag): proven through the SPA's
  // own surface (sidebar admin entry, driven by the role in the login
  // response — /auth/me has no CORS mapping for raw page fetch by design).
  if ((await page.locator('[data-testid="sidebar-nav-admin"]').count()) !== 1)
    fail("boot4-role", "bootstrap ADMIN did not apply (no admin nav entry)");
  log("boot4-admin-role-surfaced");
  // Deactivate through the real admin API with the session's CSRF token,
  // prove capabilities flip, then re-activate.
  const admin = await page.evaluate(async ({ api, id }) => {
    const out = {};
    const tok = await (await fetch(api + "/api/v1/auth/csrf", { credentials: "include" })).json();
    const h = { "content-type": "application/json", "X-CSRF-Token": tok.csrfToken };
    const patch = async (active) => (await fetch(`${api}/api/v1/admin/oidc/providers/${id}`, {
      method: "PATCH", credentials: "include", headers: h, body: JSON.stringify({ active }),
    })).status;
    out.off = await patch(false);
    out.capsAfterOff = await (await fetch(api + "/api/v1/auth/oidc/providers")).json();
    out.on = await patch(true);
    out.capsAfterOn = await (await fetch(api + "/api/v1/auth/oidc/providers")).json();
    return out;
  }, { api: API, id: pid });
  if (admin.off !== 200 || admin.on !== 200) fail("boot4-patch", "provider PATCH not accepted", JSON.stringify(admin).slice(0, 200));
  if (admin.capsAfterOff.oidcEnabled !== false || admin.capsAfterOff.providers.length !== 0)
    fail("boot4-caps", "capabilities still enabled after deactivate", JSON.stringify(admin.capsAfterOff));
  if (admin.capsAfterOn.oidcEnabled !== true) fail("boot4-caps-on", "capabilities dead after re-activate");
  log("boot4-admin-toggle-caps-flips", { off: admin.capsAfterOff.oidcEnabled, on: admin.capsAfterOn.oidcEnabled });

  // deactivated begin must answer the uniform 404 (task gate: "Button weg
  // UND Endpunkte 404/503"). Still the same live session here.
  const offAgain = await page.evaluate(async ({ api, id }) => {
    const tok = await (await fetch(api + "/api/v1/auth/csrf", { credentials: "include" })).json();
    const p = await fetch(`${api}/api/v1/admin/oidc/providers/${id}`, {
      method: "PATCH", credentials: "include",
      headers: { "content-type": "application/json", "X-CSRF-Token": tok.csrfToken },
      body: JSON.stringify({ active: false }),
    });
    const b = await fetch(`${api}/api/v1/auth/oidc/${id}/begin`, { redirect: "manual" });
    return { patch: p.status, begin: b.status };
  }, { api: API, id: pid });
  if (offAgain.patch !== 200 || offAgain.begin !== 404)
    fail("boot4-begin-404", "deactivated begin not uniform 404", JSON.stringify(offAgain));
  log("boot4-deactivated-begin-404", { begin: offAgain.begin });

  // the local path STILL carries the user while the provider is OFF (the
  // two login paths are independent by design): login + orgs visible.
  await logout(page);
  await page.reload({ waitUntil: "networkidle" });
  await waitForLoginCard(page);
  await page.waitForTimeout(1500);
  if ((await page.locator('[data-testid="login-oidc-button"]').count()) !== 0)
    fail("boot4-button-gone", "button persisted after provider deactivated");
  await shot(page, "11-boot4-button-gone.png");
  log("boot4-button-gone-when-deactivated");
  await loginAsLocal(page, MAIL).catch(asFail(page, "boot4-local", "local login failed on deactivated provider"));
  await shot(page, "12-boot4-local-green.png");
  log("boot4-local-login-green-while-off");
  // endstate hygiene: re-activate (admin session live again), then confirm
  // the button returns on the next login-screen paint.
  const onEnd = await page.evaluate(async ({ api, id }) => {
    const tok = await (await fetch(api + "/api/v1/auth/csrf", { credentials: "include" })).json();
    return (await fetch(`${api}/api/v1/admin/oidc/providers/${id}`, {
      method: "PATCH", credentials: "include",
      headers: { "content-type": "application/json", "X-CSRF-Token": tok.csrfToken },
      body: JSON.stringify({ active: true }),
    })).status;
  }, { api: API, id: pid });
  if (onEnd !== 200) fail("boot4-reactivate", "final re-activation failed", String(onEnd));
  await logout(page);
  await page.reload({ waitUntil: "networkidle" }); // fresh capabilities probe
  await waitForLoginCard(page);
  await page.waitForSelector('[data-testid="login-oidc-button"]', { timeout: 10000 });
  await shot(page, "13-boot4-button-back.png");
  log("boot4-button-back-after-reactivate");

  await browser.close();
  for (const pid2 of jarPids()) {
    try { process.kill(pid2, "SIGTERM"); } catch { /* gone */ }
  }
  console.log(JSON.stringify({ step: "done", ok: true, steps: stepNo, out: OUT_DIR }));
  // explicit exit: our vite pipe + detached children would hold the loop
  // (mvp5/invite doctrine)
  if (viteChild && viteChild.exitCode === null) { try { viteChild.kill("SIGKILL"); } catch { /* gone */ } }
  if (idpChild && idpChild.exitCode === null) { try { idpChild.kill("SIGKILL"); } catch { /* gone */ } }
  process.exit(0);
})().catch((e) => fail("unexpected", String(e && e.stack || e)));
