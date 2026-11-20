// UI-REFRESH-1 evidence leg (AC4): compare geometry.json from the baseline
// run (main@e38c859) against the new run. Any control whose bounding box
// moved/changed by more than TOL px on any edge = Befund (exit 1).
//   node ui-refresh-1-geomdiff.cjs <old.json> <new.json> [tolPx]
"use strict";
const fs = require("node:fs");
const [oldP, newP, tolS] = process.argv.slice(2);
if (!oldP || !newP) { console.log(JSON.stringify({ step: "config", ok: false })); process.exit(2); }
const TOL = Number(tolS || "2");
const oldG = JSON.parse(fs.readFileSync(oldP, "utf8"));
const newG = JSON.parse(fs.readFileSync(newP, "utf8"));
let findings = 0;
const rows = [];
for (const id of Object.keys(oldG)) {
  const a = oldG[id], b = newG[id];
  if (!b) { rows.push({ id, issue: "missing in new" }); findings++; continue; }
  const d = ["x", "y", "width", "height"].map((k) => Math.abs(a[k] - b[k]));
  const worst = Math.max(...d);
  if (worst > TOL) { rows.push({ id, old: a, new: b, worst: +worst.toFixed(2) }); findings++; }
}
for (const id of Object.keys(newG)) if (!oldG[id]) rows.push({ id, issue: "new-only geometry" });
console.log(JSON.stringify({ step: "geomdiff", ok: findings === 0, tolerancePx: TOL, compared: Object.keys(oldG).length, findings: rows }, null, 1));
process.exit(findings === 0 ? 0 : 1);
