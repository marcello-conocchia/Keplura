#!/usr/bin/env node
// Checks that every German UI string passed to tr()/$t() in desktop/src has an
// English entry in desktop/src/i18n-en.ts. Usage:
//   node scripts/check-i18n.cjs          -> exit 1 on missing entries
//   node scripts/check-i18n.cjs --list   -> print all keys as JSON
const fs = require("fs");
const path = require("path");

const src = path.join(__dirname, "..", "desktop", "src");
const CALL = /(?:\btr|\$t)\(\s*("(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')/g;

function files(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((d) => {
    const p = path.join(dir, d.name);
    return d.isDirectory() ? files(p) : /\.(vue|ts)$/.test(d.name) ? [p] : [];
  });
}

// String literal as written in the source -> its value (JS escapes, either quote).
function unquote(lit) {
  const body = lit.slice(1, -1);
  let out = "";
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c !== "\\") { out += c; continue; }
    const n = body[++i];
    if (n === "n") out += "\n";
    else if (n === "t") out += "\t";
    else if (n === "u") { out += String.fromCharCode(parseInt(body.slice(i + 1, i + 5), 16)); i += 4; }
    else out += n; // \\ \" \' and anything else: the character itself
  }
  return out;
}

const keys = new Map();
for (const f of files(src)) {
  const text = fs.readFileSync(f, "utf8");
  for (const m of text.matchAll(CALL)) {
    const k = unquote(m[1]);
    if (!keys.has(k)) keys.set(k, path.relative(src, f));
  }
}

if (process.argv.includes("--list")) {
  console.log(JSON.stringify([...keys.keys()], null, 1));
  process.exit(0);
}

const en = fs.readFileSync(path.join(src, "i18n-en.ts"), "utf8");
const defined = new Set();
for (const m of en.matchAll(/^\s*("(?:[^"\\]|\\.)*")\s*:/gm)) defined.add(JSON.parse(m[1]));
const missing = [...keys].filter(([k]) => !defined.has(k) && /[a-zäöüß]/i.test(k));
for (const [k, f] of missing) console.error(`missing English text (${f}): ${JSON.stringify(k)}`);
const placeholders = (s) => (s.match(/\{\d+\}/g) || []).sort().join();
let bad = 0;
for (const m of en.matchAll(/^\s*("(?:[^"\\]|\\.)*")\s*:\s*("(?:[^"\\]|\\.)*")/gm)) {
  const [de, enText] = [JSON.parse(m[1]), JSON.parse(m[2])];
  if (placeholders(de) !== placeholders(enText)) { console.error(`placeholder mismatch: ${JSON.stringify(de)}`); bad++; }
}
if (missing.length || bad) process.exit(1);
console.log(`i18n: ${keys.size} strings, all translated`);
