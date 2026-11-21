/** Local fixture server ONLY: real browser login, reload, DELETE and mandatory password change. */
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const ui = process.env.SECURITY_FIXES_UI || 'http://localhost:1420';
const api = process.env.SECURITY_FIXES_API || 'http://localhost:18090';
for (const url of [ui, api]) assert.equal(new URL(url).hostname, 'localhost', 'Use an isolated localhost fixture server');
const email = process.env.SECURITY_FIXES_EMAIL;
const password = process.env.SECURITY_FIXES_PASSWORD;
assert(email && password, 'Set SECURITY_FIXES_EMAIL/PASSWORD for the local bootstrap ADMIN fixture');
const steps = [];
const pass = step => { steps.push(step); console.log(JSON.stringify({ step, ok: true })); };
async function login(page, email, password) {
  await page.goto(ui);
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill(password);
  await page.getByTestId('login-submit').click();
}
async function request(page, path, method = 'GET', body) {
  return page.evaluate(async ({ api, path, method, body }) => {
    const csrfResponse = await fetch(`${api}/api/v1/auth/csrf`, { credentials: 'include' });
    const { csrfToken } = await csrfResponse.json();
    const response = await fetch(api + path, {
      method, credentials: 'include',
      headers: method === 'GET' ? {} : { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: response.status, body: response.status === 204 ? null : await response.json() };
  }, { api, path, method, body });
}
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  try {
    const admin = await browser.newContext({ locale: 'de-DE' });
    const page = await admin.newPage();
    await login(page, email, password);
    await page.getByTestId('sidebar-nav-admin').waitFor();
    await page.reload();
    await page.getByTestId('sidebar-nav-admin').waitFor();
    const profile = await request(page, '/api/v1/auth/me');
    assert.equal(profile.status, 200); assert.equal(profile.body.role, 'ADMIN');
    assert(profile.body.userId > 0 && profile.body.displayName);
    pass('real-admin-login-and-reload-retain-profile-and-navigation');

    const created = await request(page, '/api/v1/admin/mcp-servers', 'POST', {
      name: `Browser fixture ${Date.now()}`, url: 'http://127.0.0.1:9', enabled: false,
    });
    assert.equal(created.status, 201);
    const deleted = await request(page, `/api/v1/admin/mcp-servers/${created.body.id}`, 'DELETE');
    assert.equal(deleted.status, 204);
    pass('real-admin-delete-succeeds-through-browser-cors-and-csrf');

    const org = await request(page, '/api/v1/orgs', 'POST', { name: `Security fixture ${Date.now()}` });
    assert.equal(org.status, 201);
    const colleague = `browser-fixture-${Date.now()}@example.test`;
    const member = await request(page, `/api/v1/orgs/${org.body.id}/members`, 'POST', {
      email: colleague, displayName: 'Synthetic colleague',
    });
    assert.equal(member.status, 201); assert(member.body.startPassword);
    const separate = await browser.newContext({ locale: 'de-DE' });
    const userPage = await separate.newPage();
    await login(userPage, colleague, member.body.startPassword);
    await userPage.getByTestId('change-password-view').waitFor();
    await userPage.reload();
    await userPage.getByTestId('change-password-view').waitFor();
    await userPage.getByTestId('cp-current').fill(member.body.startPassword);
    const replacement = 'Synthetic-browser-password-2026';
    await userPage.getByTestId('cp-new').fill(replacement);
    await userPage.getByTestId('cp-repeat').fill(replacement);
    await userPage.getByTestId('cp-submit').click();
    await userPage.getByTestId('change-password-view').waitFor({ state: 'hidden' });
    const updated = await request(userPage, '/api/v1/auth/me');
    assert.equal(updated.status, 200); assert.equal(updated.body.mustChangePassword, false);
    pass('real-start-password-login-reload-and-password-change');

    // Only this negative leg is simulated: profile retrieval is unavailable.
    await page.route('**/api/v1/auth/me', route => route.fulfill({ status: 503,
      headers: { 'Access-Control-Allow-Origin': ui, 'Access-Control-Allow-Credentials': 'true' },
      contentType: 'application/json', body: '{"error":"synthetic profile outage"}' }));
    await page.reload();
    await page.getByTestId('login-card').waitFor();
    assert.equal(await page.getByTestId('sidebar-nav-admin').count(), 0);
    pass('simulated-profile-outage-returns-to-login-without-generic-identity');
    console.log(JSON.stringify({ passed: steps.length, total: 4 }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
