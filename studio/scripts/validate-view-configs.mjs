#!/usr/bin/env node
// validate-view-configs.mjs — structural lint of the generated content-manager view configs against
// the current schemas, with NO DB / no running Strapi (CI-friendly; sibling of validate-schemas.mjs).
// Catches the drift that breaks the admin: a layout referencing a field the schema no longer has, a
// schema field missing from the form, a bad key/uid, or an over-wide edit row. Run:
//   node scripts/validate-view-configs.mjs
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = join(ROOT, 'src', 'api');
const SYNC = join(ROOT, 'config', 'sync');
const SYSTEM = new Set(['id', 'documentId', 'createdAt', 'updatedAt', 'publishedAt', 'createdBy', 'updatedBy']);

// map singularName → schema attribute names
const schemaAttrs = {};
for (const dir of readdirSync(API)) {
	const p = join(API, dir, 'content-types', dir, 'schema.json');
	if (!existsSync(p)) continue;
	const s = JSON.parse(readFileSync(p, 'utf8'));
	schemaAttrs[s.info.singularName] = { attrs: new Set(Object.keys(s.attributes || {})), draft: !!(s.options && s.options.draftAndPublish) };
}

const errors = [];
const prefix = 'core-store.plugin_content_manager_configuration_content_types##api##';
const files = readdirSync(SYNC).filter((f) => f.startsWith(prefix));
let checked = 0;

for (const f of files) {
	const name = f.slice(prefix.length, -'.json'.length); // "<singular>.<singular>"
	const singular = name.split('.')[0];
	const ctx = schemaAttrs[singular];
	const err = (m) => errors.push(`${singular}: ${m}`);
	if (!ctx) { err(`view config has no matching schema (obsolete? delete config/sync/${f})`); continue; }

	const cfg = JSON.parse(readFileSync(join(SYNC, f), 'utf8'));
	const uid = `api::${singular}.${singular}`;
	if (cfg.value?.uid !== uid) err(`value.uid ${cfg.value?.uid} != ${uid}`);
	if (cfg.key !== `plugin_content_manager_configuration_content_types::${uid}`) err(`key mismatch: ${cfg.key}`);

	const { attrs } = ctx;
	const known = new Set([...attrs, ...SYSTEM]);
	const meta = cfg.value?.metadatas || {};

	// every metadata field is real; every schema attr is covered by metadata + the edit form
	for (const k of Object.keys(meta)) if (!known.has(k)) err(`metadata references unknown field '${k}'`);
	for (const a of attrs) if (!(a in meta)) err(`schema field '${a}' missing from metadatas`);

	const edit = cfg.value?.layouts?.edit || [];
	const list = cfg.value?.layouts?.list || [];
	const inEdit = new Set(edit.flat().map((c) => c.name));
	for (const a of attrs) if (!inEdit.has(a)) err(`schema field '${a}' missing from the edit form`);
	for (const cell of edit.flat()) {
		if (!known.has(cell.name)) err(`edit layout references unknown field '${cell.name}'`);
		if (!(meta[cell.name])) err(`edit field '${cell.name}' has no metadata`);
	}
	for (const [i, row] of edit.entries()) {
		const w = row.reduce((s, c) => s + (c.size || 0), 0);
		if (w > 12) err(`edit row ${i} width ${w} > 12`);
		for (const c of row) if (![4, 6, 8, 12].includes(c.size)) err(`edit field '${c.name}' odd size ${c.size}`);
	}
	for (const col of list) if (!known.has(col)) err(`list layout references unknown field '${col}'`);

	const mf = cfg.value?.settings?.mainField;
	if (mf && mf !== 'id' && !attrs.has(mf)) err(`settings.mainField '${mf}' not a schema field`);
	const sb = cfg.value?.settings?.defaultSortBy;
	if (sb && sb !== 'id' && !attrs.has(sb)) err(`settings.defaultSortBy '${sb}' not a schema field`);
	checked++;
}

if (errors.length) {
	console.error(`✗ ${errors.length} problem(s) in ${checked} view configs:\n  ` + errors.join('\n  '));
	process.exit(1);
}
console.log(`✓ ${checked} content-manager view configs are structurally valid (fields match schemas)`);
