// Reproduzierbare Spezifikations-/Tokenprüfung, kein Produkt-/CI-/A11y-Test.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const repo = path.resolve(__dirname, '../../..');
process.chdir(repo);
const base = '3a8f3987b95b8bbb7bba5b27e2b5c73a3bf23f78';
const t = JSON.parse(fs.readFileSync(path.join(__dirname,'tokens.json'),'utf8'));
const c = JSON.parse(fs.readFileSync(path.join(__dirname,'contracts.json'),'utf8'));
const index = JSON.parse(fs.readFileSync('docs/design/ui-audit-2026-11-15/source-index.json','utf8'));
const inventory = JSON.parse(fs.readFileSync('docs/design/ui-audit-2026-11-15/coverage.json','utf8'));
const checks=[];
const add=(pruefung,ok,detail)=>checks.push({pruefung,status:ok?'bestanden':'rot',detail});
const git=(...args)=>cp.execFileSync('git',args,{encoding:'utf8'}).trim();
const lum=h=>h.slice(1).match(/../g).map(s=>parseInt(s,16)/255).map(v=>v<=0.04045?v/12.92:((v+0.055)/1.055)**2.4).reduce((n,v,i)=>n+v*[0.2126,0.7152,0.0722][i],0);
const ratio=(a,b)=>(Math.max(lum(a),lum(b))+0.05)/(Math.min(lum(a),lum(b))+0.05);
const pairs=[];
for(const [theme,p] of Object.entries(t.themes)){
  const pair=(foreground,background,minimum,role)=>{const value=ratio(p[foreground],p[background]);pairs.push({theme,foreground,background,rolle:role,minimum,kontrast:Number(value.toFixed(4)),bestanden:value>=minimum});};
  const backgrounds=['canvas','content','surface','sidebar','surface-subtle','hover-tint','selection'];
  for(const bg of backgrounds){
    for(const fg of ['text','text-2','text-3','accent','accent-hover'])pair(fg,bg,4.5,'Text');
    for(const fg of ['input-edge','focus-color'])pair(fg,bg,3,'Control/Fokus');
    for(const fg of ['status-ok','status-wait','status-error'])pair(fg,bg,4.5,'Statustext');
  }
  for(const state of ['ok','wait','error']){pair('status-'+state,'status-'+state+'-bg',4.5,'Statusfläche');pair('text-2','status-'+state+'-bg',4.5,'Erläuterung');}
  pair('on-accent','accent',4.5,'Primärbutton');pair('on-accent','accent-hover',4.5,'Primärbutton Hover');pair('on-accent','status-error',4.5,'Destruktiver Abschluss');pair('disabled-text','disabled-bg',4.5,'Eigenes Disabled-Ziel');
}
add('Palette: erlaubte Kontrastpaare ohne Schwellenabrundung',pairs.every(p=>p.bestanden),{paare:pairs.length,minimumText:Math.min(...pairs.filter(p=>p.minimum===4.5).map(p=>p.kontrast)),minimumControl:Math.min(...pairs.filter(p=>p.minimum===3).map(p=>p.kontrast)),fehler:pairs.filter(p=>!p.bestanden)});
const themeNames=Object.keys(t.themes.light).sort();
add('Gleiche semantische Rollen in beiden Themes',JSON.stringify(themeNames)===JSON.stringify(Object.keys(t.themes.dark).sort()),{rollen:themeNames.length});
const names=new Set([...themeNames,...Object.keys(t.shared),...Object.keys(t.aliases)]);
const tokenErrors=[];
for(const [key,value] of Object.entries(t.aliases)){if(/^[a-z-]+$/.test(value)&&value!=='none'&&!names.has(value))tokenErrors.push(key);for(const m of value.matchAll(/var\(--ic-([a-z0-9-]+)/g))if(!names.has(m[1]))tokenErrors.push(key+':'+m[1]);}
const referenced=new Set();
function scan(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,e.name);if(e.isDirectory())scan(f);else if(/\.(vue|css|ts)$/.test(e.name)){const body=fs.readFileSync(f,'utf8').replace(/\/\*[\s\S]*?\*\//g,'');for(const m of body.matchAll(/var\(--ic-([a-z0-9-]+)/g))referenced.add(m[1]);}}}
scan('desktop/src');
add('Bestands-Tokenreferenzen und Aliasse zugeordnet',tokenErrors.length===0&&[...referenced].every(n=>names.has(n)),{bestandsNamen:referenced.size,fehlend:[...referenced].filter(n=>!names.has(n)),aliasFehler:tokenErrors});
const ids=c.zuordnung.map(i=>i.id), canonical=inventory.eintraege.map(i=>i.id);
const contractIds=new Set(c.vertraege.map(x=>x.id));
add('109 Inventar-IDs vollständig/eindeutig mit gültigen Verträgen',ids.length===109&&new Set(ids).size===109&&canonical.every(id=>ids.includes(id))&&c.zuordnung.every(i=>i.vertraege.length&&i.vertraege.every(v=>contractIds.has(v))&&i.status==='offen'),{ids:ids.length,vertragsfamilien:contractIds.size});
const files=new Set(c.vertraege.flatMap(x=>x.quelldateien));
add('Alle 36 Vue-Dateien zugeordnet',files.size===36&&index.files.every(f=>files.has(f.file))&&[...files].every(f=>fs.existsSync(f)),{anzahl:files.size,fehlend:index.files.filter(f=>!files.has(f.file)).map(f=>f.file)});
const changed=index.files.filter(f=>crypto.createHash('sha256').update(fs.readFileSync(f.file)).digest('hex')!==f.sha256).map(f=>f.file);
add('Vue-Quelltexte identisch zur ursprünglichen Auditbasis',changed.length===0,{dateien:index.files.length,abweichend:changed});
add('Originaltracker unverändert/offen',git('diff',base,'--','docs/design/ui-audit-2026-11-15/coverage.json')===''&&inventory.eintraege.every(i=>i.status==='offen'),{offen:inventory.eintraege.filter(i=>i.status==='offen').length});
const changedFiles=[...new Set([git('diff','--name-only',base),git('ls-files','--others','--exclude-standard')].join('\n').split(/\r?\n/).filter(Boolean))];
add('Eigener Diff nur Dokumentation/isolierte Referenz',changedFiles.every(f=>f.startsWith('docs/')),{dateien:changedFiles.length});
let count=0;const missing=[],badUrls=[];
for(const file of changedFiles.filter(f=>f.endsWith('.md'))){const body=fs.readFileSync(file,'utf8');for(const m of body.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)){const target=m[1];if(/^https?:/.test(target)){try{new URL(target);if(/\s/.test(target))badUrls.push({file,target});}catch{badUrls.push({file,target});}continue;}if(target.startsWith('#'))continue;count++;const local=path.resolve(path.dirname(file),decodeURIComponent(target.split('#')[0]));if(!fs.existsSync(local)&&!local.endsWith(path.sep+'checks.json'))missing.push({file,target});}}
add('Lokale Markdown-Ziele vorhanden, externe URLs syntaktisch gültig',!missing.length&&!badUrls.length,{lokaleLinks:count,fehlend:missing,urlFehler:badUrls});
const generated=cp.spawnSync(process.execPath,[path.join(__dirname,'build-catalogue.cjs'),'--check'],{encoding:'utf8'});
add('Katalog/CSS entsprechen der Tokenquelle',generated.status===0,{exitcode:generated.status});
const whitespace=cp.spawnSync('git',['diff','--check',base],{encoding:'utf8'});
add('git diff --check',whitespace.status===0,{exitcode:whitespace.status});
const suspicious=changedFiles.filter(f=>/\.(md|json|html|css)$/.test(f)).filter(f=>/(?:gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|C:[\\/]Users[\\/]|\/Users\/|\/home\/(?:hermes|conoc)\/)/.test(fs.readFileSync(f,'utf8')));
add('Keine offensichtlichen Secrets/persönlichen Pfade in neuen Referenztexten',suspicious.length===0,{auffaelligeDateien:suspicious});
const report={zeitpunktUtc:new Date().toISOString(),vergleichsbasis:base,produktbasis:index.sourceCommit,art:'Spezifikations-/Tokenprüfung; keine App-/CI-/A11y-Abnahme',ergebnis:checks.every(p=>p.status==='bestanden')?'bestanden':'rot',pruefungen:checks};
fs.writeFileSync(path.join(__dirname,'checks.json'),JSON.stringify(report,null,2)+'\n');
fs.writeFileSync(path.join(__dirname,'contrast-pairs.json'),'{\n  "methode": "WCAG relative sRGB-Luminanz; Prüfung vor Rundung, Werte zur Anzeige auf vier Dezimalstellen",\n  "paare": [\n'+pairs.map(p=>'    '+JSON.stringify(p)).join(',\n')+'\n  ]\n}\n');
console.log(JSON.stringify(report,null,2));
if(report.ergebnis!=='bestanden')process.exitCode=1;
