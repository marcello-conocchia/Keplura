const { test } = require("node:test");
const assert = require("node:assert/strict");
const ts = require("typescript");
const fs = require("node:fs");
const path = require("node:path");

// Loads src/i18n.ts with a tiny stand-in for vue (only ref/watchEffect are used).
function load(file, mods) {
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, "../src", file), "utf8"),
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const m = { exports: {} };
  new Function("exports", "require", code)(m.exports, (id) => mods[id]);
  return m.exports;
}
const vue = { ref: (v) => ({ value: v }), watchEffect: () => {} };
const i18n = load("i18n.ts", { vue, "./i18n-en": load("i18n-en.ts", {}), "./i18n-server-en": load("i18n-server-en.ts", {}) });
i18n.setLocale("en");

test("message and label fields are translated", () => {
  const r = i18n.translateServer({
    message: "Die Rechte-Übernahme ist ausgeschaltet: Alle Mitglieder der Wissensbasis sehen alle Dateien.",
    broadGroups: [{ label: "Alle angemeldeten Personen" }],
    findings: [{ relativePath: "Vertraulich", categories: ["Gesundheit"], broadGroups: ["Domänen-Benutzer"] }],
  });
  assert.equal(r.message, "Permission mirroring is switched off: all members of the knowledge base see all files.");
  assert.equal(r.broadGroups[0].label, "All signed-in people");
  assert.deepEqual(r.findings[0].categories, ["Health"]);
  assert.deepEqual(r.findings[0].broadGroups, ["Domain users"]);
});

test("user data is never translated", () => {
  const r = i18n.translateServer({
    findings: [{ relativePath: "Vertraulich" }],
    folders: [{ folder: "Gesundheit", broadFiles: 1 }],
    displayName: "Verbunden.", location: "Person nicht gefunden.",
  });
  assert.equal(r.findings[0].relativePath, "Vertraulich");
  assert.equal(r.folders[0].folder, "Gesundheit");
  assert.equal(r.displayName, "Verbunden.");
  assert.equal(r.location, "Person nicht gefunden.");
});

test("labels use exact matches only, no patterns", () => {
  assert.equal(i18n.translateServer({ label: "Geld fehlt." }).label, "Geld fehlt.");
  assert.equal(i18n.translateServer({ message: "Name fehlt." }).message, "Name is missing.");
});

test("German UI leaves everything as it is", () => {
  i18n.setLocale("de");
  assert.equal(i18n.translateServer({ message: "Verbunden." }).message, "Verbunden.");
  i18n.setLocale("en");
});
