// M3e evidence run: proves the knowledge UI flow against a REAL running
// server jar + REAL vite dev build in headless Chromium (the documented QA
// browser flow, per docs/development.md). Steps are logged as JSON lines;
// screenshots land in OUT_DIR. Exit 0 only when every step passed.
//
// Flow: login -> (org exists? else create) -> create space -> create kb ->
// upload .txt -> status badge INDEXED -> search -> cited hits -> reload
// persistence -> [stale-session leg, requires E2E_JAR] script kills the
// running jar, restarts it on a FRESH db with a SECOND seed account -> the
// panel's next call 401s (session gone) -> re-login as account B -> assert
// ZERO stale knowledge-* rows of A -> logout -> re-login once more -> logout.
//
// The stale-session leg is the regression cover for review Finding 1: the
// 401/expired branches must drop the session AND every tenant ref (orgs /
// spaces / kbs / documents / search) — a ref that outlives its session was
// visible to the NEXT account on the same app instance.
//
// .cjs on purpose: desktop/package.json is "type": "module", and CommonJS
// keeps working with playwright-core installed via NODE_PATH (ESM resolution
// ignores NODE_PATH) — see e2e/README for the scratch-dir prereq.
const { chromium } = require("playwright-core");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const BASE = process.env.E2E_BASE || "http://localhost:1420";
const API = process.env.E2E_API || "http://127.0.0.1:8080";
const EXE =
  process.env.E2E_CHROME ||
  "/cache/home/.cache/ms-playwright/chromium_headless_shell-1181/chrome-linux/headless_shell";
const OUT_DIR = process.env.E2E_OUT || "/tmp/m3e-evidence";
// Assembled at runtime so no credential literal ever sits in a committed file.
const EMAIL = process.env.E2E_EMAIL;
const PASSWORD = process.argv[2];
if (!EMAIL || !PASSWORD) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "pass email+password via env/argv" }));
  process.exit(2);
}
// Stale-session leg is optional so the script still runs against an externally
// managed jar (the plain M3e evidence mode).
const JAR = process.env.E2E_JAR || "";
const STALE = JAR !== "";
const EMAIL_B = process.env.E2E_EMAIL_B || "e2e-b@example.com";
const PASSWORD_B = process.env.E2E_PASSWORD_B || PASSWORD;
const ORG_B_NAME = `E2E Org B ${Date.now()}`;
const DOC_NAME = "m3e-doc.txt";

const stamp = Date.now();
const SPACE_NAME = `E2E Wissen ${stamp}`;
const KB_NAME = `KB ${stamp}`;
const ORG_NAME = `E2E Org ${stamp}`;
const DOC_TEXT =
  "Die Turbine dreht mit 3000 U/min und liefert 4.2 MW. " +
  "Das Kuehlsystem nutzt Wasser aus dem Kreislauf. " +
  "Taeglich wird die Oeldruckanzeige geprueft.";

fs.mkdirSync(OUT_DIR, { recursive: true });
fs.writeFileSync(`/tmp/${DOC_NAME}`, DOC_TEXT);

let stepNo = 0;
function log(step, extra = {}) {
  console.log(JSON.stringify({ step, no: ++stepNo, ok: true, ...extra }));
}
function fail(step, error, detail) {
  console.log(JSON.stringify({ step, no: ++stepNo, ok: false, error, ...(detail ? { detail } : {}) }));
  process.exit(1);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Find live `java -jar …` PIDs WITHOUT shell pattern matching: scanning
 * /proc self-excludes this process tree (a self-matching pkill/kill -f here
 * has killed agent shells before). Matching is on the argv vector — argv[0]
 * must BE a java binary and some argument must be a .jar path — so a shell
 * whose -c string merely mentions "java -jar …" is never touched (a broad
 * cmdline-substring match has SIGTERMed the launcher's own bash before).
 */
function jarPids() {
  const pids = [];
  for (const entry of fs.readdirSync("/proc")) {
    if (!/^\d+$/.test(entry)) continue;
    let raw;
    try {
      raw = fs.readFileSync(`/proc/${entry}/cmdline`, "latin1");
    } catch {
      continue; // vanished or unreadable
    }
    const argv = raw.split("\0").filter(Boolean);
    const exe = argv[0] || "";
    const isJava = exe === "java" || exe.endsWith("/java");
    const hasJar = argv.some((a, i) => i > 0 && a.endsWith(".jar"));
    if (isJava && hasJar) pids.push(Number(entry));
  }
  return pids;
}
async function waitForHealth(upt, ms) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${API}/api/v1/health`);
      if (r.status === (upt ? 200 : 503)) return true;
      // 503 = graceful shutdown in progress on old Tomcat = socket gone
      if (!upt && (r.status === 503 || r.status >= 400)) return true;
    } catch {
      if (!upt) return true;
    }
    await sleep(400);
  }
  return false;
}

async function loginAs(page, email, password) {
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
  await page.fill('[data-testid="login-email"]', email);
  await page.fill('[data-testid="login-password"]', password);
  await page.click('[data-testid="login-submit"]');
  // REDESIGN (14.11.): the shell opens on Chat; areas are reached via the sidebar.
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
}

/** REDESIGN: switch area through the sidebar (chat | wissen | team | admin). */
async function go(page, view) {
  await page.click(`[data-testid="sidebar-nav-${view}"]`);
  if (view === "wissen") await page.waitForSelector('[data-testid="session-card"]', { timeout: 15000 });
  if (view === "team") await page.waitForSelector('[data-testid="team-view"]', { timeout: 15000 });
}

/** REDESIGN: logout lives in the account menu (avatar, top of the sidebar). */
async function logout(page) {
  await page.click('[data-testid="sidebar-user"]');
  await page.click('[data-testid="sidebar-logout"]');
}

/**
 * Login -> first org (create if none) -> knowledge panel mounted. Shared by
 * account A (full flow) and account B (stale-session assertion).
 */
async function bootSession(page, email, password, orgName) {
  await page.goto(BASE, { waitUntil: "networkidle" });
  await loginAs(page, email, password);
  await go(page, "team");
  const orgCount = await page.locator('[data-testid="org-item"]').count();
  if (orgCount === 0) {
    // UI-POLISH-1: the create form lives behind the "+" action in a dialog.
    await page.click('[data-testid="org-create-new"]');
    await page.fill('[data-testid="org-create-name"]', orgName);
    await page.click('[data-testid="org-create-submit"]');
    await page.waitForSelector('[data-testid="org-item"]', { timeout: 10000 });
    await go(page, "wissen");
    return "created";
  }
  await go(page, "wissen");
  return `present:${orgCount}`;
}
(async () => {
  const browser = await chromium.launch({ executablePath: EXE });
  const page = await browser.newPage({ viewport: { width: 1100, height: 1400 } });
  const shot = (name) => page.screenshot({ path: path.join(OUT_DIR, name), fullPage: true });

  // --- login ---
  const orgState = await bootSession(page, EMAIL, PASSWORD, ORG_NAME);
  log("login", { note: `session card visible (org ${orgState})` });
  await shot("01-session.png");

  // --- knowledge section mounted? ---
  await page.waitForSelector('[data-testid="knowledge-section"]', { timeout: 10000 });
  const orgName = await page.locator('[data-testid="knowledge-org"]').innerText();
  log("knowledge-mounted", { org: orgName });

  // --- create space (dialog behind the "+" action, UI-POLISH-1) ---
  await page.click('[data-testid="knowledge-space-new"]');
  await page.fill('[data-testid="knowledge-space-name"]', SPACE_NAME);
  await page.click('[data-testid="knowledge-space-submit"]');
  await page.waitForFunction(
    (name) => document.querySelector('[data-testid="knowledge-space"]')?.textContent.trim() === name,
    SPACE_NAME,
    { timeout: 10000 },
  );
  log("create-space", { name: SPACE_NAME, note: "auto-selected after create" });

  // --- create kb (dialog behind the "Neu" action, UI-POLISH-1) ---
  await page.click('[data-testid="knowledge-kb-new"]');
  await page.fill('[data-testid="knowledge-kb-name"]', KB_NAME);
  await page.click('[data-testid="knowledge-kb-submit"]');
  await page.waitForSelector('[data-testid="knowledge-kb-active"]', { timeout: 10000 });
  const kbItems = await page.locator('[data-testid="knowledge-kb-item"]').count();
  log("create-kb", { name: KB_NAME, kbItems });
  await shot("02-kb-created.png");

  // --- upload .txt ---
  await page.setInputFiles('[data-testid="knowledge-upload-input"]', `/tmp/${DOC_NAME}`);
  await page.waitForSelector('[data-testid="knowledge-doc-item"]', { timeout: 20000 });
  const docName = await page.locator('[data-testid="knowledge-doc-name"]').innerText();
  const docStatus = await page.locator('[data-testid="knowledge-doc-status"]').first().getAttribute("data-index-status");
  const toast = await page.locator('[data-testid="knowledge-toast"]').innerText();
  log("upload", { docName, docStatus, toast });
  if (docStatus !== "INDEXED") fail("upload-status", "expected INDEXED", docStatus);
  await shot("03-doc-indexed.png");

  // --- search with citations ---
  await page.fill('[data-testid="knowledge-search-query"]', "turbine drehzahl");
  await page.click('[data-testid="knowledge-search-submit"]');
  await page.waitForSelector('[data-testid="knowledge-result-item"]', { timeout: 15000 });
  const hits = await page.locator('[data-testid="knowledge-result-item"]').count();
  const firstSource = await page.locator('[data-testid="knowledge-result-source"]').first().innerText();
  const firstSnippet = await page.locator('[data-testid="knowledge-result-snippet"]').first().innerText();
  const firstScore = await page.locator('[data-testid="knowledge-result-score"]').first().innerText();
  const summary = await page.locator('[data-testid="knowledge-search-summary"]').innerText();
  log("search", { hits, firstSource, firstScore, summary, snippetHead: firstSnippet.slice(0, 60) });
  if (!firstSource.includes(DOC_NAME) || !firstSource.includes("Abschnitt")) {
    fail("search-citation", "hit lacks filename/chunk citation", firstSource);
  }
  await shot("04-search-hits.png");

  // =====================================================================
  // --- FINDING 1 regression (UI-POLISH-1 fix round): a create-dialog
  // error must render INSIDE the modal, above the backdrop — never as a
  // page-level toast the backdrop swallows. The org/space/KB dialogs all
  // share the AppDialog error line, so one real round-trip (dialog open +
  // submit -> server 409 -> visible error) plus a stubbed-400 org leg
  // (asserts the page-level toasts stay free of dialog error text) prove
  // the pattern for all three.
  // =====================================================================
  async function dialogErrorGeom() {
    return page.evaluate(() => {
      const el = document.querySelector('dialog[open] [data-testid="dialog-error"]');
      if (!el) return null;
      const dlg = el.closest("dialog");
      const r = el.getBoundingClientRect();
      return {
        text: el.textContent.trim(),
        inOpenDialog: !!(dlg && dlg.open),
        w: r.width, h: r.height,
      };
    });
  }

  // (a) real round-trip: KB dialog with the already-taken name -> 409
  await page.click('[data-testid="knowledge-kb-new"]');
  await page.fill('[data-testid="knowledge-kb-name"]', KB_NAME);
  await page.click('[data-testid="knowledge-kb-submit"]');
  const kbErrSeen = await page
    .waitForFunction(
      () => {
        const el = document.querySelector('dialog[open] [data-testid="dialog-error"]');
        if (!el) return false;
        const r = el.getBoundingClientRect();
        return r.width > 10 && r.height > 6 ? el.textContent.trim() : false;
      },
      { timeout: 10000 },
    )
    .then((h) => h.jsonValue())
    .catch(() => null);
  const kbErr = await dialogErrorGeom();
  if (!kbErrSeen || !kbErr || !kbErr.inOpenDialog) {
    fail("dialog-error-inside", "KB create error not visible inside the open dialog", { kbErrSeen, kbErr });
  }
  if (!String(kbErrSeen).includes("Wissensbasis mit diesem Namen")) {
    fail("dialog-error-text", "unexpected KB error text", kbErrSeen);
  }
  const kbToastText = (await page.locator('[data-testid="knowledge-toast"]').textContent().catch(() => "") || "").trim();
  if (kbToastText.includes("KB-Namen")) {
    fail("dialog-error-toast-leak", "KB dialog error leaked into the page-level toast", kbToastText);
  }
  await shot("04b-kb-dialog-error-inside.png");
  // scoped to the OPEN dialog — every AppDialog keeps its close button in
  // the DOM; the hidden ones would time out actionability
  await page.click('dialog[open] [data-testid="dialog-close"]');
  await page
    .waitForFunction(() => !document.querySelector("dialog[open]"), { timeout: 5000 })
    .catch(() => fail("dialog-close", "KB dialog did not close"));

  // (b) org dialog, deterministic 400 via route stub (the server never sees
  // it): while the response is in flight the page-level toasts carry the
  // upload-OK message — asserting the INVALID text appears ONLY inside the
  // dialog proves no dialog error can render under the backdrop.
  const INVALID = "Name ungültig. Bitte 1–200 Zeichen.";
  await page.route("**/api/v1/orgs", (route) => {
    if (route.request().method() === "POST") {
      route.fulfill({ status: 400, contentType: "application/json", body: '{"error":"name is invalid"}' });
    } else {
      route.continue();
    }
  });
  await go(page, "team");
  await page.click('[data-testid="org-create-new"]');
  await page.fill('[data-testid="org-create-name"]', "x".repeat(210));
  await page.click('[data-testid="org-create-submit"]');
  const orgErrSeen = await page
    .waitForFunction(
      () => {
        const el = document.querySelector('dialog[open] [data-testid="dialog-error"]');
        return el ? el.textContent.trim() : false;
      },
      { timeout: 10000 },
    )
    .then((h) => h.jsonValue())
    .catch(() => null);
  const orgErr = await dialogErrorGeom();
  if (!orgErrSeen || !orgErr || !orgErr.inOpenDialog || !orgErr.text.includes("Name ungültig")) {
    await page.unroute("**/api/v1/orgs").catch(() => {});
    fail("dialog-error-inside", "org create error not visible inside the open dialog", { orgErrSeen, orgErr });
  }
  const orgToast = (await page.locator('[data-testid="org-create-toast"]').textContent().catch(() => "") || "").trim();
  if (orgToast.includes(INVALID)) {
    await page.unroute("**/api/v1/orgs").catch(() => {});
    fail("dialog-error-toast-leak", "org dialog error rendered as page-level toast", orgToast);
  }
  const kbToast2 = (await page.locator('[data-testid="knowledge-toast"]').textContent().catch(() => "") || "").trim();
  if (kbToast2.includes(INVALID)) {
    await page.unroute("**/api/v1/orgs").catch(() => {});
    fail("dialog-error-toast-leak", "org dialog error rendered into the knowledge toast", kbToast2);
  }
  // Unroute NOW, before any further step touches the org API — a lingering
  // POST stub would swallow the stale-leg's real B-org create later on.
  await page.unroute("**/api/v1/orgs");
  log("dialog-error-inside", {
    note: "FINDING 1 regression: KB 409 + org stubbed-400 errors render inside the open dialog (top layer, above backdrop); no toast leak",
    kbErr: kbErr.text, orgErr: orgErr.text,
  });
  await shot("04c-org-dialog-error-inside.png");
  await page.click('dialog[open] [data-testid="dialog-close"]');

  // --- persistence check: reload page (SPA re-bootstrap) — kb+doc still listed ---
  await page.reload({ waitUntil: "networkidle" });
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
  await go(page, "wissen");
  await page.waitForSelector('[data-testid="knowledge-section"]', { timeout: 15000 });
  await page.waitForSelector('[data-testid="knowledge-doc-item"]', { timeout: 15000 }).catch(() => {});
  const docAfterReload = await page.locator('[data-testid="knowledge-doc-item"]').count();
  log("reload-persistence", { docAfterReload });
  if (docAfterReload < 1) fail("reload-persistence", "document list empty after reload");
  await shot("05-after-reload.png");

  // =====================================================================
  // --- stale-session leg (Finding 1 regression) ---
  //
  // Kill the jar and restart it on a FRESH db seeded ONLY with account B:
  // A's cookie now names a session that does not exist, so A's next
  // knowledge call 401s. Before the fix, A's rows (org + KB names, doc
  // names, snippets) survived in the pinia store; account B would see them
  // at least until a fetch overwrote them. The panel snapshot right after
  // the 401 (before any re-login) proves the store-level teardown.
  // =====================================================================
  if (STALE) {
    // remember what A had visible so we can assert none of it shows for B
    const aKbTexts = await page.locator('[data-testid="knowledge-kb-item"]').allInnerTexts();
    const aDocTexts = await page.locator('[data-testid="knowledge-doc-name"]').allInnerTexts();
    if (aKbTexts.length === 0 || aDocTexts.length === 0) {
      fail("stale-baseline", "account A rows missing before kill", { aKbTexts, aDocTexts });
    }

    for (const pid of jarPids()) {
      try { process.kill(pid, "SIGTERM"); } catch { /* already gone */ }
    }
    if (!(await waitForHealth(false, 20000))) fail("stale-kill", "server still answering after SIGTERM");
    log("stale-kill", { note: "jar terminated, old session gone" });

    const dbFile = `/tmp/e2e-stale-${stamp}/db`;
    fs.mkdirSync(path.dirname(dbFile), { recursive: true });
    // Capture the restarted server's own log (incl. the stale-cookie 401
    // request line) next to the other evidence — that 401 is the point.
    const bootFd = fs.openSync(path.join(OUT_DIR, "restarted_server.log"), "w");
    const child = spawn(
      "java",
      [
        "-jar", JAR,
      "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
        `--spring.datasource.url=jdbc:h2:file:${dbFile};AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
        "--keplura.dev.seed.email=" + EMAIL_B,
        "--keplura.dev.seed.password=" + PASSWORD_B,
        "--keplura.dev.seed.display-name=E2E User B",
      ],
      { stdio: ["ignore", bootFd, bootFd], detached: true },
    );
    child.unref();
    if (!(await waitForHealth(true, 90000))) fail("stale-restart", "restarted jar never came up", { pid: child.pid });
    log("stale-restart", { note: "fresh db seeded with second account only" });

    // Panel's next call must 401 -> session dropped -> login view returns.
    await page.click('[data-testid="knowledge-reload"]');
    await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
    log("stale-401-drops-session", { note: "expired branch rendered LoginView" });

    // NO page reload from here: the pinia store (and any ref the expired
    // branch failed to clear) survives view unmount/mount — this is what
    // makes the assertion below a real regression test, not a tautology.
    // Account B signs in on the SAME app instance / SAME store. The assert
    // fires BEFORE B creates its org: that is the leak window — the panel
    // renders kbs/documents while the store is still unscheduled, so any ref
    // the expired branch failed to clear is visible here as A's rows.
    await loginAs(page, EMAIL_B, PASSWORD_B);
    await page.waitForSelector('[data-testid="knowledge-section"]', { timeout: 10000 });
    await sleep(700); // let any restore/probe settle before reading the DOM
    const kbRows = await page.locator('[data-testid="knowledge-kb-item"]').allInnerTexts();
    const docRows = await page.locator('[data-testid="knowledge-doc-name"]').allInnerTexts();
    const leaked = [...kbRows, ...docRows].filter(
      (t) => aKbTexts.some((a) => t.includes(a.trim())) || aDocTexts.some((a) => t.includes(a.trim())),
    );
    if (kbRows.length !== 0 || docRows.length !== 0 || leaked.length !== 0) {
      fail("stale-tenant-leak", "account A rows visible to account B", { kbRows, docRows, leaked });
    }
    await go(page, "team");
    await page.click('[data-testid="org-create-new"]');
    await page.fill('[data-testid="org-create-name"]', ORG_B_NAME);
    await page.click('[data-testid="org-create-submit"]');
    await page.waitForSelector('[data-testid="org-item"]', { timeout: 10000 });
    await go(page, "wissen");
    await page.waitForFunction(
      (name) => document.querySelector('[data-testid="knowledge-org"]')?.textContent.trim() === name,
      ORG_B_NAME,
      { timeout: 15000 },
    );
    await sleep(700);
    const kbRowsAfter = await page.locator('[data-testid="knowledge-kb-item"]').count();
    if (kbRowsAfter !== 0) fail("stale-tenant-leak-late", "A rows appeared after B scoped in", kbRowsAfter);
    log("stale-relogin-clean", { note: "zero stale knowledge rows after expired->re-login" });
    await shot("06-stale-relogin-clean.png");
  }

  // --- logout clears knowledge state (no stale tenant rows) ---
  await logout(page);
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 10000 });
  log("logout");

  // --- one login/logout cycle proves the teardown is repeatable ---
  await bootSession(page, STALE ? EMAIL_B : EMAIL, STALE ? PASSWORD_B : PASSWORD, ORG_B_NAME);
  await logout(page);
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 10000 });
  log("logout-cycle-2");

  await browser.close();
  log("done", { shots: fs.readdirSync(OUT_DIR) });
})().catch((e) => fail("crash", String(e && e.message ? e.message : e)));
