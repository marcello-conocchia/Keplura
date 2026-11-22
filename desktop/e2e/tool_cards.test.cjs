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

const card = (over = {}) => ({ id: "a", promptMessageId: 1, tool: "t", source: "S", risk: "CRITICAL", argumentsText: "{}", argumentFields: [],
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

test("a dependent WRITE card needs the same concrete decision as CRITICAL", () => {
  const followup = card({ risk: "WRITE", parentCallId: "parent", parentTool: "create_ticket" });
  assert.equal(c.canDecide(followup, at("2026-11-21T10:05:00Z")), true);
  assert.equal(c.canDecide({ ...followup, needsConfirmation: false }, at("2026-11-21T10:05:00Z")), false);
});

test("summary uses lossless server text, marks every shortening; exact text stays the basis", () => {
  const fields = [
    { name: "id", value: "9007199254740993" }, // beyond 2^53: must not become ...992
    { name: "html", value: "<img src=x onerror=1>" },
    { name: "long", value: "x".repeat(400) + "; delete_all" },
  ];
  const e = c.argumentEntries(fields);
  assert.equal(e[0].value, "9007199254740993");
  assert.equal(e[1].value, "<img src=x onerror=1>");
  assert.equal(e[2].truncated, true);
  assert.ok(!e[2].value.includes("delete_all"));
  assert.equal(c.summaryIsPartial(fields), true); // → card tells the person to check the full text
  const many = Array.from({ length: 21 }, (_, i) => ({ name: i === 20 ? "delete_all" : `k${i}`, value: String(i) }));
  assert.equal(c.argumentEntries(many).length, 20);
  assert.equal(c.hiddenArgumentCount(many), 1);
  assert.equal(c.summaryIsPartial(many), true);
  assert.equal(c.summaryIsPartial([{ name: "a", value: "1" }]), false);
  assert.deepEqual(c.argumentEntries(null), []);
  // the exact text is a plain string from the server — no JSON.parse, no rounding
  const card = { argumentsText: '{"id":9007199254740993,"delete_all":true}' };
  assert.ok(card.argumentsText.includes("9007199254740993") && card.argumentsText.includes("delete_all"));
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
