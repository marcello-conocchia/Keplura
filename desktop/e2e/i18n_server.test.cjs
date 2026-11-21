const { test } = require("node:test");
const assert = require("node:assert/strict");
const ts = require("typescript");
const fs = require("node:fs");
const path = require("node:path");
const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, "../src/i18n-server-en.ts"), "utf8"),
  { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const m = { exports: {} };
new Function("exports", code)(m.exports);
const { SERVER_EN, SERVER_EN_PATTERNS } = m.exports;

function en(text) {
  if (SERVER_EN[text] !== undefined) return SERVER_EN[text];
  for (const [p, r] of SERVER_EN_PATTERNS) if (p.test(text)) return text.replace(p, r);
  return text;
}

test("fixed server texts", () => {
  assert.equal(en("Der Abgleich läuft bereits."), "The sync is already running.");
  assert.equal(en("Alle angemeldeten Personen"), "All signed-in people");
});

test("texts with numbers and names", () => {
  assert.equal(en("Mehr als 20000 Dateien – nur die ersten wurden geprüft."), "More than 20000 files – only the first were checked.");
  assert.equal(en("3 Datei(en) konnten nicht übernommen werden."), "3 file(s) could not be imported.");
  assert.equal(en("Anna ist Mitglied von „Muster GmbH“."), "Anna is a member of “Muster GmbH”.");
  assert.equal(en("Lesen erlaubt über: Domänen-Benutzer, Vertrieb."), "Read allowed via: Domänen-Benutzer, Vertrieb.");
  assert.equal(en("Linux-Gruppe (GID 100)"), "Linux group (GID 100)");
});

test("tool risk and schema messages (TU-01)", () => {
  assert.equal(en("Unbekannte Risikoklasse. Erlaubt sind READ, WRITE und CRITICAL."), "Unknown risk class. Allowed are READ, WRITE and CRITICAL.");
  assert.equal(en("Das Eingabeschema wird nicht unterstützt (nicht unterstütztes Schlüsselwort: pattern)."),
    "The input schema is not supported (unsupported keyword: pattern).");
  assert.equal(en("Das Eingabeschema wird nicht unterstützt (ungültiger Typ: type)."), "The input schema is not supported (invalid value: type).");
});

test("user data stays as it is", () => {
  for (const s of ["Vertrieb – Angebote", "personal/Gehaltsliste.csv", "Muster GmbH", ""]) assert.equal(en(s), s);
});
