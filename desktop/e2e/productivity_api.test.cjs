const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");
const { test } = require("node:test");
const ts = require("typescript");

const source = readFileSync(join(__dirname, "../src/productivity-api.ts"), "utf8");
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
});

for (const desktop of [true, false]) {
  test("OAuth start sends a named desktop query parameter (" + desktop + ")", async () => {
    const api = { exports: {} };
    const requests = [];
    const serverUrl = "https://keplura.example.test";
    const payload = { flowId: "flow", authorizationUrl: "https://provider.example.test/authorize" };
    const modules = {
      "./api": { serverUrl },
      "./i18n": { translateServer: (value) => value },
      "./transport": {
        inTauri: () => desktop,
        desktopFetch: async (url, init) => {
          requests.push({ url: new URL(url), method: init.method });
          return Response.json(payload);
        },
      },
    };
    new Function("exports", "require", outputText)(api.exports, (name) => modules[name]);

    assert.deepEqual(await api.exports.startConnection(1, "openapi", 2), { kind: "ok", value: payload });
    assert.equal(requests.length, 1);
    assert.equal(requests[0].method, "POST");
    assert.equal(requests[0].url.pathname, "/api/v1/orgs/1/connections/openapi/2/start");
    assert.deepEqual([...requests[0].url.searchParams], [["desktop", String(desktop)]]);
  });
}
