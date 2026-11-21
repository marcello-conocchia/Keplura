// MVP-5 parser-flow E2E: drives the DOCUMENTED browser flow (vite dev server
// on :1420 — the one CORS origin the server allows for credentialed session
// calls — + headless chromium, same harness family as knowledge_flow_e2e.cjs)
// against a REAL Spring Boot jar with the REAL Tika pipeline, proving the PO
// loop in the app UI:
//
//   A. upload real .docx      -> badge INDEXED, search phrase -> cited hit
//   B. upload real .pdf       -> badge INDEXED, search phrase -> cited hit
//   C. upload scan-like .pdf  -> badge NO_TEXT_LAYER, absent from search
//   D. restart jar at pdf-max-pages=30, upload a 60-page pdf -> the UI shows
//      the honest rejection toast AND the doc is NOT in the list (the upload
//      transaction rolled back); docs from A-C survive the restart with their
//      PERSISTED statuses (proof the marker is real db state).
//
// Fresh state: own temp H2 db + upload dir, own dev-seeded account (email via
// env E2E_EMAIL, password via argv[2] — same credential convention as the
// other e2e scripts; nothing literal in this file).
//
// Run (from desktop/e2e):
//   E2E_JAR=../../server/target/keplura-server-0.1.0.jar \
//   E2E_EMAIL=<seed-email> NODE_PATH=<scratch>/node_modules \
//     node mvp5_flow_e2e.cjs <seed-password>
// (port 1420 must be free — the script starts its OWN vite + jar)
//
// JSON-line output per step, screenshots to E2E_OUT; exit 0 only if all pass.

const { chromium } = require("playwright-core");
const { spawn, execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const BASE = process.env.E2E_BASE || "http://localhost:1420";
const API_PORT = process.env.E2E_API_PORT || "8099";
const API = `http://127.0.0.1:${API_PORT}`;
const EXE =
  process.env.E2E_CHROME ||
  "/cache/home/.cache/ms-playwright/chromium_headless_shell-1181/chrome-linux/headless_shell";
const OUT_DIR = process.env.E2E_OUT || "/tmp/mvp5-evidence";
const JAR = process.env.E2E_JAR || "";
const DB_DIR = process.env.E2E_DB_DIR || "/tmp/mvp5-db";
const EMAIL = process.env.E2E_EMAIL;
const PASSWORD = process.argv[2];
if (!EMAIL || !PASSWORD) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "email via E2E_EMAIL env, password as argv[2]" }));
  process.exit(2);
}
if (!JAR) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "E2E_JAR must point at the built server jar" }));
  process.exit(2);
}

const stamp = Date.now();
const ORG_NAME = `MVP5 Org ${stamp}`;
const SPACE_NAME = `MVP5 Wissen ${stamp}`;
const KB_NAME = `MVP5 KB ${stamp}`;

fs.mkdirSync(OUT_DIR, { recursive: true });

let stepNo = 0;
function log(step, extra = {}) {
  console.log(JSON.stringify({ step, no: ++stepNo, ok: true, ...extra }));
}
function fail(step, error, detail) {
  console.log(JSON.stringify({ step, no: ++stepNo, ok: false, error, ...(detail !== undefined ? { detail } : {}) }));
  process.exit(1);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function run(cmd, args, opts = {}) {
  const child = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"], ...opts });
  let out = "";
  child.stdout.on("data", (d) => { out += d; });
  child.stderr.on("data", (d) => { out += d; });
  child.on("exit", (code) => { out += `\n[exit ${code}]`; });
  allChildren.push(child); // process.exit() below must not orphan jar/vite
  return { child, get output() { return out } };
}
const allChildren = [];
function killAll() {
  for (const c of allChildren) {
    try { c.kill("SIGKILL"); } catch { /* already gone */ }
  }
}
process.on("exit", killAll);

async function waitForHealth(upt, ms) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${API}/api/v1/health`);
      if (upt && r.status === 200) return true;
      if (!upt && (r.status === 503 || r.status >= 400)) return true;
    } catch {
      if (!upt) return true;
    }
    await sleep(400);
  }
  return false;
}

async function loginAs(page, email, password) {
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 25000 });
  await page.fill('[data-testid="login-email"]', email);
  await page.fill('[data-testid="login-password"]', password);
  await page.click('[data-testid="login-submit"]');
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 25000 });
}

/** Upload through the REAL panel; returns doc rows + the latest toast text. */
async function uploadAndRows(page, file) {
  await page.setInputFiles('[data-testid="knowledge-upload-input"]', file);
  // REDESIGN: wait for the toast of THIS file (an earlier toast may still be
  // shown), then for the list refresh to settle before reading rows.
  const base = require("node:path").basename(file);
  await page.waitForFunction(
    (name) => (document.querySelector('[data-testid="knowledge-toast"]')?.textContent || "").includes(name),
    base,
    { timeout: 40000 },
  );
  await page.waitForFunction(() => !document.querySelector('[data-testid="knowledge-upload-progress"]'), undefined, { timeout: 40000 });
  await sleep(600);
  const rows = await page.$$eval('[data-testid="knowledge-doc-item"]', (els) =>
    els.map((el) => ({
      name: el.querySelector('[data-testid="knowledge-doc-name"]')?.textContent?.trim() || "",
      status: el.querySelector('[data-testid="knowledge-doc-status"]')?.getAttribute("data-index-status") || "",
    })),
  );
  const toast = (await page.locator('[data-testid="knowledge-toast"]').first().textContent().catch(() => "") || "").trim();
  return { rows, toast };
}

async function searchFor(page, q) {
  // Wait for THIS search's HTTP response: the result list survives from the
  // previous query in the DOM, so a bare "some result exists" check would
  // read stale hits (found by the leg B run against leg A's list).
  const resp = page.waitForResponse(
    (r) => r.url().endsWith("/search") && r.request().method() === "POST",
    { timeout: 25000 },
  );
  await page.fill('[data-testid="knowledge-search-query"]', q);
  await page.click('[data-testid="knowledge-search-submit"]');
  await resp;
  await page.waitForFunction(
    () => !!document.querySelector('[data-testid="knowledge-result-item"], [data-testid="knowledge-result-empty"]'),
    { timeout: 25000 },
  );
  await sleep(250); // let vue re-render the fresh list, not the old one
  const hits = await page.locator('[data-testid="knowledge-result-item"]').count();
  const sources = await page.locator('[data-testid="knowledge-result-source"]').allInnerTexts();
  const snippets = await page.locator('[data-testid="knowledge-result-snippet"]').allInnerTexts();
  return { hits, sources, snippets };
}

function jarArgs(extra) {
  return [
    "-jar", JAR,
      "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
    `--server.port=${API_PORT}`,
    `--spring.datasource.url=jdbc:h2:file:${DB_DIR}/mvp5;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
    `--spring.flyway.url=jdbc:h2:file:${DB_DIR}/mvp5;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
    `--keplura.upload.dir=${DB_DIR}/objects`,
    ...extra,
  ];
}

(async () => {
  // --- fixtures: real pdf/docx bytes generated locally ---
  try {
    execFileSync("python3", [path.join(__dirname, "mvp5_fixtures.py"), "--dir", "/tmp", "--big-pages", "60"], { stdio: "pipe" });
  } catch (e) {
    fail("fixtures", "mvp5_fixtures.py failed", String(e.stdout || e.message).slice(0, 300));
  }
  log("fixtures", { note: "spec/scan/big pdf + plan docx generated" });

  // --- boot: jar on a FRESH temp db, dev-seeded account (survives restarts) ---
  fs.rmSync(DB_DIR, { recursive: true, force: true });
  fs.mkdirSync(DB_DIR, { recursive: true });
  const boot = run("java", jarArgs([
    `--keplura.dev.seed.email=${EMAIL}`,
    `--keplura.dev.seed.password=${PASSWORD}`,
    "--keplura.dev.seed.display-name=MVP5 QA",
  ]), {});
  if (!(await waitForHealth(true, 120000))) fail("boot-jar", "server not healthy in 120s", boot.output.slice(-500));
  log("boot-jar", { api: API });

  // --- vite dev server (same stack as the documented QA flow) ---
  // VITE_SERVER_URL must name the host "localhost" (not 127.0.0.1): Chrome
  // treats an IP literal and "localhost" as different SITES, so the
  // session cookie would be dropped as cross-site on every credentialed
  // fetch even though CORS allows the origin. Health checks below keep
  // using 127.0.0.1 (node fetch has no such concept).
  const web = run("npm", ["run", "dev", "--", "--host", "127.0.0.1", "--port", String(new URL(BASE).port), "--strictPort"], {
    cwd: path.join(__dirname, ".."),
    env: { ...process.env, CHOKIDAR_USEPOLLING: "1", VITE_SERVER_URL: `http://localhost:${API_PORT}` },
  });
  let ready = false;
  for (let i = 0; i < 120 && !ready; i++) {
    try { ready = (await fetch(BASE + "/")).ok; } catch { /* not yet */ }
    if (!ready) await sleep(500);
  }
  if (!ready) fail("boot-web", "dev server not reachable", web.output.slice(-500));
  log("boot-web", { base: BASE });

  const browser = await chromium.launch({ executablePath: EXE });
  const page = await browser.newPage({ locale: 'de-DE', viewport: { width: 1100, height: 1400 } });
  const shot = (name) => page.screenshot({ path: path.join(OUT_DIR, name), fullPage: true });

  try {
    await page.goto(BASE, { waitUntil: "load" });
    await loginAs(page, EMAIL, PASSWORD);
    // UI-POLISH-1: the create forms live behind "+" actions in a dialog now.
    // The triggers only ever OPEN the dialog, so clicking unconditionally is
    // idempotent even after a failed earlier create.
    // REDESIGN (14.11.): organisations under "Team", documents under "Wissen".
    await page.click('[data-testid="sidebar-nav-team"]');
    await page.waitForSelector('[data-testid="team-view"]', { timeout: 20000 });
    await page.click('[data-testid="org-create-new"]');
    await page.fill('[data-testid="org-create-name"]', ORG_NAME);
    await page.click('[data-testid="org-create-submit"]');
    await page.waitForSelector('[data-testid="org-item"]', { timeout: 20000 });
    await page.click('[data-testid="sidebar-nav-wissen"]');
    await page.waitForSelector('[data-testid="knowledge-section"]', { timeout: 20000 });
    await page.click('[data-testid="knowledge-space-new"]');
    await page.fill('[data-testid="knowledge-space-name"]', SPACE_NAME);
    await page.click('[data-testid="knowledge-space-submit"]');
    await page.click('[data-testid="knowledge-kb-new"]');
    await page.fill('[data-testid="knowledge-kb-name"]', KB_NAME);
    await page.click('[data-testid="knowledge-kb-submit"]');
    await page.waitForSelector('[data-testid="knowledge-kb-active"]', { timeout: 20000 });
    log("boot-session", { kb: KB_NAME });
    await shot("01-kb-ready.png");

    // ===== leg A: real DOCX -> INDEXED badge + cited search hit =====
    {
      const r = await uploadAndRows(page, "/tmp/mvp5_plan.docx");
      const row = r.rows.find((x) => x.name.includes("mvp5_plan"));
      if (!row) fail("upload-docx", "docx row missing after upload", r);
      log("upload-docx", { status: row.status, toast: r.toast });
      if (row.status !== "INDEXED") fail("upload-docx-status", "expected INDEXED", row);
      const s = await searchFor(page, "Kostenplanung Werksniederlassung");
      log("search-docx", { hits: s.hits, source: s.sources[0] || "", snippet: (s.snippets[0] || "").slice(0, 60) });
      if (s.hits < 1 || !s.sources[0].includes("mvp5_plan")) fail("search-docx", "no cited docx hit", s);
      if (!s.snippets[0].includes("Kostenplanung")) fail("search-docx-snippet", "hit text not from docx", s.snippets[0]);
      await shot("02-docx-indexed.png");
    }

    // ===== leg B: real PDF -> INDEXED badge + cited search hit =====
    {
      const r = await uploadAndRows(page, "/tmp/mvp5_spec.pdf");
      const row = r.rows.find((x) => x.name.includes("mvp5_spec"));
      if (!row) fail("upload-pdf", "pdf row missing after upload", r);
      log("upload-pdf", { status: row.status, toast: r.toast });
      if (row.status !== "INDEXED") fail("upload-pdf-status", "expected INDEXED", row);
      const s = await searchFor(page, "Betriebstemperatur Gasturbine 72");
      log("search-pdf", { hits: s.hits, source: s.sources[0] || "", snippet: (s.snippets[0] || "").slice(0, 60) });
      if (s.hits < 1 || !s.sources.some((x) => x.includes("mvp5_spec"))) fail("search-pdf", "no cited pdf hit", s);
      await shot("03-pdf-indexed.png");
    }

    // ===== leg C: scan-like PDF (no text layer) -> NO_TEXT_LAYER badge =====
    {
      const r = await uploadAndRows(page, "/tmp/mvp5_scan.pdf");
      const row = r.rows.find((x) => x.name.includes("mvp5_scan"));
      if (!row) fail("upload-scan", "scan row missing after upload", r);
      log("upload-scan", { status: row.status, toast: r.toast });
      if (row.status !== "NO_TEXT_LAYER") fail("upload-scan-status", "expected NO_TEXT_LAYER", row);
      const s = await searchFor(page, "Betriebstemperatur Gasturbine 72");
      const leaked = s.sources.filter((x) => x.includes("mvp5_scan"));
      log("search-scan-absent", { hits: s.hits, scanCited: leaked.length });
      if (leaked.length !== 0) fail("scan-leaked-into-search", "NO_TEXT_LAYER doc cited", leaked);
      await shot("04-scan-no-text-layer.png");
    }

    // ===== leg D: over-cap pdf on a jar with pdf-max-pages=30 -> honest 400 =====
    // The 60-page fixture cannot extract under the cap: the UI must show the
    // rejection toast and the row must NOT exist (upload transaction rolled
    // back). Re-launching the jar on the SAME db also proves A-C statuses are
    // persisted db state, not per-process derivations.
    {
      boot.child.kill("SIGTERM");
      if (!(await waitForHealth(false, 30000))) fail("restart-kill", "first jar never died");
      const boot2 = run("java", jarArgs(["--keplura.parser.pdf-max-pages=30"]), {});
      if (!(await waitForHealth(true, 120000))) fail("restart-boot", "second jar not healthy", boot2.output.slice(-500));
      log("restart-pdfcap", { note: "same db, keplura.parser.pdf-max-pages=30" });

      await page.goto(BASE, { waitUntil: "load" });
      await loginAs(page, EMAIL, PASSWORD);
      await page.click('[data-testid="sidebar-nav-wissen"]');
      await page.waitForSelector('[data-testid="knowledge-section"]', { timeout: 20000 });
      await page.waitForFunction(
        () => document.querySelectorAll('[data-testid="knowledge-doc-item"]').length >= 3,
        { timeout: 20000 },
      );
      const persisted = await page.$$eval('[data-testid="knowledge-doc-item"]', (els) =>
        els.map((el) => ({
          name: el.querySelector('[data-testid="knowledge-doc-name"]')?.textContent?.trim() || "",
          status: el.querySelector('[data-testid="knowledge-doc-status"]')?.getAttribute("data-index-status") || "",
        })),
      );
      const survived = ["mvp5_plan", "mvp5_spec", "mvp5_scan"].map(
        (n) => persisted.find((x) => x.name.includes(n))?.status || "MISSING",
      );
      log("status-persistence", { survived });
      if (survived.join(",") !== "INDEXED,INDEXED,NO_TEXT_LAYER") {
        fail("status-persistence", "statuses wrong after jar restart", survived);
      }

      const r = await uploadAndRows(page, "/tmp/mvp5_big.pdf");
      const boomRow = r.rows.find((x) => x.name.includes("mvp5_big"));
      log("upload-overcap", { toast: r.toast, rowPresent: !!boomRow });
      if (!/konnte nicht übernommen werden/.test(r.toast)) fail("overcap-toast", "missing honest rejection toast", r.toast);
      if (boomRow) fail("overcap-rollback", "rejected upload left a visible row", boomRow);
      await shot("05-overcap-rejected.png");
      boot2.child.kill("SIGKILL");
    }

    await browser.close();
    log("done", { shots: fs.readdirSync(OUT_DIR) });
    process.exit(0);
  } catch (e) {
    fail("crash", String((e && e.stack) || e));
  }
})().catch((e) => fail("crash", String((e && e.message) || e)));
