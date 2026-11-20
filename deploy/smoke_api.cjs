#!/usr/bin/env node
// DEPLOY-BUNDLE acceptance smoke (t_bc138a04): full prod-path API flow
// against a RUNNING server (prod profile, real PostgreSQL+pgvector) with a
// hermetic mock OpenAI-compatible upstream for chat. Steps:
//   health -> login (Argon2-seeded user) -> org -> space -> KB
//   -> upload (txt) -> search (pgvector RAG hit) -> chat conversation
//   -> SSE turn through the mock -> history persisted.
// One JSON line per step on stdout; exit 0 = every step ok.
//
// Usage: node deploy/smoke_api.cjs --base http://127.0.0.1:8080 \
//          --email X --password Y [--mock-port 19923]
//
// The chat leg REQUIRES the server process to have KEPLURA_GATEWAY_BASE_URL
// pointing at this script's mock (the booting wrapper passes it; see
// deploy/README.md "Smoke-Lauf" for the exact command).
'use strict';
const http = require('http');

function arg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] !== undefined ? process.argv[i + 1] : dflt;
}
const BASE = arg('base', 'http://127.0.0.1:8080');
const MAIL = arg('email', 'smoke@keplura.test');
// Deliberately NO default: a credential never gets committed, not even a
// throwaway smoke one (--password is passed by the operator/account seed).
const SECRET = arg('password', null);
if (!SECRET) {
  console.error('usage: node smoke_api.cjs --base URL --email ADDR --password SECRET [--mock-port N]');
  process.exit(2);
}
const MOCK_PORT = parseInt(arg('mock-port', '19923'), 10);

let COUNTED = 0;
function step(name, ok, extra) {
  COUNTED += 1;
  console.log(JSON.stringify({ step: name, ok, ...(extra || {}) }));
  if (!ok) { console.error(JSON.stringify({ fatal: name })); process.exit(1); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function request(method, urlStr, body, headers) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlStr);
    const data = body === undefined ? null : (Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body)));
    const req = http.request(
      { host: u.hostname, port: u.port || 80, path: u.pathname + u.search, method,
        headers: { ...(data ? { 'content-length': String(data.length) } : {}), ...(headers || {}) } },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers,
          body: Buffer.concat(chunks).toString('utf8') }));
      });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// --- hermetic mock upstream: OpenAI chat-completions over SSE ---------------
function startMock() {
  const srv = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      if (!req.url.endsWith('/chat/completions')) { res.writeHead(404); res.end(); return; }
      let asked = '';
      try {
        const j = JSON.parse(raw);
        const msgs = j.messages || [];
        asked = msgs.length ? String(msgs[msgs.length - 1].content || '') : '';
      } catch { /* keep empty */ }
      const answer = 'SMOKE-ANTWORT(' + asked.slice(0, 24).replace(/[\\"]/g, '') + ')';
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      for (const w of answer.split(' ')) {
        res.write('data: ' + JSON.stringify({ choices: [{ delta: { content: w + ' ' } }] }) + '\n\n');
      }
      res.write('data: [DONE]\n\n');
      res.end();
    });
  });
  return new Promise((r) => srv.listen(MOCK_PORT, '127.0.0.1', () => r(srv)));
}

(async () => {
  const mock = await startMock();
  process.on('exit', () => mock.close());

  // cookie jar: keep BOTH the session cookie and the CSRF double-submit
  // cookie (SEC-2/ADR-0005: header AND cookie must carry the bound token).
  const jar = {};
  let csrf = '';
  const absorb = (res) => {
    for (const c of res.headers['set-cookie'] || []) {
      const kv = c.split(';')[0];
      const name = kv.split('=')[0];
      if (name === 'KEPLURASESSION' || name === 'keplura_csrf') jar[name] = kv;
    }
  };
  const cookieHeader = () => Object.values(jar).join('; ');
  const H = (extra) => ({ cookie: cookieHeader(), ...(csrf ? { 'x-csrf-token': csrf } : {}), ...extra });

  // 1) health (prod profile: no seeding, no H2 fallback -- the real stack)
  let r = await request('GET', BASE + '/api/v1/health');
  step('health', r.status === 200 && r.body.includes('"UP"'), { status: r.status });

  // 2) login -- the user row was seeded via the documented hash tool
  r = await request('POST', BASE + '/api/v1/auth/login',
    { email: MAIL, password: SECRET }, { 'content-type': 'application/json' });
  absorb(r);
  let body = {}; try { body = JSON.parse(r.body); } catch { /* 4xx text */ }
  csrf = body.csrfToken || '';
  step('login', r.status === 200 && !!csrf && !!jar['KEPLURASESSION'], { status: r.status });

  // 3) org
  r = await request('POST', BASE + '/api/v1/orgs', { name: 'Smoke Org' },
    { 'content-type': 'application/json', ...H() });
  let org = {}; try { org = JSON.parse(r.body); } catch {}
  step('org', (r.status === 200 || r.status === 201) && !!org.id, { status: r.status, org: org.id });

  // 4) space
  r = await request('POST', BASE + `/api/v1/orgs/${org.id}/spaces`, { name: 'Smoke Space' },
    { 'content-type': 'application/json', ...H() });
  let space = {}; try { space = JSON.parse(r.body); } catch {}
  step('space', (r.status === 200 || r.status === 201) && !!space.id, { status: r.status, space: space.id });

  // 5) knowledge base
  r = await request('POST', BASE + `/api/v1/orgs/${org.id}/spaces/${space.id}/knowledge-bases`,
    { name: 'Smoke KB' }, { 'content-type': 'application/json', ...H() });
  let kb = {}; try { kb = JSON.parse(r.body); } catch {}
  step('kb', (r.status === 200 || r.status === 201) && !!kb.id, { status: r.status, kb: kb.id });

  // 6) upload (multipart, distinctive phrase for the RAG proof)
  const phrase = 'Die Rauchwarnschwelle der Leitwarte liegt bei 42 Prozent';
  const fileBytes = Buffer.from(phrase + '.\nWeitere Betriebsnotiz ohne Bezug.\n', 'utf8');
  const b = 'smokeboundary42';
  const head = Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="file"; filename="notiz.txt"\r\n`
    + 'Content-Type: text/plain\r\n\r\n', 'utf8');
  const tail = Buffer.from(`\r\n--${b}--\r\n`, 'utf8');
  r = await request('POST', BASE + `/api/v1/orgs/${org.id}/spaces/${space.id}/knowledge-bases/${kb.id}/documents`,
    Buffer.concat([head, fileBytes, tail]), { 'content-type': `multipart/form-data; boundary=${b}`, ...H() });
  let doc = {}; try { doc = JSON.parse(r.body); } catch {}
  step('upload', (r.status === 200 || r.status === 201) && !!doc.id, { status: r.status, doc: doc.id });

  // 7) search after async indexing settles (real pgvector store on real PG)
  let hit = null;
  for (let i = 0; i < 20 && !hit; i++) {
    await sleep(1000);
    r = await request('POST', BASE + `/api/v1/orgs/${org.id}/spaces/${space.id}/knowledge-bases/${kb.id}/search`,
      { query: 'Rauchwarnschwelle Leitwarte', topK: 3 }, { 'content-type': 'application/json', ...H() });
    if (r.status === 200) {
      let s = {}; try { s = JSON.parse(r.body); } catch {}
      if (s.results && s.results.some((x) => String(x.text).includes('42'))) hit = s;
    }
  }
  step('search-rag', !!hit, hit ? { backend: hit.backend, model: hit.model, top: hit.results[0].documentId } : { lastStatus: r.status });

  // 8) chat conversation scoped to the KB + one SSE turn through the mock
  r = await request('POST', BASE + `/api/v1/orgs/${org.id}/spaces/${space.id}/chat/conversations`,
    { knowledgeBaseId: kb.id, title: 'Smoke Chat' }, { 'content-type': 'application/json', ...H() });
  let conv = {}; try { conv = JSON.parse(r.body); } catch {}
  step('chat-create', (r.status === 200 || r.status === 201) && !!conv.id, { status: r.status, conv: conv.id });

  const promptBytes = Buffer.from(JSON.stringify({ prompt: 'Was sagt die Notiz zur Rauchwarnschwelle?' }));
  r = await request('POST', BASE + `/api/v1/orgs/${org.id}/chat/conversations/${conv.id}/stream`,
    promptBytes, { 'content-type': 'application/json', accept: 'text/event-stream', ...H() });
  step('chat-stream', r.status === 200 && r.body.includes('SMOKE-ANTWORT'),
    { status: r.status, bytes: r.body.length });

  // 9) transcript persisted (what the desktop re-reads after reload)
  r = await request('GET', BASE + `/api/v1/orgs/${org.id}/chat/conversations/${conv.id}`, null, H());
  let t = {}; try { t = JSON.parse(r.body); } catch {}
  const msgs = t.messages || [];
  const persisted = msgs.some((m) => /assistant/i.test(m.role) && String(m.content).includes('SMOKE-ANTWORT'))
    && msgs.some((m) => /user/i.test(m.role));
  step('chat-history', r.status === 200 && persisted, { messages: msgs.length });

  console.log(JSON.stringify({ done: true, steps: COUNTED }));
  mock.close();
  process.exit(0);
})().catch((e) => { console.error(JSON.stringify({ error: String((e && e.message) || e) })); process.exit(1); });
