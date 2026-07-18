// validate-schemas.mjs — structural lint over all src/api content-type schemas.
// Checks: valid JSON; required top-level keys; every relation target api::x.x
// resolves to an existing src/api/x; inversedBy/mappedBy pairs are consistent.
// Does NOT require a DB or a running Strapi. Run from studio/.

import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = join(ROOT, 'src', 'api');

const errors = [];
const schemas = {}; // uid -> { name, schema }
const apiDirs = new Set(readdirSync(API).filter((d) => statSync(join(API, d)).isDirectory()));

for (const name of apiDirs) {
  const ctRoot = join(API, name, 'content-types');
  if (!existsSync(ctRoot)) { errors.push(`${name}: missing content-types/`); continue; }
  // the content-type subfolder may differ from the api folder (Strapi keys the
  // UID off both independently — see the era-modifier / region-name single types)
  const ctDirs = readdirSync(ctRoot).filter((d) => statSync(join(ctRoot, d)).isDirectory());
  if (ctDirs.length !== 1) { errors.push(`${name}: expected exactly one content-type dir, found ${ctDirs.length}`); continue; }
  const ctName = ctDirs[0];
  const p = join(ctRoot, ctName, 'schema.json');
  if (!existsSync(p)) { errors.push(`${name}: missing content-types/${ctName}/schema.json`); continue; }
  let schema;
  try { schema = JSON.parse(readFileSync(p, 'utf8')); }
  catch (e) { errors.push(`${name}: invalid JSON — ${e.message}`); continue; }
  // Strapi requires the content-type folder name to equal singularName
  if (schema.info?.singularName !== ctName) {
    errors.push(`${name}: content-type dir "${ctName}" !== singularName "${schema.info?.singularName}"`);
  }
  for (const k of ['kind', 'collectionName', 'info', 'attributes']) {
    if (!(k in schema)) errors.push(`${name}: missing top-level "${k}"`);
  }
  // pluralName must exist and (per Strapi's app-wide unicity check) differ from
  // the singularName.
  if (!schema.info?.pluralName || schema.info.pluralName === schema.info.singularName) {
    errors.push(`${name}: pluralName must be present and differ from singularName`);
  }
  if (!['collectionType', 'singleType'].includes(schema.kind)) {
    errors.push(`${name}: bad kind "${schema.kind}"`);
  }
  // UID is api::<apiFolder>.<contentTypeFolder>
  schemas[`api::${name}.${ctName}`] = { name, schema };
}

// relation-target resolution + inverse consistency
for (const [uid, { name, schema }] of Object.entries(schemas)) {
  for (const [attr, def] of Object.entries(schema.attributes ?? {})) {
    if (def.type !== 'relation') continue;
    const target = def.target;
    if (!schemas[target]) {
      errors.push(`${name}.${attr}: relation target "${target}" does not exist`);
      continue;
    }
    const inv = def.inversedBy ?? def.mappedBy;
    if (inv) {
      const targetAttrs = schemas[target].schema.attributes ?? {};
      const back = targetAttrs[inv];
      if (!back) {
        errors.push(`${name}.${attr}: inverse "${inv}" not found on ${target}`);
      } else if (back.type !== 'relation' || back.target !== uid) {
        errors.push(`${name}.${attr}: inverse ${target}.${inv} does not point back to ${uid}`);
      } else {
        const owning = def.inversedBy && back.mappedBy === attr;
        const mapped = def.mappedBy && back.inversedBy === attr;
        if (!owning && !mapped) {
          errors.push(`${name}.${attr}: inversedBy/mappedBy not paired with ${target}.${inv}`);
        }
      }
    }
  }
}

const collectionTypes = Object.values(schemas).filter((s) => s.schema.kind === 'collectionType').length;
const singleTypes = Object.values(schemas).filter((s) => s.schema.kind === 'singleType').length;
console.log(`Validated ${Object.keys(schemas).length} types: ${collectionTypes} collection, ${singleTypes} single.`);
if (errors.length) {
  console.error(`\n${errors.length} problem(s):`);
  for (const e of errors) console.error(`  ✗ ${e}`);
  process.exit(1);
}
console.log('All schemas structurally valid. ✓');
