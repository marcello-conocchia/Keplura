// README screenshots (docs/assets/screenshots/*-dark.png) in English, dark mode.
//
// Starts a mock OpenAI-compatible model (fixed answer, NOT a real model), the
// server jar (dev profile, fresh H2 database) and expects the built desktop
// bundle on E2E_BASE (vite preview, VITE_SERVER_URL=http://localhost:8080).
// Sample data is fictitious. Retrieval and citations are real.
//
//   E2E_JAR=server/target/keplura-server-0.1.0.jar node desktop/e2e/readme_screenshots.cjs <out-dir>
"use strict";
const { chromium } = require("playwright");
const { spawn } = require("node:child_process");
const http = require("node:http");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const OUT = path.resolve(process.argv[2] || "docs/assets/screenshots");
const JAR = process.env.E2E_JAR;
const BASE = process.env.E2E_BASE || "http://localhost:1420";
const API = "http://127.0.0.1:8080";
const MOCK_PORT = 19931;
const MAIL = "alex.baker@example.com";
const SECRET = "Readme-Screens-2026!";
if (!JAR) { console.error("E2E_JAR required"); process.exit(2); }

const ANSWER =
  "Request vacation at least **two weeks in advance** in the HR portal. " +
  "Your manager approves the request within three working days [1].\n\n" +
  "- Remaining leave from the previous year expires on **31 March** [1].\n" +
  "- Bridge days are agreed within the team by the end of January [1].";

const DOCS = {
  "Vacation-policy.md": "# Vacation policy\n\nRequest vacation at least two weeks in advance in the HR portal. " +
    "Your manager approves the request within three working days.\n\nRemaining leave from the previous year " +
    "expires on 31 March. Bridge days are agreed within the team by the end of January.\n",
  "Travel-expense-policy.md": "# Travel expense policy\n\nHotels are reimbursed up to 120 euros per night. " +
    "Submit receipts within four weeks after the trip.\n",
  "IT-security-guide.md": "# IT security guide\n\nLock your screen when you leave your desk. Report phishing " +
    "emails to the IT service desk. Passwords have at least 12 characters.\n",
  "Onboarding-checklist.md": "# Onboarding checklist\n\nFirst day: laptop, badge, accounts. First week: " +
    "meet your team and read the employee handbook.\n",
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const mock = http.createServer((req, res) => {
  if (req.method !== "POST" || !req.url.endsWith("/chat/completions")) { res.writeHead(404).end(); return; }
  const body = [];
  req.on("data", (c) => body.push(c));
  req.on("end", () => {
    // Cite the number the server gave the vacation policy in the context block.
    const n = (Buffer.concat(body).toString().match(/\[(\d+)\] Vacation-policy\.md/) || [])[1] || "1";
    const answer = ANSWER.replaceAll("[1]", `[${n}]`);
    res.writeHead(200, { "content-type": "text/event-stream" });
    for (const tok of answer.match(/.{1,8}/gs)) {
      res.write(`data: ${JSON.stringify({ id: "x", model: "local-model", choices: [{ index: 0, delta: { content: tok } }] })}\n\n`);
    }
    res.write(`data: ${JSON.stringify({ id: "x", model: "local-model", choices: [{ index: 0, delta: {}, finish_reason: "stop" }] })}\n\n`);
    res.end("data: [DONE]\n\n");
  });
});

async function healthy() {
  try { return (await fetch(API + "/api/v1/health")).ok; } catch { return false; }
}

async function bootJar() {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), "keplura-shots-"));
  const log = fs.openSync(path.join(work, "server.log"), "a");
  const child = spawn("java", ["-jar", JAR, "--spring.profiles.active=dev", "--server.port=8080",
    `--spring.datasource.url=jdbc:h2:file:${work}/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`,
    `--keplura.upload.dir=${work}/uploads`,
    "--keplura.dev.seed.email=" + MAIL, "--keplura.dev.seed.password=" + SECRET,
    "--keplura.dev.seed.display-name=Alex Baker",
    "--keplura.admin.bootstrap.email=" + MAIL, "--keplura.admin.bootstrap.password=" + SECRET],
  { stdio: ["ignore", log, log], env: { ...process.env, KEPLURA_GATEWAY_BASE_URL: `http://127.0.0.1:${MOCK_PORT}/v1`,
    KEPLURA_CHAT_MODEL: "local-model", KEPLURA_GATEWAY_API_KEY: "local" } });
  for (let i = 0; i < 120 && !(await healthy()); i++) await sleep(1000);
  if (!(await healthy())) throw new Error("server not healthy, see " + work);
  return child;
}

async function shot(page, name) {
  await sleep(600);
  await page.screenshot({ path: path.join(OUT, name) });
  console.log("saved", name);
}

(async () => {
  await new Promise((r) => mock.listen(MOCK_PORT, "127.0.0.1", r));
  const server = await bootJar();
  const browser = await chromium.launch();
  try {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "dark", locale: "en-US" });
    const page = await ctx.newPage();
    await page.goto(BASE, { waitUntil: "networkidle" });
    await page.waitForSelector('[data-testid="login-card"]');
    await shot(page, "login-dark.png");

    await page.fill('[data-testid="login-email"]', MAIL);
    await page.fill('[data-testid="login-password"]', SECRET);
    await page.click('[data-testid="login-submit"]');
    await page.waitForLoadState("networkidle");

    // Getting started: organization, workspace, first knowledge base.
    for (const [field, value] of [["Organization name", "Example Corp"], ["Workspace name", "People & Organization"],
      ["Knowledge base name", "Employee Handbook"]]) {
      await page.getByLabel(field).fill(value);
      await page.getByLabel(field).press("Enter");
      await sleep(800);
    }
    await page.waitForSelector('[data-testid="sidebar-nav-wissen"]');
    await page.click('[data-testid="sidebar-nav-wissen"]');
    await page.waitForLoadState("networkidle");

    // Upload the sample documents.
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "keplura-docs-"));
    const files = Object.entries(DOCS).map(([n, t]) => { const p = path.join(dir, n); fs.writeFileSync(p, t); return p; });
    for (const f of files) {
      await page.locator('input[type="file"]').first().setInputFiles(f);
      await sleep(1500);
    }
    // A second knowledge base and workspace make the sidebar realistic.
    const kbAdd = page.getByRole("button", { name: "New knowledge base" });
    if (await kbAdd.count()) {
      await kbAdd.first().click();
      await page.fill('[data-testid="knowledge-kb-name"]', "Policies");
      await page.locator('[data-testid="knowledge-kb-name"]').press("Enter");
      await sleep(1000);
      await page.getByText("Employee Handbook").first().click();
    }
    await page.waitForLoadState("networkidle");

    // Chat with knowledge.
    await page.click('[data-testid="sidebar-nav-chat"]');
    await page.waitForLoadState("networkidle");
    await page.selectOption('[data-testid="chat-kb-select"]', { label: "Employee Handbook" });
    await sleep(1500);
    await page.getByPlaceholder("Ask Keplura …").fill("How do I request vacation and when does remaining leave expire?");
    await page.keyboard.press("Enter");
    await page.waitForSelector("text=Sources", { timeout: 30000 });
    await page.getByPlaceholder("Ask Keplura …").blur();
    await shot(page, "chat-dark.png");

    // Knowledge view (after the chat, so no confirmation banner is left).
    await page.click('[data-testid="sidebar-nav-wissen"]');
    await page.waitForLoadState("networkidle");
    await shot(page, "knowledge-dark.png");

    // Administration → data sources.
    await page.click('[data-testid="sidebar-nav-admin"]').catch(() => page.getByRole("button", { name: "Administration" }).click());
    await page.getByRole("button", { name: "Data sources" }).first().click();
    await page.waitForLoadState("networkidle");
    await shot(page, "admin-datasources-dark.png");
  } finally {
    await browser.close();
    server.kill();
    mock.close();
  }
})().catch((e) => { console.error(e); process.exit(1); });
