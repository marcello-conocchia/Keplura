// Gezielt gegen QA, ohne Modellaufruf. /stream wird immer abgefangen.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const env = process.env;
for (const k of ['E2E_EMAIL','E2E_PASSWORD','E2E_OUT','E2E_ORG_ID','E2E_SPACE_ID','E2E_KB_ID']) if(!env[k]) throw Error(`${k} fehlt`);
fs.mkdirSync(env.E2E_OUT,{recursive:true});
const results=[];
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox'],...(env.E2E_CHROME?{executablePath:env.E2E_CHROME}:{})});
  const page=await browser.newPage({ locale: 'de-DE',viewport:{width:1440,height:1000},locale:'de-DE'});
  const shot=name=>page.screenshot({path:path.join(env.E2E_OUT,`${name}.png`)});
  async function point(name,fn,mode='echt'){
    try{await fn();results.push({name,mode,status:'bestanden'});}
    catch(e){results.push({name,mode,status:'rot',reason:e.message.slice(0,500)});await shot(`FAIL-${results.length}`).catch(()=>{});}
    console.log(JSON.stringify(results.at(-1)));fs.writeFileSync(path.join(env.E2E_OUT,'results.json'),JSON.stringify(results,null,2));
  }
  let streams=0, release;
  await page.route('**/chat/conversations/*/stream',async route=>{
    streams++; await new Promise(resolve=>{release=resolve;});
    await route.fulfill({status:503,json:{error:'Simulierter Prüffehler'}}).catch(()=>{});
  });
  try{
    await page.goto(env.E2E_BASE||'http://localhost:1420');
    await page.getByTestId('login-email').fill(env.E2E_EMAIL);await page.getByTestId('login-password').fill(env.E2E_PASSWORD);await page.getByTestId('login-submit').click();
    await page.getByTestId('sidebar-org').click();await page.getByTestId(`sidebar-org-${env.E2E_ORG_ID}`).click();await page.getByTestId(`sidebar-space-${env.E2E_SPACE_ID}`).click();
    await page.getByTestId('sidebar-nav-chat').click();
    const prompt=page.getByTestId('chat-prompt');
    await point('Ein Einstieg, neuer Chat ohne Wissensbasis',async()=>{
      await page.getByTestId('chat-new-plain').click();
      await page.waitForFunction(()=>document.querySelector('[data-testid="chat-kb-select"]').selectedIndex===0);
      assert.equal(await page.getByTestId('chat-new-rag').count(),0);
      assert(await page.getByText('Wie kann ich dir helfen?',{exact:true}).isVisible());
      assert(await page.getByText('Wissen hinzufügen',{exact:true}).isVisible());assert(await page.getByTestId('chat-send').isDisabled());
    });
    await point('Hauptnavigation bleibt beim Verlauf und Seitenwechsel stehen',async()=>{
      const y=await page.getByTestId('sidebar-nav-wissen').evaluate(e=>e.getBoundingClientRect().y);
      await page.getByTestId('sidebar-chats-toggle').click();assert.equal(await page.getByTestId('sidebar-nav-wissen').evaluate(e=>e.getBoundingClientRect().y),y);
      await page.getByTestId('sidebar-chats-toggle').click();await page.getByTestId('sidebar-nav-wissen').click();
      assert.equal(await page.getByTestId('sidebar-nav-wissen').getAttribute('aria-current'),'page');
      assert.equal(await page.getByTestId('sidebar-nav-wissen').evaluate(e=>e.getBoundingClientRect().y),y);
      await page.getByTestId('sidebar-nav-chat').click();
    });
    await point('Wissen optional wählen: Entwurf bleibt, neuer Chat neutral',async()=>{
      await prompt.fill('Mein Entwurf bleibt erhalten.');await page.getByTestId('chat-kb-select').selectOption(env.E2E_KB_ID);
      await page.getByTestId('chat-ctx-kb').waitFor();assert.equal(await prompt.inputValue(),'Mein Entwurf bleibt erhalten.');
      await page.getByTestId('chat-new-plain').click();
      await page.waitForFunction(()=>document.querySelector('[data-testid="chat-kb-select"]').selectedIndex===0);
    });
    await point('Plusmenü, Dateiaktion, Wissensuntermenü und Escape',async()=>{
      await page.getByTestId('chat-context-add').click();assert(await page.getByTestId('chat-attach').isVisible());
      await page.getByTestId('chat-extra-kbs').press('ArrowRight');await page.getByTestId('chat-extra-kb-item').first().waitFor();
      await page.keyboard.press('Escape');await page.keyboard.press('Escape');
      await page.getByTestId('chat-context-add').waitFor();assert.equal(await page.locator('[role="menu"]').count(),0);
      await page.getByTestId('chat-templates').click();await page.getByTestId('chat-template-save').waitFor();await page.keyboard.press('Escape');
    });
    await point('Mehrzeilig, automatische Höhe und IME ohne versehentliches Senden',async()=>{
      await prompt.fill('Eine Zeile');await prompt.press('Shift+Enter');assert((await prompt.inputValue()).includes('\n'));
      await prompt.dispatchEvent('keydown',{key:'Enter',code:'Enter',isComposing:true,bubbles:true,cancelable:true});assert.equal(streams,0);
      await prompt.fill(Array(12).fill('Eine längere Zeile').join('\n'));
      await page.waitForFunction(()=>document.querySelector('[data-testid="chat-prompt"]').getBoundingClientRect().height>48);
      assert((await prompt.evaluate(e=>e.getBoundingClientRect().height))<=200);
    });
    await point('Enter sendet, Stopp kehrt zur Eingabe zurück',async()=>{
      await prompt.fill('Nur eine simulierte Anfrage.');await prompt.press('Enter');await page.getByTestId('chat-stop').waitFor();
      assert.equal(await page.getByTestId('chat-send').count(),0);
      assert(await page.getByTestId('chat-kb-select').isDisabled());
      await page.waitForFunction(()=>document.querySelector('[data-testid="chat-streaming"]'));
      await page.getByTestId('chat-stop').click();release?.();await page.getByTestId('chat-send').waitFor();assert.equal(streams,1);
    },'HTTP-Antwort angehalten, kein Modellaufruf');
    for(const scheme of ['light','dark'])for(const width of [1440,949,768,390]){
      await page.setViewportSize({width,height:width===390?844:1000});await page.emulateMedia({colorScheme:scheme});
      await page.waitForFunction(s=>document.documentElement.getAttribute('data-ic-theme')===s,scheme);
      await page.evaluate(async()=>{await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame);await Promise.all(document.getAnimations().filter(a=>a.constructor.name==='CSSTransition').map(a=>a.finished.catch(()=>{})));});
      await page.waitForFunction(w=>(w<=760)===!!document.querySelector('[data-testid="mobile-menu-open"]'),width);
      await point(`Chat ${scheme} ${width}: kompakt und ohne Überlauf`,async()=>{
        await prompt.fill('');
        assert(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));
        const composer=await page.locator('.msg-composer').boundingBox();assert(composer.height<180,`Höhe ${composer.height}`);
        const size=await page.getByTestId('chat-send').boundingBox();assert(size.height>=(width<768?44:40));
        await shot(`chat-${scheme}-${width}`);
      });
    }
  }finally{release?.();await browser.close();}
  console.log(`${results.filter(r=>r.status==='bestanden').length}/${results.length} bestanden`);process.exitCode=results.some(r=>r.status==='rot')?1:0;
})().catch(e=>{console.error(e.message);process.exitCode=1;});
