// Static check: every named import resolves to a real export, and no module imports a symbol it
// doesn't use. Cheap guard against the ReferenceError class of bug the sea.mjs extraction can cause
// (a moved symbol still referenced, or a now-unused import left behind).
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';

const ROOT = resolve(process.argv[2] || 'web/js');
const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = join(d, e);
    if (statSync(p).isDirectory()) walk(p);
    else if (e.endsWith('.mjs') && !e.endsWith('.test.mjs')) files.push(p);
  }
})(ROOT);

const exportsOf = new Map();   // file -> Set(exported names)
const importsOf = new Map();   // file -> [{from, names}]
const srcOf = new Map();

for (const f of files) {
  const src = readFileSync(f, 'utf8');
  srcOf.set(f, src);
  const ex = new Set();
  // export const/let/function/class NAME
  for (const m of src.matchAll(/^export\s+(?:async\s+)?(?:const|let|var|function|class)\s+([A-Za-z_$][\w$]*)/gm)) ex.add(m[1]);
  // export { a, b as c }
  for (const m of src.matchAll(/^export\s*\{([^}]*)\}/gm))
    for (const part of m[1].split(','))
      { const t = part.trim(); if (t) ex.add((t.split(/\s+as\s+/)[1] || t).trim()); }
  exportsOf.set(f, ex);

  const imps = [];
  for (const m of src.matchAll(/^import\s*\{([^}]*)\}\s*from\s*["']([^"']+)["']/gm)) {
    const names = m[1].split(',').map(s => s.trim()).filter(Boolean)
      .map(s => { const [orig, alias] = s.split(/\s+as\s+/).map(x => x.trim()); return { orig, local: alias || orig }; });
    imps.push({ from: m[2], names, raw: m[0] });
  }
  importsOf.set(f, imps);
}

let bad = 0;
for (const f of files) {
  for (const imp of importsOf.get(f)) {
    if (!imp.from.startsWith('.')) continue;
    const target = resolve(dirname(f), imp.from);
    if (!exportsOf.has(target)) { console.log(`MISSING MODULE  ${f} -> ${imp.from}`); bad++; continue; }
    const ex = exportsOf.get(target);
    for (const n of imp.names) {
      if (!ex.has(n.orig)) { console.log(`NOT EXPORTED    ${f}: '${n.orig}' from ${imp.from}`); bad++; }
      // used anywhere outside the import statement itself?
      const body = srcOf.get(f).replace(imp.raw, '');
      if (!new RegExp(`\\b${n.local.replace(/\$/g, '\\$')}\\b`).test(body)) {
        console.log(`UNUSED IMPORT   ${f}: '${n.local}' from ${imp.from}`); bad++;
      }
    }
  }
}
console.log(bad ? `\n${bad} problem(s)` : `\nOK — ${files.length} modules, all named imports resolve and are used`);
process.exit(bad ? 1 : 0);
