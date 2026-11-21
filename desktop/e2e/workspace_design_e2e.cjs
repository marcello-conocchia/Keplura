// Visual and navigation acceptance against a disposable, configured QA server.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const env = process.env;
for (const key of ['E2E_EMAIL', 'E2E_PASSWORD', 'E2E_OUT']) {
  if (!env[key]) throw new Error(`${key} fehlt.`);
}
(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'],
    ...(env.E2E_CHROME ? { executablePath: env.E2E_CHROME } : {}) });
  const page = await browser.newPage({ locale: 'de-DE', viewport: { width: 1600, height: 1050 } });
  fs.mkdirSync(env.E2E_OUT, { recursive: true });
  const shot = async name => {
    await page.evaluate(() => document.fonts.ready);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      `Horizontaler Seitenüberlauf: ${name}`);
    await page.screenshot({ path: path.join(env.E2E_OUT, `${name}.png`), fullPage: true });
    console.log(JSON.stringify({ step: name, ok: true }));
  };
  try {
    await page.goto(env.E2E_BASE || 'http://localhost:1420');
    await page.getByTestId('login-submit').waitFor();
    await shot('login');
    await page.getByTestId('login-email').fill(env.E2E_EMAIL);
    await page.getByTestId('login-password').fill(env.E2E_PASSWORD);
    await page.getByTestId('login-submit').click();
    const created = page.waitForResponse(r => r.request().method() === 'POST'
      && r.url().endsWith('/chat/conversations'));
    await page.getByTestId('chat-new-plain').click();
    assert((await created).ok());
    await page.getByTestId('chat-ready').waitFor();
    await shot('chat');
    for (const [nav, target] of [['wissen', 'knowledge-section'], ['team', 'team-view'], ['admin', 'admin-panel']]) {
      await page.getByTestId(`sidebar-nav-${nav}`).click();
      await page.getByTestId(target).waitFor();
      await shot(nav);
    }
    await page.getByTestId('admin-tab-mcp').click();
    await page.getByTestId('admin-mcp-server').first().waitFor();
    await shot('mcp');
    await page.getByTestId('admin-mcp-server').first().locator('.mcp-tools > summary').click();
    await page.getByTestId('admin-mcp-tool').first().waitFor();
    await shot('mcp-tools');
    await page.getByTestId('sidebar-user').click();
    await page.getByTestId('theme-dark').click();
    await shot('dark-admin');
    await page.getByTestId('sidebar-nav-chat').click();
    await shot('dark-chat');
    await page.setViewportSize({ width: 390, height: 844 });
    const input = await page.getByTestId('chat-prompt').boundingBox();
    const actions = await page.locator('.composer-bar').boundingBox();
    assert(input && actions && input.y + input.height <= actions.y,
      'Eingabetext und Aktionen überlagern sich auf dem Handy');
    await shot('mobile-chat');
    await page.getByTestId('mobile-menu-open').click();
    await page.getByTestId('sidebar-nav-admin').click();
    await page.getByTestId('admin-panel').waitFor();
    await shot('mobile-admin');
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
