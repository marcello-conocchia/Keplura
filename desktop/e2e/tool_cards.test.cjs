// TU-02: pure helpers of the chat tool call cards (src/tool-cards.ts), node:test like i18n_server.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const ts = require("typescript");
const fs = require("node:fs");
const path = require("node:path");
const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, "../src/tool-cards.ts"), "utf8"),
  { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const m = { exports: {} };
new Function("exports", code)(m.exports);
const c = m.exports;

const card = (over = {}) => ({ id: "a", promptMessageId: 1, tool: "t", source: "S", risk: "CRITICAL", arguments: {},
  status: "PENDING", needsConfirmation: true, result: null, error: null, createdAt: "2026-11-21T10:00:00Z",
  expiresAt: "2026-11-21T10:10:00Z", ...over });
const at = (iso) => Date.parse(iso);

test("every server status has an honest label; unknown values are 'unclear', never success", () => {
  for (const s of ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "REJECTED", "BLOCKED", "EXPIRED", "CANCELLED", "UNKNOWN"]) {
    assert.ok(c.statusMeta(s).label.length > 0, s);
  }
  assert.equal(c.statusMeta("SUCCEEDED").tone, "ok");
  assert.equal(c.statusMeta("SOMETHING_NEW").label, "Ausgang unklar");
  assert.notEqual(c.statusMeta("SOMETHING_NEW").tone, "ok");
});

test("only a pending, confirmable card before its deadline can be decided", () => {
  assert.equal(c.canDecide(card(), at("2026-11-21T10:05:00Z")), true);
  assert.equal(c.canDecide(card(), at("2026-11-21T10:10:00Z")), false); // deadline reached
  assert.equal(c.canDecide(card({ status: "RUNNING" }), at("2026-11-21T10:05:00Z")), false);
  assert.equal(c.canDecide(card({ needsConfirmation: false }), at("2026-11-21T10:05:00Z")), false);
  assert.equal(c.canDecide(card({ expiresAt: null }), at("2026-11-21T10:05:00Z")), false);
  assert.equal(c.canDecide(card({ expiresAt: "garbage" }), at("2026-11-21T10:05:00Z")), false);
});

test("arguments become plain text lines, long and nested values are shortened", () => {
  const e = c.argumentEntries({ id: "T-1", n: 3, ok: true, none: null, nested: { a: [1, 2] }, html: "<img src=x onerror=1>", long: "x".repeat(500) });
  assert.deepEqual(e.slice(0, 6).map((x) => x.value), ["T-1", "3", "true", "null", "{\"a\":[1,2]}", "<img src=x onerror=1>"]);
  assert.ok(e[6].value.endsWith(" …") && e[6].value.length < 410);
  assert.deepEqual(c.argumentEntries(null), []);
  assert.deepEqual(c.argumentEntries([1, 2]), []);
  const many = Object.fromEntries(Array.from({ length: 25 }, (_, i) => [`k${i}`, i]));
  assert.equal(c.argumentEntries(many).length, 20);
  assert.equal(c.hiddenArgumentCount(many), 5);
});

test("cards render after the answer of their question, or after the question if no answer exists", () => {
  const msgs = [{ id: 1, role: "user" }, { id: 2, role: "assistant" }, { id: 3, role: "user" }];
  assert.equal(c.cardsPromptAt(msgs, 0), null); // answered → cards go below the answer
  assert.equal(c.cardsPromptAt(msgs, 1), 1);
  assert.equal(c.cardsPromptAt(msgs, 2), 3); // unanswered question
  const grouped = c.groupByPrompt([card({ id: "a" }), card({ id: "b", promptMessageId: 3 }), card({ id: "c", promptMessageId: null })]);
  assert.deepEqual([...grouped.keys()], [1, 3]);
  assert.deepEqual(c.replaceCard([card({ id: "a" }), card({ id: "b" })], card({ id: "b", status: "REJECTED" })).map((x) => x.status),
    ["PENDING", "REJECTED"]);
});
