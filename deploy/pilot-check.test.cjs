'use strict';
// These tests verify the acceptance checker itself, not model quality. The
// actual installation is checked separately with pilot-check.cjs and real models.
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { randomBytes } = require('node:crypto');

async function check(fault) {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'keplura-check-'));
  const password = randomBytes(24).toString('hex');
  const csrf = randomBytes(24).toString('hex');
  const cookie = randomBytes(24).toString('hex');
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const raw = Buffer.concat(chunks).toString();
    function send(status, body) {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    }
    const url = req.url;
    if (url === '/') { res.end('<div id="app"></div>'); return; }
    if (url.endsWith('/health')) return send(200, { status: 'UP' });
    if (url.endsWith('/auth/login')) {
      if (JSON.parse(raw).password !== password) return send(401, {});
      res.setHeader('Set-Cookie', `KEPLURASESSION=${cookie}; HttpOnly; SameSite=Lax`);
      return send(200, { csrfToken: csrf });
    }
    if (req.headers.cookie !== `KEPLURASESSION=${cookie}`) return send(401, {});
    if (req.method === 'POST' && req.headers['x-csrf-token'] !== csrf) return send(403, {});
    if (url === '/api/v1/orgs') return send(201, { id: 1 });
    if (url.endsWith('/spaces')) return send(201, { id: 2 });
    if (url.endsWith('/knowledge-bases')) return send(201, { id: 3 });
    if (url.endsWith('/documents')) {
      return send(req.method === 'POST' ? 201 : 200,
        req.method === 'POST' ? { id: 4 } : [{ id: 4, indexStatus: 'INDEXED' }]);
    }
    if (url.endsWith('/search')) return send(200, {
      backend: fault === 'memory' ? 'memory' : 'pgvector', model: 'bge-m3',
      results: [{ documentId: 4, text: 'Die Rauchwarnschwelle beträgt 42 Prozent.' }],
    });
    if (url.endsWith('/conversations')) return send(201, { id: 5 });
    if (url.endsWith('/stream')) {
      res.setHeader('Content-Type', 'text/event-stream');
      res.end(`event: turn\ndata: {"status":"${fault === 'incomplete' ? 'incomplete' : 'complete'}"}\n\n`);
      return;
    }
    if (url.endsWith('/conversations/5')) return send(200, { messages: [{
      id: 6, role: 'assistant', status: 'complete', model: 'qwen2.5:7b',
      content: fault === 'wrong-answer' ? 'Die Schwelle beträgt 99 Prozent.' : 'Die Schwelle beträgt 42 Prozent.',
      citations: fault === 'no-citations' ? [] : [{ documentId: 4 }],
    }] });
    send(404, {});
  });
  try {
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const out = path.join(temp, 'result.json');
    const child = spawn(process.execPath, [path.join(__dirname, 'pilot-check.cjs'),
      '--base', `http://127.0.0.1:${server.address().port}`, '--out', out], {
      env: { ...process.env, KEPLURA_ADMIN_BOOTSTRAP_EMAIL: 'pilot@example.test', KEPLURA_ADMIN_BOOTSTRAP_PASSWORD: password },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let output = '';
    child.stdout.on('data', chunk => output += chunk);
    child.stderr.on('data', chunk => output += chunk);
    const [code] = await once(child, 'exit');
    const report = JSON.parse(fs.readFileSync(out, 'utf8'));
    for (const secret of [password, csrf, cookie]) {
      assert(!output.includes(secret), 'Geheimnis in der Ausgabe');
      assert(!JSON.stringify(report).includes(secret), 'Geheimnis im Prüfprotokoll');
    }
    return { code, report };
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(temp, { recursive: true, force: true });
  }
}

test('Abnahmelauf erkennt vollständigen belegten Ablauf und schützt Geheimnisse', async () => {
  const { code, report } = await check();
  assert.equal(code, 0);
  assert.equal(report.ok, true);
  assert.equal(report.steps.length, 11);
});
for (const [fault, name] of [
  ['memory', 'Memory-Fallback'], ['no-citations', 'fehlende Quelle'],
  ['incomplete', 'unvollständige Übertragung'], ['wrong-answer', 'falscher Antwortfakt'],
]) test(`Abnahmelauf verweigert Grün bei: ${name}`, async () => {
  const { code, report } = await check(fault);
  assert.equal(code, 1);
  assert.equal(report.ok, false);
});
