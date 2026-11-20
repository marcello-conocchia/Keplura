// Lokale, rein lesende Designreferenz. Nur erlaubte Dateien, nur Loopback.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const repo = path.resolve(__dirname, '../../..');
const fonts = [path.join(repo, 'desktop/node_modules/@fontsource-variable/inter/files/inter-latin-wght-normal.woff2'), path.resolve(repo, '../../desktop/node_modules/@fontsource-variable/inter/files/inter-latin-wght-normal.woff2')];
const font = fonts.find(f=>fs.existsSync(f));
const files = {'/': ['catalogue.html','text/html; charset=utf-8'], '/catalogue.html':['catalogue.html','text/html; charset=utf-8'], '/tokens.css':['tokens.css','text/css; charset=utf-8']};
const port = Number(process.env.KEPLURA_DESIGN_PORT || 1423);
if (!Number.isInteger(port) || port<1024 || port>65535) throw Error('Ungültiger Vorschauport');
const server = http.createServer((req,res)=>{
  if (!['GET','HEAD'].includes(req.method)) {res.writeHead(405);res.end();return;}
  const url = new URL(req.url,'http://localhost');
  const asset = files[url.pathname];
  const file = url.pathname==='/inter.woff2' ? font : asset ? path.join(__dirname,asset[0]) : null;
  if (!file) {res.writeHead(404);res.end('Nicht gefunden');return;}
  res.writeHead(200,{'Content-Type':url.pathname==='/inter.woff2'?'font/woff2':asset[1], 'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  if(req.method==='HEAD') res.end();else fs.createReadStream(file).pipe(res);
});
server.listen(port,'127.0.0.1',()=>console.log('Designreferenz lokal auf Port '+port+'; Inter '+(font?'lokal verfügbar':'nicht installiert, System-Fallback')));
