// UI-09: echte QA-Leseprüfung, dann synthetische Zustandsmatrix.
// Sämtliche schreibenden Datenquellen-Aufrufe werden abgefangen; keine echten Abgleiche.
const {chromium}=require('playwright-core');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const env=process.env;
for(const k of ['E2E_EMAIL','E2E_PASSWORD','E2E_OUT','E2E_ORG_ID','E2E_SPACE_ID','E2E_KB_ID'])if(!env[k])throw Error(`${k} fehlt`);
fs.mkdirSync(env.E2E_OUT,{recursive:true});
const results=[];
const base={orgId:+env.E2E_ORG_ID,spaceId:+env.E2E_SPACE_ID,knowledgeBaseId:+env.E2E_KB_ID,kind:'LOCAL',location:'/qa/files',username:null,tenantId:null,clientId:null,secretSet:false,includePatterns:'',excludePatterns:'**/archiv/**',maxFileBytes:20971520,mirrorPermissions:true,intervalMinutes:15,enabled:true,lastRunStartedAt:'2026-11-15T10:00:00Z',lastRunFinishedAt:'2026-11-15T10:01:00Z',lastRunStatus:'OK',lastRunMessage:null,filesSeen:43,filesIndexed:43,filesFailed:0};
let fixtures=[
  {...base,id:-9001,displayName:'Handbücher'},
  {...base,id:-9002,displayName:'Archiv',kind:'SMB',location:'\\\\fileserver\\Archiv',enabled:false,secretSet:true,username:'Prüfkonto'},
  {...base,id:-9003,displayName:'Neue Freigabe',lastRunStatus:null,lastRunStartedAt:null,lastRunFinishedAt:null,enabled:false,filesSeen:0,filesIndexed:0},
  {...base,id:-9004,displayName:'Laufender Import',lastRunStatus:'RUNNING',lastRunFinishedAt:null},
  {...base,id:-9005,displayName:'Richtlinien',kind:'SHAREPOINT',location:'https://beispiel.sharepoint.com/sites/richtlinien',lastRunStatus:'PARTIAL',filesFailed:2},
  {...base,id:-9006,displayName:'Finanzen',lastRunStatus:'FAILED',lastRunMessage:'Die Verbindung zur Freigabe konnte nicht hergestellt werden.',filesSeen:0,filesIndexed:0},
];
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox'],...(env.E2E_CHROME?{executablePath:env.E2E_CHROME}:{})});
  const page=await browser.newPage({viewport:{width:1440,height:1000},locale:'de-DE'});
  const row=id=>page.locator(`[data-source-id="${id}"]`), dialog=()=>page.locator('dialog[open]');
  const shot=n=>page.screenshot({path:path.join(env.E2E_OUT,`${n}.png`)});
  async function point(name,fn,mode='simulierte Datenquellen/HTTP-Antworten'){
    try{await fn();results.push({name,mode,status:'bestanden'});}catch(e){results.push({name,mode,status:'rot',reason:e.message.slice(0,600)});await shot(`FAIL-${results.length}`).catch(()=>{});}
    console.log(JSON.stringify(results.at(-1)));fs.writeFileSync(path.join(env.E2E_OUT,'results.json'),JSON.stringify(results,null,2));
  }
  const menu=async(id,name)=>{await row(id).getByTestId('datasource-actions').click();await page.getByRole('menuitem',{name,exact:true}).click();};
  let listMode='real',realRows=[],listRelease,problemMode='fail',problemRelease,deleteMode='fail',deleteRelease,runRelease,wizardRelease;
  let runCalls=0,toggleCalls=0,deleteCalls=0,created=null,deleteReady;
  const routeRoot='**/api/v1/admin/datasources';
  await page.route(routeRoot,async r=>{
    if(r.request().method()==='GET'){
      const mode=listMode;
      if(mode==='real'){const response=await r.fetch();realRows=await response.json();return r.fulfill({response});}
      if(mode==='hold')await new Promise(resolve=>{listRelease=resolve;});
      if(mode==='error')return r.fulfill({status:503,json:{error:'Prüffehler'}});
      return r.fulfill({status:200,json:mode==='empty'?[]:fixtures});
    }
    assert.equal(r.request().method(),'POST');assert.notEqual(listMode,'real','Kein echter Schreibaufruf zulässig');
    const input=r.request().postDataJSON();created={...base,...input,id:-9010,enabled:false,lastRunStatus:null,lastRunStartedAt:null,lastRunFinishedAt:null,filesSeen:0,filesIndexed:0};
    await new Promise(resolve=>{wizardRelease=resolve;});fixtures.push(created);await r.fulfill({status:201,json:created});
  });
  await page.route('**/api/v1/admin/datasources/*',async r=>{
    const id=Number(r.request().url().split('/').at(-1)),method=r.request().method();
    if(method==='GET')return r.continue();
    assert(id<0,'Schreibzugriff auf vorhandene Quelle verhindert');
    if(method==='PATCH'){toggleCalls++;const input=r.request().postDataJSON();const current=fixtures.find(x=>x.id===id);Object.assign(current,input);return r.fulfill({status:200,json:current});}
    assert.equal(method,'DELETE');deleteCalls++;await new Promise(resolve=>{deleteRelease=resolve;deleteReady?.();});
    if(deleteMode==='fail')return r.fulfill({status:503,json:{error:'Prüffehler'}});
    fixtures=fixtures.filter(x=>x.id!==id);return r.fulfill({status:204,body:''});
  });
  await page.route('**/api/v1/admin/datasources/*/problems',async r=>{
    if(problemMode==='fail')await new Promise(resolve=>{problemRelease=resolve;});
    if(problemMode==='fail')return r.fulfill({status:503,json:{error:'Prüffehler'}});
    return r.fulfill({status:200,json:problemMode==='empty'?[]:[{relativePath:'Ordner/Ein_sehr_langer_Dateiname_fuer_die_Problemansicht_auf_dem_Handy.txt',error:'Die Datei konnte nicht gelesen werden.'}]});
  });
  await page.route('**/api/v1/admin/datasources/*/preview',r=>r.fulfill({status:200,json:{filesFound:2,filesAccepted:2,bytesAccepted:1200,skippedType:0,skippedSize:0,skippedPattern:0,sample:['handbuch/start.txt'],principals:[{principal:'public',files:2,label:'Alle angemeldeten Personen'}],aclSampled:2,aclUnreadable:0,message:'Verbunden.'}}));
  await page.route('**/api/v1/admin/datasources/*/run',async r=>{
    const id=Number(r.request().url().split('/').at(-2));assert(id<0,'Kein echter Abgleich zulässig');runCalls++;
    if(id===-9010)return r.fulfill({status:503,json:{error:'Prüffehler'}});
    await new Promise(resolve=>{runRelease=resolve;});return r.fulfill({status:202,body:''});
  });
  const reload=()=>page.getByTestId('datasource-reload').click();
  async function mount(){await page.getByTestId('sidebar-nav-admin').click();await page.getByTestId('admin-tab-datasources').click();}
  try{
    await page.goto(env.E2E_BASE||'http://localhost:1420');await page.getByTestId('login-email').fill(env.E2E_EMAIL);await page.getByTestId('login-password').fill(env.E2E_PASSWORD);await page.getByTestId('login-submit').click();
    await page.getByTestId('sidebar-org').click();await page.getByTestId(`sidebar-org-${env.E2E_ORG_ID}`).click();await page.getByTestId(`sidebar-space-${env.E2E_SPACE_ID}`).click();await mount();
    await point('Vorhandene Quellen unverändert lesen: Betrieb getrennt vom Ergebnis',async()=>{
      await page.getByTestId('datasource-item').first().waitFor();assert(realRows.length>0);
      const paused=realRows.find(d=>!d.enabled&&d.lastRunStatus==='OK');assert(paused,'Pausierte QA-Quelle mit erfolgreichem Vorlauf erforderlich');
      assert.equal(await row(paused.id).getByTestId('datasource-operation').innerText(),'Pausiert');assert.equal(await row(paused.id).getByTestId('datasource-status').innerText(),'Erfolgreich');assert(await row(paused.id).getByTestId('datasource-run').isDisabled());
      await page.getByRole('heading',{name:'Datenquellen',exact:true}).scrollIntoViewIfNeeded();await shot('real-overview-dark');
    },'echter QA-Lesezugriff');
    listMode='fixtures';await reload();await row(-9001).waitFor();
    await point('Alle Betriebs-/Ergebniszustände, Regeln für Abgleichen',async()=>{
      for(const [id,operation,status] of [[-9001,'Aktiv','Erfolgreich'],[-9002,'Pausiert','Erfolgreich'],[-9003,'Nicht aktiviert','Noch kein Abgleich'],[-9004,'Aktiv','Wird abgeglichen'],[-9005,'Aktiv','Teilweise erfolgreich'],[-9006,'Aktiv','Fehlgeschlagen']]){
        assert.equal(await row(id).getByTestId('datasource-operation').innerText(),operation);assert.equal(await row(id).getByTestId('datasource-status').innerText(),status);
      }
      for(const id of [-9002,-9003,-9004])assert(await row(id).getByTestId('datasource-run').isDisabled());assert(await row(-9001).getByTestId('datasource-run').isEnabled());
    });
    await point('Suche, Betriebsfilter, keine Treffer und Zurücksetzen',async()=>{
      await page.getByTestId('datasource-search').fill('ARCHIV');assert.equal(await page.getByTestId('datasource-item').count(),1);
      await page.getByTestId('datasource-search').fill('');await page.getByTestId('datasource-filter').selectOption('problems');assert.equal(await page.getByTestId('datasource-item').count(),2);
      await page.getByTestId('datasource-search').fill('NichtVorhanden');await page.getByTestId('datasource-no-results').waitFor();await page.getByRole('button',{name:'Filter zurücksetzen',exact:true}).click();assert.equal(await page.getByTestId('datasource-item').count(),6);
    });
    await point('Problem-Dialog: Ladezustand, Fehler statt leer, Wiederholen',async()=>{
      await row(-9005).getByTestId('datasource-problems').click();await page.getByTestId('datasource-problems-loading').waitFor();
      await page.waitForFunction(()=>!!document.querySelector('[data-testid="datasource-problems-loading"]'));assert(problemRelease);problemRelease();await dialog().getByTestId('dialog-error').waitFor();
      problemMode='empty';await page.getByTestId('datasource-problems-retry').click();await page.getByText('Keine Dateien mit Problemen gemeldet.',{exact:true}).waitFor();
      await dialog().getByTestId('dialog-close').click();problemMode='entries';await row(-9005).getByTestId('datasource-problems').click();await page.getByTestId('datasource-problems-list').waitFor();await page.keyboard.press('Escape');
    });
    await point('Menü → Einstellungen: Secret bleibt leer, Escape gibt Fokus zurück',async()=>{
      await menu(-9002,'Einstellungen');await dialog().waitFor();assert.equal(await dialog().locator('input[type=password]').inputValue(),'');await page.getByTestId('datasource-secret-saved').waitFor();
      await page.keyboard.press('Escape');await dialog().waitFor({state:'hidden'});
      assert.equal(await page.evaluate(()=>document.activeElement?.getAttribute('data-testid')),'datasource-actions');assert.equal(toggleCalls,0);
    });
    await point('Abgleichen: Busy blockiert doppelte Anfragen, Rückmeldung',async()=>{
      await row(-9001).getByTestId('datasource-run').click();assert(await row(-9001).getByTestId('datasource-run').isDisabled());
      await row(-9001).getByTestId('datasource-run').dispatchEvent('click');assert.equal(runCalls,1);assert(runRelease);runRelease();await page.getByTestId('datasource-note').waitFor();
      assert.match(await page.getByTestId('datasource-note').innerText(),/gestartet/);
    });
    await point('Pausieren im Aktiv-Filter erhält historischen Abgleich und Fokus',async()=>{
      await page.getByTestId('datasource-filter').selectOption('active');const finished=fixtures.find(x=>x.id===-9001).lastRunFinishedAt;
      await menu(-9001,'Pausieren');await row(-9001).waitFor({state:'hidden'});await page.waitForFunction(()=>document.activeElement?.getAttribute('data-testid')==='datasource-search');
      await page.getByTestId('datasource-filter').selectOption('paused');await row(-9001).waitFor();assert.equal(await row(-9001).getByTestId('datasource-status').innerText(),'Erfolgreich');assert.equal(fixtures.find(x=>x.id===-9001).lastRunFinishedAt,finished);
      await page.getByTestId('datasource-filter').selectOption('all');
    });
    await point('Entfernen: sicherer Fokus, Abbruch, Busy, Fehler bleibt, Wiederholen',async()=>{
      await menu(-9003,'Entfernen');await dialog().getByTestId('confirm-cancel').waitFor();await page.waitForFunction(()=>document.activeElement?.getAttribute('data-testid')==='confirm-cancel');await page.keyboard.press('Escape');assert.equal(deleteCalls,0);
      await menu(-9003,'Entfernen');await dialog().getByTestId('confirm-submit').click();assert(await dialog().getByTestId('confirm-cancel').isDisabled());await page.keyboard.press('Escape');assert(await dialog().isVisible());assert(deleteRelease);deleteRelease();await dialog().getByTestId('dialog-error').waitFor();assert(await row(-9003).isVisible());
      deleteMode='ok';const ready=new Promise(resolve=>{deleteReady=resolve;});await dialog().getByTestId('confirm-submit').click();await page.waitForFunction(()=>document.querySelector('[data-testid="confirm-submit"][aria-busy="true"]'));
      // Zweite Anfrage ist getrennt angehalten.
      await ready;deleteRelease();await dialog().waitFor({state:'hidden'});await row(-9003).waitFor({state:'hidden'});assert.equal(deleteCalls,2);
    });
    await point('Assistent: Entwurf, Busy, Schritte, Teilfehler nach Aktivierung',async()=>{
      await page.getByTestId('datasource-create').click();await dialog().locator('input[type=radio][value=LOCAL]').check();await page.getByTestId('datasource-next').click();
      await page.getByTestId('datasource-name').fill('UI9 Testassistent');await page.getByTestId('datasource-location').fill('/qa/files');await page.keyboard.press('Escape');await page.getByTestId('dialog-keep-editing').click();assert.equal(await page.getByTestId('datasource-name').inputValue(),'UI9 Testassistent');
      await page.getByTestId('datasource-next').click();assert(await page.getByTestId('datasource-next').isDisabled());await page.keyboard.press('Escape');assert(await dialog().isVisible());assert(wizardRelease);wizardRelease();await dialog().getByRole('heading',{name:'Auswahl',exact:true}).waitFor();
      await page.getByTestId('datasource-next').click();await dialog().getByRole('heading',{name:'Rechte',exact:true}).waitFor();await page.getByTestId('datasource-next').click();await dialog().getByRole('heading',{name:'Zeitplan',exact:true}).waitFor();await page.getByTestId('datasource-next').click();await dialog().getByRole('heading',{name:'Aktivieren',exact:true}).waitFor();
      await page.getByTestId('datasource-activate').click();await dialog().waitFor({state:'hidden'});assert.match(await page.getByTestId('datasource-note').innerText(),/aktiviert, aber der erste Abgleich konnte nicht gestartet werden/);assert.equal(created.mirrorPermissions,true);
    });
    await point('Listenfehler erhält bestehende Daten; initialer Fehler und leere Quelle',async()=>{
      listMode='error';await reload();await page.getByTestId('datasource-list-error').waitFor();assert(await row(-9002).isVisible());
      await page.getByTestId('admin-tab-status').click();await page.getByTestId('admin-tab-datasources').click();await page.getByTestId('datasource-list-error').waitFor();assert.equal(await page.getByTestId('datasource-empty').count(),0);
      listMode='empty';await page.getByRole('button',{name:'Erneut versuchen',exact:true}).click();await page.getByTestId('datasource-empty').waitFor();assert.equal(await page.getByTestId('datasource-no-results').count(),0);
      listMode='fixtures';await reload();await row(-9002).waitFor();
    });
    for(const scheme of ['light','dark'])for(const width of [1440,1030,768,390]){
      await page.setViewportSize({width,height:width===390?844:1000});await page.emulateMedia({colorScheme:scheme});await page.waitForFunction(s=>document.documentElement.getAttribute('data-ic-theme')===s,scheme);
      await page.evaluate(async()=>{await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame);await Promise.all(document.getAnimations().filter(a=>a.constructor.name==='CSSTransition').map(a=>a.finished.catch(()=>{})));});
      await point(`Datenquellen ${scheme} ${width}: Katalog, Reflow, Menüs`,async()=>{
        await page.getByRole('heading',{name:'Datenquellen',exact:true}).scrollIntoViewIfNeeded();assert(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));
        const field=page.getByTestId('datasource-search');assert.equal(await field.evaluate(e=>getComputedStyle(e).borderRadius),'6px');assert((await field.boundingBox()).height>=(width<768?44:40));
        if(width===390){const more=await row(-9002).getByTestId('datasource-actions').boundingBox();assert(more.width>=44&&more.height>=44);}
        if(width===390)assert(await page.getByTestId('admin-section-select').isVisible());
        await shot(`overview-${scheme}-${width}`);await row(-9002).getByTestId('datasource-actions').click();await page.getByRole('menuitem',{name:'Einstellungen',exact:true}).waitFor();assert(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));await page.keyboard.press('Escape');
      });
      await point(`Assistent ${scheme} ${width}: ruhige Flächen und Fokus`,async()=>{
        await page.getByTestId('datasource-create').click();await dialog().waitFor();
        const borders=await dialog().evaluate(e=>({head:getComputedStyle(e.querySelector('.head')).borderBottomWidth,footer:getComputedStyle(e.querySelector('.dialog-footer')).borderTopWidth,nav:getComputedStyle(e.querySelector('.wizard-nav')).borderRightWidth}));assert.equal(borders.head,'0px');assert.equal(borders.footer,'0px');assert.equal(borders.nav,'0px');
        const selected=dialog().locator('.ds-choice').filter({has:page.locator('input:checked')});assert(await selected.count());
        await shot(`wizard-${scheme}-${width}`);await page.keyboard.press('Escape');await dialog().waitFor({state:'hidden'});
      });
    }
    await point('Assistent bei geringer Höhe: Inhalt und Fuß erreichbar',async()=>{
      await page.setViewportSize({width:480,height:400});await page.getByTestId('datasource-create').click();await dialog().locator('input[value=LOCAL]').check();await page.getByTestId('datasource-next').click();
      const field=page.getByTestId('datasource-name');await field.scrollIntoViewIfNeeded();assert(await field.evaluate(e=>{const r=e.getBoundingClientRect();return document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)===e;}));
      await dialog().getByTestId('dialog-close').click();if(await page.getByTestId('dialog-discard').isVisible())await page.getByTestId('dialog-discard').click();await dialog().waitFor({state:'hidden'});
    });
  }finally{listRelease?.();problemRelease?.();deleteRelease?.();runRelease?.();wizardRelease?.();await browser.close();}
  console.log(`${results.filter(r=>r.status==='bestanden').length}/${results.length} bestanden`);process.exitCode=results.some(r=>r.status==='rot')?1:0;
})().catch(e=>{console.error(e.message);process.exitCode=1;});
