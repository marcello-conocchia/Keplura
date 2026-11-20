// DARK-FS evidence run (PO-Instruktion 09.09.): proves Dark-Mode tokens,
// mode persistence and the fullscreen toggle in headless Chromium against
// the REAL vite dev build (browser fallback path of fullscreen.ts) and a
// seeded server jar for the logged-in legs.
//
// Flow (each step a JSON line; exit 0 only when every step passed):
//   1 system=light        -> html data-ic-theme=light, canvas #F5F5F7 (tokens
//                             applied through --ic-* only)
//   2 OS flips dark       -> matchMedia change listener re-resolves: bg
//                             #17171A (mode "system" follows the live OS)
//   3 explicit dark       -> login-screen theme-toggle clicked twice
//                             clicked system->light->dark; now an OS flip
//                             back to light must NOT change anything
//   4 reload persistence  -> pre-paint script applies dark BEFORE the app
//                             boots (attr + canvas bg dark on first probe)
//   5 login + sidebar     -> real session; sidebar renders dark content
//                             (#1D1D20); header toggle cycles to light and
//                             the tokens flip app-wide
//   6 fullscreen button   -> click sidebar fullscreen-toggle: browser path
//                             enters document.fullscreen (fullscreenchange
//                             mirrors into the button aria-label/title)
//   7 toggle exits        -> click again: document.exitFullscreen; label
//                             flips back (native Escape-exit is NOT
//                             implemented by headless_shell — the Escape
//                             path is proven in packaged_dark_e2e.py)
//   8 back to system      -> cycle to "system", reload, attr=light with
//                             colorScheme light -> documented default state
//
// Credentials positional argv (convention of knowledge/chat flows):
//   node dark_flow_e2e.cjs <mail> <secret>
// Env: E2E_BASE (default http://localhost:1420), E2E_CHROME, E2E_OUT
// (default /tmp/dark-fs-evidence). This flow assumes an EXTERNALLY running
// vite (:1420) and seeded jar (:8080) like knowledge_flow_e2e does — the
// ci-local.sh "dark" suite wrapper boots both.
//
// .cjs on purpose: desktop/package.json is "type": "module"; CommonJS keeps
// working with playwright-core via NODE_PATH — see e2e/README.md.
"use strict";
const { chromium } = require("playwright-core");
const fs = require("node:fs");
const path = require("node:path");


/** REDESIGN: after login the appearance lives in the account menu (explicit choice, no cycling). */
async function setTheme(page, mode) {
  await page.click('[data-testid="sidebar-user"]');
  const id = mode === "light" ? "theme-light" : mode === "dark" ? "theme-dark" : "theme-toggle";
  await page.click(`[data-testid="${id}"]`);
}
const ARGS = process.argv.slice(2);
const BASE = process.env.E2E_BASE || "http://localhost:1420";
const EXE =
  process.env.E2E_CHROME ||
  "/cache/home/.cache/ms-playwright/chromium_headless_shell-1181/chrome-linux/headless_shell";
const OUT_DIR = process.env.E2E_OUT || "/tmp/dark-fs-evidence";
const EMAIL = ARGS[0];
const PASSWORD = ARGS.length > 1 ? ARGS[1] : "";
if (!EMAIL || !PASSWORD) {
  console.log(JSON.stringify({ step: "config", ok: false, error: "pass email+password via argv" }));
  process.exit(2);
}

// UI-04 (15.11.): token ground-truth is the authoritative System-1.0 source
// docs/design/system-v1/tokens.json (theme.css is generated from it and checked
// at prebuild) — read it instead of hard-coding values that drift.
const TOKENS = JSON.parse(fs.readFileSync(
  path.join(__dirname, "..", "..", "docs", "design", "system-v1", "tokens.json"), "utf8")).themes;
const rgb = (hex) => {
  const n = parseInt(hex.replace("#", ""), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
};
const LIGHT_CANVAS = rgb(TOKENS.light.canvas);
const DARK_CANVAS = rgb(TOKENS.dark.canvas);
const DARK_CONTENT = rgb(TOKENS.dark.content);
const DARK_TEXT = rgb(TOKENS.dark.text);

fs.mkdirSync(OUT_DIR, { recursive: true });

let stepNo = 0;
function log(step, extra = {}) {
  console.log(JSON.stringify({ step, no: ++stepNo, ok: true, ...extra }));
}
function fail(step, error, detail) {
  console.log(JSON.stringify({ step, no: ++stepNo, ok: false, error, ...(detail ? { detail } : {}) }));
  process.exit(1);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Theme attribute + the two ground-truth computed colours. */
async function themeState(page) {
  return page.evaluate(() => ({
    attr: document.documentElement.getAttribute("data-ic-theme"),
    canvas: getComputedStyle(document.body).backgroundColor,
    text: getComputedStyle(document.body).color,
  }));
}

async function assertDark(page, where) {
  const st = await themeState(page);
  if (st.attr !== "dark" || st.canvas !== DARK_CANVAS) {
    fail("tokens-dark", `not dark at ${where}`, JSON.stringify(st));
  }
  return st;
}
async function assertLight(page, where) {
  const st = await themeState(page);
  if (st.attr !== "light" || st.canvas !== LIGHT_CANVAS) {
    fail("tokens-light", `not light at ${where}`, JSON.stringify(st));
  }
  return st;
}

(async () => {
  const browser = await chromium.launch({ executablePath: EXE });
  const page = await browser.newPage({
    viewport: { width: 1100, height: 800 },
    colorScheme: "light",
  });
  const shot = (name) => page.screenshot({ path: path.join(OUT_DIR, name) });

  // --- 1. fresh profile = mode "system"; OS emulated light -> light tokens ---
  await page.goto(BASE, { waitUntil: "networkidle" });
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
  await assertLight(page, "login/system-light");
  await shot("01-light-system.png");
  log("system-light", { note: "attr=light via prefers-color-scheme resolution" });

  // --- 2. live OS flip to dark: the matchMedia listener must follow ---
  await page.emulateMedia({ colorScheme: "dark" });
  await page.waitForFunction(
    () => document.documentElement.getAttribute("data-ic-theme") === "dark",
    undefined,
    { timeout: 5000 },
  );
  await assertDark(page, "system-dark");
  await shot("02-dark-system.png");
  log("system-follows-os", { note: "emulated OS dark re-resolved live" });

  // --- 3. explicit dark via the login-screen control (system->light->dark) ---
  await page.click('[data-testid="theme-toggle"]');
  await page.waitForFunction(
    () => document.documentElement.getAttribute("data-ic-theme") === "light",
    undefined,
    { timeout: 5000 },
  );
  await page.click('[data-testid="theme-toggle"]');
  await assertDark(page, "explicit-dark");
  const stored = await page.evaluate(() => localStorage.getItem("keplura.theme.mode"));
  if (stored !== "dark") fail("persist-write", "localStorage key wrong", String(stored));
  // explicit dark must IGNORE a contradicting OS setting…
  await page.emulateMedia({ colorScheme: "light" });
  await sleep(300);
  await assertDark(page, "explicit-dark-vs-os-light");
  log("explicit-dark", { stored, note: "OS flip back does not override an explicit choice" });

  // --- 4. reload: the pre-paint script applies dark before the app boots ---
  await page.reload({ waitUntil: "domcontentloaded" });
  const early = await page.evaluate(() => ({
    attr: document.documentElement.getAttribute("data-ic-theme"),
    appMounted: !!document.querySelector("#app .login, #app .shell"),
  }));
  if (early.attr !== "dark") fail("prepaint-attr", "attr not dark right after domContentLoaded", JSON.stringify(early));
  await page.waitForSelector('[data-testid="login-card"]', { timeout: 15000 });
  await assertDark(page, "after-reload");
  await shot("03-dark-after-reload.png");
  log("reload-persistence", { attrAtDOMContentLoaded: early.attr });

  // --- 5. real session: sidebar + dark content surface ---
  await page.fill('[data-testid="login-email"]', EMAIL);
  await page.fill('[data-testid="login-password"]', PASSWORD);
  await page.click('[data-testid="login-submit"]');
  await page.waitForSelector('[data-testid="sidebar"]', { timeout: 15000 });
  const side = await page.evaluate(() => {
    const el = document.querySelector('[data-testid="sidebar"]');
    return {
      bg: getComputedStyle(el).backgroundColor,
      fg: getComputedStyle(el).color,
    };
  });
  if (side.bg !== DARK_CANVAS /* REDESIGN v3: sidebar uses --ic-sidebar (= canvas tone) */ || side.fg !== DARK_TEXT) {
    fail("sidebar-dark-tokens", "sidebar not dark", JSON.stringify(side));
  }
  // --- 5b. RELEASE-GATE-1 version leg: the sidebar pill must show the
  // serverVersion the freshly-built jar reports at /api/v1/system/info —
  // "eine Version, drei Orte" proven live in the running UI (0.1.0).
  const apiVersion = await page.evaluate(async () => {
    const r = await fetch("http://localhost:8080/api/v1/system/info");
    return (await r.json()).serverVersion;
  });
  if (apiVersion !== "0.1.0") {
    fail("version-api", "server jar reports wrong version", String(apiVersion));
  }
  const pill = await page.textContent('[data-testid="sidebar-version"]').catch(() => null);
  if (!pill || !pill.includes(`v${apiVersion}`)) {
    fail("version-pill", "sidebar does not show the server version", String(pill));
  }
  await shot("04-dark-session.png");
  log("sidebar-dark", { ...side, serverVersion: apiVersion, pill: pill.trim() });
  // cycle to light from the sidebar header — flips app-wide incl. this panel
  await setTheme(page, "system"); // dark -> system (OS light = light)
  await assertLight(page, "sidebar-cycle-to-system");
  await shot("05-light-session.png");
  log("sidebar-cycle", { note: "dark -> system(=light) via header control" });

  // --- 6. fullscreen toggle (browser fallback path: Fullscreen API) ---
  // REDESIGN: the control lives in the account menu (open it, act, re-open to read the label).
  await page.click('[data-testid="sidebar-user"]');
  await page.click('[data-testid="fullscreen-toggle"]');
  await page.waitForFunction(() => document.fullscreenElement !== null, undefined, { timeout: 5000 });
  await sleep(200); // let fullscreenchange mirror into the button label
  await page.click('[data-testid="sidebar-user"]');
  const enterLabel = await page.getAttribute('[data-testid="fullscreen-toggle"]', "aria-label");
  if (enterLabel !== "Vollbild verlassen") {
    fail("fs-enter-label", "button label did not flip", String(enterLabel));
  }
  await shot("06-fullscreen.png");
  log("fullscreen-enter", { enterLabel });

  // --- 7. toggle again exits (headless_shell implements no native
  // Escape-exit — that leg is proven on the packaged runtime, where OUR
  // handler owns the key; here the button round-trip + fullscreenchange
  // mirror is what the browser path must prove) --------------------------
  await page.click('[data-testid="fullscreen-toggle"]'); // menu is still open from the label read
  await page.waitForFunction(() => document.fullscreenElement === null, undefined, { timeout: 5000 });
  await sleep(200);
  await page.click('[data-testid="sidebar-user"]');
  const exitLabel = await page.getAttribute('[data-testid="fullscreen-toggle"]', "aria-label");
  await page.keyboard.press("Escape");
  if (exitLabel !== "Vollbild aktivieren") {
    fail("fs-exit-label", "button label did not flip back", String(exitLabel));
  }
  log("fullscreen-exit", { exitLabel });

  // --- 8. back to system default + persistence of the reset ---
  await setTheme(page, "light"); // system -> light
  await setTheme(page, "dark"); // light -> dark
  await setTheme(page, "system"); // dark -> system
  const st8 = await themeState(page);
  if (st8.attr !== "light") fail("reset-system", "system should resolve light (OS light)", JSON.stringify(st8));
  const stored8 = await page.evaluate(() => localStorage.getItem("keplura.theme.mode"));
  if (stored8 !== "system") fail("reset-persist", "storage should read system", String(stored8));
  log("reset-to-system", { stored: stored8, resolved: st8.attr });

  await browser.close();
  log("done", { steps: stepNo, note: "DARK-FS flow fully green" });
})().catch((err) => {
  console.log(JSON.stringify({ step: "harness", ok: false, error: String(err && err.message ? err.message : err) }));
  process.exit(1);
});
