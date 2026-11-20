// Gemeinsame Verbindungsansichten: echte Leseansichten, alle weiteren HTTP-Antworten simuliert.
const {chromium}=require('playwright-core'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const env=process.env;for(const k of ['E2E_EMAIL','E2E_PASSWORD','E2E_OUT'])if(!env[k])throw Error(`${k} fehlt`);
fs.mkdirSync(env.E2E_OUT,{recursive:true});const results=[];
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox'],...(env.E2E_CHROME?{executablePath:env.E2E_CHROME}:{})});
  const page=await browser.newPage({viewport:{width:1440,height:1000},locale:'de-DE'});
  const dlg=()=>page.locator('dialog[open]'),shot=n=>page.screenshot({path:path.join(env.E2E_OUT,`${n}.png`)});
  async function point(name,fn,mode='synthetische Konfiguration und simulierte HTTP-Antworten'){
    try{await fn();results.push({name,mode,status:'bestanden'});}catch(e){results.push({name,mode,status:'rot',reason:e.message.slice(0,600)});await shot(`FAIL-${results.length}`).catch(()=>{});}
    console.log(JSON.stringify(results.at(-1)));fs.writeFileSync(path.join(env.E2E_OUT,'results.json'),JSON.stringify(results,null,2));
  }
  const tabs={websearch:'admin-tab-websearch',mcp:'admin-tab-mcp',directory:'admin-tab-directory'};
  const go=async key=>{const select=page.getByTestId('admin-section-select');if(await select.isVisible())await select.selectOption(key);else await page.getByTestId(tabs[key]).click();await page.getByTestId(`admin-${key}-section`).waitFor();};
  const menu=async(row,name)=>{await row.getByTestId('connection-actions').click();await page.getByRole('menuitem',{name,exact:true}).click();};
  let mock=false,readError=false,writeError=false,webTestError=false,mcpPatchError=false;
  let web={source:'admin',provider:'brave',url:'',hasApiKey:true,results:5,blockedDomains:'werbung.test',enabled:true,secretStorageAvailable:true};
  let mcps=[{id:-10001,name:'Ticketsystem',url:'https://werkzeuge.example.test/mcp',hasToken:true,enabled:true,tools:[{name:'tickets_lesen',description:'Tickets suchen und lesen.',enabled:true},{name:'ticket_anlegen',description:'Ein neues Ticket anlegen.',enabled:false}],lastCheckedAt:'2026-11-15T12:00:00Z',lastError:null,allSpaces:false,spaceIds:[5]}];
  let dirs=[{id:-10101,orgId:+env.E2E_ORG_ID,kind:'LDAP',displayName:'Firmenverzeichnis',url:'ldaps://verzeichnis.example.test',baseDn:'dc=example,dc=test',bindDn:'uid=reader,dc=example,dc=test',secretSet:true,userFilter:'',startTls:false,allowPlainLdap:false,loginEnabled:true,autoProvision:false,enabled:true}];
  let webPayload,dirPayload,mcpPayload;
  const wait=()=>new Promise(r=>setTimeout(r,350));
  await page.route('**/api/v1/admin/web-search',async r=>{
    const method=r.request().method();if(!mock){assert.equal(method,'GET');return r.continue();}
    if(method==='GET')return r.fulfill(readError?{status:503,json:{error:'Prüffehler'}}:{status:200,json:web});
    assert.equal(method,'PUT');webPayload=r.request().postDataJSON();await wait();if(writeError)return r.fulfill({status:503,json:{error:'Prüffehler'}});
    web={...web,...webPayload,apiKey:undefined,hasApiKey:webPayload.apiKey===null?web.hasApiKey:!!webPayload.apiKey};web.enabled=web.provider!=='none'&&(web.provider==='searxng'||web.hasApiKey);return r.fulfill({status:200,json:web});
  });
  await page.route('**/api/v1/admin/web-search/test',async r=>{assert(mock,'Kein echter Suchdienst-Test zulässig');await wait();return r.fulfill(webTestError?{status:503,json:{error:'Prüffehler'}}:{status:200,json:{ok:true,message:'Verbindung erfolgreich geprüft.',results:[{title:'Ein Prüfergebnis',url:'https://example.test/ergebnis',snippet:'Prüftext'}]}});});
  await page.route('**/api/v1/admin/mcp-servers',async r=>{
    const method=r.request().method();if(!mock){assert.equal(method,'GET');return r.continue();}
    if(method==='GET')return r.fulfill(readError?{status:503,json:{error:'Prüffehler'}}:{status:200,json:mcps});
    assert.equal(method,'POST');mcpPayload=r.request().postDataJSON();await wait();if(writeError)return r.fulfill({status:503,json:{error:'Prüffehler'}});
    const server={...mcps[0],id:-10002,name:mcpPayload.name,url:mcpPayload.url,hasToken:!!mcpPayload.token,tools:[],lastError:'Der Prüfserver ist nicht erreichbar.',lastCheckedAt:null,allSpaces:false,spaceIds:[]};mcps.push(server);return r.fulfill({status:201,json:server});
  });
  await page.route('**/api/v1/admin/mcp-servers/*',async r=>{
    const last=r.request().url().split('/').at(-1),method=r.request().method();if(!mock){assert.equal(method,'GET');return r.continue();}
    if(method==='GET'){if(readError)return r.fulfill({status:503,json:{error:'Prüffehler'}});return r.fulfill({status:200,json:last==='spaces'?[{id:5,name:'Pilotbereich',orgName:'Prüforganisation'}]:[{called_at:'2026-11-15T12:10:00Z',server_name:'Ticketsystem',tool_name:'tickets_lesen',duration_ms:32,ok:true,error:null,user_id:1}]});}
    const id=Number(last);assert(id<0);await wait();if(writeError||mcpPatchError)return r.fulfill({status:503,json:{error:'Prüffehler'}});
    if(method==='DELETE'){mcps=mcps.filter(x=>x.id!==id);return r.fulfill({status:204,body:''});}
    assert.equal(method,'PATCH');const p=r.request().postDataJSON(),server=mcps.find(x=>x.id===id);if(p.enabledTools)server.tools=server.tools.map(t=>({...t,enabled:p.enabledTools.includes(t.name)}));for(const k of ['enabled','allSpaces','spaceIds'])if(k in p)server[k]=p[k];return r.fulfill({status:200,json:server});
  });
  await page.route('**/api/v1/admin/mcp-servers/*/refresh',async r=>{assert(mock);const id=Number(r.request().url().split('/').at(-2));assert(id<0);await wait();return r.fulfill({status:200,json:mcps.find(x=>x.id===id)});});
  await page.route('**/api/v1/admin/directories',async r=>{
    const method=r.request().method();if(!mock){assert.equal(method,'GET');return r.continue();}if(method==='GET')return r.fulfill(readError?{status:503,json:{error:'Prüffehler'}}:{status:200,json:dirs});
    assert.equal(method,'POST');dirPayload=r.request().postDataJSON();await wait();return r.fulfill({status:201,json:{...dirPayload,id:-10102,secretSet:!!dirPayload.bindPassword,enabled:true}});
  });
  await page.route('**/api/v1/admin/directories/*',async r=>{assert(mock);const id=Number(r.request().url().split('/').at(-1));assert(id<0);await wait();if(writeError)return r.fulfill({status:503,json:{error:'Prüffehler'}});if(r.request().method()==='DELETE'){dirs=dirs.filter(x=>x.id!==id);return r.fulfill({status:204,body:''});}dirPayload=r.request().postDataJSON();Object.assign(dirs[0],dirPayload);return r.fulfill({status:200,json:dirs[0]});});
  await page.route('**/api/v1/admin/directories/*/test',async r=>{assert(mock);await wait();return r.fulfill({status:200,json:{ok:true,message:'Verbindung erfolgreich geprüft.',usersFound:3}});});
  await page.route('**/api/v1/admin/directories/*/lookup',async r=>{assert(mock);await wait();return r.fulfill({status:200,json:{found:true,message:'Gefunden.',displayName:'Testperson',email:'testperson@example.test',principals:['Gruppe: Prüfung']}});});
  try{
    await page.goto(env.E2E_BASE||'http://localhost:1420');await page.getByTestId('login-email').fill(env.E2E_EMAIL);await page.getByTestId('login-password').fill(env.E2E_PASSWORD);await page.getByTestId('login-submit').click();await page.getByTestId('sidebar-nav-admin').click();
    for(const key of Object.keys(tabs))await point(`Echte Leseansicht ${key}`,async()=>{await go(key);await page.getByRole('heading',{name:key==='mcp'?'MCP-Server':key==='directory'?'Verzeichnis':'Websuche',exact:true}).waitFor();await page.getByTestId(`admin-${key}-section`).locator('.connection-loading').waitFor({state:'hidden'});await shot(`real-${key}`);},'nur echter QA-Lesezugriff, keine Verbindungstests');
    mock=true;await go('websearch');
    await point('Websuche: Schlüssel beibehalten/entfernen und gespeicherter Status',async()=>{
      await page.getByTestId('admin-websearch-key').waitFor();assert.equal(await page.getByTestId('admin-websearch-key').inputValue(),'');await page.getByTestId('admin-websearch-save').click();await page.getByTestId('websearch-message').waitFor();assert.equal(webPayload.apiKey,null);
      await page.getByTestId('websearch-remove-key').check();assert(await page.getByTestId('admin-websearch-key').isDisabled());await page.getByTestId('admin-websearch-save').click();await page.getByTestId('websearch-message').waitFor();assert.equal(webPayload.apiKey,'');assert(!web.hasApiKey);
    });
    await point('Websuche: ungespeichert, Busy, Testerfolg und Fehler ohne alte Treffer',async()=>{
      await page.getByTestId('admin-websearch-provider').selectOption('searxng');await page.getByTestId('admin-websearch-url').fill('https://suche.example.test');assert(await page.getByTestId('admin-websearch-test').isDisabled());await page.getByTestId('admin-websearch-save').click();assert(await page.getByTestId('admin-websearch-save').isDisabled());await page.getByTestId('websearch-message').waitFor();
      await page.getByTestId('admin-websearch-test').click();await page.getByText('Ein Prüfergebnis',{exact:true}).waitFor();webTestError=true;await page.getByTestId('admin-websearch-test').click();await page.getByTestId('websearch-message').waitFor();assert.equal(await page.getByText('Ein Prüfergebnis',{exact:true}).count(),0);assert.equal(await page.getByTestId('websearch-message').getAttribute('role'),'alert');webTestError=false;
    });
    await go('mcp');const server=()=>page.getByTestId('admin-mcp-server').filter({hasText:'Ticketsystem'});
    await point('MCP: Werkzeuge/Bereiche, fehlgeschlagene Änderung bleibt unverändert',async()=>{
      await server().locator('.mcp-tools > summary').click();await server().getByTestId('admin-mcp-tool').nth(1).click();await page.waitForFunction(()=>document.querySelectorAll('[data-testid="admin-mcp-tool"]')[1]?.checked);
      mcpPatchError=true;await server().getByTestId('admin-mcp-tool').first().click();await page.getByTestId('mcp-message').waitFor();assert(await server().getByTestId('admin-mcp-tool').first().isChecked());mcpPatchError=false;
      await server().locator('.mcp-access > summary').click();await server().getByTestId('admin-mcp-all-spaces').click();await page.waitForFunction(()=>document.querySelector('[data-testid="admin-mcp-all-spaces"]').checked);
    });
    await point('MCP: fokussierter Anlagedialog, Dirty/Busy, Teilfehler und geleerter Schlüssel',async()=>{
      await page.getByTestId('admin-mcp-open').click();await page.getByTestId('admin-mcp-name').fill('UI10 Prüfserver');await page.getByTestId('admin-mcp-url').fill('https://werkzeuge.example.test/neu');await page.getByTestId('admin-mcp-token').fill('Prüfwert');await dlg().getByRole('button',{name:'Abbrechen',exact:true}).click();await page.getByTestId('dialog-keep-editing').click();
      await page.getByTestId('admin-mcp-add').click();assert(await page.getByTestId('admin-mcp-add').isDisabled());await page.keyboard.press('Escape');assert(await dlg().isVisible());await dlg().waitFor({state:'hidden'});assert.equal(await page.getByTestId('admin-mcp-token').inputValue(),'');await page.getByTestId('admin-mcp-server').filter({hasText:'UI10 Prüfserver'}).waitFor();assert.match(await page.getByTestId('mcp-message').innerText(),/Gespeichert, aber/);
    });
    await point('MCP: Entfernen-Fehler im Dialog, Abbrechen erhält Verbindung und Fokus',async()=>{
      writeError=true;await menu(server(),'Entfernen');await dlg().getByTestId('confirm-submit').click();await dlg().getByTestId('dialog-error').waitFor();await dlg().getByTestId('confirm-cancel').click();await page.waitForFunction(()=>document.activeElement?.getAttribute('data-testid')==='connection-actions');assert(await server().isVisible());writeError=false;
    });
    await go('directory');const directory=()=>page.getByTestId('directory-item').first();
    await point('Verzeichnis: gespeichertes Passwort bleibt leer, Save ohne Passwort erhält es',async()=>{
      await menu(directory(),'Bearbeiten');assert.equal(await dlg().locator('input[type=password]').inputValue(),'');await page.getByTestId('directory-save').click();assert(await page.getByTestId('directory-save').isDisabled());await dlg().waitFor({state:'hidden'});assert(!Object.hasOwn(dirPayload,'bindPassword'));assert.equal(dirs[0].allowPlainLdap,false);
    });
    await point('Verzeichnis: Verbindungstest, Benutzerabfrage und Fokusrückgabe',async()=>{
      await directory().getByTestId('directory-test').click();await page.getByTestId('directory-test-result').waitFor();await menu(directory(),'Benutzer nachschlagen');await page.getByTestId('directory-lookup-login').fill('tester');await page.getByTestId('directory-lookup-login').press('Enter');await page.getByTestId('directory-lookup-result').waitFor();assert.match(await page.getByTestId('directory-lookup-result').innerText(),/Testperson/);await page.keyboard.press('Escape');await page.waitForFunction(()=>document.activeElement?.getAttribute('data-testid')==='connection-actions');
    });
    await point('Verzeichnis: Entwurf schützen und Entfernen-Fehler erhalten',async()=>{
      await page.getByTestId('directory-create').click();await page.getByTestId('directory-url').fill('ldaps://neu.example.test');await dlg().getByRole('button',{name:'Abbrechen',exact:true}).click();await page.getByTestId('dialog-discard').click();
      writeError=true;await menu(directory(),'Entfernen');await dlg().getByTestId('confirm-submit').click();await dlg().getByTestId('dialog-error').waitFor();assert(await directory().isVisible());await dlg().getByTestId('confirm-cancel').click();writeError=false;
    });
    for(const key of Object.keys(tabs))await point(`${key}: Ladefehler statt fälschlich leerer Ansicht`,async()=>{readError=true;await go(key==='websearch'?'mcp':'websearch');await go(key);await page.getByRole('alert').first().waitFor();assert.equal(await page.locator('.connection-empty').count(),0);readError=false;await page.getByRole('button',{name:'Erneut versuchen',exact:true}).first().click();await page.getByRole('alert').first().waitFor({state:'hidden'});});
    await point('Websuche: Tavily und Ausschalten erhalten die Anbieterwege',async()=>{
      await go('websearch');await page.getByTestId('admin-websearch-provider').selectOption('tavily');await page.getByTestId('admin-websearch-key').fill('Prüfwert');await page.getByTestId('admin-websearch-save').click();await page.getByTestId('websearch-message').waitFor();assert.equal(webPayload.provider,'tavily');assert.equal(await page.getByTestId('admin-websearch-key').inputValue(),'');
      await page.getByTestId('admin-websearch-provider').selectOption('none');await page.getByTestId('admin-websearch-save').click();await page.getByTestId('websearch-message').waitFor();assert.equal(await page.getByTestId('websearch-status').innerText(),'Ausgeschaltet');assert(await page.getByTestId('admin-websearch-test').isDisabled());
      await page.getByTestId('admin-websearch-provider').selectOption('searxng');await page.getByTestId('admin-websearch-save').click();await page.getByTestId('websearch-message').waitFor();
    });
    await point('Leere MCP-/Verzeichnisliste ist von einem Ladefehler getrennt',async()=>{
      const savedMcps=mcps;mcps=[];await go('mcp');await page.getByTestId('mcp-empty').waitFor();mcps=savedMcps;await page.getByRole('button',{name:'MCP-Verbindungen aktualisieren',exact:true}).click();await page.getByTestId('admin-mcp-server').first().waitFor();
      const savedDirs=dirs;dirs=[];await go('directory');await page.getByTestId('directory-empty').waitFor();dirs=savedDirs;await page.getByRole('button',{name:'Verzeichnisse aktualisieren',exact:true}).click();await page.getByTestId('directory-item').waitFor();
    });
    for(const scheme of ['light','dark'])for(const width of [1440,768,390]){
      await page.setViewportSize({width,height:width===390?844:1000});await page.emulateMedia({colorScheme:scheme});await page.waitForFunction(s=>document.documentElement.getAttribute('data-ic-theme')===s,scheme);
      for(const key of Object.keys(tabs))await point(`${key} ${scheme} ${width}: Katalog und Reflow`,async()=>{
        await go(key);const section=page.getByTestId(`admin-${key}-section`);await section.scrollIntoViewIfNeeded();await page.evaluate(async()=>{await new Promise(requestAnimationFrame);await Promise.all(document.getAnimations().filter(a=>a.constructor.name==='CSSTransition').map(a=>a.finished.catch(()=>{})));});assert(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));await shot(`${key}-${scheme}-${width}`);
        if(key==='mcp'){await page.getByTestId('admin-mcp-open').click();assert.equal(await page.getByTestId('admin-mcp-name').evaluate(e=>getComputedStyle(e).borderRadius),'6px');await shot(`mcp-dialog-${scheme}-${width}`);await page.keyboard.press('Escape');}
        if(key==='directory'){await page.getByTestId('directory-create').click();await shot(`directory-dialog-${scheme}-${width}`);await page.keyboard.press('Escape');}
      });
    }
  }finally{await browser.close();}
  console.log(`${results.filter(x=>x.status==='bestanden').length}/${results.length} bestanden`);process.exitCode=results.some(x=>x.status==='rot')?1:0;
})().catch(e=>{console.error(e.message);process.exitCode=1;});
