// UI-04 Phase 4: Chat/Quelle gegen eine echte QA-Instanz mit echtem Modell.
// ENV: E2E_EMAIL, E2E_PASSWORD, E2E_OUT, E2E_ORG_ID, E2E_SPACE_ID, E2E_KB_ID.
// Optional E2E_BASE, E2E_CHROME, E2E_ANSWER_TIMEOUT_MS (Standard 300000).
// Mit page.route veränderte Fälle sind als "simuliert" gekennzeichnet.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const env = process.env;
for (const k of ['E2E_EMAIL', 'E2E_PASSWORD', 'E2E_OUT', 'E2E_ORG_ID', 'E2E_SPACE_ID', 'E2E_KB_ID']) {
  if (!env[k]) throw new Error(`${k} fehlt.`);
}
const out = env.E2E_OUT;
fs.mkdirSync(out, { recursive: true });
const ANSWER = Number(env.E2E_ANSWER_TIMEOUT_MS || 300000);
const results = [];
const stamp = Date.now();

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'],
    ...(env.E2E_CHROME ? { executablePath: env.E2E_CHROME } : {}) });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'de-DE', acceptDownloads: true });
  const page = await ctx.newPage();
  const shot = async (name, full = false) => {
    await page.evaluate(() => document.fonts.ready);
    assert(!(await page.content()).includes(env.E2E_PASSWORD), `Secret im DOM bei ${name}`);
    await page.screenshot({ path: path.join(out, `${name}.png`), fullPage: full });
  };
  const active = () => page.evaluate(() => {
    const a = document.activeElement;
    return { tag: a?.tagName, testid: a?.getAttribute('data-testid'), label: a?.getAttribute('aria-label'), inPreview: !!a?.closest('#document-source-preview') };
  });
  const rootOverflow = () => page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
  async function point(id, name, fn, mode = 'echt') {
    try { const note = await fn(); results.push({ id, name, mode, status: 'bestanden', ...(note ? { note } : {}) }); }
    catch (e) { results.push({ id, name, mode, status: 'rot', reason: String(e.message).slice(0, 300) });
      try { await shot(`FAIL-${id.replace('/', '-')}-${results.length}`); } catch {} }
    console.log(JSON.stringify(results.at(-1)));
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(results, null, 2));
  }
  const answers = page.getByTestId('chat-msg-assistant');
  const waitAnswerDone = async (count) => {
    await page.waitForFunction(n => document.querySelectorAll('[data-testid="chat-msg-assistant"]').length >= n
      && !document.querySelector('[data-testid="chat-streaming"]'), count, { timeout: ANSWER, polling: 500 });
  };
  const send = async text => {
    await page.getByTestId('chat-prompt').fill(text);
    await page.getByTestId('chat-send').click();
  };
  const preview = page.locator('#document-source-preview');

  try {
    await page.goto(env.E2E_BASE || 'http://localhost:1420');
    await page.getByTestId('login-email').fill(env.E2E_EMAIL);
    await page.getByTestId('login-password').fill(env.E2E_PASSWORD);
    await page.getByTestId('login-submit').click();
    await page.getByTestId('sidebar-org').click();
    await page.getByTestId(`sidebar-org-${env.E2E_ORG_ID}`).click();
    await page.getByTestId(`sidebar-space-${env.E2E_SPACE_ID}`).click();
    await page.getByTestId('sidebar-nav-chat').click();

    await point('V04', 'Hauptbasiswechsel erhält Entwurf und startet neuen Wissens-Chat', async () => {
      const created = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/chat/conversations'));
      await page.getByTestId('chat-new-plain').click();
      assert((await created).ok());
      await page.getByTestId('chat-ready').waitFor();
      const draft = `Entwurf ${stamp}: bleibt dieser Text erhalten?`;
      await page.getByTestId('chat-prompt').fill(draft);
      const before = await page.getByTestId('chat-conv-item').count();
      const created2 = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/chat/conversations'));
      await page.getByTestId('chat-kb-select').selectOption(String(env.E2E_KB_ID));
      const body = await (await created2).json();
      assert.equal(body.knowledgeBaseId, Number(env.E2E_KB_ID), 'neuer Chat nicht an Basis gebunden');
      await page.getByTestId('chat-ctx-kb').waitFor();
      assert.equal(await page.getByTestId('chat-prompt').inputValue(), draft, 'Entwurf verloren');
      assert.equal((await active()).testid, 'chat-prompt', 'Fokus nicht im Composer');
      const after = await page.getByTestId('chat-conv-item').count();
      await shot('chat-kb-switch');
      return `Unterhaltungen ${before} → ${after}; Kontext: ${(await page.getByTestId('chat-ctx-kb').innerText()).trim()}`;
    });
    await point('V04', 'Zusatzbasen und Anhänge getrennt von der Hauptbasis bedienbar', async () => {
      // Voraussetzung: zweite Basis im Bereich (E2E_EXTRA_KB_NAME, per Seed angelegt).
      await page.getByTestId('chat-context-add').waitFor();
      if (!env.E2E_EXTRA_KB_NAME) return 'keine zweite Basis konfiguriert – Zusatzbasis nicht geprüft';
      const mainBefore = await page.getByTestId('chat-kb-select').inputValue();
      await page.getByTestId('chat-context-add').click();
      await page.getByTestId('chat-extra-kbs').press('ArrowRight');
      await page.getByTestId('chat-extra-kb-item').filter({ hasText: env.E2E_EXTRA_KB_NAME }).click();
      await page.keyboard.press('Escape'); await page.keyboard.press('Escape'); // Untermenü und Plusmenü schließen
      const chip = page.getByTestId('chat-extra-kb-chip').filter({ hasText: env.E2E_EXTRA_KB_NAME });
      await chip.waitFor();
      assert.equal(await page.getByTestId('chat-kb-select').inputValue(), mainBefore, 'Zusatzbasis änderte Hauptbasis');
      await shot('chat-extra-kb-chip');
      await chip.getByRole('button').click();
      await chip.waitFor({ state: 'detached', timeout: 3000 });
      assert.equal(await page.getByTestId('chat-kb-select').inputValue(), mainBefore);
      return `Zusatzbasis „${env.E2E_EXTRA_KB_NAME}“ hinzugefügt/entfernt, Hauptbasis unverändert`;
    });

    await point('V02', 'Echte lange Antwort: Markdown, Tabelle, Code, Quellen, Lesetypografie', async () => {
      await send('Wie oft wird das Pilotgerät inspiziert, wer ist verantwortlich und wie läuft die Inspektion ab? '
        + 'Antworte mit einer kurzen Einleitung, einer Markdown-Tabelle (Spalten: Punkt, Angabe) und einem kurzen Python-Codeblock, '
        + 'der aus einem Datum die nächste Inspektion berechnet.');
      await page.getByTestId('chat-streaming').waitFor({ timeout: 60000 });
      await shot('chat-streaming');
      await waitAnswerDone(1);
      const a = answers.last();
      const text = await a.innerText();
      assert.match(text, /37/, 'Frist 37 Tage fehlt');
      const tables = await a.locator('table').count(), pres = await a.locator('pre').count();
      const cites = await a.getByTestId('chat-citation').count();
      assert(cites > 0, 'keine Quellen');
      const typo = await a.locator('.acontent').evaluate(e => { const c = getComputedStyle(e); return `${c.fontSize}/${c.lineHeight} ${c.fontFamily.split(',')[0]}`; });
      if (pres) {
        await page.mouse.move(0, 0); // kein Hover: Knopf muss trotzdem sichtbar sein (Touch)
        const overlap = await a.locator('pre').first().evaluate(p => { const b = p.querySelector('.code-copy'); if (!b) return 'kein Kopierknopf'; if (+getComputedStyle(b).opacity < 1) return 'unsichtbar ohne Hover'; const r = b.getBoundingClientRect(), t = p.getBoundingClientRect(); return r.bottom <= t.top + parseFloat(getComputedStyle(p).paddingTop) ? 'ok' : 'überdeckt'; });
        assert.equal(overlap, 'ok', `Kopierknopf: ${overlap}`);
        await ctx.grantPermissions(['clipboard-read', 'clipboard-write']);
        await a.getByTestId('chat-code-copy').first().click();
        const clip = await page.evaluate(() => navigator.clipboard.readText());
        assert.match(clip, /def |import |timedelta|print/, `Zwischenablage: ${clip.slice(0, 60)}`);
      }
      await a.scrollIntoViewIfNeeded();
      await shot('chat-answer-1440', true);
      return `Tabellen ${tables}, Codeblöcke ${pres}, Quellen ${cites}, Text ${typo}, Länge ${text.length}`;
    });

    await point('V02', 'Anhang im Chat: Datei-Kontext wird in echter Antwort genutzt', async () => {
      const file = path.join(out, `Anhang-UI4-${stamp}.txt`);
      fs.writeFileSync(file, 'Ansprechpartnerin für das Pilotgerät ist Frau Testfeld aus dem Team Betrieb. Telefon-Durchwahl 4711.\n');
      await page.getByTestId('chat-attach-input').setInputFiles(file);
      await page.getByTestId('chat-attachment-chip').waitFor({ timeout: 30000 });
      await shot('chat-attachment-chip');
      const n = await answers.count();
      await send('Wer ist laut dem Anhang Ansprechpartnerin für das Pilotgerät, und welche Durchwahl hat sie?');
      await waitAnswerDone(n + 1);
      const text = await answers.last().innerText();
      assert.match(text, /Testfeld|4711/, `Anhang nicht genutzt: ${text.slice(0, 160)}`);
      fs.unlinkSync(file);
      return text.replace(/\s+/g, ' ').slice(0, 140);
    });

    await point('V03', 'Feedback hilfreich: Zustand gesetzt und wieder zurückgenommen', async () => {
      const up = answers.last().getByTestId('chat-feedback-up');
      const r1 = page.waitForResponse(r => /feedback/.test(r.url()));
      await up.click(); assert((await r1).ok());
      await page.waitForFunction(() => [...document.querySelectorAll('[data-testid="chat-feedback-up"]')].at(-1)?.getAttribute('aria-pressed') === 'true');
      const r2 = page.waitForResponse(r => /feedback/.test(r.url()));
      await up.click(); assert((await r2).ok());
      await page.waitForFunction(() => [...document.querySelectorAll('[data-testid="chat-feedback-up"]')].at(-1)?.getAttribute('aria-pressed') === 'false');
    });
    await point('V03', 'Export als DOCX und PDF liefert Dateien', async () => {
      const res = [];
      for (const fmt of ['docx', 'pdf']) {
        await answers.first().getByTestId('chat-msg-export').click();
        const dl = page.waitForEvent('download', { timeout: 60000 });
        await page.getByTestId(`chat-export-${fmt}`).click();
        const d = await dl; const p = await d.path(); const size = fs.statSync(p).size;
        assert(size > 1000, `${fmt} nur ${size} Bytes`);
        const head = fs.readFileSync(p).subarray(0, 4).toString('latin1');
        assert(fmt === 'pdf' ? head === '%PDF' : head.startsWith('PK'), `${fmt}: falscher Dateikopf`);
        res.push(`${d.suggestedFilename()} ${size} B`);
      }
      assert.equal(await page.getByTestId('chat-notice').count(), 0);
      return res.join(', ');
    });
    await point('V03', 'Stopp während Streaming beendet die Antwort, Composer wieder bereit', async () => {
      const n = await answers.count();
      await send('Erkläre ausführlich in mindestens zehn Absätzen, warum regelmäßige Inspektionen wichtig sind.');
      await page.getByTestId('chat-stop').waitFor({ timeout: 60000 });
      // Erst stoppen, wenn tatsächlich Text gestreamt wurde, sonst ist "bisheriger Text" leer.
      await page.waitForFunction(() => (document.querySelector('[data-testid="chat-streaming"] .streamtext')?.textContent || '').trim().length > 60, null, { timeout: ANSWER, polling: 200 });
      const partial = (await page.locator('[data-testid="chat-streaming"] .streamtext').innerText()).trim();
      await page.getByTestId('chat-stop').click();
      await page.getByTestId('chat-streaming').waitFor({ state: 'detached', timeout: 30000 });
      await page.getByTestId('chat-send').waitFor();
      await page.getByTestId('chat-notice').waitFor();
      const notice = (await page.getByTestId('chat-notice').innerText()).trim();
      await page.waitForFunction(k => document.querySelectorAll('[data-testid="chat-msg-assistant"]').length > k, n, { timeout: 15000 });
      const last = (await answers.last().innerText()).replace(/\s+/g, ' ');
      assert(last.includes(partial.replace(/\s+/g, ' ').slice(0, 30)), `gespeicherter Text weicht ab: ${last.slice(0, 80)}`);
      assert.match(last, /gestoppt/, 'Status „gestoppt“ fehlt');
      await shot('chat-after-stop');
      return `Antworten ${n} → ${await answers.count()}; Hinweis: ${notice}; gespeichert: ${last.slice(0, 80)}`;
    });
    await point('V03', 'Neu versuchen sendet die letzte Frage erneut und schließt ab', async () => {
      const n = await answers.count();
      await answers.last().getByTestId('chat-msg-retry').click();
      await page.getByTestId('chat-streaming').waitFor({ timeout: 60000 });
      await page.getByTestId('chat-stop').click(); // Länge begrenzen, Rückkehr in Bereitschaft prüfen
      await page.getByTestId('chat-streaming').waitFor({ state: 'detached', timeout: 30000 });
      return `Antworten vorher ${n}, nachher ${await answers.count()}`;
    });
    await point('V03', 'Vorlagen im leeren Chat füllen den Composer', async () => {
      await page.getByTestId('chat-new-plain').click();
      await page.getByTestId('chat-ready').waitFor();
      const ex = page.getByTestId('chat-example').first();
      const label = (await ex.innerText()).trim();
      await ex.click();
      assert.equal((await page.getByTestId('chat-prompt').inputValue()).trim(), label);
      await page.getByTestId('chat-prompt').fill('');
      // zurück zur Unterhaltung mit Antwort
      await page.getByTestId('chat-conv-item').nth(1).click();
      await answers.first().waitFor();
      return label;
    });

    // ---- Quelle (V05)
    await point('V05', 'Zitat öffnet Vorschau an der zitierten Stelle (nichtmodal ≥1440)', async () => {
      await page.setViewportSize({ width: 1440, height: 1000 });
      const chip = answers.first().getByTestId('chat-citation').first();
      await chip.click();
      await preview.waitFor();
      await page.getByTestId('document-preview-highlight').waitFor({ timeout: 20000 });
      const modal = await preview.evaluate(d => d.matches(':modal'));
      assert.equal(modal, false, 'bei 1440 modal');
      const a = await active(); assert(a.inPreview, `Fokus nicht im Panel: ${JSON.stringify(a)}`);
      const hl = await page.getByTestId('document-preview-highlight').innerText();
      const visible = await page.getByTestId('document-preview-highlight').evaluate(e => { const r = e.getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight; });
      assert(!(await rootOverflow()), 'Root-Überlauf');
      const chatW = await page.getByTestId('chat-thread').evaluate(e => Math.round(e.getBoundingClientRect().width));
      await shot('source-1440-nonmodal');
      return `markiert: „${hl.slice(0, 60)}“, im Sichtbereich ${visible}, Chatbreite ${chatW}px`;
    });
    await point('V05', 'Nichtmodal: Tab in den Chat möglich, Escape dort schließt Panel nicht', async () => {
      await page.getByTestId('chat-prompt').focus();
      await page.keyboard.press('Escape');
      await page.waitForTimeout(200);
      assert.equal(await preview.count(), 1, 'Escape im Composer schloss Panel');
      await page.getByRole('button', { name: 'Dokumentvorschau schließen' }).focus();
      await page.keyboard.press('Escape');
      await preview.waitFor({ state: 'detached', timeout: 3000 });
      const a = await active();
      assert.equal(a.testid, 'chat-citation', `Fokus nach Schließen: ${JSON.stringify(a)}`);
    });
    await point('V05', 'Download des Originals liefert Datei', async () => {
      await answers.first().getByTestId('chat-citation').first().click();
      await preview.waitFor();
      const href = await page.getByTestId('chat-source-download').getAttribute('href');
      const r = await page.evaluate(async h => { const x = await fetch(h, { credentials: 'include' }); return { s: x.status, n: (await x.arrayBuffer()).byteLength }; }, href);
      assert.equal(r.s, 200); assert(r.n > 0);
      return `HTTP ${r.s}, ${r.n} Bytes`;
    });
    await point('V05/V06', 'Resize 1440→1200 macht Panel modal, Fokus bleibt im Panel; zurück nichtmodal', async () => {
      await page.getByRole('button', { name: 'Dokumentvorschau schließen' }).focus();
      await page.setViewportSize({ width: 1200, height: 900 });
      await page.waitForTimeout(300);
      assert.equal(await preview.evaluate(d => d.matches(':modal')), true, 'bei 1200 nicht modal');
      assert((await active()).inPreview, 'Fokus verloren nach Resize');
      await shot('source-1200-modal');
      for (let i = 0; i < 12; i++) { await page.keyboard.press('Tab'); const a = await active(); assert(a.inPreview || a.tag === 'BODY', `Tab verlässt modales Panel: ${JSON.stringify(a)}`); }
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.waitForTimeout(300);
      assert.equal(await preview.evaluate(d => d.matches(':modal')), false);
      assert((await active()).inPreview, 'Fokus verloren nach Rück-Resize');
    });
    await point('V05', 'Ladezustand sichtbar', async () => {
      await page.getByRole('button', { name: 'Dokumentvorschau schließen' }).click();
      await page.route('**/preview?*', async r => { await new Promise(f => setTimeout(f, 2000)); await r.continue(); });
      await answers.first().getByTestId('chat-citation').first().click();
      await page.getByText('Die Textstelle wird geladen').waitFor({ timeout: 2000 });
      await shot('source-loading');
      await page.getByTestId('document-preview-highlight').waitFor({ timeout: 20000 });
      await page.unroute('**/preview?*');
    }, 'simuliert: echte Antwort, künstlich 2 s verzögert');
    for (const [code, label] of [[404, 'nicht mehr vorhanden'], [403, 'kein Zugriff']]) {
      await point('V05', `Fehlerzustand HTTP ${code} (${label}): sichere Meldung mit Wiederholen`, async () => {
        await page.getByRole('button', { name: 'Dokumentvorschau schließen' }).click();
        await page.route('**/preview?*', r => r.fulfill({ status: code, contentType: 'application/json', body: '{}' }));
        await answers.first().getByTestId('chat-citation').first().click();
        const err = preview.getByRole('alert');
        await err.waitFor({ timeout: 10000 });
        const t = (await err.innerText()).trim();
        await shot(`source-error-${code}`);
        await page.unroute('**/preview?*');
        await preview.getByRole('button', { name: 'Erneut versuchen' }).click();
        await page.getByTestId('document-preview-highlight').waitFor({ timeout: 20000 });
        return t.replace(/\s+/g, ' ');
      }, `simuliert: HTTP ${code}`);
    }

    // ---- Ansichten (V06)
    const sizes = [[1440, 1000], [1200, 900], [900, 640], [768, 1024], [480, 400], [390, 844], [320, 640]];
    for (const scheme of ['light', 'dark']) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.waitForFunction(s => document.documentElement.getAttribute('data-ic-theme') === s, scheme); // Modus "system" folgt prefers-color-scheme
      for (const [w, h] of sizes) {
        await point('V06', `Chat+Quelle ${scheme} ${w}×${h}`, async () => {
          if (await preview.count()) await page.getByRole('button', { name: 'Dokumentvorschau schließen' }).click();
          await page.setViewportSize({ width: w, height: h });
          await page.waitForTimeout(250);
          assert(!(await rootOverflow()), 'Root-Überlauf Chat');
          const inner = await page.evaluate(() => ['.chat-view', '.chat-thread', '.chat-transcript', '.msg-composer'].map(s => { const e = document.querySelector(s); const r = e.getBoundingClientRect(); return { s, over: e.scrollWidth - e.clientWidth, right: Math.round(r.right) }; }).filter(x => x.over > 1 || x.right > innerWidth + 1));
          assert.deepEqual(inner, [], `Innerer Überlauf: ${JSON.stringify(inner)}`);
          const comp0 = await page.getByTestId('chat-send').boundingBox();
          const needsScroll = !(comp0 && comp0.y + comp0.height <= h + 1 && comp0.y >= 0);
          await page.getByTestId('chat-send').scrollIntoViewIfNeeded();
          const comp = await page.getByTestId('chat-send').boundingBox();
          assert(comp && comp.y + comp.height <= h + 1 && comp.y >= 0, `Senden nicht erreichbar: ${JSON.stringify(comp)}`);
          const prompt = await page.getByTestId('chat-prompt').boundingBox();
          assert(prompt && prompt.y >= 0 && prompt.y < h, 'Eingabefeld nicht erreichbar');
          if (needsScroll) await shot(`chat-${scheme}-${w}x${h}-composer`);
          await shot(`chat-${scheme}-${w}x${h}`);
          const chip = answers.first().getByTestId('chat-citation').first();
          await chip.scrollIntoViewIfNeeded();
          await chip.click();
          await page.getByTestId('document-preview-highlight').waitFor({ timeout: 20000 });
          assert(!(await rootOverflow()), 'Root-Überlauf mit Quelle');
          const pb = await preview.boundingBox();
          assert(pb.width <= w + 1, `Panel breiter als Viewport: ${pb.width}`);
          const close = await page.getByRole('button', { name: 'Dokumentvorschau schließen' }).boundingBox();
          assert(close.y >= 0 && close.x + close.width <= w + 1, 'Schließen nicht erreichbar');
          const modal = await preview.evaluate(d => d.matches(':modal'));
          await shot(`source-${scheme}-${w}x${h}`);
          await page.keyboard.press('Escape');
          if (await preview.count()) await page.getByRole('button', { name: 'Dokumentvorschau schließen' }).click();
          await preview.waitFor({ state: 'detached', timeout: 3000 });
          return `Panel ${Math.round(pb.width)}px, ${modal ? 'modal' : 'nichtmodal'}, Schließen ${Math.round(close.width)}×${Math.round(close.height)}${needsScroll ? ', Composer erst nach Scrollen' : ''}`;
        });
      }
    }
    await page.emulateMedia({ colorScheme: 'light' });
    await point('V06', 'Drehen Quer 844×390 → Hoch 390×844: Composer ohne Scrollen sichtbar, kein Restversatz', async () => {
      await page.setViewportSize({ width: 844, height: 390 });
      await page.waitForTimeout(250);
      await page.getByTestId('chat-send').scrollIntoViewIfNeeded();
      await page.setViewportSize({ width: 390, height: 844 });
      await page.waitForTimeout(250);
      const b = await page.getByTestId('chat-send').boundingBox();
      assert(b.y >= 0 && b.y + b.height <= 844, `Senden nach Drehen bei y=${Math.round(b.y)}`);
      const extra = await page.evaluate(() => { const v = document.querySelector('.chat-view'); return v.scrollHeight - v.clientHeight; });
      assert(extra <= 1, `Chatbereich ${extra}px höher als sichtbar`);
      await shot('chat-rotate-390x844');
      await page.setViewportSize({ width: 1440, height: 1000 });
    });

  } finally {
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(results, null, 2));
    await browser.close();
    const red = results.filter(r => r.status !== 'bestanden').length;
    console.log(JSON.stringify({ summary: { total: results.length, red } }));
    if (red) process.exitCode = 1;
  }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
