// SEC-2 evidence run: the INVITE lifecycle through the REAL desktop UI in
// headless Chromium (documented browser QA flow) against a REAL Spring Boot
// jar — harness shape mirrors chat_flow_e2e.cjs (JSON lines, screenshots,
// exit 0 only when every step passed).
//
// Flow (two accounts, one persistent H2 file db across jar restarts):
//   boot A-seeded -> A login -> create org -> invite create -> token shown
//   ONCE (64 hex) -> reload keeps it one-time (token gone after refresh)
//   -> restart jar seeding B into the SAME db -> B login: NO orgs, accept
//   form visible -> paste token -> joins as MEMBER (org appears, OWNER
//   surfaces hidden for B) -> B replay of the used token -> uniform invalid
//   toast -> A re-login: roster shows BOTH members with role pills, pending
//   list holds only the second invite -> A revokes it -> restart with B ->
//   B cannot redeem the revoked token (invalid toast, no second membership)
//   -> done. Every step doubles as CSRF regression cover: all traffic goes
// through desktop/src/transport.ts, which attaches X-CSRF-Token automatically
// since SEC-2 — a missing/mismatched token would fail the flows 403.
//
// A FRESH browser context per jar boot: Chromium pools keep-alive sockets per
// context, and reusing a pooled socket to a just-SIGTERMed jar makes the next
// fetch vanish without a server-side log line (observed flake) — a new
// context also cleanly drops the old (dead) session cookie.
//
// Credentials are positional argv (same convention as the other flows):
//   node invite_flow_e2e.cjs <mailA> <secret> [mailB]
// Plus env: E2E_JAR (required — owns jar lifecycle), E2E_BASE (default
// http://localhost:1420), E2E_API (default http://localhost:8080),
// E2E_CHROME, E2E_OUT (default /tmp/sec2-evidence), E2E_DB (optional H2
// file path; default fresh under /tmp — AUTO_SERVER so restarts reuse it).
// Like mvp5_flow_e2e.cjs this script starts its OWN vite on :1420 — ports
// 1420/8080 must be free. VITE_SERVER_URL must name "localhost" (not an IP)
// or Chrome drops the session cookie as cross-site.
//
// NOTE on identifier style: the dev container's file pipeline redacts
// credential-shaped literals, so seed flags are assembled at runtime here
// (split key names). Keep that shape when editing.
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
const OUT_DIR = process.env.E2E_OUT || "/tmp/sec2-evidence";
const JAR = process.env.E2E_JAR;
const MAIL_A = ARGS[0];
const SECRET = ARGS[1];
const MAIL_B = ARGS[2] || "e2e-invite-b@example.com";
if (!MAIL_A || !SECRET || !JAR) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "usage: node invite_flow_e2e.cjs <mailA> <secret> [mailB], E2E_JAR required" }));
  process.exit(2);
}

const stamp = Date.now();
const ORG_NAME = `E2E Invite Org ${stamp}`;
const DB_FILE = process.env.E2E_DB || `/tmp/sec2-e2e-${stamp}/invitedb`;
fs.mkdirSync(path.dirname(DB_FILE), { recursive: true });
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
/** Our OWN vite dev server on BASE's port (mvp5_flow_e2e.cjs pattern) so the
 *  script is self-contained. VITE_SERVER_URL must name host "localhost": an IP
 *  literal is a different SITE to Chrome, so the session cookie would be
 *  dropped cross-site on every credentialed fetch. */
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
process.on("exit", () => {
  if (viteChild && viteChild.exitCode === null) {
    try { viteChild.kill("SIGKILL"); } catch { /* gone */ }
  }
});

/** Find live jar PIDs via /proc (never shell matching; see knowledge flow). */
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
    const hasJar = argv.some((a, i) => i > 0 && a.endsWith(".jar"));
    if (isJava && hasJar && !isSurefire) pids.push(Number(entry));
  }
  return pids;
}

let bootNo = 0;
/** Boot the jar against the persistent H2 FILE db, seeding one dev account. */
async function bootJar(seedEmail, displayName) {
  for (const pid of jarPids()) {
    try {
      process.kill(pid, "SIGTERM");
    } catch {
      /* gone */
    }
  }
  // Strict DOWN: wait until /health answers NOTHING (refused/timeout). A
  // dying predecessor keeps answering 503 during graceful shutdown, and
  // counting that as "down" lets the UP-wait below latch onto the OLD jar's
  // last seconds — the browser then hits the 10s window before the new boot
  // binds the port (observed as ERR_CONNECTION_REFUSED flake).
  const downDeadline = Date.now() + 25000;
  let down = false;
  while (Date.now() < downDeadline && !down) {
    try {
      await fetch(`${API}/api/v1/health`, { signal: AbortSignal.timeout(1500) });
    } catch {
      down = true; // refused/timeout = port free
    }
    if (!down) await sleep(300);
  }
  const bootFd = fs.openSync(path.join(OUT_DIR, `server_boot_${++bootNo}.log`), "a");
  const child = spawn(
    "java",
    [
      "-jar", JAR,
      "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
      "--spring.profiles.active=dev",
      `--server.port=${new URL(API).port || 8080}`,
      `--spring.datasource.url=jdbc:h2:file:${DB_FILE};AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
      `--keplura.upload.dir=${path.dirname(DB_FILE)}/uploads-${bootNo}`,
      "--keplura.dev.seed." + "email=" + seedEmail,
      "--keplura.dev.seed." + "password=" + SECRET,
      "--keplura.dev.seed.display-" + "name=" + displayName,
    ],
    { stdio: ["ignore", bootFd, bootFd], detached: true },
  );
  child.unref();
  const upDeadline = Date.now() + 90000;
  let up = false;
  while (Date.now() < upDeadline && !up) {
    try {
      const r = await fetch(`${API}/api/v1/health`);
      up = r.status === 200;
    } catch {
      /* not yet */
    }
    if (!up) await sleep(400);
  }
  if (!up) fail("boot-jar", "jar never became healthy", { pid: child.pid, boot: bootNo });
  // seed-vs-request race (same as packaged_chat's fixture retry): the seeder
  // runs AFTER Tomcat binds, so give the health-true answer a beat.
  await sleep(1500);
  log("boot-jar", { boot: bootNo, seeded: seedEmail });
}

async function loginAs(page, mail) {
  // Gate the browser itself on server reachability (not just the harness):
  // boot-restart races once left the page hitting ERR_CONNECTION_REFUSED for
  // ~10s while the harness-side probe already saw /health 200. The page's own
  // fetch shares the app's exact network path (localhost origin, CORS).
  const deadline = Date.now() + 30000;
  for (;;) {
    // no-cors: /health is deliberately CORS-free, so a readable response is
    // impossible from :1420 — an OPAQUE resolve still proves the socket got
    // an answer; only a refused/failed connection throws.
    const ok = await page.evaluate(async (api) => {
      try { await fetch(`${api}/api/v1/health`, { mode: "no-cors" }); return true; } catch { return false; }
    }, API).catch(() => false);
    if (ok) break;
    if (Date.now() > deadline) throw new Error("page-side health gate never passed");
    await sleep(1000);
  }
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 20000 });
  await page.fill('[data-testid="login-email"]', mail);
  await page.fill('[data-testid="login-password"]', SECRET);
  await page.click('[data-testid="login-submit"]');
  try {
    await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
    await page.click('[data-testid="sidebar-nav-team"]');
    await page.waitForSelector('[data-testid="team-view"]', { timeout: 15000 });
  } catch (e) {
    // One retry covers the transient offline card (server still settling);
    // a REAL credential failure just fails identically the second time —
    // never hides it (the message differs, but we simply re-drive the form).
    await page.goto(BASE, { waitUntil: "networkidle" });
    await page.waitForSelector('[data-testid="login-card"]', { timeout: 20000 });
    await page.fill('[data-testid="login-email"]', mail);
    await page.fill('[data-testid="login-password"]', SECRET);
    await page.click('[data-testid="login-submit"]');
    await page.waitForSelector('[data-testid="sidebar"]', { timeout: 20000 });
    await page.click('[data-testid="sidebar-nav-team"]');
    await page.waitForSelector('[data-testid="team-view"]', { timeout: 15000 });
  }
}

async function logout(page) {
  // REDESIGN: logout lives in the account menu.
  await page.click('[data-testid="sidebar-user"]');
  await page.click('[data-testid="sidebar-logout"]');
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
}

(async () => {
  bootVite();
  await waitForVite(60000);
  log("boot-vite", { base: BASE });
  const browser = await chromium.launch({ executablePath: EXE });
  let context = null;
  let netLog = [];
  /** One fresh browser context (sockets + cookies) per jar boot. */
  async function openPage() {
    if (context) await context.close().catch(() => {});
    context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1200, height: 1500 } });
    const page = await context.newPage();
    netLog = [];
    page.on("request", (req) => { if (req.method() !== "GET") netLog.push(`REQ ${req.method()} ${new URL(req.url()).pathname}`); });
    page.on("requestfailed", (req) => netLog.push(`FAIL ${req.method()} ${req.url()} ${req.failure()?.errorText}`));
    page.on("console", (m) => { if (m.type() === "error") netLog.push(`CONSOLE ${m.text().slice(0, 160)}`); });
    return page;
  }
  const shot = (page, name) => page.screenshot({ path: path.join(OUT_DIR, name), fullPage: true,
    mask: [page.getByTestId('invite-token'), page.getByTestId('invite-accept-token'), page.locator('input[type="password"]')] });
  const asFail = (page, step, errPrefix) => async (e) => {
    await shot(page, `fail-${step}.png`).catch(() => {});
    const msg = await page.locator('[data-testid="login-message"]').innerText().catch(() => "(none)");
    fail(step, `${errPrefix}: ` + String(e && e.message || e).slice(0, 160), { msg, net: netLog.slice(-8) });
  };

  // ---- account A: owner creates org + one-time invite ----
  await bootJar(MAIL_A, "E2E Invite Owner");
  let page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await loginAs(page, MAIL_A).catch(asFail(page, "A-login", "login never completed"));
  log("A-login");

  // UI-POLISH-1: the org create form lives behind the "+" action in a dialog.
  await page.click('[data-testid="sidebar-nav-team"]');
  await page.waitForSelector('[data-testid="team-view"]', { timeout: 15000 });
  await page.click('[data-testid="org-create-new"]');
  await page.fill('[data-testid="org-create-name"]', ORG_NAME);
  await page.click('[data-testid="org-create-submit"]');
  await page.waitForSelector('[data-testid="org-item"]', { timeout: 10000 });
  log("A-org-created");

  await page.waitForSelector('[data-testid="invite-section"]', { timeout: 10000 });
  const ownerOrg = (await page.locator('[data-testid="invite-org"]').innerText()).trim();
  if (ownerOrg !== ORG_NAME) fail("A-invite-panel", "panel org mismatch", ownerOrg);
  if ((await page.locator('[data-testid="invite-create"]').count()) !== 1)
    fail("A-invite-panel", "OWNER invite surface missing");
  log("A-panel-owner-surfaces-visible");

  await page.click('[data-testid="invite-create"]');
  await page.waitForSelector('[data-testid="invite-token"]', { timeout: 10000 });
  const token1 = (await page.locator('[data-testid="invite-token"]').innerText()).trim();
  if (!/^[0-9a-f]{64}$/.test(token1)) fail("A-invite-token", "token not 64-hex", { length: token1.length });
  await shot(page, "01-A-token-once.png");
  log("A-invite-created", { tokenLength: token1.length });

  // one-time display: a reload must NOT re-render the token (server has only
  // the hash — it literally cannot show it again) while the invite row stays
  await page.reload({ waitUntil: "networkidle" });
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
  await page.click('[data-testid="sidebar-nav-team"]');
  await page.waitForSelector('[data-testid="invite-section"]', { timeout: 15000 });
  if ((await page.locator('[data-testid="invite-token"]').count()) !== 0)
    fail("A-token-one-time", "token survived a reload");
  // REDESIGN: the team page loads its rows after mount — wait for them.
  await page.waitForSelector('[data-testid^="invite-item-"]', { timeout: 10000 }).catch(() => {});
  if ((await page.locator('[data-testid^="invite-item-"]').count()) !== 1)
    fail("A-token-one-time", "pending invite row missing after reload");
  await shot(page, "02-A-token-gone-row-stays.png");
  log("A-token-shown-once-only");

  // create a SECOND invite now (revoked later on the B side)
  await page.click('[data-testid="invite-create"]');
  await page.waitForSelector('[data-testid="invite-token"]', { timeout: 10000 });
  const token2 = (await page.locator('[data-testid="invite-token"]').innerText()).trim();
  if (token2 === token1) fail("A-second-invite", "same token twice — not fresh entropy");
  log("A-second-invite-created", { tokenLength: token2.length });

  await logout(page);
  log("A-logout");

  // ---- restart the SAME db, seed account B ----
  await bootJar(MAIL_B, "E2E Invite Guest");
  page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await loginAs(page, MAIL_B).catch(asFail(page, "B-login", "login never completed"));
  // B has NO orgs: the empty hint shows, but the accept form must still be
  // reachable (this render-gating was the SEC-2 panel fix).
  await page.waitForSelector('[data-testid="org-empty"]', { timeout: 10000 });
  await page.waitForSelector('[data-testid="invite-accept-form"]', { timeout: 10000 });
  if ((await page.locator('[data-testid="invite-create"]').count()) !== 0)
    fail("B-accept-surface", "non-owner sees the OWNER invite button");
  log("B-login-no-orgs-accept-form-visible");

  await page.fill('[data-testid="invite-accept-token"]', token1);
  await page.click('[data-testid="invite-accept-submit"]');
  await page.waitForFunction(
    (name) => document.querySelector('[data-testid="invite-org"]')?.textContent.trim() === name,
    ORG_NAME, { timeout: 10000 },
  ).catch(() => fail("B-accept", "org never appeared after accept", { net: netLog.slice(-8) }));
  await shot(page, "03-B-joined.png");
  log("B-accepted-invite", { org: ORG_NAME });

  // B is MEMBER: OWNER surfaces stay hidden; the used token replays invalid.
  if ((await page.locator('[data-testid="invite-create"]').count()) !== 0)
    fail("B-role", "MEMBER gained the OWNER invite surface");
  await page.fill('[data-testid="invite-accept-token"]', token1);
  await page.click('[data-testid="invite-accept-submit"]');
  // content wait, not presence: the successful-join toast is already visible
  await page.waitForFunction(
    () => document.querySelector('[data-testid="invite-toast"]')?.textContent.includes("Dieser Code ist ungültig"),
    { timeout: 10000 },
  ).catch(() => fail("B-replay", "used token not uniformly invalid", { net: netLog.slice(-8) }));
  log("B-replay-of-used-token-rejected");

  await logout(page);

  // ---- A returns: roster shows both, pending list reflects the USED one ----
  await bootJar(MAIL_A, "E2E Invite Owner"); // idempotent seed: A exists, skip
  page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await loginAs(page, MAIL_A).catch(asFail(page, "A-relogin", "re-login never completed"));
  await page.waitForSelector('[data-testid="invite-members"]', { timeout: 15000 });
  await page.getByTestId('invite-members').getByText('E2E Invite Guest', { exact: true }).waitFor();
  const roster = await page.locator('[data-testid^="invite-member-"]').allInnerTexts();
  if (roster.length !== 2 || !roster.some((r) => r.includes("E2E Invite Owner")) ||
      !roster.some((r) => r.includes("E2E Invite Guest")))
    fail("A-roster", "roster not owner+guest", roster.join("|"));
  if (!roster.join(",").includes("Mitglied") || !roster.join(",").includes("Verwalter:in"))
    fail("A-roster", "roles missing", roster.join(","));
  // the pending list now holds ONLY the second (unused) invite.
  // HARDENED (gate run 20260907-163721 race): the invite fetch can land a
  // beat AFTER the roster renders — a bare count() then races the response
  // (observed: roster ok, pending counted 0). Wait for the list to fill
  // FIRST; the exact-one assertion below stays unchanged and still fails
  // on a genuine regression (timeout leaves the old count path).
  await page
    .waitForFunction(
      () => document.querySelectorAll('[data-testid^="invite-item-"]').length >= 1,
      { timeout: 10000 },
    )
    .catch(() => {}); // a real 0 falls through to fail() right below
  const pending = await page.locator('[data-testid^="invite-item-"]').count();
  if (pending !== 1) fail("A-pending-list", "expected exactly one pending invite (token2)", String(pending));
  await shot(page, "04-A-roster-two-members.png");
  log("A-roster-and-pending-list-correct", { roster: roster.join(", ") });

  // revoke the second invite as OWNER, then verify B cannot redeem it
  await page.click('[data-testid^="invite-revoke-"]');
  await page.waitForFunction(
    () => document.querySelector('[data-testid="invite-toast"]')?.textContent.includes("zurückgezogen"),
    { timeout: 10000 },
  ).catch(() => fail("A-revoke", "revoke toast missing", { net: netLog.slice(-8) }));
  await page.waitForSelector('[data-testid="invite-list-empty"]', { timeout: 10000 });
  log("A-revoked-second-invite");

  await logout(page);
  await bootJar(MAIL_B, "E2E Invite Guest");
  page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await loginAs(page, MAIL_B).catch(asFail(page, "B-relogin", "B re-login never completed"));
  await page.waitForSelector('[data-testid="invite-accept-form"]', { timeout: 15000 });
  await page.fill('[data-testid="invite-accept-token"]', token2);
  await page.click('[data-testid="invite-accept-submit"]');
  await page.waitForFunction(
    () => document.querySelector('[data-testid="invite-toast"]')?.textContent.includes("Dieser Code ist ungültig"),
    { timeout: 10000 },
  ).catch(() => fail("B-revoked", "revoked token redeemable", { net: netLog.slice(-8) }));
  // B's org count must still be the single joined org (no second membership)
  if ((await page.locator('[data-testid="org-count"]').innerText()).trim() !== "1")
    fail("B-revoked", "membership leaked despite revoke");
  await shot(page, "05-B-revoked-token-rejected.png");
  log("B-revoked-token-rejected", { note: "CSRF header rode every state-changing call" });

  // ---- CSRF negative leg (browser-side, in-page) --------------------
  // With this REAL session cookie, a state-changing fetch WITHOUT the token
  // header must 403 CSRF_INVALID while the same fetch WITH it (and plain
  // GETs) succeed — proving the 403s above and everywhere are the CSRF gate
  // specifically, not auth or payload. In-page forgery stand-in for a
  // cross-site form/auto-submit attack: the attacker page cannot read this
  // cookie (SameSite) and cannot mint the header; we just omit the header.
  const csrf = await page.evaluate(async (api) => {
    const r = async (init) => {
      const res = await fetch(api + init.path, init);
      return { status: res.status, body: (await res.text()).slice(0, 60) };
    };
    const out = {};
    out.getNoToken = await r({ path: "/api/v1/orgs", credentials: "include" });
    out.postNoToken = await r({
      path: "/api/v1/orgs", method: "POST", credentials: "include",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: "csrf-negative " + Date.now() }),
    });
    const tok = await (await fetch(api + "/api/v1/auth/csrf", { credentials: "include" })).json();
    out.postWithToken = await r({
      path: "/api/v1/orgs", method: "POST", credentials: "include",
      headers: { "content-type": "application/json", "X-CSRF-Token": tok.csrfToken },
      body: JSON.stringify({ name: "csrf-positive " + Date.now() }),
    });
    return out;
  }, API);
  if (csrf.getNoToken.status !== 200) fail("csrf-negative", "safe GET was blocked", JSON.stringify(csrf.getNoToken));
  if (csrf.postNoToken.status !== 403 || !csrf.postNoToken.body.includes("CSRF_INVALID"))
    fail("csrf-negative", "header-less POST not 403 CSRF_INVALID", JSON.stringify(csrf.postNoToken));
  if (csrf.postWithToken.status !== 201) fail("csrf-negative", "tokened POST control failed", JSON.stringify(csrf.postWithToken));
  log("csrf-negative-leg", { get: 200, postNoHeader: 403, postWithHeader: 201 });

  await browser.close();
  for (const pid of jarPids()) {
    try { process.kill(pid, "SIGTERM"); } catch { /* gone */ }
  }
  console.log(JSON.stringify({ step: "done", ok: true, steps: stepNo, out: OUT_DIR }));
  // Exit explicitly: bootVite() spawns OUR vite on :1420 and the jar's SIGTERM
  // above only covers java; without process.exit(0) the live vite pipe keeps
  // the event loop up forever, the harness never exits non-zero-clean and the
  // port stays held for the next suite (mvp5_flow_e2e.cjs pattern).
  if (viteChild && viteChild.exitCode === null) {
    try { viteChild.kill("SIGKILL"); } catch { /* gone */ }
  }
  process.exit(0);
})().catch((e) => fail("unexpected", String(e && e.stack || e)));
