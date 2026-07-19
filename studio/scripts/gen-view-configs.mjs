#!/usr/bin/env node
// gen-view-configs.mjs — generate config-sync content-manager configuration (edit-form + list-view
// layouts) for every api content type from its schema.json. Sibling of gen-schemas.mjs: the admin
// form is DERIVED from the schema so new/changed collection types get a consistent, sensible layout
// without hand-editing core-store JSON. Run:  node scripts/gen-view-configs.mjs
//
// Writes config/sync/core-store.plugin_content_manager_configuration_content_types##api##<n>.<n>.json
// (the exact shape config-sync round-trips). Import them with the config-sync plugin (see
// studio/CLAUDE.md → Config sync). Idempotent; re-run after a schema change.
import { readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const API = join(ROOT, 'src', 'api');
const SYNC = join(ROOT, 'config', 'sync');

// System fields Strapi injects. They get metadata entries (hidden in the edit form) but never sit in a
// hand-authored layout — mirrors what Strapi's own generated configuration does.
const SYSTEM = ['createdAt', 'updatedAt', 'createdBy', 'updatedBy'];
const WIDE = new Set(['text', 'richtext', 'blocks', 'json', 'component', 'dynamiczone', 'media']);
const isRelation = (a) => a.type === 'relation';
const isMulti = (a) => isRelation(a) && /(manyToMany|oneToMany)/.test(a.relation || '');
const isWide = (a) => WIDE.has(a.type);
const sizeOf = (a) => (isWide(a) ? 12 : 6);

// ---- load every api schema, keyed by uid, and pick each type's display (main) field ----
const types = {};
for (const dir of readdirSync(API)) {
	const ctDir = join(API, dir, 'content-types', dir);
	const schemaPath = join(ctDir, 'schema.json');
	if (!existsSync(schemaPath)) continue; // e.g. world-bundle (custom, collectionless) has no schema
	const schema = JSON.parse(readFileSync(schemaPath, 'utf8'));
	const uid = `api::${schema.info.singularName}.${schema.info.singularName}`;
	types[uid] = { dir, schema, uid, attrs: schema.attributes || {} };
}

// display field: first present of these as a scalar, else first required string, else first string, else id
function pickMain(attrs) {
	const pref = ['name', 'key', 'tag', 'type', 'title', 'artTag', 'displayName'];
	for (const p of pref) if (attrs[p] && ['string', 'uid'].includes(attrs[p].type)) return p;
	for (const p of pref) if (attrs[p] && !isRelation(attrs[p]) && !isWide(attrs[p])) return p;
	const req = Object.entries(attrs).find(([, a]) => a.required && ['string', 'uid'].includes(a.type));
	if (req) return req[0];
	const str = Object.entries(attrs).find(([, a]) => ['string', 'uid'].includes(a.type));
	return str ? str[0] : 'id';
}
for (const t of Object.values(types)) t.main = pickMain(t.attrs);
const mainOf = (uid) => (types[uid] ? types[uid].main : 'id');

// ---- per-field metadata ----
function editMeta(name, attr) {
	const m = { label: name, description: '', placeholder: '', visible: true, editable: true };
	if (isRelation(attr)) m.mainField = attr.target ? mainOf(attr.target) : 'id';
	return m;
}
function listMeta(name, attr) {
	const sortable = !isWide(attr) && !isMulti(attr); // multi-relations & json/text aren't sortable columns
	return { label: name, searchable: sortable, sortable };
}

// ---- edit layout: schema-order fields packed into a 12-col grid (2 halves/row, wides full-width) ----
function editLayout(attrs) {
	const rows = [];
	let row = [], width = 0;
	for (const [name, attr] of Object.entries(attrs)) {
		const size = sizeOf(attr);
		if (size === 12 || width + size > 12) { if (row.length) rows.push(row); row = []; width = 0; }
		row.push({ name, size });
		width += size;
		if (width >= 12) { rows.push(row); row = []; width = 0; }
	}
	if (row.length) rows.push(row);
	return rows;
}

// ---- list columns: id, the main field, then a few useful scalar/single-relation columns (cap 6) ----
function listLayout(attrs, main) {
	const cols = ['id'];
	if (main !== 'id' && attrs[main]) cols.push(main);
	for (const [name, attr] of Object.entries(attrs)) {
		if (cols.length >= 6) break;
		if (name === main || isWide(attr) || isMulti(attr)) continue; // skip json/text + list-of relations
		cols.push(name);
	}
	return cols;
}

function buildConfig(t) {
	const { uid, attrs, main } = t;
	const metadatas = {
		id: { edit: {}, list: { label: 'id', searchable: true, sortable: true } },
		documentId: { edit: {}, list: { label: 'documentId', searchable: true, sortable: true } },
	};
	for (const [name, attr] of Object.entries(attrs)) metadatas[name] = { edit: editMeta(name, attr), list: listMeta(name, attr) };
	for (const name of SYSTEM) {
		const isBy = name.endsWith('By');
		const edit = { label: name, description: '', placeholder: '', visible: false, editable: true };
		if (isBy) edit.mainField = 'firstname';
		metadatas[name] = { edit, list: { label: name, searchable: true, sortable: true } };
	}
	const main_ = attrs[main] && !isWide(attrs[main]) && !isRelation(attrs[main]) ? main : 'id';
	return {
		key: `plugin_content_manager_configuration_content_types::${uid}`,
		value: {
			settings: {
				bulkable: true, filterable: true, searchable: true, pageSize: 10,
				relationOpenMode: 'modal', mainField: main, defaultSortBy: main_, defaultSortOrder: 'ASC',
			},
			metadatas,
			layouts: { list: listLayout(attrs, main), edit: editLayout(attrs) },
			uid,
		},
		type: 'object', environment: null, tag: null,
	};
}

let n = 0;
for (const t of Object.values(types)) {
	const cfg = buildConfig(t);
	const file = join(SYNC, `core-store.plugin_content_manager_configuration_content_types##api##${t.schema.info.singularName}.${t.schema.info.singularName}.json`);
	writeFileSync(file, JSON.stringify(cfg, null, 2) + '\n');
	n++;
	console.log(`  ${t.uid.padEnd(34)} main=${cfg.value.settings.mainField.padEnd(12)} rows=${cfg.value.layouts.edit.length} list=[${cfg.value.layouts.list.join(',')}]`);
}
console.log(`\nwrote ${n} content-manager view configs to config/sync/`);
