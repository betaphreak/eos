// Bake the Anbennar advisor portraits for the web view (docs/privy-council.md §3).
//
//   node web/build-advisors.mjs
//
// The advisor art lives FLAT in the Anbennar mod at gfx/interface/advisors/<culture>_<role>[_female].dds
// (male = no suffix, female = _female; classic uncompressed 77×77 BGRA). For each culture we pack one
// small WebP grid — columns = the 22 EU4 advisor roles (fixed order), rows = [male, female] — so any
// feature can show `culture × role × gender` by cell. Per-culture files are the reusable, lazy-loadable
// unit (a session shows only the POV colony's culture(s), not all ~35). A manifest records the layout,
// the advisor-role→art-role map, and the fallback culture.
//
// Graceful: with the Anbennar cache absent / a fetch failing, a culture's missing cells are left
// transparent and the culture is skipped if it has no art at all — the frontend falls back (to the
// fallback culture, then the initials tile). Enumerates the folder live so it self-updates with the mod.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodeDds } from './dds.mjs';
import { prefetch as anbPrefetch, get as anbGet, REF } from './anbennar.mjs';
import sharp from 'sharp';

const WEB = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.join(WEB, 'assets', 'advisors');
const OUT_MANIFEST = path.join(OUT_DIR, 'portraits.json');
const ADV_DIR = 'gfx/interface/advisors';
const CELL = 77;   // the source portraits are 77×77

// the 22 EU4 advisor roles (fixed column order; the frontend indexes by this). Longest-suffix match
// strips the role off a filename to recover the culture, so order here is display order, not matching.
const ROLES = [
  'natural_scientist', 'diplomat', 'theologian', 'navigator',   // the 4 privy-council advisors first
  'statesman', 'grand_captain', 'master_of_mint', 'treasurer', 'trader', 'spymaster',
  'inquisitor', 'court_mage', 'philosopher', 'artist', 'quartermaster', 'recruitmaster',
  'commandant', 'fortification_expert', 'colonial_governor', 'army_organiser', 'army_reformer',
  'naval_reformer',
];
const GENDERS = ['male', 'female'];

// the privy-council advisor id → its portrait role (docs/privy-council.md §0)
const ADVISOR_ROLE = { technology: 'natural_scientist', foreign: 'diplomat', religion: 'theologian', globe: 'navigator' };
const FALLBACK_CULTURE = 'harimari';   // Royal Harimari — the representative for art-less races (Rahen demo)

// --- enumerate the advisors folder: the anbennar.mjs disk cache (a junction to a local clone serves
// this with no network), else the GitLab tree API (paginated) ---------------------------------------
async function listAdvisorFiles() {
  const cacheDir = anbGet(ADV_DIR);   // the cached advisors dir, if warmed (or junctioned to a clone)
  if (cacheDir && fs.existsSync(cacheDir) && fs.statSync(cacheDir).isDirectory())
    return fs.readdirSync(cacheDir).filter(n => n.toLowerCase().endsWith('.dds'));
  const base = `https://gitlab.com/api/v4/projects/anbennar%2Fanbennar-eu4-dev/repository/tree`;
  const names = [];
  for (let page = 1; page <= 30; page++) {
    const url = `${base}?path=${encodeURIComponent(ADV_DIR)}&ref=${REF}&per_page=100&page=${page}`;
    const r = await fetch(url);
    if (!r.ok) break;
    const batch = await r.json();
    if (!batch.length) break;
    for (const e of batch) if (e.type === 'blob' && e.name.endsWith('.dds')) names.push(e.name);
    if (batch.length < 100) break;
  }
  return names;
}

// parse "<culture>_<role>[_female].dds" → {culture, role, gender}; null if no known role matches
function parseName(name) {
  let s = name.replace(/\.dds$/i, '');
  let gender = 'male';
  if (/_female$/i.test(s)) { gender = 'female'; s = s.replace(/_female$/i, ''); }
  // longest role suffix wins (roles contain underscores, e.g. natural_scientist)
  let role = null, culture = null;
  for (const r of ROLES) {
    if (s === r) { culture = 'advisor'; role = r; break; }   // the generic advisor_court_mage
    if (s.endsWith('_' + r) && (!role || r.length > role.length)) { role = r; culture = s.slice(0, -(r.length + 1)); }
  }
  return role ? { culture, role, gender } : null;
}

// decode a DDS to a CELL×CELL RGBA buffer (native 77×77; box-average if a source ever differs); null on failure
function ddsCell(file) {
  let img;
  try { img = decodeDds(fs.readFileSync(file)); } catch { return null; }
  const { width: w, height: h, rgba } = img;
  if (w === CELL && h === CELL) return Buffer.from(rgba);
  const out = Buffer.alloc(CELL * CELL * 4);
  for (let j = 0; j < CELL; j++)
    for (let i = 0; i < CELL; i++) {
      const sx = Math.min(w - 1, Math.floor(i * w / CELL)), sy = Math.min(h - 1, Math.floor(j * h / CELL));
      const so = (sy * w + sx) * 4, dst = (j * CELL + i) * 4;
      out[dst] = rgba[so]; out[dst + 1] = rgba[so + 1]; out[dst + 2] = rgba[so + 2]; out[dst + 3] = rgba[so + 3];
    }
  return out;
}

// --- main ----------------------------------------------------------------------------
const files = await listAdvisorFiles().catch(() => []);
if (!files.length) {
  console.log('No advisor art listed (Anbennar unreachable?) — skipping the bake (frontend falls back). ');
  process.exit(0);
}

// group by culture → { culture: { role: { male, female } } }
const byCulture = {};
for (const name of files) {
  const p = parseName(name);
  if (!p) continue;
  ((byCulture[p.culture] ||= {})[p.role] ||= {})[p.gender] = `${ADV_DIR}/${name}`;
}

// warm the disk cache (parallel) so the synchronous decode below is a local read
await anbPrefetch(files.map(n => `${ADV_DIR}/${n}`));

fs.mkdirSync(OUT_DIR, { recursive: true });
const gridW = ROLES.length * CELL, gridH = GENDERS.length * CELL;
const cultures = {};
let baked = 0, cells = 0, missing = 0;

for (const [culture, roles] of Object.entries(byCulture)) {
  const sheet = Buffer.alloc(gridW * gridH * 4);   // transparent by default
  const have = [];
  for (let c = 0; c < ROLES.length; c++)
    for (let g = 0; g < GENDERS.length; g++) {
      const rel = roles[ROLES[c]]?.[GENDERS[g]];
      if (!rel) continue;
      const local = anbGet(rel);
      const cell = local ? ddsCell(local) : null;
      if (!cell) { missing++; continue; }
      const ox = c * CELL, oy = g * CELL;
      for (let y = 0; y < CELL; y++)
        cell.copy(sheet, ((oy + y) * gridW + ox) * 4, y * CELL * 4, (y + 1) * CELL * 4);
      if (g === 0 && !have.includes(ROLES[c])) have.push(ROLES[c]);
      cells++;
    }
  if (!have.length) continue;   // no usable art for this culture
  await sharp(sheet, { raw: { width: gridW, height: gridH, channels: 4 } })
    .webp({ quality: 88, alphaQuality: 100, effort: 5 }).toFile(path.join(OUT_DIR, `${culture}.webp`));
  cultures[culture] = { roles: have };
  baked++;
}

const manifest = {
  ref: REF, cell: CELL, roles: ROLES, genders: GENDERS,
  advisorRole: ADVISOR_ROLE, fallbackCulture: FALLBACK_CULTURE,
  cultures,   // culture -> { roles: [available role slugs] }; the WebP is assets/advisors/<culture>.webp
};
fs.writeFileSync(OUT_MANIFEST, JSON.stringify(manifest));

console.log(`Baked ${baked} culture portrait sheets → web/assets/advisors/ (${cells} cells, ${missing} missing) `
  + `at ${gridW}×${gridH} each; manifest ${path.relative(WEB, OUT_MANIFEST)} (${Object.keys(cultures).length} cultures).`);
