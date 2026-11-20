// UI-REFRESH-2 evidence leg (AC2): compare chat geometry.json from the
// baseline run (pre-migration tree) against the migrated run. Anchor keys
// are "<state>:<testid>" — an anchor only participates when it exists in
// BOTH trees (states are reached identically by the harness; a missing key
// on one side IS a finding when the other side has it in a state the
// harness always renders). Any edge moving more than TOL px = Befund.
//   node ui-refresh-2-geomdiff.cjs <old.json> <new.json> [tolPx]
"use strict";
const fs = require("node:fs");
const [oldP, newP, tolS] = process.argv.slice(2);
if (!oldP || !newP) { console.log(JSON.stringify({ step: "config", ok: false })); process.exit(2); }
const TOL = Number(tolS || "2");
const oldG = JSON.parse(fs.readFileSync(oldP, "utf8"));
const newG = JSON.parse(fs.readFileSync(newP, "utf8"));
let findings = 0;
const rows = [];
let compared = 0;
const keys = new Set([...Object.keys(oldG), ...Object.keys(newG)]);
for (const k of keys) {
  const a = oldG[k], b = newG[k];
  if (!b) { rows.push({ id: k, issue: "missing in new" }); findings++; continue; }
  if (!a) { rows.push({ id: k, issue: "new-only geometry" }); findings++; continue; }
  compared++;
  const d = ["x", "y", "width", "height"].map((p) => Math.abs(a[p] - b[p]));
  const worst = Math.max(...d);
  if (worst > TOL) { rows.push({ id: k, old: a, new: b, worst: +worst.toFixed(2) }); findings++; }
}
console.log(JSON.stringify({ step: "geomdiff2", ok: findings === 0, tolerancePx: TOL, compared, findings: rows }, null, 1));
process.exit(findings === 0 ? 0 : 1);
