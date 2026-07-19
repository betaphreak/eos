#!/usr/bin/env node
// make-world-bundle.mjs — snapshot studio's GET /api/world-bundle to a file: the FixtureWorldSource
// offline/CI artifact (a gitignored/CI-produced snapshot, NOT committed source-of-truth). The engine
// then boots from it via `-Dworldbundle.fixture=<path>` with no Strapi reachable
// (WorldSourceIntegrationTest, and — post-cutover — the whole suite/offline dev).
//
// Usage (studio must be running + seeded):
//   node tools/make-world-bundle.mjs --out .map/world-bundle.json.gz
//   node tools/make-world-bundle.mjs --url https://civstudio.com/api/world-bundle --token $WORLD_BUNDLE_TOKEN --out fixture.json
//
// Writes gzipped when --out ends with .gz (FixtureWorldSource reads either). Prints dataset/count so a
// truncated or empty bundle is obvious.
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { gzipSync } from 'node:zlib';

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

const url = arg('url', process.env.WORLD_BUNDLE_URL || 'http://localhost:1337/api/world-bundle');
const token = arg('token', process.env.WORLD_BUNDLE_TOKEN || '');
const out = arg('out', null);
if (!out) {
  console.error('usage: node tools/make-world-bundle.mjs --out <path[.gz]> [--url URL] [--token TOKEN]');
  process.exit(2);
}

const res = await fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
if (!res.ok) {
  console.error(`world-bundle fetch ${url} → HTTP ${res.status} ${res.statusText}`);
  process.exit(1);
}
const json = await res.text(); // Node fetch transparently gunzips a gzip response
const bundle = JSON.parse(json); // validate it parses
const nDatasets = Object.keys(bundle.resources || {}).length;
if (nDatasets === 0) {
  console.error('bundle has no resources — is studio seeded?');
  process.exit(1);
}
console.log(`fetched ${url}: meta=${JSON.stringify(bundle.meta)} datasets=${nDatasets}`);

mkdirSync(dirname(out) || '.', { recursive: true });
const data = out.endsWith('.gz') ? gzipSync(json) : Buffer.from(json, 'utf8');
writeFileSync(out, data);
console.log(`wrote ${out} (${data.length} bytes${out.endsWith('.gz') ? ', gzipped' : ''})`);
