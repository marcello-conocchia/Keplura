// UI-05: echte Vue-Bausteine im isolierten Prüfstand + Einbindung in die echte QA-App.
// E2E_EMAIL / E2E_PASSWORD nur aus ENV. Keine schreibenden Backend-Aufrufe:
// synthetische MCP-/Modellzeilen und ihre Antworten sind ausdrücklich simuliert.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const tokens = require('../../docs/design/system-v1/tokens.json');
const env = process.env;
for (const key of ['E2E_EMAIL', 'E2E_PASSWORD', 'E2E_OUT']) if (!env[key]) throw new Error(`${key} fehlt`);
fs.mkdirSync(env.E2E_OUT, { recursive: true });
const results = [], base = env.E2E_BASE || 'http://localhost:1420';
const style = el => { const s = getComputedStyle(el); return { height: el.getBoundingClientRect().height, radius: s.borderRadius, font: s.fontSize, border: s.borderTopColor, color: s.color, bg: s.backgroundColor, outline: s.outlineWidth, offset: s.outlineOffset }; };
const rgb = hex => `rgb(${[1,3,5].map(i => parseInt(hex.slice(i,i+2),16)).join(', ')})`;

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'], ...(env.E2E_CHROME ? { executablePath: env.E2E_CHROME } : {}) });
  const ctx = await browser.newContext({ locale: 'de-DE', viewport: { width: 1440, height: 1000 }, locale: 'de-DE' });
  const page = await ctx.newPage();
  const dialog = () => page.locator('dialog[open]');
  const shot = name => page.screenshot({ path: path.join(env.E2E_OUT, `${name}.png`) });
  async function point(name, fn, mode = 'echt') {
    try { await fn(); results.push({ name, mode, status: 'bestanden' }); }
    catch (e) { results.push({ name, mode, status: 'rot', reason: e.message.slice(0,500) }); await shot(`FAIL-${results.length}`).catch(()=>{}); }
    console.log(JSON.stringify(results.at(-1)));
    fs.writeFileSync(path.join(env.E2E_OUT, 'results.json'), JSON.stringify(results,null,2));
  }
  try {
    for (const scheme of ['light','dark']) for (const width of [1440,768,390]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto(`${base}/e2e/fixtures/shared-controls.html`);
      await page.getByTestId('field').waitFor();
      const field = page.getByRole('textbox', { name: 'Wissensbasis', exact: true });
      await point(`FO-Vertrag ${scheme} ${width}`, async () => {
        const s = await field.evaluate(style);
        assert.equal(s.height, width < 768 ? 44 : 40);
        assert.equal(s.radius,'6px'); assert.equal(s.font,width < 768 ? '16px' : '14px');
        assert.equal(s.border,rgb(tokens.themes[scheme]['input-edge']));
        assert.equal(s.bg,rgb(tokens.themes[scheme].surface));
        assert.equal(await field.getAttribute('aria-describedby'),'scope basis-hint');
        assert.notEqual(await field.getAttribute('required'),null);
        assert.equal(await field.getAttribute('maxlength'),'80');
        await field.fill('Teamwissen'); assert.equal(await field.inputValue(),'Teamwissen');
        await page.getByRole('button',{name:'Hinweis wechseln',exact:true}).click();
        assert.equal(await field.getAttribute('aria-describedby'),'extra basis-hint');
        await page.getByRole('button',{name:'Fehler wechseln',exact:true}).click();
        assert.equal(await field.getAttribute('aria-invalid'),'true');
        assert.equal(await field.getAttribute('aria-describedby'),'extra basis-hint basis-error');
        assert.equal(await page.locator('#basis-error').innerText(),'Bitte gib einen Namen ein.');
        await field.focus(); const f = await field.evaluate(style); assert.equal(f.outline,'2px');assert.equal(f.offset,'2px');
        await page.getByRole('button',{name:'Nur lesen wechseln',exact:true}).click();assert.notEqual(await field.getAttribute('readonly'),null);
        await page.getByRole('button',{name:'Sperre wechseln',exact:true}).click(); assert(await field.isDisabled());
        assert.equal(await page.locator('.ic-pill[role="status"],.ic-pill[aria-live]').count(),0);
        assert.equal(await page.locator('.ic-pill').count(),5);
        assert(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));
        await shot(`controls-${scheme}-${width}`);
      });
    }
    await page.goto(`${base}/e2e/fixtures/shared-controls.html`);
    await point('Bestätigung: sicherer Erstfokus, Escape und Fokusrückgabe', async () => {
      await page.getByTestId('open-confirm').click();
      await dialog().getByTestId('confirm-cancel').waitFor();
      await page.waitForFunction(()=>document.activeElement?.getAttribute('data-testid')==='confirm-cancel');
      await page.keyboard.press('Escape');
      await page.waitForFunction(()=>!document.querySelector('dialog[open]'));
      assert.equal(await page.evaluate(()=>document.activeElement?.getAttribute('data-testid')),'open-confirm');
    });
    await point('Bestätigung: Busy blockiert Schließen und Mehrfachaktion', async () => {
      await page.getByTestId('open-confirm').click(); await dialog().getByTestId('confirm-submit').click();
      assert(await dialog().getByTestId('confirm-submit').isDisabled());assert(await dialog().getByTestId('confirm-cancel').isDisabled());
      await page.keyboard.press('Escape');await page.keyboard.press('Escape');
      await page.waitForFunction(()=>!!document.querySelector('dialog[open]'));
      assert(await page.getByRole('status').isVisible());
      // Busy einer gewöhnlichen Button-Aktion ist auch nativ disabled.
      assert(await page.getByTestId('busy-action').isDisabled());
      assert.equal(await page.getByTestId('clicks').innerText(),'0');
    });

    await page.setViewportSize({width:1440,height:1000});
    await page.goto(base);
    await page.getByTestId('login-email').fill(env.E2E_EMAIL);
    await page.getByTestId('login-password').fill(env.E2E_PASSWORD);
    await page.getByTestId('login-submit').click();
    await page.getByTestId('sidebar-nav-admin').waitFor({timeout:20000});
    if (env.E2E_ORG_ID) { await page.getByTestId('sidebar-org').click(); await page.getByTestId(`sidebar-org-${env.E2E_ORG_ID}`).click(); }
    await page.getByTestId('sidebar-nav-admin').click();

    // Die realen Listen bleiben erhalten. Nur eine zusätzliche Zeile ist synthetisch.
    const mcp = {id:-5005,name:'UI5 Prüfverbindung',url:'http://example.test/mcp',hasToken:false,enabled:false,tools:[],lastCheckedAt:null,lastError:null,allSpaces:false,spaceIds:[]};
    let removed=false, deletes=0, fail=true;
    await page.route('**/api/v1/admin/mcp-servers',async route=>{
      const response=await route.fetch();const real=await response.json();
      await route.fulfill({response,json:[...real,...(removed?[]:[mcp])]});
    });
    await page.route('**/api/v1/admin/mcp-servers/-5005',async route=>{
      assert.equal(route.request().method(),'DELETE');deletes++;
      await new Promise(r=>setTimeout(r,600));
      if(fail)await route.fulfill({status:503,json:{error:'Prüffehler'}});
      else {removed=true;await route.fulfill({status:204,body:''});}
    });
    await page.getByTestId('admin-tab-mcp').click();
    const row=()=>page.getByTestId('admin-mcp-server').filter({hasText:mcp.name});
    await point('MCP: Abbrechen erhält Verbindung und ruft DELETE nicht auf',async()=>{
      await row().getByTestId('connection-actions').click();await page.getByRole('menuitem',{name:'Entfernen',exact:true}).click();
      await dialog().getByTestId('confirm-cancel').click();assert.equal(deletes,0);assert(await row().isVisible());
    },'synthetische Zeile, echte Oberfläche');
    await point('MCP: Busy, Fehler bleibt im Dialog, erneuter Versuch',async()=>{
      await row().getByTestId('connection-actions').click();await page.getByRole('menuitem',{name:'Entfernen',exact:true}).click();
      await dialog().getByTestId('confirm-submit').click();assert(await dialog().getByTestId('confirm-cancel').isDisabled());
      await page.keyboard.press('Escape');assert.equal(await page.locator('dialog[open]').count(),1);
      await dialog().getByTestId('dialog-error').waitFor();assert.equal(deletes,1);assert(await row().isVisible());
      await page.waitForFunction(()=>document.activeElement?.getAttribute('data-testid')==='dialog-error');
      await shot('mcp-confirm-error-dark');
      fail=false;await dialog().getByTestId('confirm-submit').click();
      await page.locator('dialog[open]').waitFor({state:'hidden'});
      await row().waitFor({state:'hidden'});assert.equal(deletes,2);
    },'HTTP 503/204 und Verzögerung simuliert');

    await page.route('**/api/v1/admin/providers',async route=>{
      const response=await route.fetch();const real=await response.json();
      await route.fulfill({response,json:[...real,{id:-5006,name:'UI5 Prüfanbieter',providerType:'ollama',baseUrl:'http://example.test',secretSet:false,secretMasked:'',timeoutSeconds:30,enabled:true}]});
    });
    let embedding, modelPuts=0;
    await page.route('**/api/v1/admin/models',async route=>{
      const response=await route.fetch();const real=await response.json();
      embedding={id:-5006,providerId:-5006,providerName:'UI5 Prüfanbieter',name:'UI5 Prüfmodell',technicalModelId:'fixture',modelType:'EMBEDDING',embeddingDim:1024,maxOutputTokens:null,temperature:null,enabled:true,isDefault:false,createdAt:'2026-11-15T00:00:00Z',updatedAt:'2026-11-15T00:00:00Z'};
      await route.fulfill({response,json:[...real,embedding]});
    });
    await page.route('**/api/v1/admin/models/-5006/default',async route=>{modelPuts++;await route.fulfill({status:503,json:{error:'Prüffehler'}});});
    // Admin lädt seine Listen beim Mounten, daher über eine andere Hauptseite neu öffnen.
    await page.getByTestId('sidebar-nav-team').click();await page.getByTestId('sidebar-nav-admin').click();
    await page.getByTestId('admin-tab-models').click();
    await point('Embedding: Folgen genannt, Abbruch unverändert, Fehler am Dialog',async()=>{
      await page.getByTestId('admin-model-default--5006').click();
      const modal=page.getByRole('dialog');assert((await modal.innerText()).includes('nicht automatisch neu verarbeitet'));
      await dialog().getByTestId('confirm-cancel').click();assert.equal(modelPuts,0);
      await page.getByTestId('admin-model-default--5006').click();await dialog().getByTestId('confirm-submit').click();
      await dialog().getByTestId('dialog-error').waitFor();assert.equal(modelPuts,1);
      await shot('embedding-confirm-error-dark');await dialog().getByTestId('confirm-cancel').click();
    },'synthetischer Anbieter/Modell und HTTP 503');

    const navigate = async id => { if(await page.getByTestId('mobile-menu-open').isVisible()) await page.getByTestId('mobile-menu-open').click(); await page.getByTestId(id).click(); };
    for(const scheme of ['light','dark'])for(const width of [1440,768,390]){
      await page.setViewportSize({width,height:1000});await page.emulateMedia({colorScheme:scheme});
      await page.waitForFunction(w => (w <= 760) === !!document.querySelector('[data-testid="mobile-menu-open"]'), width);
      await point(`Echte Formulare ${scheme} ${width}: Team, Wissen, Administration`,async()=>{
        await navigate('sidebar-nav-team');
        await page.getByRole('button',{name:'Mitglied hinzufügen',exact:true}).first().click();
        const field=page.getByRole('dialog').locator('input').first();await field.waitFor();
        assert.equal((await field.evaluate(style)).height,width<768?44:40);
        assert.equal((await field.evaluate(style)).radius,'6px');await shot(`team-dialog-${scheme}-${width}`);
        await page.getByRole('dialog').getByRole('button',{name:'Abbrechen',exact:true}).click();
        await navigate('sidebar-nav-wissen');
        await page.locator('.ic-native-control,.ic-field-input').first().waitFor();await shot(`knowledge-${scheme}-${width}`);
        await navigate('sidebar-nav-admin');if(await page.getByTestId('admin-section-select').isVisible())await page.getByTestId('admin-section-select').selectOption('mcp');else await page.getByTestId('admin-tab-mcp').click();
        await page.getByTestId('admin-mcp-open').click();const raw=page.getByTestId('admin-mcp-name');
        assert.equal((await raw.evaluate(style)).height,width<768?44:40);assert.equal((await raw.evaluate(style)).radius,'6px');
        assert(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));await shot(`mcp-${scheme}-${width}`);await dialog().getByTestId('dialog-close').click();
      });
    }
  } finally {await browser.close();}
  console.log(`${results.filter(x=>x.status==='bestanden').length}/${results.length} bestanden`);
  process.exitCode=results.some(x=>x.status==='rot')?1:0;
})().catch(e=>{console.error(e.message);process.exitCode=1;});
