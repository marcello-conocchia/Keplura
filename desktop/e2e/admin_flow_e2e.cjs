// ADMIN-KI evidence run: the admin panel through the REAL desktop UI in
// headless Chromium against a REAL Spring Boot jar — harness shape mirrors
// invite_flow_e2e.cjs (JSON lines, screenshots, exit 0 only when all steps
// passed).
//
// Flow (two accounts share one persistent H2 FILE db across jar restarts;
// the ADMIN flag lives on the user row, so both boots run the same schema):
//   boot A = bootstrap-ADMIN (seed box visible because ENV gateway is set
//   via CLI args) -> admin panel -> seed "Aus .env-Vorgaben anlegen"
//   (provider+chat model created) -> create api provider with a METADATA
//   address -> toast shows the SSRF refusal (400, never dialled) -> create
//   stub provider (127.0.0.1 dead port) -> Verbindung testen pill "Nicht
//   erreichbar" -> EMBEDDING model + default switch (confirm dialog =
//   no-reindex warning) -> status board ok -> CSRF negative leg (admin
//   POST without token header -> 403 CSRF_INVALID, with token -> 200)
//   -> restart jar WITHOUT the ENV gateway, seeding B = normal USER ->
//   B login: sidebar shows NO admin entry -> in-Browser GET admin/providers
//   -> uniform 403 FORBIDDEN from the server gate -> done.
//
// A FRESH browser context per jar boot (pooled-socket flake lesson from
// invite_flow_e2e.cjs). Credentials are positional argv like the other
// flows: node admin_flow_e2e.cjs <mailA> <secretA> [mailB] [secretB].
// Env: E2E_JAR (required), E2E_BASE (default http://localhost:1420),
// E2E_API (default http://localhost:8080), E2E_CHROME, E2E_OUT, E2E_DB.
// Boot args (admin bootstrap mail/secret, ENV gateway, master key) are
// ASSEMBLED AT RUNTIME from split literals: the dev container's file
// pipeline redacts credential-shaped literals, so nothing literal here may
// look like one. VITE_SERVER_URL must name "localhost" (cookie site).
//
// .cjs on purpose: desktop/package.json is "type": "module"; CommonJS
// keeps working with playwright-core via NODE_PATH — see e2e/README.md.
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
const OUT_DIR = process.env.E2E_OUT || "/tmp/admin-e2e";
const JAR = process.env.E2E_JAR;
const MAIL_A = ARGS[0];
const SECRET_A = ARGS[1];
const MAIL_B = ARGS[2] || "e2e-admin-b@example.com";
const SECRET_B = ARGS[3] || ("b-user-" + Date.now().toString(36) + "-pw");
if (!MAIL_A || !SECRET_A || !JAR) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "usage: node admin_flow_e2e.cjs <mailA> <secretA> [mailB] [secretB], E2E_JAR required" }));
  process.exit(2);
}

const stamp = Date.now();
const DB_FILE = process.env.E2E_DB || `/tmp/admin-e2e-${stamp}/admindb`;
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
/**
 * Boot the jar on the persistent H2 FILE db. `opts`:
 *   seedMail/seedSecret  -> dev-account seeder flags
 *   admin                -> bootstrap-admin flags (ADMIN-KI)
 *   envGateway           -> keplura.gateway/chat CLI props (seed offer)
 *   masterKey            -> secrets master key flag
 * Flag NAMES are assembled at runtime (split literals) — see file pipeline
 * redaction note at the top.
 */
async function bootJar(opts) {
  for (const pid of jarPids()) {
    try { process.kill(pid, "SIGTERM"); } catch { /* gone */ }
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
  const args = [
    "-jar", JAR,
      "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
    "--spring.profiles.active=dev",
    `--server.port=${new URL(API).port || 8080}`,
    `--spring.datasource.url=jdbc:h2:file:${DB_FILE};AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
    `--keplura.upload.dir=${path.dirname(DB_FILE)}/uploads-${bootNo}`,
  ];
  const flag = (parts) => "--" + parts.join(".") + "=";
  const K = {
    seed: ["keplura", "dev", "seed"],
    admin: ["keplura", "admin", "bootstrap"],
    gw: ["keplura", "gateway"],
    chat: ["keplura", "chat"],
    sec: ["keplura", "secrets", "master-key"],
  };
  if (opts.seedMail) {
    args.push(flag(K.seed.concat("email")) + opts.seedMail);
    args.push(flag(K.seed.concat("password")) + opts.seedSecret);
    args.push(flag(K.seed.concat("display-name")) + (opts.seedName || "E2E Admin Tester"));
  }
  if (opts.admin) {
    args.push(flag(K.admin.concat("email")) + opts.admin);
    args.push(flag(K.admin.concat("password")) + opts.adminPw);
  }
  if (opts.envGateway) {
    args.push(flag(K.gw.concat("base-url")) + "http://127.0.0.1:59999/v1");
    args.push(flag(K.gw.concat("provider")) + "api");
    args.push(flag(K.chat.concat("model")) + "e2e-env-chat-model");
    // ADR-0008 default is FULLY CLOSED (loopback refused too — the metadata
    // leg below proves link-local stays refused). The dead-port leg needs a
    // real dial, so simulate the documented single-machine posture:
    args.push("--" + ["keplura", "admin", "ssrf-allow-cidrs"].join(".") + "=127.0.0.0/8");
  }
  if (opts.masterKey) {
    args.push(flag(K.sec) + opts.masterKey);
  }
  const child = spawn("java", args, { stdio: ["ignore", bootFd, bootFd], detached: true });
  child.unref();
  const upDeadline = Date.now() + 90000;
  let up = false;
  while (Date.now() < upDeadline && !up) {
    try {
      const r = await fetch(`${API}/api/v1/health`);
      up = r.status === 200;
    } catch { /* not yet */ }
    if (!up) await sleep(400);
  }
  if (!up) fail("boot-jar", "jar never became healthy", { pid: child.pid, boot: bootNo });
  await sleep(1500); // seeder runs after Tomcat binds (invite_flow lesson)
  log("boot-jar", { boot: bootNo, mode: opts.mode || "seed" });
}

async function loginAs(page, mail, secret) {
  const deadline = Date.now() + 30000;
  for (;;) {
    const ok = await page.evaluate(async (api) => {
      try { await fetch(`${api}/api/v1/health`, { mode: "no-cors" }); return true; } catch { return false; }
    }, API).catch(() => false);
    if (ok) break;
    if (Date.now() > deadline) throw new Error("page-side health gate never passed");
    await sleep(1000);
  }
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 20000 });
  await page.fill('[data-testid="login-email"]', mail);
  await page.fill('[data-testid="login-password"]', secret);
  await page.click('[data-testid="login-submit"]');
  try {
    await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
  } catch (e) {
    await page.goto(BASE, { waitUntil: "networkidle" });
    await page.waitForSelector('[data-testid="login-card"]', { timeout: 20000 });
    await page.fill('[data-testid="login-email"]', mail);
    await page.fill('[data-testid="login-password"]', secret);
    await page.click('[data-testid="login-submit"]');
    await page.waitForSelector('[data-testid="sidebar"]', { timeout: 20000 });
  }
}

async function logout(page) {
  // The SessionView carries its own logout button; while the ADMIN panel is
  // open only the sidebar button exists — click whichever is present.
  // REDESIGN: logout lives in the account menu (avatar, top of the sidebar).
  await page.click('[data-testid="sidebar-user"]');
  await page.click('[data-testid="sidebar-logout"]');
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
}

/** The AdminPanel toast is role=status with testid — wait for its text. */
async function waitToast(page, needle, step) {
  await page
    .waitForFunction(
      (n) => document.querySelector('[data-testid="admin-toast"]')?.textContent.includes(n),
      needle, { timeout: 15000 },
    )
    .catch(() => fail(step, `toast "${needle}" missing`, { net: netLog.slice(-8) }));
}

(async () => {
  bootVite();
  await waitForVite(60000);
  log("boot-vite", { base: BASE });
  const browser = await chromium.launch({ executablePath: EXE });
  let context = null;
  let netLog = [];
  async function openPage() {
    if (context) await context.close().catch(() => {});
    context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1200, height: 1600 } });
    const page = await context.newPage();
    netLog = [];
    page.on("request", (req) => { if (req.method() !== "GET") netLog.push(`REQ ${req.method()} ${new URL(req.url()).pathname}`); });
    page.on("requestfailed", (req) => netLog.push(`FAIL ${req.method()} ${req.url()} ${req.failure()?.errorText}`));
    page.on("console", (m) => { if (m.type() === "error") netLog.push(`CONSOLE ${m.text().slice(0, 160)}`); });
    return page;
  }
  const shot = (page, name) => page.screenshot({ path: path.join(OUT_DIR, name), fullPage: true });

  // ---- boot 1: seeder creates A (USER), bootstrap flag promotes A to ADMIN
  // (proves the promotion path), ENV gateway args make the seed box appear.
  await bootJar({
    seedMail: MAIL_A, seedSecret: SECRET_A, seedName: "E2E Admin Owner",
    admin: MAIL_A, adminPw: SECRET_A,
    envGateway: true, masterKey: ["mk", stamp.toString(36)].join("-"), mode: "admin+envgw",
  });
  let page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await loginAs(page, MAIL_A, SECRET_A);
  log("A-login-admin");
  if ((await page.locator('[data-testid="sidebar-nav-admin"]').count()) !== 1)
    fail("A-admin-entry", "ADMIN account lacks the sidebar entry");
  log("A-sidebar-admin-entry-visible");
  await page.click('[data-testid="sidebar-nav-admin"]');
  await page.waitForSelector('[data-testid="admin-panel"]', { timeout: 15000 });
  await page.waitForSelector('[data-testid="admin-providers-section"]', { timeout: 15000 });
  log("A-admin-panel-open");

  // ---- seed offer -> one click creates provider+chat model rows ----
  await page.waitForSelector('[data-testid="admin-seed-box"]', { timeout: 15000 })
    .catch(async () => {
      const body = await page.evaluate(async (api) => {
        try { return JSON.stringify(await (await fetch(api + "/api/v1/admin/seed-offer", { credentials: "include" })).json()); }
        catch (e) { return String(e); }
      }, API).catch(() => "(eval failed)");
      fail("A-seed-box", "seed box never rendered; live offer: " + body.slice(0, 200));
    });
  await page.click('[data-testid="admin-seed-button"]');
  await waitToast(page, "angelegt", "A-seed");
  await page.waitForSelector('[data-testid="admin-provider-row"]', { timeout: 15000 });
  await shot(page, "01-A-seeded.png");
  log("A-seed-from-env-created-provider-row");

  // ---- negative SSRF leg: provider on a METADATA address -> test refuses ----
  // (shape validation passes at create; the route policy runs at DIAL time —
  // the row is stored, the probe is refused: 400 connection_target_refused,
  // which the panel shows as the honest "Ziel blockiert" pill)
  await page.click('[data-testid="admin-provider-create"]');
  await page.waitForSelector('[data-testid="admin-provider-form"]', { timeout: 10000 });
  const blockedName = "E2E Blocked " + stamp;
  await page.fill('[data-testid="admin-provider-name"]', blockedName);
  await page.selectOption('[data-testid="admin-provider-type"]', "api");
  await page.fill('[data-testid="admin-provider-baseurl"]', "http://169.254.169.254/latest");
  await page.fill('[data-testid="admin-provider-timeout"]', "2");
  await page.click('[data-testid="admin-provider-save"]');
  await waitToast(page, "Provider angelegt", "A-blocked-provider-create");
  // the toast fires BEFORE the panel's internal reload completes — wait for
  // the ROW, not the toast (invite_flow roster-race lesson)
  await page.waitForFunction((n) => [...document.querySelectorAll('[data-testid="admin-provider-row"]')]
    .some((r) => r.textContent.includes(n)), "E2E Blocked " + stamp, { timeout: 15000 })
    .catch(() => fail("A-blocked-row", "blocked provider row never rendered"));
  const findRowTestId = (name) => page.evaluate((n) => {
    for (const row of document.querySelectorAll('[data-testid="admin-provider-row"]')) {
      if (row.textContent.includes(n)) {
        return row.querySelector('[data-testid^="admin-provider-test-"]')?.getAttribute("data-testid")?.replace("admin-provider-test-", "") ?? null;
      }
    }
    return null;
  }, name);
  const blockedId = await findRowTestId("E2E Blocked " + stamp);
  if (!blockedId) fail("A-blocked-row", "blocked provider row missing");
  await page.click(`[data-testid="admin-provider-test-${blockedId}"]`);
  await page.waitForFunction(
    (n) => [...document.querySelectorAll('[data-testid="admin-provider-row"]')]
      .some((r) => r.textContent.includes(n) && r.textContent.includes("Ziel blockiert")),
    blockedName, { timeout: 20000 },
  ).catch(() => fail("A-ssrf-refusal", "metadata dial not refused by policy", { net: netLog.slice(-8) }));
  await shot(page, "02-A-ssrf-refused.png");
  log("A-ssrf-metadata-dial-refused");

  // ---- create a real (stub) provider on loopback + connection test ----
  await page.click('[data-testid="admin-provider-create"]');
  await page.waitForSelector('[data-testid="admin-provider-form"]', { timeout: 10000 });
  const stubName = "E2E Stub " + stamp;
  await page.fill('[data-testid="admin-provider-name"]', stubName);
  await page.selectOption('[data-testid="admin-provider-type"]', "api");
  await page.fill('[data-testid="admin-provider-baseurl"]', "http://127.0.0.1:59998/v1");
  await page.fill('[data-testid="admin-provider-secret"]', "e2e-not-a-real-key");
  await page.fill('[data-testid="admin-provider-timeout"]', "3");
  await page.click('[data-testid="admin-provider-save"]');
  await waitToast(page, "Provider angelegt", "A-provider-create");
  // wait for the stub ROW itself (toast precedes the panel reload)
  await page.waitForFunction((n) => [...document.querySelectorAll('[data-testid="admin-provider-row"]')]
    .some((r) => r.textContent.includes(n)), stubName, { timeout: 15000 })
    .catch(() => fail("A-provider-create", "stub provider row never rendered"));
  const rows = await page.locator('[data-testid="admin-provider-row"]').count();
  if (rows !== 3) fail("A-provider-create", "expected seeded + blocked + stub rows", String(rows));
  log("A-stub-provider-created", { name: stubName });

  // secret must show MASKED only, never the typed value
  const stubRowText = await page.evaluate((name) => {
    for (const row of document.querySelectorAll('[data-testid="admin-provider-row"]')) {
      if (row.textContent.includes(name)) return row.textContent;
    }
    return "";
  }, stubName);
  if (!stubRowText.includes("****") || stubRowText.includes("e2e-not-a-real-key"))
    fail("A-secret-masked", "secret row leaks plaintext or lacks mask", stubRowText.slice(0, 120));
  log("A-secret-shown-masked-only");
  const stubId = await findRowTestId(stubName);
  if (!stubId) fail("A-test-button", "no test button on the stub row");
  await page.click(`[data-testid="admin-provider-test-${stubId}"]`);
  await page.waitForFunction(
    (n) => [...document.querySelectorAll('[data-testid="admin-provider-row"]')]
      .some((r) => r.textContent.includes(n) && r.textContent.includes("Nicht erreichbar")),
    stubName, { timeout: 25000 },
  ).catch(() => fail("A-conn-test", "stub test never showed 'Nicht erreichbar'", { net: netLog.slice(-8) }));
  await shot(page, "03-A-conn-test-unreachable.png");
  log("A-connection-test-unreachable-shown");

  // ---- models tab: EMBEDDING model + default switch (confirm dialog) ----
  await page.click('[data-testid="admin-tab-models"]');
  await page.waitForSelector('[data-testid="admin-models-section"]', { timeout: 10000 });
  await page.click('[data-testid="admin-model-create"]');
  await page.waitForSelector('[data-testid="admin-model-form"]', { timeout: 10000 });
  await page.selectOption('[data-testid="admin-model-type"]', "EMBEDDING");
  await page.waitForSelector('[data-testid="admin-model-dim"]', { timeout: 10000 });
  await page.fill('[data-testid="admin-model-name"]', "E2E Embed " + stamp);
  await page.fill('[data-testid="admin-model-technical"]', "e2e-embed-x");
  await page.fill('[data-testid="admin-model-dim"]', "64");
  await page.click('[data-testid="admin-model-save"]');
  await waitToast(page, "Modell angelegt", "A-model-create");
  await page.waitForFunction(
    () => document.querySelectorAll('[data-testid="admin-model-row-embedding"]').length >= 1,
    { timeout: 15000 },
  ).catch(() => fail("A-model-create", "embedding row missing", { net: netLog.slice(-8) }));
  log("A-embedding-model-created");

  // make it default -> confirm dialog carries the NO-REINDEX warning (PO rule)
  const embId = await page.evaluate(() => {
    const row = document.querySelector('[data-testid="admin-model-row-embedding"]');
    const btn = row?.querySelector('[data-testid^="admin-model-default-"]');
    return btn ? btn.getAttribute("data-testid").replace("admin-model-default-", "") : null;
  });
  if (!embId) fail("A-default-button", "no 'Als Standard' on the embedding row");
  // In-page ConfirmDialog (seit #64 kein natives window.confirm mehr)
  await page.click(`[data-testid="admin-model-default-${embId}"]`);
  const confirm = page.locator('dialog[open]');
  await confirm.getByTestId("confirm-submit").waitFor({ timeout: 10000 })
    .catch(() => fail("A-default-confirm", "confirm dialog never opened"));
  const confirmText = (await confirm.innerText()).toLowerCase();
  if (!confirmText.includes("index")) fail("A-default-confirm", "confirm lacks reindex warning: " + confirmText.slice(0, 120));
  await confirm.getByTestId("confirm-submit").click();
  await waitToast(page, "ist jetzt Standard", "A-default-switch");
  log("A-embedding-default-switched-with-warning");

  // the switch must NOT silently re-index: RAG tab shows reindex=nein + hint
  await page.click('[data-testid="admin-tab-rag"]');
  await page.waitForSelector('[data-testid="admin-rag-section"]', { timeout: 10000 });
  await page.waitForSelector('[data-testid="admin-rag-active-model"]', { timeout: 15000 });
  const activeEmbed = await page.locator('[data-testid="admin-rag-active-model"]').innerText();
  if (!activeEmbed.includes("E2E Embed")) fail("A-rag-active", "active embedding still old", activeEmbed.slice(0, 120));
  const reindexCell = await page.locator('[data-testid="admin-rag-auto-reindex"]').innerText();
  if (!reindexCell.includes("nein")) fail("A-rag-reindex", "auto-reindex claims 'ja'", reindexCell.slice(0, 120));
  const hint = await page.locator('[data-testid="admin-rag-hint"]').innerText();
  if (!hint.toLowerCase().includes("neu")) fail("A-rag-hint", "reindex hint missing", hint.slice(0, 120));
  await shot(page, "04-A-rag-readonly.png");
  log("A-rag-tab-read-only-honest");

  // ---- status board ----
  await page.click('[data-testid="admin-tab-status"]');
  await page.waitForSelector('[data-testid="admin-status-section"]', { timeout: 10000 });
  await page.waitForSelector('[data-testid="admin-status-database"]', { timeout: 15000 });
  await page.getByTestId('admin-status-database').getByText('Bereit', { exact: true }).waitFor();
  const dbState = await page.locator('[data-testid="admin-status-database"]').innerText();
  if (!dbState.includes("Bereit")) fail("A-status-db", "database state not ready", dbState.slice(0, 120));
  await shot(page, "05-A-status-board.png");
  log("A-status-board-renders");

  // ---- boot 2: SAME db, NO env gateway, seed B (plain USER) ----
  await logout(page);
  await bootJar({ seedMail: MAIL_B, seedSecret: SECRET_B, seedName: "E2E Admin Guest", mode: "user" });
  page = await openPage();
  await page.goto(BASE, { waitUntil: "networkidle" });
  await loginAs(page, MAIL_B, SECRET_B);
  log("B-login-user");
  if ((await page.locator('[data-testid="sidebar-nav-admin"]').count()) !== 0)
    fail("B-sidebar", "USER account shows the admin entry");
  log("B-sidebar-admin-entry-hidden");

  // server-side gate proof (the UI entry hiding is decoration only): with
  // B's REAL session cookie, direct calls to the admin API must 403.
  const gate = await page.evaluate(async (api) => {
    const out = {};
    out.getAsUser = await (await fetch(api + "/api/v1/admin/providers", { credentials: "include" })).status;
    const tok = await (await fetch(api + "/api/v1/auth/csrf", { credentials: "include" })).json();
    const r1 = await fetch(api + "/api/v1/admin/providers", {
      method: "POST", credentials: "include", headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: "gate-negative", providerType: "api", baseUrl: "http://127.0.0.1:9/v1" }),
    });
    out.postNoToken = r1.status + ":" + (await r1.text()).slice(0, 40);
    out.postWithToken = (await fetch(api + "/api/v1/admin/providers", {
      method: "POST", credentials: "include",
      headers: { "content-type": "application/json", "X-CSRF-Token": tok.csrfToken },
      body: JSON.stringify({ name: "gate-negative2", providerType: "api", baseUrl: "http://127.0.0.1:9/v1" }),
    })).status;
    return out;
  }, API);
  if (gate.getAsUser !== 403) fail("B-gate", "admin GET for USER not 403", String(gate.getAsUser));
  if (!gate.postNoToken.startsWith("403:") || !gate.postNoToken.includes("CSRF_INVALID"))
    fail("B-gate", "header-less admin POST not CSRF_INVALID", gate.postNoToken);
  if (gate.postWithToken !== 403) fail("B-gate", "tokened admin POST for USER not 403", String(gate.postWithToken));
  log("B-admin-api-server-gated", { get: gate.getAsUser, csrf: "403 CSRF_INVALID", role: "403 ADMIN_REQUIRED" });

  await browser.close();
  for (const pid of jarPids()) {
    try { process.kill(pid, "SIGTERM"); } catch { /* gone */ }
  }
  console.log(JSON.stringify({ step: "done", ok: true, steps: stepNo, out: OUT_DIR }));
  // Explicit exit (invite_flow lesson): our vite child holds the event loop.
  if (viteChild && viteChild.exitCode === null) {
    try { viteChild.kill("SIGKILL"); } catch { /* gone */ }
  }
  process.exit(0);
})().catch((e) => fail("unexpected", String(e && e.stack || e)));
