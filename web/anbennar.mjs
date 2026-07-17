// On-demand fetch of Anbennar EU4 mod files from GitLab at the locked ref, cached under
// .anbennar-cache/<ref>/. The Node sibling of com.civstudio.data.AnbennarFiles (same cache layout, so
// the two share downloads) — the raw mod files are not vendored. See docs/anbennar-files.md.
//
// The build's synchronous bakers call get(rel) which serves straight from the disk cache; warm it
// first with the async prefetch(paths) (parallel fetch) so a bake pays no per-file round trip. The
// Anbennar repo is public, so no token is needed.
//
//   import { prefetch, get, REF } from './anbennar.mjs';
//   await prefetch(['gfx/interface/resources.dds']);        // warm (parallel)
//   const dds = fs.readFileSync(get('gfx/interface/resources.dds'));   // sync, from cache
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = 'https://gitlab.com/anbennar/anbennar-eu4-dev';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const LOCK = path.join(ROOT, 'civstudio-engine/src/main/resources/anbennar-source.lock');

export const REF = (() => {
  try { const s = fs.readFileSync(LOCK, 'utf8').trim(); if (s) return s; } catch { /* fall through */ }
  return 'new-master';
})();

const CACHE = path.join(process.env.ANBENNAR_CACHE_DIR || path.join(ROOT, '.anbennar-cache'), REF);

const norm = p => p.replace(/\\/g, '/').replace(/^\/+/, '').replace(/\/+$/, '');
// url-encode each path segment, preserving '/' (EU4 filenames contain spaces)
const encPath = p => p.split('/').map(s => encodeURIComponent(s).replace(/%20/g, '%20')).join('/');
const rawUrl = rel => `${BASE}/-/raw/${REF}/${encPath(rel)}`;

function writeCache(local, buf) {
  fs.mkdirSync(path.dirname(local), { recursive: true });
  const tmp = local + '.dl-' + process.pid + '.part';
  fs.writeFileSync(tmp, buf);
  fs.renameSync(tmp, local);
}

async function fetchRaw(rel) {
  const r = await fetch(rawUrl(rel));
  if (r.status === 404) throw new Error('anbennar file not found at ' + REF + ': ' + rel);
  if (!r.ok) throw new Error('fetch ' + r.status + ' for ' + rel);
  return Buffer.from(await r.arrayBuffer());
}

// run async thunks with bounded concurrency (kept modest — GitLab throttles the raw endpoint)
async function pool(items, worker, concurrency = 8) {
  let i = 0;
  const runners = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (i < items.length) { const idx = i++; await worker(items[idx], idx); }
  });
  await Promise.all(runners);
}

/**
 * Warm the disk cache in parallel so a later synchronous {@link get} hits it with no round trip.
 * `paths` are mod-relative (e.g. `gfx/interface/resources.dds`). Failed fetches are swallowed so a
 * bake degrades to its fallback. Call once before a bake.
 */
export async function prefetch(paths = [], concurrency = 8) {
  await pool([...new Set(paths.map(norm))], async rel => {
    try {
      const local = path.join(CACHE, rel);
      if (!fs.existsSync(local)) writeCache(local, await fetchRaw(rel));
    } catch { /* leave to the caller's fallback */ }
  }, concurrency);
}

/** The local cached file for a mod-relative path, or null if it was not warmed / could not be fetched. */
export function get(rel) {
  const local = path.join(CACHE, norm(rel));
  return fs.existsSync(local) ? local : null;
}

// https://gitlab.com/api/v4/projects/<url-encoded group/project>
function projectApiBase() {
  const u = new URL(BASE);
  const project = u.pathname.replace(/^\/+/, '').replace(/\.git$/, '');
  return `${u.protocol}//${u.host}/api/v4/projects/${encodeURIComponent(project)}`;
}

/**
 * Mod-relative blob paths under a directory (recursive). The Node sibling of
 * {@code AnbennarFiles.list}. Fast offline path: if the cache (the `.anbennar-cache` junction → a
 * local clone) already holds the directory, walk it on disk — no network. Otherwise fall back to the
 * paginated GitLab tree API. Returned paths are mod-relative with forward slashes.
 */
export async function list(dir) {
  dir = norm(dir);
  const localDir = path.join(CACHE, dir);
  if (fs.existsSync(localDir)) {
    const out = [];
    const walk = d => {
      for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const fp = path.join(d, e.name);
        if (e.isDirectory()) walk(fp);
        else out.push(path.relative(CACHE, fp).replace(/\\/g, '/'));
      }
    };
    walk(localDir);
    if (out.length) return out;
  }
  const apiBase = projectApiBase();
  const blobs = [];
  for (let page = 1; ; ) {
    const url = `${apiBase}/repository/tree?path=${encodeURIComponent(dir)}`
      + `&ref=${encodeURIComponent(REF)}&recursive=true&per_page=100&page=${page}`;
    const r = await fetch(url);
    if (!r.ok) throw new Error(`GitLab tree API ${r.status} for ${dir}`);
    for (const e of await r.json()) if (e.type === 'blob') blobs.push(e.path);
    const next = r.headers.get('x-next-page');
    if (!next) break;
    page = parseInt(next, 10);
  }
  return blobs;
}
