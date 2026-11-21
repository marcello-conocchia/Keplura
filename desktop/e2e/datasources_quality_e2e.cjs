// Runs against the disposable DS fixture: imported public text says 37 Tage;
// its quality set has one visible and one restricted source (50 percent).
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const env = process.env;
for (const key of ['E2E_OUT', 'E2E_EMAIL', 'E2E_PASSWORD', 'E2E_ORG_ID', 'E2E_KB_ID', 'E2E_API']) {
  if (!env[key]) throw new Error(`${key} fehlt.`);
}
fs.mkdirSync(env.E2E_OUT, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'],
    ...(env.E2E_CHROME ? { executablePath: env.E2E_CHROME } : {}) });
  const page = await browser.newPage({ locale: 'de-DE', viewport: { width: 1440, height: 1000 } });
  const step = async (name, fn) => { await fn(); console.log(JSON.stringify({ step: name, ok: true })); };
  const stats = async () => page.evaluate(async url => {
    const response = await fetch(url, { credentials: 'include' });
    if (!response.ok) throw new Error(`Statistik: HTTP ${response.status}`);
    return response.json();
  }, `${env.E2E_API}/api/v1/admin/quality/feedback?orgId=${env.E2E_ORG_ID}`);
  const expectStats = async (up, down) => {
    for (let attempt = 0; attempt < 30; attempt++) {
      const actual = await stats();
      if (actual.up === up && actual.down === down) return;
      await page.waitForTimeout(200);
    }
    const actual = await stats();
    assert.deepEqual({ up: actual.up, down: actual.down }, { up, down });
  };
  try {
    await page.goto(env.E2E_BASE || 'http://localhost:1420');
    await page.getByTestId('login-email').fill(env.E2E_EMAIL);
    await page.getByTestId('login-password').fill(env.E2E_PASSWORD);
    await page.getByTestId('login-submit').click();
    const created = page.waitForResponse(response => response.request().method() === 'POST'
      && response.url().endsWith('/chat/conversations'));
    await page.getByTestId('chat-new-plain').click();
    const plain = await (await created).json();
    await page.locator(`[data-testid="chat-conv-delete"][data-conv-id="${plain.id}"]`)
      .locator('..').locator('[data-testid="chat-conv-item"].active').waitFor();
    const before = await stats();
    await step('Importierte Quelle liefert belegte Antwort im Browser', async () => {
      const grounded = page.waitForResponse(response => response.request().method() === 'POST'
        && response.url().endsWith('/chat/conversations'));
      await page.getByTestId('chat-kb-select').selectOption(env.E2E_KB_ID);
      assert.equal((await (await grounded).json()).knowledgeBaseId, Number(env.E2E_KB_ID));
      await page.getByTestId('chat-ctx-kb').waitFor();
      await page.getByTestId('chat-prompt').fill('Alle wie viele Tage erfolgt die Inspektion des Pilotgeraets? Antworte anhand der Quelle.');
      await page.getByTestId('chat-send').click();
      await page.getByTestId('chat-msg-assistant').last().waitFor({ timeout: 180000 });
      await page.getByTestId('chat-streaming').waitFor({ state: 'hidden', timeout: 180000 });
      assert.match(await page.getByTestId('chat-msg-assistant').last().innerText(), /37/);
      assert.match(await page.getByTestId('chat-citation').allTextContents().then(x => x.join(' ')), /oeffentlich\.txt/);
      await page.screenshot({ path: path.join(env.E2E_OUT, 'imported-source-answer.png'), fullPage: true });
    });
    await step('Daumen hoch, wechseln und zurücknehmen aktualisiert die Statistik', async () => {
      const answer = page.getByTestId('chat-msg-assistant').last();
      await answer.getByTestId('chat-feedback-up').click();
      await expectStats(before.up + 1, before.down);
      await answer.getByTestId('chat-feedback-down').click();
      await expectStats(before.up, before.down + 1);
      await answer.getByTestId('chat-feedback-down').click();
      await expectStats(before.up, before.down);
    });
    await step('Antwortqualität zeigt echte Trefferquote und Bewertungszahlen', async () => {
      await page.getByTestId('sidebar-nav-admin').click();
      await page.getByTestId('admin-tab-quality').click();
      await page.getByTestId('quality-kb').selectOption(env.E2E_KB_ID);
      const completed = page.waitForResponse(response =>
        response.url().endsWith('/api/v1/admin/quality/runs') && response.request().method() === 'POST');
      await page.getByTestId('quality-run').click();
      const response = await completed;
      assert(response.ok());
      assert.equal((await response.json()).percent, 50);
      assert.match(await page.getByTestId('quality-score').innerText(), /50 %/);
      assert((await page.getByTestId('quality-feedback').innerText()).replace(/\s+/g, ' ').trim()
        .startsWith(`${before.up} ${before.down} `));
      await page.screenshot({ path: path.join(env.E2E_OUT, 'quality-and-feedback.png'), fullPage: true });
    });
  } catch (error) {
    await page.screenshot({ path: path.join(env.E2E_OUT, 'failure.png'), fullPage: true });
    console.log(JSON.stringify({ step: 'Datenquellen und Antwortqualität', ok: false, error: error.message.slice(0, 700) }));
    process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
