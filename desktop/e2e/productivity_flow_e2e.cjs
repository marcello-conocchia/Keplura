// Real browser acceptance against a disposable migrated server and local model.
// E2E_PDF is a readable PDF fixture; optional E2E_SCAN verifies scan rejection.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const out = process.env.E2E_OUT;
if (!out || !process.env.E2E_EMAIL || !process.env.E2E_PASSWORD || !process.env.E2E_PDF) {
  throw new Error('E2E_OUT, E2E_EMAIL, E2E_PASSWORD und E2E_PDF fehlen.');
}
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true,
    ...(process.env.E2E_CHROME ? { executablePath: process.env.E2E_CHROME } : {}), args: ['--no-sandbox'] });
  const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1440, height: 1000 },
    permissions: ['clipboard-read', 'clipboard-write'] });
  const page = await context.newPage();
  const step = async (name, fn) => { await fn(); console.log(JSON.stringify({ step: name, ok: true })); };
  try {
    await step('Anmelden und globale Suche per Strg+K und Sidebar', async () => {
      await page.goto(process.env.E2E_BASE || 'http://localhost:1420');
      await page.getByTestId('login-email').fill(process.env.E2E_EMAIL);
      await page.getByTestId('login-password').fill(process.env.E2E_PASSWORD);
      await page.getByTestId('login-submit').click();
      await page.getByTestId('sidebar-search').waitFor();
      await page.keyboard.press('Control+k');
      await page.getByTestId('command-palette-input').fill(process.env.E2E_SEARCH_TERM || 'Abnahme');
      await page.getByTestId('command-palette-item').first().waitFor();
      await page.screenshot({ path: path.join(out, 'global-search.png'), fullPage: true });
      await page.getByTestId('command-palette-input').press('ArrowDown');
      await page.getByTestId('command-palette-input').press('Enter');
      await page.getByTestId('command-palette').waitFor({ state: 'hidden' });
      await page.getByTestId('sidebar-search').click();
      await page.getByTestId('command-palette-input').press('Escape');
      await page.getByTestId('sidebar-nav-chat').click();
      await page.getByTestId('chat-new-plain').click();
    });
    if (process.env.E2E_SPACE_TERM && process.env.E2E_KB_TERM && process.env.E2E_DOCUMENT_TERM) {
      await step('Suche navigiert zu Arbeitsbereich, Wissensbasis, Dokument und eigenem Chat', async () => {
        for (const [term, target] of [[process.env.E2E_SPACE_TERM, 'chat-view'],
          [process.env.E2E_KB_TERM, 'knowledge-section'], [process.env.E2E_DOCUMENT_TERM, 'knowledge-section'],
          [process.env.E2E_SEARCH_TERM || 'Abnahme_%_', 'chat-view']]) {
          await page.getByTestId('sidebar-search').click();
          await page.getByTestId('command-palette-input').fill(term);
          await page.getByTestId('command-palette-item').first().click();
          await page.getByTestId('command-palette').waitFor({ state: 'hidden' });
          await page.getByTestId(target).waitFor();
        }
        await page.getByTestId('chat-new-plain').click();
      });
    }
    const question = 'Fasse die angehängte Datei kurz auf Deutsch zusammen. Nenne die technischen Angaben.';
    const template = `Prüfvorlage ${Date.now()}`;
    await step('Vorlage speichern und in die Eingabe einfügen', async () => {
      await page.getByTestId('chat-prompt').fill(question);
      await page.getByTestId('chat-templates').click();
      await page.getByTestId('chat-template-save').click();
      await page.getByTestId('chat-template-title').fill(template);
      await page.getByTestId('chat-template-submit').click();
      await page.getByTestId('chat-template-title').waitFor({ state: 'hidden' });
      await page.getByTestId('chat-prompt').fill('');
      await page.getByTestId('chat-templates').click();
      await page.getByTestId('chat-template-item').filter({ hasText: template }).click();
      assert.equal(await page.getByTestId('chat-prompt').inputValue(), question);
    });
    await step('Zusätzliche Wissensbasis wählen und entfernen', async () => {
      await page.getByTestId('chat-context-add').click();
      await page.getByTestId('chat-extra-kbs').press('ArrowRight');
      await page.getByTestId('chat-extra-kb-item').first().click();
      await page.keyboard.press('Escape');
      await page.getByTestId('chat-extra-kb-chip').first().waitFor();
      await page.getByTestId('chat-extra-kb-chip').first().getByRole('button').click();
      await page.getByTestId('chat-extra-kb-chip').waitFor({ state: 'hidden' });
    });
    await step('PDF anhängen und echte Antwort erzeugen', async () => {
      await page.getByTestId('chat-attach-input').setInputFiles(process.env.E2E_PDF);
      await page.getByTestId('chat-attachment-chip').waitFor();
      await page.getByTestId('chat-send').click();
      await page.getByTestId('chat-msg-assistant').first().waitFor({ timeout: 180000 });
      await page.getByTestId('chat-streaming').waitFor({ state: 'hidden', timeout: 180000 });
      assert((await page.getByTestId('chat-msg-assistant').first().innerText()).length > 40);
      await page.screenshot({ path: path.join(out, 'attachment-answer.png'), fullPage: true });
    });
    await step('Antwort als Word/PDF laden und für E-Mail kopieren', async () => {
      for (const format of ['docx', 'pdf']) {
        await page.getByTestId('chat-msg-assistant').last().getByTestId('chat-msg-export').click();
        const downloading = page.waitForEvent('download');
        await page.getByTestId(`chat-export-${format}`).click();
        const download = await downloading;
        await download.saveAs(path.join(out, `antwort.${format}`));
        const bytes = fs.readFileSync(path.join(out, `antwort.${format}`));
        assert(format === 'pdf' ? bytes.subarray(0, 5).toString() === '%PDF-' : bytes.subarray(0, 2).toString() === 'PK');
      }
      await page.getByTestId('chat-msg-assistant').last().getByTestId('chat-msg-export').click();
      await page.getByTestId('chat-export-email').click();
      await page.waitForFunction(async () => (await navigator.clipboard.readText()).length > 30,
        null, { timeout: 5000 });
      const copied = await page.evaluate(() => navigator.clipboard.readText());
      assert(copied.length > 30);
    });
    if (process.env.E2E_SCAN) {
      await step('Scan-PDF zeigt verständliche deutsche Fehlermeldung', async () => {
        await page.getByTestId('chat-attach-input').setInputFiles(process.env.E2E_SCAN);
        await page.getByTestId('chat-notice').waitFor();
        assert.match(await page.getByTestId('chat-notice').innerText(), /keinen lesbaren Text|Scan/);
        await page.screenshot({ path: path.join(out, 'scan-rejected.png'), fullPage: true });
      });
    }
    if (process.env.E2E_WEB === '1') {
      await step('Web-Schalter liefert eine echte Antwort mit Quellenlinks', async () => {
        await page.getByTestId('chat-new-plain').click();
        await page.getByTestId('chat-web-toggle').click();
        await page.getByTestId('chat-prompt').fill('Was ist PostgreSQL? Nutze die Websuche und nenne am Ende die Quellen als Markdown-Links.');
        await page.getByTestId('chat-send').click();
        await page.getByTestId('chat-msg-assistant').first().waitFor({ timeout: 180000 });
        await page.getByTestId('chat-streaming').waitFor({ state: 'hidden', timeout: 180000 });
        await page.getByTestId('chat-msg-assistant').first().locator('a[href^="http"]').first().waitFor();
        await page.screenshot({ path: path.join(out, 'web-answer.png'), fullPage: true });
      });
    }
  } catch (e) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true });
    console.log(JSON.stringify({ step: 'Chat-Produktivität', ok: false, error: e.message.slice(0, 700) }));
    process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
