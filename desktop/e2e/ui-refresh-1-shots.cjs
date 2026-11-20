// UI-REFRESH-1 evidence leg (AC4): Vorher/Nachher-Screenshots AND
// per-control bounding-box geometry for Sidebar + Login, light + dark.
//
//   node ui-refresh-1-shots.cjs <outDir> [mail] [secret]
// Env: E2E_BASE (default http://localhost:1420), E2E_CHROME (headless_shell).
//
// Steps (deterministic theme route via the real toggle, same anchors as
// dark_flow_e2e): login-light -> login-dark (2 cycles) -> login -> sidebar
// session shot (dark) -> sidebar shot after 1 cycle back to light.
// Writes shots + geometry.json (bounding rects of every key testid).
"use strict";
const { chromium } = require("playwright-core");
const fs = require("node:fs");
const path = require("node:path");

const ARGS = process.argv.slice(2);
const OUT_DIR = ARGS[0] || "/tmp/ui-refresh-shots";
const BASE = process.env.E2E_BASE || "http://localhost:1420";
const EXE =
  process.env.E2E_CHROME ||
  "/cache/home/.cache/ms-playwright/chromium_headless_shell-1181/chrome-linux/headless_shell";
const EMAIL = ARGS[1];
const PASSWORD = ARGS.length > 2 ? ARGS[2] : "";
if (!EMAIL || !PASSWORD) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "pass email+password via argv" }));
  process.exit(2);
}
fs.mkdirSync(OUT_DIR, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const GEOM_IDS = [
  "login-card", "login-form", "login-email", "login-password", "login-submit",
  "connection-status", "sidebar", "theme-toggle", "fullscreen-toggle",
  "sidebar-reload", "sidebar-nav-wissen", "sidebar-nav-chat", "sidebar-kb-count",
  "sidebar-org", "sidebar-logout", "sidebar-status", "sidebar-version", "sidebar-model",
];

(async () => {
  const browser = await chromium.launch({ executablePath: EXE });
  const page = await browser.newPage({ viewport: { width: 1100, height: 800 }, colorScheme: "light" });
  const shot = (name) => page.screenshot({ path: path.join(OUT_DIR, name) });

  await page.goto(BASE, { waitUntil: "networkidle" });
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
  await shot("login-light.png");

  // geometry on the login screen (before any interaction)
  const geom = {};
  for (const id of GEOM_IDS) {
    const box = await page.locator(`[data-testid="${id}"]`).first().boundingBox().catch(() => null);
    if (box) geom[id] = box;
  }

  // explicit dark via the real control (system -> light -> dark)
  await page.click('[data-testid="theme-toggle"]');
  await page.waitForFunction(() => document.documentElement.getAttribute("data-ic-theme") === "light", undefined, { timeout: 5000 });
  await page.click('[data-testid="theme-toggle"]');
  await page.waitForFunction(() => document.documentElement.getAttribute("data-ic-theme") === "dark", undefined, { timeout: 5000 });
  await sleep(250);
  await shot("login-dark.png");

  await page.fill('[data-testid="login-email"]', EMAIL);
  await page.fill('[data-testid="login-password"]', PASSWORD);
  await page.click('[data-testid="login-submit"]');
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
  await page.waitForSelector('[data-testid="sidebar-version"]', { timeout: 15000 });
  await sleep(250);
  await shot("sidebar-dark.png");
  for (const id of GEOM_IDS) {
    const box = await page.locator(`[data-testid="${id}"]`).first().boundingBox().catch(() => null);
    if (box) geom[id] = box;
  }

  // one cycle: dark -> system (OS light) => light sidebar
  await page.click('[data-testid="theme-toggle"]');
  await page.waitForFunction(() => document.documentElement.getAttribute("data-ic-theme") === "light", undefined, { timeout: 5000 });
  await sleep(250);
  await shot("sidebar-light.png");

  fs.writeFileSync(path.join(OUT_DIR, "geometry.json"), JSON.stringify(geom, null, 1));
  await browser.close();
  console.log(JSON.stringify({ step: "shots", ok: true, out: OUT_DIR, ids: Object.keys(geom).length }));
})().catch((err) => {
  console.log(JSON.stringify({ step: "harness", ok: false, error: String(err && err.message ? err.message : err) }));
  process.exit(1);
});
