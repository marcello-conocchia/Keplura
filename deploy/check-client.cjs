'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i < 0 ? fallback : process.argv[i + 1];
}
function createClient() {
  const file = arg('env-file');
  const config = Object.fromEntries((file ? fs.readFileSync(file, 'utf8') : '').split(/\r?\n/)
    .filter(line => /^[A-Z][A-Z0-9_]*=/.test(line)).map(line => {
      const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1).trim().replace(/^(["'])(.*)\1$/, '$2')];
    }));
  Object.assign(config, process.env);
  const base = new URL(arg('base', 'http://localhost:18080')).origin;
  const cookies = new Map();
  let csrf = '';
  async function request(path, {method = 'GET', body, timeout = 180000} = {}) {
    const headers = { Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; ') };
    if (csrf) headers['X-CSRF-Token'] = csrf;
    if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
    const response = await fetch(base + path, {method, body, headers, signal: AbortSignal.timeout(timeout), redirect:'error'});
    for (const cookie of response.headers.getSetCookie()) {
      const kv = cookie.split(';')[0]; const i = kv.indexOf('='); cookies.set(kv.slice(0,i), kv.slice(i+1));
    }
    return response;
  }
  async function json(path, options) {
    const response = await request(path, options);
    assert(response.ok, `HTTP ${response.status} bei ${options?.method || 'GET'} ${path}`);
    return response.json();
  }
  async function login() {
    const email = config.KEPLURA_ADMIN_BOOTSTRAP_EMAIL, password = config.KEPLURA_ADMIN_BOOTSTRAP_PASSWORD;
    assert(email && password, 'Admin-Zugang fehlt.');
    csrf = (await json('/api/v1/auth/login', {method:'POST', body:{email,password}})).csrfToken;
    assert(csrf, 'CSRF-Nachweis fehlt.');
  }
  return {request, json, login};
}
module.exports = {arg, createClient};
