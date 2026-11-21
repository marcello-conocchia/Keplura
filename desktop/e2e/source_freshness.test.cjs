const { test } = require("node:test");
const assert = require("node:assert/strict");
const ts = require("typescript");
const fs = require("node:fs");
const path = require("node:path");
const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, "../src/source-freshness.ts"), "utf8"), {compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText;
const compiled = {exports:{}};
// German UI strings: stand-in for src/i18n.ts (no Vue needed here)
const i18n = { tr: (s) => s, dateLocale: () => "de-DE" };
new Function("exports", "require", code)(compiled.exports, (m) => (m === "./i18n" ? i18n : require(m)));
const { sourceFreshness } = compiled.exports;
const now = new Date("2026-11-15T12:00:00Z");
test("Drei Kalenderjahre: Grenze ist frisch, erst davor älter", () => {
  assert.equal(sourceFreshness("2023-11-15T12:00:00Z", now).older, false);
  assert.equal(sourceFreshness("2023-11-15T11:59:59Z", now).older, true);
  assert.equal(sourceFreshness("2026-11-15T00:00:00Z", now).label, "15.11.2026");
});
test("Fehlende, ungültige und zukünftige Datumswerte erhalten keine Alterswarnung", () => {
  for (const value of [null, undefined, "", "kein Datum"]) {
    assert.equal(sourceFreshness(value, now).label, "unbekannt");
    assert.equal(sourceFreshness(value, now).older, false);
  }
  assert.equal(sourceFreshness("2030-01-01T00:00:00Z", now).older, false);
});
test("Datumsanzeige ist unabhängig von der lokalen Zeitzone; Schalttag begrenzt den Stichtag", () => {
  assert.equal(sourceFreshness("2020-04-12T23:30:00-05:00", now).label, "13.04.2020");
  assert.equal(sourceFreshness("2021-02-28T12:00:00Z", new Date("2024-02-29T12:00:00Z")).older, false);
});
