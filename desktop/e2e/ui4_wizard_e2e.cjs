// UI-04 Phase 4: Dialog-/Assistentenvalidierung gegen eine echte QA-Instanz.
// ENV: E2E_EMAIL, E2E_PASSWORD (Admin), E2E_OUT, E2E_ORG_ID, E2E_SPACE_ID,
// E2E_LOCAL_ROOT (z. B. /qa/files), E2E_SMB_LOCATION, E2E_SMB_USER, E2E_SMB_PASSWORD.
// Optional E2E_BASE, E2E_CHROME. Zugangsdaten nur aus ENV, nie in Belegen.
// Fälle mit page.route sind als "simuliert" gekennzeichnet (künstliche
// Verzögerung bzw. künstliche HTTP-Antwort); alle anderen laufen echt.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const env = process.env;
for (const k of ['E2E_EMAIL', 'E2E_PASSWORD', 'E2E_OUT', 'E2E_ORG_ID', 'E2E_SPACE_ID', 'E2E_LOCAL_ROOT']) {
  if (!env[k]) throw new Error(`${k} fehlt.`);
}
const out = env.E2E_OUT;
fs.mkdirSync(out, { recursive: true });
const results = [];
const secrets = [env.E2E_PASSWORD, env.E2E_SMB_PASSWORD].filter(Boolean);
const stamp = Date.now();

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'],
    ...(env.E2E_CHROME ? { executablePath: env.E2E_CHROME } : {}) });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'de-DE' });
  const page = await ctx.newPage();
  const dlg = () => page.locator('dialog.ic-dialog[open]');
  const active = () => page.evaluate(() => {
    const a = document.activeElement;
    return { tag: a?.tagName, testid: a?.getAttribute('data-testid'), label: a?.getAttribute('aria-label'), text: (a?.textContent || '').trim().slice(0, 60),
      inDialog: !!a?.closest('dialog[open]'), value: a?.type === 'radio' ? a.value : undefined };
  });
  const shot = async (name, full = false) => {
    await page.evaluate(() => document.fonts.ready);
    const html = await page.content();
    for (const s of secrets) assert(!html.includes(s), `Secret im DOM bei ${name}`);
    await page.screenshot({ path: path.join(out, `${name}.png`), fullPage: full });
  };
  const rootOverflow = () => page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
  async function point(id, name, fn, mode = 'echt') {
    try { const note = await fn(); results.push({ id, name, mode, status: 'bestanden', ...(note ? { note } : {}) }); }
    catch (e) { results.push({ id, name, mode, status: 'rot', reason: String(e.message).slice(0, 300) });
      try { await shot(`FAIL-${id}-${results.length}`); } catch {} }
    console.log(JSON.stringify(results.at(-1)));
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(results, null, 2));
  }
  const openWizard = async () => {
    if (await dlg().count()) { console.log(JSON.stringify({ cleanup: 'Dialog aus vorherigem Fall noch offen' })); await forceClose(); }
    await page.getByTestId('datasource-create').click();
    await dlg().waitFor();
  };
  const forceClose = async () => {
    // Aufräumen zwischen Fällen über den regulären Verwerfen-Weg.
    for (let i = 0; i < 3 && await dlg().count(); i++) {
      if (await page.getByTestId('dialog-discard').isVisible().catch(() => false)) await page.getByTestId('dialog-discard').click();
      else await dlg().getByTestId('dialog-close').click();
      await page.waitForTimeout(150);
    }
  };
  const fillLocal = async (name, location) => {
    await page.locator('input[type="radio"][value="LOCAL"]').check();
    await page.getByTestId('datasource-next').click();
    await page.getByTestId('datasource-name').fill(name);
    await page.getByTestId('datasource-location').fill(location);
  };
  const row = name => page.getByTestId('datasource-item').filter({ hasText: name });
  const atStep = n => dlg().locator('.wizard-main > .wizard-caption', { hasText: `Schritt ${n} von 6` });

  try {
    await page.goto(env.E2E_BASE || 'http://localhost:1420');
    await page.getByTestId('login-email').fill(env.E2E_EMAIL);
    await page.getByTestId('login-password').fill(env.E2E_PASSWORD);
    await page.getByTestId('login-submit').click();
    await page.getByTestId('sidebar-org').click();
    await page.getByTestId(`sidebar-org-${env.E2E_ORG_ID}`).click();
    await page.getByTestId(`sidebar-space-${env.E2E_SPACE_ID}`).click();
    await page.getByTestId('sidebar-nav-admin').click();
    await page.getByTestId('admin-tab-datasources').click();
    await page.getByTestId('datasource-create').waitFor();

    await point('V11', 'Initialfokus auf Dialogtitel, großer Rahmen', async () => {
      await openWizard();
      const a = await active();
      assert.equal(a.tag, 'H2'); assert.match(a.text, /Datenquelle hinzufügen/);
      const box = await dlg().boundingBox();
      assert(box.width >= 900, `Assistent nur ${box.width}px breit`);
      await shot('wizard-1440-step1');
      return `Breite ${Math.round(box.width)}px`;
    });
    await point('V11', 'Tab/Shift+Tab bleiben im modalen Dialog', async () => {
      const seen = new Set();
      for (let i = 0; i < 25; i++) { await page.keyboard.press('Tab'); const a = await active(); assert(a.inDialog, `Tab ${i + 1} verlässt Dialog: ${JSON.stringify(a)}`); seen.add(a.testid || a.text || a.tag); }
      for (let i = 0; i < 25; i++) { await page.keyboard.press('Shift+Tab'); const a = await active(); assert(a.inDialog, `Shift+Tab ${i + 1} verlässt Dialog`); }
      return `${seen.size} unterschiedliche Fokusziele`;
    });
    await point('V11', 'Escape ohne Änderung schließt, Fokus zurück auf Auslöser', async () => {
      await page.keyboard.press('Escape');
      await dlg().waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
      assert.equal(await dlg().count(), 0, 'Dialog noch offen');
      assert.equal((await active()).testid, 'datasource-create');
    });
    await point('V11', 'Escape mit Änderung: Verwerfen-Abfrage, Fokus „Weiter bearbeiten“', async () => {
      await openWizard();
      await page.locator('input[type="radio"][value="LOCAL"]').check();
      await page.keyboard.press('Escape');
      await page.getByTestId('dialog-keep-editing').waitFor();
      assert.equal((await active()).testid, 'dialog-keep-editing');
      await shot('wizard-discard-1440');
    });
    await point('V11', 'Weiter bearbeiten erhält Eingabe und Fokusposition', async () => {
      await page.getByTestId('dialog-keep-editing').click();
      assert(await page.locator('input[type="radio"][value="LOCAL"]').isChecked());
      const a = await active(); assert.equal(a.value, 'LOCAL', `Fokus: ${JSON.stringify(a)}`);
    });
    await point('V11', 'Doppeltes Escape umgeht Dirty-Schutz nicht', async () => {
      await page.keyboard.press('Escape'); await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
      assert.equal(await dlg().count(), 1, 'Dialog wurde trotz ungespeicherter Änderung geschlossen');
      assert(await page.locator('input[type="radio"][value="LOCAL"]').isChecked() || await page.getByTestId('dialog-keep-editing').isVisible());
      if (await page.getByTestId('dialog-keep-editing').isVisible()) await page.getByTestId('dialog-keep-editing').click();
    });
    await point('V11', 'Footer-Abbrechen nutzt denselben Schutz; Verwerfen schließt mit Fokusrückkehr', async () => {
      await page.getByTestId('datasource-cancel').click();
      await page.getByTestId('dialog-discard').waitFor();
      await page.getByTestId('dialog-discard').click();
      await dlg().waitFor({ state: 'detached', timeout: 3000 }).catch(() => {});
      assert.equal(await dlg().count(), 0);
      assert.equal((await active()).testid, 'datasource-create');
    });
    await point('V11', 'Klick auf Hintergrund mit Änderung zeigt Verwerfen-Abfrage', async () => {
      await openWizard();
      await page.locator('input[type="radio"][value="LOCAL"]').check();
      await page.mouse.click(5, 5);
      await page.getByTestId('dialog-keep-editing').waitFor({ timeout: 2000 });
      await page.getByTestId('dialog-keep-editing').click();
    });
    await point('V11/V09', 'Fehlgeschlagene Verbindung (echter nicht vorhandener Ordner): Fehler fokussiert, Werte bleiben', async () => {
      await page.getByTestId('datasource-next').click();
      const nm = `UI4 Fehlerfall ${stamp}`, loc = `${env.E2E_LOCAL_ROOT}/gibt-es-nicht-${stamp}`;
      await page.getByTestId('datasource-name').fill(nm);
      await page.getByTestId('datasource-location').fill(loc);
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('dialog-error').waitFor({ timeout: 30000 });
      assert.equal((await active()).testid, 'dialog-error');
      assert.equal(await page.getByTestId('datasource-name').inputValue(), nm);
      assert.equal(await page.getByTestId('datasource-location').inputValue(), loc);
      const msg = (await page.getByTestId('dialog-error').innerText()).trim();
      await shot('wizard-connection-error');
      return `Meldung: ${msg.slice(0, 120)}`;
    });
    await forceClose();

    await point('V11', 'Busy beim Speichern: Escape/Schließen/Abbrechen gesperrt, Statushinweis', async () => {
      await openWizard();
      await page.route('**/admin/datasources/*/preview', async r => { await new Promise(f => setTimeout(f, 2500)); await r.continue(); });
      await fillLocal(`UI4 Langsam ${stamp}`, `${env.E2E_LOCAL_ROOT}/public`);
      await page.getByTestId('datasource-next').click();
      await page.waitForFunction(() => document.querySelector('[data-testid="datasource-next"]')?.getAttribute('aria-busy') === 'true');
      await page.keyboard.press('Escape');
      await dlg().getByTestId('dialog-close').click({ force: true });
      assert.equal(await dlg().count(), 1, 'Dialog während Busy geschlossen');
      assert(await page.getByTestId('datasource-cancel').isDisabled());
      await page.locator('.busy-notice').waitFor({ timeout: 1000 });
      await shot('wizard-busy');
      await page.getByTestId('datasource-next').and(page.locator(':not([aria-busy])')).waitFor({ timeout: 30000 });
      await page.unroute('**/admin/datasources/*/preview');
      assert.equal(await page.locator('.busy-notice').count(), 0, 'Busy-Hinweis bleibt nach Ende stehen');
    }, 'simuliert: echte Antwort, künstlich 2,5 s verzögert');
    await point('V11/V09', 'Probelauf HTTP 500: wahrheitsgemäßer Fehler, Werte bleiben', async () => {
      // Wir stehen nach dem langsamen Fall auf Schritt 3; zurück auf 2 und erneut prüfen.
      await page.getByRole('button', { name: 'Zurück', exact: true }).click();
      await page.route('**/admin/datasources/*/preview', r => r.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"simuliert"}' }));
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('dialog-error').waitFor({ timeout: 10000 });
      assert.equal(await page.getByTestId('datasource-location').inputValue(), `${env.E2E_LOCAL_ROOT}/public`);
      await page.unroute('**/admin/datasources/*/preview');
      return (await page.getByTestId('dialog-error').innerText()).trim().slice(0, 120);
    }, 'simuliert: HTTP 500');
    await point('V11', 'Nach gespeichertem Probelauf kein falscher Verwerfen-Dialog', async () => {
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-next').and(page.locator(':not([aria-busy])')).waitFor({ timeout: 30000 });
      await atStep(3).waitFor();
      await page.getByTestId('datasource-cancel').click();
      await page.waitForTimeout(200);
      assert.equal(await page.getByTestId('dialog-discard').count(), 0, 'Verwerfen-Abfrage trotz gespeichertem Stand');
      assert.equal(await dlg().count(), 0);
    });

    // ---- LOCAL vollständig (echt)
    const localName = `UI4 Linux ${stamp}`;
    await point('V08', 'LOCAL: Verbindung, Probelauf, Auswahl', async () => {
      await openWizard();
      await fillLocal(localName, env.E2E_LOCAL_ROOT);
      await shot('local-step2');
      await page.getByTestId('datasource-next').click();
      await atStep(3).waitFor({ timeout: 60000 });
      await shot('local-step3');
      return (await page.locator('.ds-form').first().innerText()).replace(/\s+/g, ' ').slice(0, 160);
    });
    await point('V08/V09', 'LOCAL: Rechte-Vorschau sichtbar, ACL-Warnung beim Abwählen', async () => {
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-acl-preview').waitFor({ timeout: 60000 });
      const acl = (await page.getByTestId('datasource-acl-preview').innerText()).replace(/\s+/g, ' ');
      await shot('local-step4-acl');
      await page.getByTestId('datasource-mirror').uncheck();
      await page.locator('.ds-warn').waitFor();
      await shot('local-step4-acl-warning');
      await page.getByTestId('datasource-mirror').check();
      assert.equal(await page.locator('.ds-warn').count(), 0);
      return acl.slice(0, 200);
    });
    await point('V08', 'LOCAL: Zurück behält Werte, Zeitplan, Aktivierung, Abgleich „Erfolgreich“', async () => {
      await page.getByRole('button', { name: 'Zurück', exact: true }).click();
      await atStep(3).waitFor();
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-acl-preview').waitFor({ timeout: 60000 });
      assert(await page.getByTestId('datasource-mirror').isChecked());
      await page.getByTestId('datasource-next').click();
      await atStep(5).waitFor({ timeout: 60000 });
      await page.locator('select').selectOption('60');
      await shot('local-step5');
      await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-activate').waitFor();
      await shot('local-step6');
      await page.getByTestId('datasource-activate').click();
      await dlg().waitFor({ state: 'detached', timeout: 30000 });
      await row(localName).waitFor();
      await page.waitForFunction(n => {
        const r = [...document.querySelectorAll('[data-testid="datasource-item"]')].find(e => e.textContent.includes(n));
        return /^Erfolgreich/.test(r?.querySelector('[data-testid="datasource-status"]')?.textContent?.trim() || '');
      }, localName, { timeout: 120000, polling: 2000 }).catch(async () => {
        // Liste lädt nicht selbst nach: einmal neu laden.
        await page.getByTestId('admin-tab-datasources').click();
      });
      const st = (await row(localName).getByTestId('datasource-status').innerText()).trim();
      await shot('local-list-active');
      return st;
    });

    await point('V09', 'Aktivierung ok, Start fehlgeschlagen: Teilstand wird gemeldet', async () => {
      const nm = `UI4 Startfehler ${stamp}`;
      await openWizard();
      await fillLocal(nm, `${env.E2E_LOCAL_ROOT}/public`);
      for (let s = 3; s <= 6; s++) { await page.getByTestId('datasource-next').click(); await atStep(s).waitFor({ timeout: 60000 }); }
      await page.route('**/admin/datasources/*/run', r => r.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"simuliert"}' }));
      await page.getByTestId('datasource-activate').click();
      await dlg().waitFor({ state: 'detached', timeout: 30000 });
      const msg = page.getByText(/ist aktiviert, aber der erste Abgleich konnte nicht gestartet werden/);
      await msg.waitFor({ timeout: 5000 });
      assert.equal(await page.getByText(/ist aktiv\. Der erste Abgleich läuft/).count(), 0);
      await page.unroute('**/admin/datasources/*/run');
      await shot('activation-start-failed');
      return (await msg.innerText()).slice(0, 200);
    }, 'simuliert: HTTP 503 auf /run nach echter Aktivierung');

    // ---- SMB vollständig (echt)
    if (env.E2E_SMB_LOCATION && env.E2E_SMB_PASSWORD) {
      const smbName = `UI4 SMB ${stamp}`;
      await point('V08/V10', 'SMB: Verbindung mit Dienstkonto, Secret danach aus Formular entfernt', async () => {
        await openWizard();
        await page.locator('input[type="radio"][value="SMB"]').check();
        await page.getByTestId('datasource-next').click();
        await page.getByTestId('datasource-name').fill(smbName);
        await page.getByTestId('datasource-location').fill(env.E2E_SMB_LOCATION);
        await page.getByLabel('Dienstkonto (nur Lesen)').fill(env.E2E_SMB_USER);
        await page.getByLabel('Passwort', { exact: true }).fill(env.E2E_SMB_PASSWORD);
        await page.getByTestId('datasource-next').click();
        await atStep(3).waitFor({ timeout: 90000 });
        await shot('smb-step3');
        await page.getByRole('button', { name: 'Zurück', exact: true }).click();
        const pw = page.locator('input[type="password"]');
        assert.equal(await pw.inputValue(), '', 'Passwort bleibt im Eingabestate');
        await page.getByTestId('datasource-secret-saved').waitFor();
        await shot('smb-step2-secret-saved');
      });
      await point('V08', 'SMB: NTFS-Rechte, Zeitplan, Aktivierung', async () => {
        await page.getByTestId('datasource-next').click();
        await atStep(3).waitFor({ timeout: 90000 });
        await page.getByTestId('datasource-next').click();
        await page.getByTestId('datasource-acl-preview').waitFor({ timeout: 90000 });
        const acl = (await page.getByTestId('datasource-acl-preview').innerText()).replace(/\s+/g, ' ');
        await shot('smb-step4-acl');
        await page.getByTestId('datasource-next').click();
        await atStep(5).waitFor({ timeout: 90000 });
        await page.getByTestId('datasource-next').click();
        await page.getByTestId('datasource-activate').click();
        await dlg().waitFor({ state: 'detached', timeout: 60000 });
        await row(smbName).waitFor();
        await page.waitForFunction(n => {
          const r = [...document.querySelectorAll('[data-testid="datasource-item"]')].find(e => e.textContent.includes(n));
          return /^Erfolgreich/.test(r?.querySelector('[data-testid="datasource-status"]')?.textContent?.trim() || '');
        }, smbName, { timeout: 120000, polling: 2000 }).catch(() => {});
        await page.getByTestId('admin-tab-datasources').click();
        const st = (await row(smbName).getByTestId('datasource-status').innerText()).trim();
        await shot('smb-list-active');
        return `ACL: ${acl.slice(0, 120)} | Status: ${st}`;
      });
      await point('V10', 'SMB bearbeiten: leeres Feld behält Zugang (Probelauf grün)', async () => {
        await row(smbName).getByTestId('datasource-actions').click();
        await page.getByRole('menuitem', { name: 'Einstellungen', exact: true }).click();
        await dlg().waitFor();
        await page.getByTestId('datasource-secret-saved').waitFor();
        assert.equal(await page.locator('input[type="password"]').inputValue(), '');
        await page.getByLabel('Passwort ändern (optional)').waitFor();
        await page.getByTestId('datasource-next').click();
        await atStep(3).waitFor({ timeout: 90000 });
      });
      await point('V10/V09', 'SMB: falsches Ersatzpasswort wird ehrlich abgelehnt, richtiges ersetzt wieder', async () => {
        await page.getByRole('button', { name: 'Zurück', exact: true }).click();
        await page.locator('input[type="password"]').fill(`falsch-${stamp}`);
        await page.getByTestId('datasource-next').click();
        await page.getByTestId('dialog-error').waitFor({ timeout: 90000 });
        const msg = (await page.getByTestId('dialog-error').innerText()).trim();
        await shot('smb-wrong-secret');
        await page.locator('input[type="password"]').fill(env.E2E_SMB_PASSWORD);
        await page.getByTestId('datasource-next').click();
        await atStep(3).waitFor({ timeout: 90000 });
        assert.equal(await page.getByTestId('dialog-error').count(), 0);
        await page.getByTestId('datasource-cancel').click();
        await page.waitForTimeout(200);
        await forceClose();
        return msg.slice(0, 160);
      });
    }

    await point('V08', 'SharePoint: Formularvariante (keine echte Integration ohne Testtenant)', async () => {
      await openWizard();
      await page.locator('input[type="radio"][value="SHAREPOINT"]').check();
      await page.getByTestId('datasource-next').click();
      await page.getByLabel(/Verzeichnis-ID/).waitFor();
      await page.getByLabel(/Anwendungs-ID/).waitFor();
      await page.getByLabel(/Clientschlüssel/).waitFor();
      await shot('sharepoint-step2');
      await forceClose();
    }, 'nur Formular');

    await point('V11/V09', 'Entfernen-Bestätigung (sm-Dialog): Fokus, Escape, Ergebnis wahrheitsgemäß', async () => {
      // Aufräumen der Testquellen erfolgt getrennt per API; hier zählt das Verhalten des Dialogs.
      const mine = page.getByTestId('datasource-item').filter({ hasText: String(stamp) });
      await page.getByTestId('admin-tab-datasources').click();
      const before = await mine.count();
      await mine.first().getByTestId('datasource-actions').click();
      await page.getByRole('menuitem', { name: 'Entfernen', exact: true }).click();
      await dlg().waitFor();
      await shot('delete-confirm');
      await page.keyboard.press('Escape');
      await dlg().waitFor({ state: 'detached', timeout: 3000 });
      assert.equal((await active()).testid, 'datasource-actions', 'Fokus nicht zurück am Zeilenmenü');
      await mine.first().getByTestId('datasource-actions').click();
      await page.getByRole('menuitem', { name: 'Entfernen', exact: true }).click();
      await dlg().waitFor();
      const del = page.waitForResponse(r => r.request().method() === 'DELETE', { timeout: 15000 }).catch(() => null);
      await dlg().getByRole('button', { name: 'Entfernen', exact: true }).click();
      await Promise.race([dlg().waitFor({ state: 'hidden', timeout: 30000 }), dlg().getByTestId('dialog-error').waitFor({ timeout: 30000 })]);
      const res = await del;
      await page.waitForTimeout(500);
      const after = await mine.count();
      const note = (await page.locator('.ds-note, dialog[open] [data-testid="dialog-error"]').allInnerTexts()).join(' ').trim();
      if (after < before) { assert.match(note, /entfernt/); return `entfernt (HTTP ${res?.status()})`; }
      // Nicht gelöscht: dann darf keine Erfolgsmeldung erscheinen.
      assert.doesNotMatch(note, /entfernt/, 'Erfolg gemeldet, obwohl die Quelle noch da ist');
      assert(note.length > 0, 'Fehlschlag ohne Meldung');
      await shot('delete-failed-message');
      await dlg().getByTestId('confirm-cancel').click();
      return `NICHT entfernt, Meldung: ${note} (HTTP ${res ? res.status() : 'keine Antwort – CORS-Preflight'})`;
    });

    // ---- Viewports / Dunkel (V12)
    const sizes = [[1440, 1000], [900, 640], [768, 1024], [480, 400], [390, 844], [320, 640]];
    for (const scheme of ['light', 'dark']) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.waitForFunction(s => document.documentElement.getAttribute('data-ic-theme') === s, scheme); // Modus "system" folgt prefers-color-scheme
      for (const [w, h] of sizes) {
        await point('V12', `Assistent ${scheme} ${w}×${h}: Rahmen, Aktionen erreichbar, kein Root-Überlauf`, async () => {
          await page.setViewportSize({ width: w, height: h });
          await openWizard();
          await page.locator('input[type="radio"][value="LOCAL"]').check();
          await page.getByTestId('datasource-next').click();
          await page.getByTestId('datasource-name').fill(`Ansicht ${w}`);
          // Jedes Formularfeld muss nach dem Hineinscrollen tatsächlich oben liegen (nicht vom Fuß verdeckt).
          const covered = [];
          for (const f of await dlg().locator('.ds-form input, .ds-form select').all()) {
            await f.scrollIntoViewIfNeeded();
            const hit = await f.evaluate(e => { const r = e.getBoundingClientRect(); const t = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); return t === e || e.contains(t) || t?.closest('label')?.contains(e); });
            if (!hit) covered.push(await f.evaluate(e => e.getAttribute('data-testid') || e.tagName));
          }
          assert.deepEqual(covered, [], `Verdeckte Felder: ${covered.join(', ')}`);
          const box = await dlg().boundingBox();
          assert(!(await rootOverflow()), 'Root-Überlauf');
          const next = page.getByTestId('datasource-next');
          await next.scrollIntoViewIfNeeded();
          const nb = await next.boundingBox();
          assert(nb && nb.y >= 0 && nb.y + nb.height <= h + 1, `Weiter nicht im Viewport: ${JSON.stringify(nb)}`);
          const sizesOk = await page.evaluate(() => [...document.querySelectorAll('dialog[open] button:not([disabled]), dialog[open] input, dialog[open] select, dialog[open] summary')]
            .filter(e => e.offsetParent !== null && e.type !== 'radio' && e.type !== 'checkbox').map(e => { const r = e.getBoundingClientRect(); return { t: e.getAttribute('data-testid') || e.textContent.trim().slice(0, 20) || e.tagName, h: Math.round(r.height) }; }));
          await shot(`wizard-${scheme}-${w}x${h}`);
          if (w <= 480 || h <= 640) { await page.getByTestId('datasource-name').focus(); await shot(`wizard-${scheme}-${w}x${h}-focus`); }
          const small = sizesOk.filter(s => s.h < (w < 768 ? 44 : 32));
          await forceClose();
          return `Dialog ${Math.round(box.width)}×${Math.round(box.height)}; kleine Ziele: ${small.length ? JSON.stringify(small).slice(0, 160) : 'keine'}`;
        });
      }
    }
  } finally {
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(results, null, 2));
    await browser.close();
    const red = results.filter(r => r.status !== 'bestanden').length;
    console.log(JSON.stringify({ summary: { total: results.length, red } }));
    if (red) process.exitCode = 1;
  }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
