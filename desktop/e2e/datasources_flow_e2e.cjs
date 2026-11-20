// Run against a disposable, migrated server with a configured directory,
// knowledge base and a read-only local source fixture. Credentials stay in ENV.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const out = process.env.E2E_OUT;
if (!out || !process.env.E2E_EMAIL || !process.env.E2E_PASSWORD || !process.env.E2E_SOURCE_ROOT) {
  throw new Error('E2E_OUT, E2E_EMAIL, E2E_PASSWORD und E2E_SOURCE_ROOT fehlen.');
}
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true,
    ...(process.env.E2E_CHROME ? { executablePath: process.env.E2E_CHROME } : {}), args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const step = async (name, fn) => { await fn(); console.log(JSON.stringify({ step: name, ok: true })); };
  try {
    await step('Anmeldung und Verzeichnis-Verbindung', async () => {
      await page.goto(process.env.E2E_BASE || 'http://localhost:1420');
      await page.getByTestId('login-email').fill(process.env.E2E_EMAIL);
      await page.getByTestId('login-password').fill(process.env.E2E_PASSWORD);
      await page.getByTestId('login-submit').click();
      await page.getByTestId('sidebar-nav-admin').click();
      await page.getByTestId('admin-tab-directory').click();
      await page.getByTestId('directory-item').first().getByTestId('directory-test').click();
      await page.getByTestId('directory-test-result').first().waitFor();
      assert.match(await page.getByTestId('directory-test-result').first().innerText(), /erreichbar|gefunden|erfolgreich|Verbindung steht/i);
      await page.screenshot({ path: path.join(out, 'directory.png'), fullPage: true });
    });
    if (process.env.E2E_DIRECTORY_URL) {
      await step('Verzeichnis über Formular anlegen und verbinden', async () => {
        const name = `Verzeichnis-Browser ${Date.now()}`;
        await page.getByTestId('directory-create').click();
        const form = page.getByTestId('directory-form');
        await form.getByLabel('Name', { exact: true }).fill(name);
        await form.getByTestId('directory-url').fill(process.env.E2E_DIRECTORY_URL);
        await form.getByLabel('Suchbasis (Base DN)', { exact: true }).fill(process.env.E2E_DIRECTORY_BASE_DN);
        await form.getByLabel('Dienstkonto (nur Lesen)', { exact: true }).fill(process.env.E2E_DIRECTORY_BIND_DN);
        await form.getByLabel('Passwort des Dienstkontos', { exact: true }).fill(process.env.E2E_DIRECTORY_PASSWORD);
        await form.getByTestId('directory-save').click();
        const connection = page.getByTestId('directory-item').filter({ hasText: name });
        await connection.getByTestId('directory-test').click();
        await connection.getByTestId('directory-test-result').waitFor();
        assert.match(await connection.getByTestId('directory-test-result').innerText(), /erreichbar|gefunden|erfolgreich|Verbindung steht/i);
        await page.screenshot({ path: path.join(out, 'directory-created.png'), fullPage: true });
      });
    }
    await step('Benutzer nachschlagen', async () => {
      await page.getByTestId('directory-item').first().getByTestId('connection-actions').click();
      await page.getByTestId('directory-lookup').click();
      await page.getByTestId('directory-lookup-login').fill(process.env.E2E_DIRECTORY_LOGIN || 'qa-allowed');
      await page.getByTestId('directory-lookup-login').press('Enter');
      await page.getByTestId('directory-lookup-result').waitFor();
      assert.match(await page.getByTestId('directory-lookup-result').innerText(), /Gefunden|Gruppen|posix/i);
      await page.getByRole('button', { name: 'Schließen', exact: true }).click();
    });
    const name = `Browser-Abnahme ${Date.now()}`;
    await step('Datenquellen-Assistent: Art, Verbindung, Auswahl und Rechte', async () => {
      await page.getByTestId('admin-tab-datasources').click();
      await page.getByTestId('datasource-create').click();
      await page.locator('input[type="radio"][value="LOCAL"]').check();
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-name').fill(name);
      await page.getByTestId('datasource-location').fill(process.env.E2E_SOURCE_ROOT);
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-acl-preview').waitFor();
      await page.screenshot({ path: path.join(out, 'rights-preview.png'), fullPage: true });
    });
    await step('Zeitplan, Probelauf, Aktivierung und Status', async () => {
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-activate').click();
      const row = page.getByTestId('datasource-item').filter({ hasText: name });
      await row.waitFor();
      await row.getByTestId('datasource-run').click();
      await page.waitForFunction(sourceName => {
        const row = [...document.querySelectorAll('[data-testid="datasource-item"]')]
          .find(el => el.textContent.includes(sourceName));
        return /^Aktuell/.test(row?.querySelector('[data-testid="datasource-status"]')?.textContent?.trim() || '');
      }, name, { timeout: 90000 });
      await page.screenshot({ path: path.join(out, 'sources-status.png'), fullPage: true });
    });
  } catch (e) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true });
    console.log(JSON.stringify({ step: 'Datenquellen-Browser', ok: false, error: e.message.slice(0, 700) }));
    process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
