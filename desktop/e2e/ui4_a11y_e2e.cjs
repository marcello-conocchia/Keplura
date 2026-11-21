// UI-04 Phase 4 (V13): gerenderte Kontraste, Zielgrößen, Zoom-Reflow,
// reduced-motion und Tastaturfokus an der echten QA-App.
// ENV: E2E_EMAIL, E2E_PASSWORD, E2E_OUT, E2E_ORG_ID, E2E_SPACE_ID. Optional E2E_BASE, E2E_CHROME.
// Kontraste stammen aus getComputedStyle der gerenderten Elemente (Vordergrund
// gegen den effektiv darunterliegenden, mit Alpha verrechneten Hintergrund).
// Zoom wird als CSS-Viewport-Äquivalent geprüft (1440 px bei 200 % = 720 CSS px,
// 1280 px bei 400 % = 320 CSS px), nicht über die Zoom-Steuerung eines Browsers.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const env = process.env;
for (const k of ['E2E_EMAIL', 'E2E_PASSWORD', 'E2E_OUT', 'E2E_ORG_ID', 'E2E_SPACE_ID']) if (!env[k]) throw new Error(`${k} fehlt.`);
const out = env.E2E_OUT;
fs.mkdirSync(out, { recursive: true });
const results = [];
const details = {};

// Läuft im Browser: alle sichtbaren Textträger in root prüfen.
function contrastAudit(rootSel) {
  const parse = c => { const m = c.match(/rgba?\(([^)]+)\)/); if (!m) return null; const p = m[1].split(/[ ,/]+/).filter(Boolean).map(Number); return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 }; };
  const lum = ({ r, g, b }) => { const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
  const over = (top, bot) => ({ r: top.r * top.a + bot.r * (1 - top.a), g: top.g * top.a + bot.g * (1 - top.a), b: top.b * top.a + bot.b * (1 - top.a), a: 1 });
  const bgOf = el => {
    const stack = [];
    for (let e = el; e; e = e.parentElement) {
      const c = parse(getComputedStyle(e).backgroundColor);
      if (c && c.a > 0) { stack.push(c); if (c.a >= 1) break; }
      if (e.tagName === 'DIALOG' && e.open) { const c2 = parse(getComputedStyle(e).backgroundColor); if (c2 && c2.a >= 1) break; }
    }
    let base = { r: 255, g: 255, b: 255, a: 1 };
    for (const r of [document.documentElement, document.body]) { const c = parse(getComputedStyle(r).backgroundColor); if (c && c.a >= 1) base = c; }
    for (let i = stack.length - 1; i >= 0; i--) base = over(stack[i], base);
    return base;
  };
  const root = document.querySelector(rootSel) || document.body;
  const seen = new Set(), rows = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (!n.textContent.trim()) continue;
    const el = n.parentElement; if (!el || seen.has(el)) continue; seen.add(el);
    const r = el.getBoundingClientRect(); if (!r.width || !r.height) continue;
    const cs = getComputedStyle(el); if (cs.visibility === 'hidden' || +cs.opacity === 0) continue;
    if (el.closest('[disabled],[aria-disabled="true"]') || el.closest('[inert]')) continue; // WCAG 1.4.3: inaktive Bedienelemente ausgenommen
    if (el.closest('dialog') && !el.closest('dialog').open) continue;
    const fg0 = parse(cs.color); if (!fg0) continue;
    const bg = bgOf(el), fg = fg0.a < 1 ? over(fg0, bg) : fg0;
    const L1 = lum(fg), L2 = lum(bg), ratio = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
    const size = parseFloat(cs.fontSize), bold = +cs.fontWeight >= 700;
    const large = size >= 24 || (bold && size >= 18.66);
    const min = large ? 3 : 4.5;
    rows.push({ text: n.textContent.trim().slice(0, 40), ratio: Math.round(ratio * 100) / 100, min, size, fg: cs.color, bg: `rgb(${Math.round(bg.r)}, ${Math.round(bg.g)}, ${Math.round(bg.b)})` });
  }
  return { checked: rows.length, failures: rows.filter(x => x.ratio < x.min), lowest: rows.sort((a, b) => a.ratio - b.ratio).slice(0, 5) };
}
function targetAudit(min) {
  const sel = 'button, a[href], input:not([type=hidden]), select, textarea, summary, [role=button], [role=menuitem], [role=tab]';
  const rows = [];
  for (const e of document.querySelectorAll(sel)) {
    const r = e.getBoundingClientRect(); if (!r.width || !r.height) continue;
    if (getComputedStyle(e).visibility === 'hidden') continue;
    if (e.closest('dialog') && !e.closest('dialog').open) continue;
    if (e.matches('input[type=radio],input[type=checkbox]')) { const l = e.closest('label'); if (l) { const lr = l.getBoundingClientRect(); if (lr.height >= min) continue; } }
    if (e.closest('p, li')?.tagName === 'P' && e.tagName === 'A') continue; // Inline-Links im Fließtext: WCAG 2.5.8 Ausnahme
    if (r.width < min || r.height < min) rows.push({ t: e.getAttribute('data-testid') || e.getAttribute('aria-label') || e.textContent.trim().slice(0, 24) || e.tagName, w: Math.round(r.width), h: Math.round(r.height) });
  }
  return rows;
}

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'], ...(env.E2E_CHROME ? { executablePath: env.E2E_CHROME } : {}) });
  const ctx = await browser.newContext({ locale: 'de-DE', viewport: { width: 1440, height: 1000 }, locale: 'de-DE' });
  const page = await ctx.newPage();
  const shot = name => page.screenshot({ path: path.join(out, `${name}.png`) });
  async function point(id, name, fn, mode = 'echt') {
    try { const note = await fn(); results.push({ id, name, mode, status: 'bestanden', ...(note ? { note } : {}) }); }
    catch (e) { results.push({ id, name, mode, status: 'rot', reason: String(e.message).slice(0, 400) }); await shot(`FAIL-${results.length}`).catch(() => {}); }
    console.log(JSON.stringify(results.at(-1)));
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(results, null, 2));
    fs.writeFileSync(path.join(out, 'details.json'), JSON.stringify(details, null, 2));
  }
  const setScheme = async s => { await page.emulateMedia({ colorScheme: s }); await page.waitForFunction(x => document.documentElement.getAttribute('data-ic-theme') === x, s); await page.waitForTimeout(800); /* Farbübergänge abwarten, sonst Mischfarben */ };
  const openAnsweredChat = async () => {
    await page.getByTestId('sidebar-nav-chat').click();
    const items = page.getByTestId('chat-conv-item');
    await items.first().waitFor();
    for (let i = 0; i < await items.count(); i++) {
      await items.nth(i).click(); await page.waitForTimeout(400);
      if (await page.getByTestId('chat-citation').count()) return;
    }
    throw new Error('keine Unterhaltung mit Quellen gefunden (zuerst ui4_chat_e2e laufen lassen)');
  };
  try {
    await page.goto(env.E2E_BASE || 'http://localhost:1420');
    await page.getByTestId('login-email').fill(env.E2E_EMAIL);
    await page.getByTestId('login-password').fill(env.E2E_PASSWORD);
    await page.getByTestId('login-submit').click();
    await page.getByTestId('sidebar-org').click();
    await page.getByTestId(`sidebar-org-${env.E2E_ORG_ID}`).click();
    await page.getByTestId(`sidebar-space-${env.E2E_SPACE_ID}`).click();
    await openAnsweredChat();

    for (const scheme of ['light', 'dark']) {
      await setScheme(scheme);
      await point('V13', `Kontrast Chat mit Antwort (${scheme})`, async () => {
        const r = await page.evaluate(contrastAudit, '#app');
        details[`contrast-chat-${scheme}`] = r;
        assert.equal(r.failures.length, 0, `${r.failures.length} Unterschreitungen: ${JSON.stringify(r.failures.slice(0, 4))}`);
        return `${r.checked} Textträger, niedrigster ${r.lowest[0]?.ratio}:1 („${r.lowest[0]?.text}“)`;
      });
      await point('V13', `Kontrast Quellenpanel (${scheme})`, async () => {
        await page.getByTestId('chat-citation').filter({ hasText: 'Pilot-Handbuch.md' }).first().click();
        await page.getByTestId('document-preview-highlight').waitFor({ timeout: 20000 });
        const r = await page.evaluate(contrastAudit, '#document-source-preview');
        details[`contrast-source-${scheme}`] = r;
        await page.getByRole('button', { name: 'Dokumentvorschau schließen' }).click();
        assert.equal(r.failures.length, 0, `${r.failures.length} Unterschreitungen: ${JSON.stringify(r.failures.slice(0, 4))}`);
        return `${r.checked} Textträger, niedrigster ${r.lowest[0]?.ratio}:1 („${r.lowest[0]?.text}“)`;
      });
      await point('V13', `Kontrast Assistent Schritt 2 und ACL-Warnung (${scheme})`, async () => {
        await page.getByTestId('sidebar-nav-admin').click();
        await page.getByTestId('admin-tab-datasources').click();
        await page.getByTestId('datasource-create').click();
        await page.locator('input[type="radio"][value="LOCAL"]').check();
        const r1 = await page.evaluate(contrastAudit, 'dialog[open]');
        await page.getByTestId('datasource-next').click();
        const r2 = await page.evaluate(contrastAudit, 'dialog[open]');
        // ACL-Warnung ohne Speichern sichtbar machen: Schritt 4 nur über echten Probelauf erreichbar,
        // daher Warnungsfarbe direkt am Token-Paar der Komponente messen.
        const warn = await page.evaluate(() => { const p = document.createElement('p'); p.className = 'ds-warn'; p.textContent = 'Messprobe'; document.querySelector('dialog[open] .ds-form').appendChild(p); return true; });
        const r3 = await page.evaluate(contrastAudit, 'dialog[open] .ds-form');
        await page.evaluate(() => document.querySelector('dialog[open] .ds-form .ds-warn')?.remove());
        details[`contrast-wizard-${scheme}`] = { step1: r1, step2: r2, warn: r3.lowest };
        await page.getByTestId('datasource-cancel').click();
        if (await page.getByTestId('dialog-discard').count()) await page.getByTestId('dialog-discard').click();
        const fails = [...r1.failures, ...r2.failures, ...r3.failures];
        assert.equal(fails.length, 0, `${fails.length} Unterschreitungen: ${JSON.stringify(fails.slice(0, 4))}`);
        return `${r1.checked + r2.checked} Textträger, Warnung ${r3.lowest.find(x => x.text === 'Messprobe')?.ratio}:1 (Probeelement mit Komponentenklasse)`;
      });
      await openAnsweredChat();
    }
    await setScheme('light');

    await point('V13', 'Zielgrößen Desktop 1440 (≥24 px, WCAG 2.5.8)', async () => {
      const small = await page.evaluate(targetAudit, 24);
      details.targets1440 = small;
      assert.equal(small.length, 0, JSON.stringify(small.slice(0, 8)));
    });
    await point('V13', 'Zielgrößen Handy 390 Chat (≥44 px Vertrag)', async () => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.waitForTimeout(300);
      const small = await page.evaluate(targetAudit, 44);
      details.targets390 = small;
      await page.setViewportSize({ width: 1440, height: 1000 });
      assert.equal(small.length, 0, `${small.length} kleiner 44 px: ${JSON.stringify(small.slice(0, 8))}`);
    });

    for (const [label, w, h] of [['200 % von 1440×1000', 720, 500], ['400 % von 1280×1024 (WCAG 1.4.10, 320 CSS px)', 320, 256]]) {
      await point('V13', `Zoom-Reflow ${label}: Chat und Quelle ohne horizontalen Seitenüberlauf`, async () => {
        const z = await browser.newContext({ locale: 'de-DE', viewport: { width: w, height: h }, deviceScaleFactor: 2, locale: 'de-DE', storageState: await ctx.storageState() });
        const p = await z.newPage();
        try {
          await p.goto(env.E2E_BASE || 'http://localhost:1420');
          await p.getByTestId('chat-view').waitFor({ timeout: 20000 });
          const tag = label.startsWith('200') ? '200' : '400';
          await p.screenshot({ path: path.join(out, `zoom-${tag}-chat.png`) });
          const o1 = await p.evaluate(() => document.documentElement.scrollWidth - innerWidth);
          let note = '';
          // Schmale Ansicht: Navigation liegt im Menü; Organisation/Bereich wählen und Unterhaltung mit Quellen öffnen.
          const menu = async () => { const b = p.getByTestId('mobile-menu-open'); if (await b.isVisible() && (await b.getAttribute('aria-expanded')) !== 'true') await b.click(); };
          await menu();
          await p.getByTestId('sidebar-org').click();
          await p.getByTestId(`sidebar-org-${env.E2E_ORG_ID}`).click();
          const items = p.getByTestId('chat-conv-item');
          for (let i = 0; i < 8; i++) {
            await menu();
            if (!(await items.nth(i).count())) break;
            await items.nth(i).click(); await p.waitForTimeout(500);
            if (await p.getByTestId('chat-citation').filter({ hasText: 'Pilot-Handbuch.md' }).count()) break;
          }
          if (await p.getByTestId('chat-citation').count()) {
            await p.getByTestId('chat-citation').filter({ hasText: 'Pilot-Handbuch.md' }).first().scrollIntoViewIfNeeded();
            await p.getByTestId('chat-citation').filter({ hasText: 'Pilot-Handbuch.md' }).first().click();
            await p.getByTestId('document-preview-highlight').waitFor({ timeout: 20000 });
            await p.screenshot({ path: path.join(out, `zoom-${tag}-source.png`) });
            note = 'mit Quelle';
          }
          const o2 = await p.evaluate(() => Math.max(document.documentElement.scrollWidth - innerWidth, ...['.chat-view', '.chat-thread', '.chat-transcript'].map(s => { const e = document.querySelector(s); return e ? Math.max(e.scrollWidth - e.clientWidth, e.getBoundingClientRect().right - innerWidth) : 0; })));
          assert(o1 <= 0 && o2 <= 0, `Überlauf ${o1}/${o2} px`);
          return note || 'Startansicht';
        } finally { await p.screenshot({ path: path.join(out, `zoom-${w}-last.png`) }).catch(() => {}); await z.close(); }
      }, 'CSS-Viewport-Äquivalent');
    }

    await point('V13', 'prefers-reduced-motion: Übergänge der Aktionen 0 ms', async () => {
      await page.emulateMedia({ reducedMotion: 'reduce' });
      const d = await page.evaluate(() => [...document.querySelectorAll('.ic-action, .msg-action, .src-chip')].slice(0, 20).map(e => getComputedStyle(e).transitionDuration));
      await page.emulateMedia({ reducedMotion: 'no-preference' });
      const d2 = await page.evaluate(() => getComputedStyle(document.querySelector('.ic-action')).transitionDuration);
      assert(d.length && d.every(x => x.split(',').every(v => parseFloat(v) === 0)), JSON.stringify(d));
      return `${d.length} Elemente 0 s; ohne Einstellung ${d2}`;
    });

    await point('V13', 'Tastatur: Tab-Folge bis zum Composer, jedes Ziel mit sichtbarem Fokus', async () => {
      await page.getByTestId('sidebar-nav-chat').focus();
      const missing = [], seen = new Set();
      let steps = 0;
      for (let i = 0; i < 250 && !seen.has('chat-prompt'); i++) { steps = i + 1;
        await page.keyboard.press('Tab');
        const f = await page.evaluate(() => {
          const a = document.activeElement; if (!a || a === document.body) return null;
          const cs = getComputedStyle(a);
          const visible = (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) >= 2) || (cs.boxShadow && cs.boxShadow !== 'none');
          return { t: a.getAttribute('data-testid') || a.getAttribute('aria-label') || a.textContent.trim().slice(0, 24) || a.tagName, visible, fv: a.matches(':focus-visible') };
        });
        if (!f) continue;
        seen.add(f.t);
        if (!f.visible) missing.push(f.t);
      }
      details.keyboard = { seen: [...seen], missing };
      assert(seen.has('chat-prompt'), 'Composer per Tab nicht erreicht');
      assert.equal(missing.length, 0, `ohne sichtbaren Fokus: ${[...new Set(missing)].join(', ')}`);
      return `${seen.size} unterschiedliche Ziele, Composer nach ${steps} Tab-Schritten`;
    });
  } finally {
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(results, null, 2));
    fs.writeFileSync(path.join(out, 'details.json'), JSON.stringify(details, null, 2));
    await browser.close();
    const red = results.filter(r => r.status !== 'bestanden').length;
    console.log(JSON.stringify({ summary: { total: results.length, red } }));
    if (red) process.exitCode = 1;
  }
})().catch(e => { console.error(e.message); process.exitCode = 1; });
