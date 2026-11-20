#!/usr/bin/env node
'use strict';

// Acceptance against the installed stack and its REAL models. No mock upstream.
// Credentials enter through environment variables or a local, ignored env file.
// The generated example organisation is kept for a browser inspection afterwards.
const fs = require('node:fs');
const assert = require('node:assert/strict');

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i < 0 ? fallback : process.argv[i + 1];
}
function readEnv(filename) {
  if (!filename) return {};
  return Object.fromEntries(fs.readFileSync(filename, 'utf8').split(/\r?\n/)
    .filter(line => /^[A-Z][A-Z0-9_]*=/.test(line))
    .map(line => {
      const i = line.indexOf('=');
      const value = line.slice(i + 1).trim();
      return [line.slice(0, i), value.replace(/^(["'])(.*)\1$/, '$2')];
    }));
}

async function main() {
  const config = { ...readEnv(arg('env-file')), ...process.env };
  const email = config.KEPLURA_ADMIN_BOOTSTRAP_EMAIL;
  const password = config.KEPLURA_ADMIN_BOOTSTRAP_PASSWORD;
  assert(email && password, 'Admin-Zugang fehlt: lokale ENV-Datei oder Umgebungsvariablen angeben.');
  const base = new URL(arg('base', 'http://localhost:8080')).origin;
  const out = arg('out');
  const report = { startedAt: new Date().toISOString(), steps: [] };
  const cookies = new Map();
  let csrf = '';
  const save = () => { if (out) fs.writeFileSync(out, JSON.stringify(report, null, 2) + '\n'); };
  function step(name, detail = {}) {
    report.steps.push({ name, ok: true, ...detail });
    save();
    console.log(JSON.stringify({ name, ok: true, ...detail }));
  }
  async function request(path, { method = 'GET', body, authenticate = true, noCsrf = false, timeout = 180000 } = {}) {
    const headers = {};
    if (authenticate) {
      headers.Cookie = [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');
      if (csrf && !noCsrf) headers['X-CSRF-Token'] = csrf;
    }
    if (body && !(body instanceof FormData)) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(body);
    }
    const response = await fetch(base + path, { method, headers, body, signal: AbortSignal.timeout(timeout), redirect: 'error' });
    if (authenticate) for (const cookie of response.headers.getSetCookie()) {
      const kv = cookie.split(';')[0];
      const i = kv.indexOf('=');
      cookies.set(kv.slice(0, i), kv.slice(i + 1));
    }
    return response;
  }
  async function json(path, options) {
    const response = await request(path, options);
    assert(response.ok, `HTTP ${response.status} bei ${options?.method || 'GET'} ${path}`);
    return response.json();
  }

  try {
    const health = await json('/api/v1/health');
    assert.equal(health.status, 'UP');
    step('Server erreichbar');
    const page = await request('/');
    assert(page.ok && (await page.text()).includes('<div id="app">'), 'Web-Oberfläche fehlt im Server.');
    step('Browser-Oberfläche vom Server ausgeliefert');
    assert.equal((await request('/api/v1/orgs', { authenticate: false })).status, 401);
    step('Zugriff ohne Anmeldung gesperrt');
    const login = await json('/api/v1/auth/login', { method: 'POST', body: { email, password } });
    csrf = login.csrfToken;
    assert(csrf && cookies.has('KEPLURASESSION'), 'Produktionssession oder CSRF-Token fehlt.');
    step('Admin angemeldet');
    const denied = await request('/api/v1/orgs', { method: 'POST', body: { name: 'Darf nicht entstehen' }, noCsrf: true });
    assert.equal(denied.status, 403);
    step('Änderungen ohne CSRF-Nachweis gesperrt');
    const org = await json('/api/v1/orgs', { method: 'POST', body: { name: `Pilotprüfung ${new Date().toISOString()}` } });
    const spaceRoot = `/api/v1/orgs/${org.id}/spaces`;
    const space = await json(spaceRoot, { method: 'POST', body: { name: 'Pilot-Arbeitsbereich' } });
    const kbRoot = `${spaceRoot}/${space.id}/knowledge-bases`;
    const kb = await json(kbRoot, { method: 'POST', body: { name: 'Betriebshandbuch' } });
    const root = `${kbRoot}/${kb.id}`;
    report.objects = { orgId: org.id, spaceId: space.id, knowledgeBaseId: kb.id };
    step('Organisation, Arbeitsbereich und Wissensbasis angelegt');

    const file = new FormData();
    file.set('file', new Blob(['# Betriebshandbuch — Pilotprüfung\n\n## Leitwarte\nDie Rauchwarnschwelle der Leitwarte beträgt genau 42 Prozent. Bei Erreichen informiert der Schichtleiter die Sicherheitszentrale.\n'], { type: 'text/plain' }), 'betriebshandbuch.txt');
    const doc = await json(root + '/documents', { method: 'POST', body: file });
    report.objects.documentId = doc.id;
    step('Dokument von Hand hochgeladen');
    let indexed = false;
    for (let attempt = 0; attempt < 180; attempt++) {
      const docs = await json(root + '/documents');
      const found = docs.find(d => d.id === doc.id);
      assert(found?.indexStatus !== 'FAILED', 'Dokumentverarbeitung fehlgeschlagen. Server-Protokoll prüfen.');
      if (found?.indexStatus === 'INDEXED') { indexed = true; break; }
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    assert(indexed, 'Dokument nach sechs Minuten noch nicht durchsuchbar.');
    step('Dokument mit echtem Embedding-Modell verarbeitet');
    const result = await json(root + '/search', { method: 'POST', body: { query: 'Welche Rauchwarnschwelle gilt in der Leitwarte?', topK: 3 } });
    assert.equal(result.backend, 'pgvector');
    assert(result.model.includes('bge-m3'), 'Suche verwendet nicht bge-m3.');
    assert(result.results.some(hit => hit.documentId === doc.id && hit.text.includes('42')), 'Testdokument wurde nicht gefunden.');
    step('Echte semantische Suche findet den belegten Fakt', { backend: result.backend, model: result.model });

    const conv = await json(`${spaceRoot}/${space.id}/chat/conversations`, { method: 'POST', body: { knowledgeBaseId: kb.id, title: 'Pilotprüfung: Leitwarte' } });
    report.objects.conversationId = conv.id;
    const conversationPath = `/api/v1/orgs/${org.id}/chat/conversations/${conv.id}`;
    const stream = await request(conversationPath + '/stream', { method: 'POST', body: { prompt: 'Wie hoch ist die Rauchwarnschwelle der Leitwarte? Antworte kurz auf Deutsch anhand des Betriebshandbuchs.' }, timeout: 600000 });
    assert(stream.ok, `Antwort konnte nicht gestartet werden (HTTP ${stream.status}).`);
    const events = await stream.text();
    assert(events.includes('event: turn') && events.includes('"complete"'), 'Modellantwort wurde nicht vollständig übertragen.');
    const transcript = await json(conversationPath);
    const answer = transcript.messages.findLast(message => message.role === 'assistant');
    assert.equal(answer?.status, 'complete');
    assert(answer.content.includes('42'), 'Modellantwort enthält den belegten Fakt nicht.');
    assert(answer.citations.some(citation => citation.documentId === doc.id), 'Quellennachweis fehlt.');
    assert(answer.model.includes('qwen2.5'), 'Antwort verwendet nicht das vorgegebene Chat-Modell.');
    report.answer = answer.content;
    step('Echte Modellantwort mit Quelle gespeichert', { model: answer.model, citations: answer.citations.length });
    const reloaded = await json(conversationPath);
    assert(reloaded.messages.some(message => message.id === answer.id && message.content === answer.content));
    step('Antwort nach erneutem Laden erhalten');
    report.completedAt = new Date().toISOString();
    report.ok = true;
    save();
  } catch (error) {
    report.ok = false;
    // Only our own assertions contain displayable diagnostics. Network errors
    // may contain addresses; do not dump upstream responses or credential data.
    report.error = error instanceof assert.AssertionError ? error.message : 'Prüfung unterbrochen. Verbindung und Server-Protokoll prüfen.';
    save();
    console.error(report.error);
    process.exitCode = 1;
  }
}
main().catch(() => { console.error('Prüfung konnte nicht gestartet werden. Argumente und lokale ENV-Datei prüfen.'); process.exitCode = 1; });
