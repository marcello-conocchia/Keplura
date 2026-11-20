// Die Laufzeit verwendet dieselbe Wertetabelle wie die Designreferenz.
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const tokens=JSON.parse(fs.readFileSync(path.join(root,'docs/design/system-v1/tokens.json'),'utf8'));
const names=new Set([...Object.keys(tokens.shared),...Object.keys(tokens.themes.light)]);
const aliases=Object.fromEntries(Object.entries(tokens.aliases).map(([k,v])=>[k,names.has(v)?`var(--ic-${v})`:v]));
const emit=o=>Object.entries(o).map(([k,v])=>`  --ic-${k}: ${v};`).join('\n');
const start='/* IC_SYSTEM_TOKENS_START */',end='/* IC_SYSTEM_TOKENS_END */';
const expected=start+'\n:root {\n'+emit({...tokens.shared,...tokens.themes.light,...aliases})+'\n  color-scheme: light;\n}\nhtml[data-ic-theme="dark"] {\n'+emit(tokens.themes.dark)+'\n  color-scheme: dark;\n}\n'+end;
const file=path.join(root,'desktop/src/styles/theme.css');
const body=fs.readFileSync(file,'utf8').replace(/\r\n/g,'\n');
const a=body.indexOf(start),b=body.indexOf(end);
if(a<0||b<a)throw Error('Markierungen der Laufzeit-Tokens fehlen');
if(process.argv.includes('--check')){
  if(body.slice(a,b+end.length)!==expected){console.error('Laufzeit-Tokens weichen vom Designsystem ab. node scripts/sync-design-tokens.cjs ausführen.');process.exitCode=1;}
  else console.log('Laufzeit-Tokens entsprechen System 1.0.');
}else{fs.writeFileSync(file,body.slice(0,a)+expected+body.slice(b+end.length));console.log('Laufzeit-Tokens synchronisiert.');}
