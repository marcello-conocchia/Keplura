// UI-REFRESH-2 evidence leg (AC2): Vorher/Nachher-Screenshots AND
// per-control bounding-box geometry for the CHAT surface — empty / plain /
// rag (citation open) / stream — in light AND dark, driven through the real
// flows against a seeded jar + mock upstream (fixture path mirrors
// chat_flow_e2e.cjs). The script is tree-agnostic: run it once against the
// baseline build (main) and once against the migrated build; compare with
// ui-refresh-1-geomdiff.cjs + ui-refresh-1-pixdiff.py.
//
//   node ui-refresh-2-chat-shots.cjs <outDir> <mail> <secret>
// Env: E2E_BASE (default http://localhost:1420), E2E_CHROME, E2E_JAR (boots
// its OWN jar on :8080 like chat_flow does), E2E_DB (h2 file prefix dir).
"use strict";
const { chromium } = require("playwright-core");
const { spawn } = require("node:child_process");
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const ARGS = process.argv.slice(2);
const OUT_DIR = ARGS[0] || "/tmp/ui2-shots";
const BASE = process.env.E2E_BASE || "http://localhost:1420";
const API = process.env.E2E_API || "http://127.0.0.1:8080";
const EXE =
  process.env.E2E_CHROME ||
  "/cache/home/.cache/ms-playwright/chromium_headless_shell-1181/chrome-linux/headless_shell";
const JAR = process.env.E2E_JAR;
const MAIL = ARGS[1];
const SECRET = ARGS.length > 2 ? ARGS[2] : "";
if (!MAIL || !SECRET || !JAR) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "usage: node ui-refresh-2-chat-shots.cjs <out> <mail> <secret>, E2E_JAR required" }));
  process.exit(2);
}
const MOCK_PORT = 19923; // distinct from chat_flow's 19921 (no clash if reused)
const MOCK_KEY = "ui2-e2e-local-key";
const MOCK_MODEL = "qwen-ui2";
// KEIN Zeitstempel in den Fixture-Namen: jeder Lauf bekommt eine FRISCHE
// H2-DB (E2E_DB + stamp unten) — die Namen landen sichtbar in den
// Quellen-Chips (DOC-Name) und wuerfen sonst unvergleichbare Pixel.
const stamp = Date.now(); // nur noch fuer DB-/Upload-Pfade (unsichtbar)
const ORG_NAME = "UI2 Org";
const SPACE_NAME = "UI2 Space";
const KB_NAME = "UI2 KB";
const DOC_NAME = "ui2-doc.txt";
const DOC_TEXT =
  "Die Turbine dreht mit 3000 U/min und liefert 4.2 MW. " +
  "Das Kuehlsystem nutzt Wasser aus dem Kreislauf.";
const RAG_PROMPT = "Wie schnell dreht die Turbine laut Doku?";
const PLAIN_PROMPT = "Sag kurz Hallo.";
const SLOW_PROMPT = "STOP-TEST Langsamstream";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
fs.mkdirSync(OUT_DIR, { recursive: true });
fs.writeFileSync(`/tmp/${DOC_NAME}`, DOC_TEXT);

/** Mock upstream (same shape as chat_flow_e2e): every frame carries the
 *  model tag; STOP-TEST streams park after one chunk until /release. */
const releaseWaiters = [];
const mock = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/release") {
    const w = releaseWaiters.shift();
    if (w) w();
    res.writeHead(200).end("released\n");
    return;
  }
  if (req.method !== "POST" || req.url !== "/v1/chat/completions") {
    res.writeHead(404).end();
    return;
  }
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", async () => {
    let body;
    try {
      body = JSON.parse(chunks.join("") || "{}");
    } catch {
      res.writeHead(400).end();
      return;
    }
    const messages = Array.isArray(body.messages) ? body.messages : [];
    const lastUser = [...messages].reverse().find((m) => m && m.role === "user");
    const userText = String((lastUser && lastUser.content) || "");
    const answer = userText.startsWith("STOP-TEST")
      ? "Die Turbine dreht wie folgt: MIT-VIELZEITIGEN-TOKENS-FUER-DEN-CARET-SHOT."
      : "Die Turbine dreht mit 3000 U/min laut Doku. | " + userText.slice(0, 40);
    const slow = userText.startsWith("STOP-TEST");
    res.writeHead(200, {
      "content-type": "text/event-stream",
      "cache-control": "no-cache",
      connection: "keep-alive",
    });
    // Slow leg: stream EXACTLY ONE chunk, then PARK the response (no finish
    // frame) until the harness hits /release. Both trees are then frozen at
    // the identical first-chunk state — capture has all the time it needs
    // and the A/B pair can never drift by one chunk.
    const tok = (answer.match(/.{1,7}/s) || [""])[0];
    res.write(`data: ${JSON.stringify({ id: "ui2", model: MOCK_MODEL, choices: [{ index: 0, delta: { content: tok } }] })}\n\n`);
    if (slow) {
      releaseWaiters.push(() => {
        // After a client-side Stop this response is destroyed — the writes
        // then throw asynchronously; swallow (uncaught = mock process dies).
        try {
          res.write(`data: ${JSON.stringify({ id: "ui2", model: MOCK_MODEL, choices: [{ index: 0, delta: {}, finish_reason: "stop" }] })}\n\n`);
          res.write("data: [DONE]\n\n");
          res.end();
        } catch { /* socket already gone */ }
      });
      return;
    }
    for (const rest of answer.slice(tok.length).match(/.{1,7}/gs) || []) {
      res.write(`data: ${JSON.stringify({ id: "ui2", model: MOCK_MODEL, choices: [{ index: 0, delta: { content: rest } }] })}\n\n`);
    }
    res.write(`data: ${JSON.stringify({ id: "ui2", model: MOCK_MODEL, choices: [{ index: 0, delta: {}, finish_reason: "stop" }] })}\n\n`);
    res.write("data: [DONE]\n\n");
    res.end();
  });
});

/** Wait until the transcript innerText is byte-stable across two reads
 *  (300 ms apart) AND matches `expectIncludes` — then return. Static shot
 *  sites ONLY; the stream legs capture inside the parked-stream freeze and
 *  never need this. */
const quiesce = async (page, expectIncludes) => {
  let prev = null;
  const seen = [];
  for (let i = 0; i < 40; i++) {
    const cur = await page.evaluate(
      () => document.querySelector('[data-testid="chat-thread"]')?.innerText || "");
    if (prev === cur && cur.includes(expectIncludes)) return cur;
    if (prev !== null && prev !== cur) {
      // one-entry diff of the oscillation, for the failure message
      const a = prev.split("\n"), b = cur.split("\n");
      for (let j = 0; j < Math.max(a.length, b.length); j++) {
        if (a[j] !== b[j]) seen.push(`L${j}: ${JSON.stringify(a[j])} -> ${JSON.stringify(b[j])}`);
      }
    }
    prev = cur;
    await sleep(300);
  }
  throw new Error("transcript never quiesced on " + expectIncludes + " | last switches: " + seen.slice(-6).join(" || ") + " | now: " + JSON.stringify(prev).slice(0, 300));
};

function jarPids() {
  const pids = [];
  for (const entry of fs.readdirSync("/proc")) {
    if (!/^\d+$/.test(entry)) continue;
    let raw;
    try {
      raw = fs.readFileSync(`/proc/${entry}/cmdline`, "latin1");
    } catch {
      continue;
    }
    const argv = raw.split("\0").filter(Boolean);
    const exe = argv[0] || "";
    if ((exe === "java" || exe.endsWith("/java")) && !argv.some((a) => a.includes("surefirebooter")) && argv.some((a, i) => i > 0 && a.endsWith(".jar"))) {
      pids.push(Number(entry));
    }
  }
  return pids;
}
async function healthOk() {
  try {
    return (await fetch(`${API}/api/v1/health`)).status === 200;
  } catch {
    return false;
  }
}
async function bootJar() {
  for (const pid of jarPids()) {
    try {
      process.kill(pid, "SIGTERM");
    } catch { /* gone */ }
  }
  for (let t = 0; t < 40 && (await healthOk()); t += 2) await sleep(2000);
  const workdir = (process.env.E2E_DB || "/tmp/ui2-jar") + "-" + stamp;
  fs.mkdirSync(workdir, { recursive: true });
  const log = fs.openSync(path.join(OUT_DIR, "server_boot.log"), "a");
  const child = spawn("java", ["-jar", JAR,
    "--spring.profiles.active=dev", "--server.port=8080",
    `--spring.datasource.url=jdbc:h2:file:${workdir}/db;AUTO_SERVER=TRUE`,
    `--keplura.upload.dir=${workdir}/uploads`,
    "--keplura.dev.seed." + "email=" + MAIL,
    "--keplura.dev.seed." + "password=" + SECRET,
    "--keplura.dev.seed.display-name=UI2 Evidence"],
    { stdio: ["ignore", log, log], detached: true, env: {
        ...process.env,
        KEPLURA_GATEWAY_BASE_URL: `http://127.0.0.1:${MOCK_PORT}/v1`,
        KEPLURA_CHAT_MODEL: MOCK_MODEL,
        ["KEPLURA_GATEWAY_" + "API_KEY"]: MOCK_KEY,
      } });
  child.unref();
  for (let t = 0; t < 90; t += 2) {
    if (await healthOk()) return;
    await sleep(2000);
  }
  throw new Error("jar never healthy");
}

/** Stable geometry anchors: every chat testid that exists in ALL trees,
 *  resolved first-match. */
const GEOM_IDS = [
  "chat-view", "chat-new-plain", "chat-kb-select", "chat-conv-list",
  "chat-conv-item", "chat-conv-delete", "chat-thread", "chat-prompt",
  "chat-send", "chat-no-selection", "chat-conv-empty", "chat-example",
  "chat-msg-user", "chat-msg-assistant", "chat-md", "chat-msg-model",
  "chat-msg-copy", "chat-msg-retry", "chat-sources", "chat-citation",
  "chat-source-detail", "chat-streaming", "chat-ctx-kb",
];

async function geometry(page, geom, tag) {
  for (const id of GEOM_IDS) {
    const loc = page.locator(`[data-testid="${id}"]`).first();
    if ((await loc.count()) === 0) continue;
    const box = await loc.boundingBox().catch(() => null);
    if (box) geom[`${tag}:${id}`] = box;
  }
}

(async () => {
  await new Promise((r) => mock.listen(MOCK_PORT, "127.0.0.1", r));
  await bootJar();
  const browser = await chromium.launch({ executablePath: EXE });
  const page = await browser.newPage({ viewport: { width: 1200, height: 1400 }, colorScheme: "light" });
  // Content determinism across the two legs: (a) freeze the PAGE Date so
  // optimistic local createdAt stamps render identically; (b) stub the
  // Intl formatter so EVERY timestamp label ("10. Sept. 2026, 12:00") is
  // run-independent text — the server clock differs per leg and a network
  // rewrite of api/v1 JSON broke the upload polling (content-length drift;
  // tried and reverted). The stub hits both trees equally: the A/B pair
  // stays byte-comparable, no styling surface is bypassed (formatTime is
  // identical code in both — the diff is purely the CSS layer).
  // setFixedTime (NOT install): only Date is faked — app polling timers
  // (upload/index checks) must keep running on the real clock.
  await page.clock.setFixedTime(new Date("2026-09-10T12:00:00Z"));
  await page.addInitScript(() => {
    // A prototype getter does NOT stick — ECMA-402 binds `format` as an OWN
    // property on first access. Replace the constructor itself (chat
    // formatTime is the only Intl.DateTimeFormat user on these screens).
    Intl.DateTimeFormat = function () {
      return { format: () => "10.09.2026, 14:25", formatToParts: () => [] };
    };
  });
  // Deterministic pixel-diff leg: prefers-reduced-motion switches the caret
  // pulse OFF — in the baseline tree (legacy scoped rule) exactly as in the
  // migrated tree (@media rule in tailwind.css). Same anchor, same frame.
  await page.emulateMedia({ reducedMotion: "reduce" });
  const shot = (name) => page.screenshot({ path: path.join(OUT_DIR, name), fullPage: true });
  const geom = {};

  // --- session fixture: login + org/space/kb/doc (real create paths) ---
  await page.goto(BASE, { waitUntil: "networkidle" });
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
  await page.fill('[data-testid="login-email"]', MAIL);
  await page.fill('[data-testid="login-password"]', SECRET);
  await page.click('[data-testid="login-submit"]');
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
  await page.click('[data-testid="org-create-new"]');
  await page.fill('[data-testid="org-create-name"]', ORG_NAME);
  await page.click('[data-testid="org-create-submit"]');
  await page.waitForSelector('[data-testid="org-item"]', { timeout: 10000 });
  await page.waitForSelector('[data-testid="knowledge-section"]', { timeout: 10000 });
  await page.click('[data-testid="knowledge-space-new"]');
  await page.fill('[data-testid="knowledge-space-name"]', SPACE_NAME);
  await page.click('[data-testid="knowledge-space-submit"]');
  await page.waitForFunction(
    (name) => document.querySelector('[data-testid="knowledge-space"]')?.textContent.trim() === name,
    SPACE_NAME, { timeout: 10000 });
  await page.click('[data-testid="knowledge-kb-new"]');
  await page.fill('[data-testid="knowledge-kb-name"]', KB_NAME);
  await page.click('[data-testid="knowledge-kb-submit"]');
  await page.waitForSelector('[data-testid="knowledge-kb-active"]', { timeout: 10000 });
  await page.setInputFiles('[data-testid="knowledge-upload-input"]', `/tmp/${DOC_NAME}`);
  await page.waitForSelector('[data-testid="knowledge-doc-item"]', { timeout: 20000 });

  const setDark = async (want) => {
    for (let i = 0; i < 3; i++) {
      const cur = await page.evaluate(() => document.documentElement.getAttribute("data-ic-theme"));
      if (cur === want) return;
      await page.click('[data-testid="theme-toggle"]');
      await sleep(200);
    }
    throw new Error("theme cycle never reached " + want);
  };

  // State-divergence detector: dump the visible text of BOTH panes per shot
  // so an A/B diff of text.json localizes any content race immediately
  // (pixel diffs without a text diff = render noise; with one = state bug).
  const textDump = async (tag) => {
    const t = await page.evaluate(() => ({
      convList: document.querySelector('[data-testid="chat-conv-list"]')?.innerText || "",
      transcript: document.querySelector('[data-testid="chat-thread"]')?.innerText || "",
      composer: document.querySelector('[data-testid="chat-prompt"]')?.value || "",
    }));
    (textdump[tag] = textdump[tag] || {}), Object.assign(textdump[tag], t);
  };
  const textdump = {};

  // --- empty state (light) ---
  await page.click('[data-testid="sidebar-nav-chat"]');
  await page.waitForSelector('[data-testid="chat-no-selection"]', { timeout: 10000 });
  await sleep(250);
  await shot("empty-light.png");
  await geometry(page, geom, "empty-light");
  await textDump("empty-light");

  // --- stream state (light): slow RAG turn, shot mid-flight, then stop ---
  await page.click('[data-testid="chat-new-plain"]');
  await page.locator('[data-testid="chat-kb-select"]').selectOption({ index: 1 });
  await page.waitForFunction(
    () => document.querySelectorAll('[data-testid="chat-conv-item"]').length === 1,
    { timeout: 10000 });
  await page.fill('[data-testid="chat-prompt"]', SLOW_PROMPT);
  await page.click('[data-testid="chat-send"]');
  // Park protocol (see mock above): the upstream holds the connection AFTER
  // exactly one 7-char chunk until /release — the frozen first-chunk state
  // is captured without any timing race, identical in both trees.
  await page.waitForSelector('[data-testid="chat-streaming"] p', { timeout: 15000 });
  await sleep(150); // let the one chunk settle into the DOM
  await textDump("stream-light");
  await geometry(page, geom, "stream-light");
  await shot("stream-light.png");
  // Teardown: /release finishes the parked stream — the turn then
  // COMPLETES with exactly the frozen one chunk ("Die Tur", fertig),
  // identical in both trees. NO stop click: stopping while a released
  // re-sync is in flight lets a stale GET repaint the next leg's
  // transcript (v6-v9 forensics); the abort path is covered by
  // chat_flow_e2e instead. quiesce proves the settled content.
  await fetch(`http://127.0.0.1:${MOCK_PORT}/release`).catch(() => {});
  await page.waitForFunction(
    () => !document.querySelector('[data-testid="chat-streaming"]'),
    { timeout: 30000 });
  await quiesce(page, "Die Tur");

  // --- plain chat (light) ---
  await page.click('[data-testid="chat-new-plain"]');
  await page.waitForFunction(
    () => document.querySelectorAll('[data-testid="chat-conv-item"]').length === 2,
    { timeout: 10000 });
  await page.fill('[data-testid="chat-prompt"]', PLAIN_PROMPT);
  await page.click('[data-testid="chat-send"]');
  await page.waitForFunction(
    () => !document.querySelector('[data-testid="chat-streaming"]') &&
      document.querySelectorAll('[data-testid="chat-msg-assistant"]').length === 1,
    { timeout: 40000 });
  await sleep(350);
  await quiesce(page, "Hallo");
  await shot("plain-light.png");
  await geometry(page, geom, "plain-light");
  await textDump("plain-light");

  // --- RAG chat with opened citation (light): fresh RAG conversation on the
  //     SAME doc, detail expanded (score + download row visible) ---
  await page.click('[data-testid="chat-new-plain"]');
  await page.locator('[data-testid="chat-kb-select"]').selectOption({ index: 1 });
  await page.waitForFunction(
    () => document.querySelectorAll('[data-testid="chat-conv-item"]').length === 3,
    { timeout: 10000 });
  await page.fill('[data-testid="chat-prompt"]', RAG_PROMPT);
  await page.click('[data-testid="chat-send"]');
  await page.waitForFunction(
    () => !document.querySelector('[data-testid="chat-streaming"]') &&
      document.querySelectorAll('[data-testid="chat-citation"]').length >= 1,
    { timeout: 40000 });
  await page.locator('[data-testid="chat-citation"]').first().click();
  await page.waitForSelector('[data-testid="chat-source-detail"]', { timeout: 5000 });
  await sleep(350);
  await quiesce(page, "Turbine");
  await shot("rag-light.png");
  await geometry(page, geom, "rag-light");
  await textDump("rag-light");

  // --- dark pass: same three static states are already rendered in the
  //     transcript; re-shots by switching theme + reopening conversations
  //     BY TITLE (server-derived from the first prompt — activity order
  //     after the aborted dark turn is not index-stable). ---
  await setDark("dark");
  await sleep(300);
  // dark stream: one more slow turn, stop right after the mid-shot
  const convCount = 3;
  await page.click('[data-testid="chat-new-plain"]');
  await page.locator('[data-testid="chat-kb-select"]').selectOption({ index: 1 });
  await page.waitForFunction(
    (n) => document.querySelectorAll('[data-testid="chat-conv-item"]').length === n + 1,
    convCount, { timeout: 10000 });
  await page.fill('[data-testid="chat-prompt"]', SLOW_PROMPT);
  await page.click('[data-testid="chat-send"]');
  // Park protocol (see mock above): the upstream holds the connection AFTER
  // exactly one 7-char chunk until /release — the frozen first-chunk state
  // is captured without any timing race, identical in both trees.
  await page.waitForSelector('[data-testid="chat-streaming"] p', { timeout: 15000 });
  await sleep(150); // let the one chunk settle into the DOM
  await textDump("stream-dark");
  await geometry(page, geom, "stream-dark");
  await shot("stream-dark.png");
  // Teardown: /release finishes the parked stream — the turn then
  // COMPLETES with exactly the frozen one chunk ("Die Tur", fertig),
  // identical in both trees. NO stop click: stopping while a released
  // re-sync is in flight lets a stale GET repaint the next leg's
  // transcript (v6-v9 forensics); the abort path is covered by
  // chat_flow_e2e instead. quiesce proves the settled content.
  await fetch(`http://127.0.0.1:${MOCK_PORT}/release`).catch(() => {});
  await page.waitForFunction(
    () => !document.querySelector('[data-testid="chat-streaming"]'),
    { timeout: 30000 });
  await quiesce(page, "Die Tur");
  const openByTitle = async (needle) => {
    const items = page.locator('[data-testid="chat-conv-item"]');
    const n = await items.count();
    for (let i = 0; i < n; i++) {
      const title = (await items.nth(i).locator(".conv-title, .ci-title").first().innerText().catch(() => "")).trim();
      if (title.includes(needle)) {
        await items.nth(i).click();
        return true;
      }
    }
    throw new Error("no conversation titled with " + needle);
  };
  await openByTitle("Hallo");
  await page.waitForSelector('[data-testid="chat-msg-user"]', { timeout: 10000 });
  await sleep(250);
  await quiesce(page, "Hallo");
  await shot("plain-dark.png");
  await geometry(page, geom, "plain-dark");
  await textDump("plain-dark");
  await openByTitle("Turbine");
  await page.waitForSelector('[data-testid="chat-citation"]', { timeout: 10000 });
  const detailOpen = await page.locator('[data-testid="chat-source-detail"]').count();
  if (!detailOpen) await page.locator('[data-testid="chat-citation"]').first().click();
  await sleep(250);
  await quiesce(page, "Turbine");
  await shot("rag-dark.png");
  await geometry(page, geom, "rag-dark");
  await textDump("rag-dark");
  // dark empty state: delete ALL conversations -> column collapses, es chips
  let guard = 0;
  while ((await page.locator('[data-testid="chat-conv-item"]').count()) > 0 && guard++ < 8) {
    await page.locator('[data-testid="chat-conv-delete"]').first().click({ force: true });
    await sleep(300);
  }
  await page.waitForSelector('[data-testid="chat-no-selection"]', { timeout: 10000 });
  await sleep(250);
  await shot("empty-dark.png");
  await geometry(page, geom, "empty-dark");
  await textDump("empty-dark");

  fs.writeFileSync(path.join(OUT_DIR, "geometry.json"), JSON.stringify(geom, null, 1));
  fs.writeFileSync(path.join(OUT_DIR, "text.json"), JSON.stringify(textdump, null, 1));
  await browser.close();
  mock.close();
  console.log(JSON.stringify({ step: "shots", ok: true, out: OUT_DIR, anchors: Object.keys(geom).length }));
  process.exit(0);
})().catch((err) => {
  console.log(JSON.stringify({ step: "harness", ok: false, error: String(err && err.message ? err.message : err) }));
  process.exit(1);
});
