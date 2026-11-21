// Wissen-Layout gegen QA. Keine Modellaufrufe: Suche abgefangen, Dokument-Stressdaten synthetisch.
const {chromium}=require('playwright-core'), fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict');
const env=process.env;
for(const k of ['E2E_EMAIL','E2E_PASSWORD','E2E_OUT','E2E_ORG_ID','E2E_SPACE_ID','E2E_KB_ID'])if(!env[k])throw Error(`${k} fehlt`);
const results=[];fs.mkdirSync(env.E2E_OUT,{recursive:true});
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox'],...(env.E2E_CHROME?{executablePath:env.E2E_CHROME}:{})});
  const page=await browser.newPage({ locale: 'de-DE',viewport:{width:1440,height:1000},locale:'de-DE'});
  const shot=n=>page.screenshot({path:path.join(env.E2E_OUT,`${n}.png`)});
  async function point(name,fn,mode='echt'){
    try{await fn();results.push({name,mode,status:'bestanden'});}catch(e){results.push({name,mode,status:'rot',reason:e.message.slice(0,500)});await shot(`FAIL-${results.length}`).catch(()=>{});}
    console.log(JSON.stringify(results.at(-1)));fs.writeFileSync(path.join(env.E2E_OUT,'results.json'),JSON.stringify(results,null,2));
  }
  let mode='real',searchFail=false,releaseList,signalWaiting;
  const waitingReady=new Promise(resolve=>{signalWaiting=resolve;});
  await page.route('**/knowledge-bases/*/search',r=>r.fulfill(searchFail?{status:503,json:{error:'Prüffehler'}}:{status:200,json:{query:r.request().postDataJSON().query,topK:5,backend:'fixture',model:'fixture',results:[]}}));
  await page.route('**/knowledge-bases/*/documents',async r=>{
    if(r.request().method()!=='GET')return r.continue();
    const requestMode=mode;
    const response=await r.fetch();const real=await response.json();
    if(requestMode==='wait'){signalWaiting();await new Promise(resolve=>{releaseList=resolve;});}
    if(mode==='stress')return r.fulfill({response,json:[...real,...Array.from({length:10},(_,i)=>({id:-8000-i,kbId:Number(env.E2E_KB_ID),filename:i===0?'Ein_sehr_langer_Dokumentname_mit_vielen_Woertern_fuer_die_mobile_Ansicht_2026.pdf':`UI8-Prüfdatei-${i}.txt`,contentType:'text/plain',sizeBytes:1200+i,sha256:'',uploadedBy:0,indexStatus:i===1?'FAILED':'INDEXED',createdAt:'2026-11-15T12:00:00Z'}))]});
    return r.fulfill({response});
  });
  try{
    await page.goto(env.E2E_BASE||'http://localhost:1420');await page.getByTestId('login-email').fill(env.E2E_EMAIL);await page.getByTestId('login-password').fill(env.E2E_PASSWORD);await page.getByTestId('login-submit').click();
    await page.getByTestId('sidebar-org').click();await page.getByTestId(`sidebar-org-${env.E2E_ORG_ID}`).click();await page.getByTestId(`sidebar-space-${env.E2E_SPACE_ID}`).click();await page.getByTestId('sidebar-nav-wissen').click();
    const pick=async id=>{if(await page.getByTestId('knowledge-kb-switch').isVisible().catch(()=>false))await page.getByTestId('knowledge-kb-switch').click();await page.getByTestId(`knowledge-kb-select-${id}`).click();};
    await pick(env.E2E_KB_ID);await page.getByTestId('knowledge-doc-item').first().waitFor();
    await point('Echte Dokumente, Tabelle, Download und Verwaltungsdialoge',async()=>{
      assert(await page.getByRole('table').first().isVisible());assert(await page.getByRole('columnheader',{name:'Dokument',exact:true}).first().isVisible());
      const download=page.waitForEvent('download');await page.getByTestId('knowledge-doc-download').first().click();assert((await download).suggestedFilename().length>0);
      await page.getByTestId('knowledge-doc-delete').first().click();await page.locator('dialog[open]').getByTestId('confirm-cancel').click();
      await page.getByTestId(`knowledge-kb-rename-${env.E2E_KB_ID}`).click();await page.getByRole('dialog').getByRole('button',{name:'Abbrechen',exact:true}).click();
      await page.getByTestId('knowledge-kb-new').click();await page.getByTestId('knowledge-kb-name').waitFor();await page.getByRole('dialog').getByRole('button',{name:'Abbrechen',exact:true}).click();
    });
    await point('Suchzustände: kein Ergebnis und verständlicher Fehler',async()=>{
      await page.getByTestId('knowledge-search-query').fill('Prüfbegriff');await page.getByTestId('knowledge-search-submit').click();await page.getByTestId('knowledge-result-empty').waitFor();
      await page.getByRole('button',{name:'Zurücksetzen',exact:true}).click();searchFail=true;
      await page.getByTestId('knowledge-search-query').fill('Prüffehler');await page.getByTestId('knowledge-search-submit').click();await page.getByTestId('knowledge-toast').waitFor();
      assert((await page.getByTestId('knowledge-toast').innerText()).length>10);searchFail=false;
    },'Suchantworten 200 leer / HTTP 503 simuliert');
    await point('Basiswechsel: Ladezustand und keine verspätete fremde Liste',async()=>{
      const ids=await page.locator('[data-testid^="knowledge-kb-select-"]').evaluateAll(els=>els.map(e=>e.getAttribute('data-testid').split('-').at(-1)));
      const otherId=ids.find(id=>id!==env.E2E_KB_ID);assert(otherId,'Zweite QA-Basis erforderlich');
      mode='wait';await pick(otherId);await page.getByTestId('knowledge-loading').waitFor();await waitingReady;
      // Zweiter Wechsel noch während der ersten Anfrage. Dessen Antwort darf später nicht ersetzen.
      mode='real';await pick(env.E2E_KB_ID);await page.getByTestId('knowledge-doc-item').first().waitFor();
      const name=await page.getByTestId('knowledge-doc-name').first().innerText();
      const oldResponse=page.waitForResponse(r=>r.url().includes(`/knowledge-bases/${otherId}/documents`));releaseList();await oldResponse;await page.waitForLoadState('networkidle');
      await page.waitForFunction(()=>!document.querySelector('[data-testid="knowledge-loading"]'));
      assert.equal(await page.getByTestId('knowledge-doc-name').first().innerText(),name);
    },'echte Listen, erste Antwort künstlich angehalten');
    mode='stress';await page.getByTestId('knowledge-reload').click();await page.getByTestId('knowledge-doc-filter').waitFor();
    await point('Namensfilter, keine passenden Dokumente, Statusgruppen',async()=>{
      await page.getByTestId('knowledge-doc-filter').fill('UI8-Prüfdatei-1');assert.equal(await page.getByTestId('knowledge-doc-item').count(),1);
      await page.getByTestId('knowledge-doc-filter').fill('UnmöglichNichtVorhanden');assert(await page.getByText('Keine Dokumente passen zum Filter.',{exact:true}).isVisible());await page.getByTestId('knowledge-doc-filter').fill('');
    },'synthetische Stressliste');
    for(const scheme of ['light','dark'])for(const width of [1440,938,768,390]){
      await page.setViewportSize({width,height:width===390?844:1000});await page.emulateMedia({colorScheme:scheme});
      await page.waitForFunction(s=>document.documentElement.getAttribute('data-ic-theme')===s,scheme);
      await page.evaluate(async()=>{await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame);await Promise.all(document.getAnimations().filter(a=>a.constructor.name==='CSSTransition').map(a=>a.finished.catch(()=>{})));});
      await page.waitForFunction(w=>(w<1280)===!!document.querySelector('[data-testid="knowledge-kb-switch"]'),width);
      await point(`Wissen ${scheme} ${width}: Auswahl, Typografie, Aktionen und Reflow`,async()=>{
        assert(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));
        const field=page.getByTestId('knowledge-search-query');const box=await field.boundingBox();assert(box.height>=(width<768?44:40));
        assert.equal(await field.evaluate(e=>getComputedStyle(e).borderRadius),'6px');
        assert(await page.getByTestId('knowledge-doc-download').first().isVisible());assert(await page.getByTestId('knowledge-doc-reindex').first().isVisible());assert(await page.getByTestId('knowledge-doc-delete').first().isVisible());
        if(width<1280){await page.getByTestId('knowledge-kb-switch').click();await page.getByTestId(`knowledge-kb-select-${env.E2E_KB_ID}`).waitFor();assert(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));await page.keyboard.press('Escape');}
        if(width===390){const a=await page.getByTestId('knowledge-doc-delete').first().boundingBox();assert(a.width>=44&&a.height>=44);}
        await shot(`knowledge-${scheme}-${width}`);
      },'echte Oberfläche mit synthetischen langen Dateinamen/Statusgruppen');
    }
  }finally{releaseList?.();await browser.close();}
  console.log(`${results.filter(r=>r.status==='bestanden').length}/${results.length} bestanden`);process.exitCode=results.some(r=>r.status==='rot')?1:0;
})().catch(e=>{console.error(e.message);process.exitCode=1;});
