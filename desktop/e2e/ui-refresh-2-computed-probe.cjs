// UI-REFRESH-2 diagnostics: computed line-heights on the migrated tree for
// the three suspect subtrees (conv-open children, src-chip children, es-ex).
const { chromium } = require("/cache/home/.cache/keplura-e2e/node_modules/playwright-core");
const EXE = "/cache/home/.cache/ms-playwright/chromium_headless_shell-1181/chrome-linux/headless_shell";
const BASE = process.env.E2E_BASE || "http://localhost:1420";
const API = process.env.E2E_API || "http://127.0.0.1:8080";
const MAIL = process.argv[2];
const SECRET = process.argv[3];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.launch({ executablePath: EXE });
  const page = await browser.newPage({ viewport: { width: 1200, height: 1400 } });
  const probe = (sel) => page.evaluate((s) => {
    const el = document.querySelector(s);
    if (!el) return null;
    const cs = getComputedStyle(el);
    return { lh: cs.lineHeight, fs: cs.fontSize, h: el.getBoundingClientRect().height };
  }, sel);

  await page.goto(BASE, { waitUntil: "networkidle" });
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
  await page.fill('[data-testid="login-email"]', MAIL);
  await page.fill('[data-testid="login-password"]', SECRET);
  await page.click('[data-testid="login-submit"]');
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
  await page.click('[data-testid="sidebar-nav-chat"]');
  await page.waitForSelector('[data-testid="chat-no-selection"]', { timeout: 10000 });
  await sleep(300);
  console.log(JSON.stringify({
    "es-ex": await probe(".es-ex"),
    "empty-box": await probe('[data-testid="chat-no-selection"]'),
  }));

  await page.click('[data-testid="chat-new-plain"]');
  await page.fill('[data-testid="chat-prompt"]', "Sag kurz Hallo.");
  await page.click('[data-testid="chat-send"]');
  await page.waitForFunction(
    () => !document.querySelector('[data-testid="chat-streaming"]'),
    { timeout: 60000 });
  await sleep(400);
  console.log(JSON.stringify({
    "conv-open": await probe(".conv-open"),
    "conv-title": await probe(".conv-title"),
    "conv-meta": await probe(".conv-meta"),
    "acontent": await probe(".acontent"),
    "ameta-model": await probe(".msg-model"),
  }, null, 1));
  await browser.close();
})().catch((e) => { console.error("PROBE-FAIL " + e.message); process.exit(1); });
