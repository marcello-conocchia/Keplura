// Requires disposable MCP/SearXNG fixtures; never supplies production tokens.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const out = process.env.E2E_OUT;
for (const key of ['E2E_OUT', 'E2E_EMAIL', 'E2E_PASSWORD', 'E2E_MCP_URL', 'E2E_MCP_TOKEN', 'E2E_SEARX_URL']) {
  if (!process.env[key]) throw new Error(`${key} fehlt.`);
}
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true,
    ...(process.env.E2E_CHROME ? { executablePath: process.env.E2E_CHROME } : {}), args: ['--no-sandbox'] });
  const page = await browser.newPage({ locale: 'de-DE', viewport: { width: 1440, height: 1100 } });
  const step = async (name, fn) => { await fn(); console.log(JSON.stringify({ step: name, ok: true })); };
  const name = `MCP-Browser ${Date.now()}`;
  try {
    await page.goto(process.env.E2E_BASE || 'http://localhost:1420');
    await page.getByTestId('login-email').fill(process.env.E2E_EMAIL);
    await page.getByTestId('login-password').fill(process.env.E2E_PASSWORD);
    await page.getByTestId('login-submit').click();
    await page.getByTestId('sidebar-nav-admin').click();
    await page.getByTestId('admin-tab-mcp').click();
    await step('MCP verbinden: Werkzeuge sind zunächst gesperrt und Token wird geleert', async () => {
      await page.getByTestId('admin-mcp-open').click();
      await page.getByTestId('admin-mcp-name').fill(name);
      await page.getByTestId('admin-mcp-url').fill(process.env.E2E_MCP_URL);
      await page.getByTestId('admin-mcp-token').fill(process.env.E2E_MCP_TOKEN);
      await page.getByTestId('admin-mcp-add').click();
      const server = page.getByTestId('admin-mcp-server').filter({ hasText: name });
      await server.getByTestId('admin-mcp-tool').first().waitFor();
      for (const box of await server.getByTestId('admin-mcp-tool').all()) assert(!(await box.isChecked()));
      assert(!(await server.getByTestId('admin-mcp-all-spaces').isChecked()));
      assert.equal(await page.getByTestId('admin-mcp-token').inputValue(), '');
      await page.screenshot({ path: path.join(out, 'connected.png'), fullPage: true });
    });
    await step('Ein Werkzeug klassifizieren, freigeben und genau einen Arbeitsbereich freigeben; Neuladen erhält Auswahl', async () => {
      const server = page.getByTestId('admin-mcp-server').filter({ hasText: name });
      assert(await server.getByRole('checkbox', { name: 'echo', exact: true }).isDisabled()); // TU-01: unclassified = blocked
      await server.getByRole('combobox', { name: 'Risikoklasse für echo', exact: true }).selectOption('READ');
      await server.getByRole('checkbox', { name: 'echo', exact: true }).check();
      await server.getByRole('checkbox', { name: process.env.E2E_MCP_SPACE || 'Abnahme Datenquellen / Pilotbereich', exact: true }).check();
      await page.getByTestId('admin-tab-status').click();
      await page.getByTestId('admin-tab-mcp').click();
      await server.locator('.mcp-tools > summary').click();
      await server.locator('.mcp-access > summary').click();
      await server.getByRole('checkbox', { name: 'echo', exact: true }).waitFor();
      assert(await server.getByRole('checkbox', { name: 'echo', exact: true }).isChecked());
      assert(await server.getByRole('checkbox', { name: process.env.E2E_MCP_SPACE || 'Abnahme Datenquellen / Pilotbereich', exact: true }).isChecked());
      assert(!(await server.getByTestId('admin-mcp-all-spaces').isChecked()));
    });
    await step('Werkzeug-Protokoll zeigt gültige deutsche Zeitangaben', async () => {
      const log = page.getByTestId('admin-mcp-section').locator('details.log');
      await log.locator('summary').click();
      assert(!(await log.innerText()).includes('Invalid Date'));
      assert.match(await log.locator('table').innerText(), /echo/);
      await page.screenshot({ path: path.join(out, 'audit.png'), fullPage: true });
    });
    await step('Websuche im Admin-Bereich speichern und echten Suchdienst testen', async () => {
      await page.getByTestId('admin-tab-websearch').click();
      await page.getByTestId('admin-websearch-provider').selectOption('searxng');
      await page.getByTestId('admin-websearch-url').fill(process.env.E2E_SEARX_URL);
      await page.getByTestId('admin-websearch-save').click();
      await page.getByText('Gespeichert – die Websuche ist aktiv.', { exact: true }).waitFor();
      await page.getByTestId('admin-websearch-test').click();
      await page.getByTestId('admin-websearch-section').getByRole('status').filter({ hasText: /\d+ Treffer gefunden/ }).waitFor();
      // SearXNG needs no key; the field is intentionally absent for this provider.
      if (await page.getByTestId('admin-websearch-key').count()) {
        assert.equal(await page.getByTestId('admin-websearch-key').inputValue(), '');
      }
      await page.screenshot({ path: path.join(out, 'websearch-admin.png'), fullPage: true });
    });
  } catch (e) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true });
    console.log(JSON.stringify({ step: 'MCP-/Websuche-Administration', ok: false, error: e.message.slice(0, 700) }));
    process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
