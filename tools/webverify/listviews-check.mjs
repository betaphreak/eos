// Visit every api collection type's admin LIST view and report which ones fail to render the table
// (the "Cannot read properties of undefined (reading 'label')" class of crash). A React error boundary
// swallows the throw, so we detect success by the presence of the entries table and capture
// console.error for the message. Usage: node listviews-check.mjs
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from 'playwright-core';
import { loginAdmin, DEFAULT_BASE } from './login.mjs';

const API = join(process.cwd(), '..', '..', 'studio', 'src', 'api');
const types = [];
for (const d of readdirSync(API)) {
	const p = join(API, d, 'content-types', d, 'schema.json');
	if (!existsSync(p)) continue;
	const s = JSON.parse(readFileSync(p, 'utf8'));
	if ((s.kind || 'collectionType') === 'collectionType') types.push(s.info.singularName);
}

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
await loginAdmin(page, { base: DEFAULT_BASE });

const bad = [];
for (const t of types) {
	const msgs = [];
	const onMsg = (m) => { if (m.type() === 'error') msgs.push(m.text()); };
	const onErr = (e) => msgs.push('PAGEERROR: ' + (e.message || e));
	page.on('console', onMsg);
	page.on('pageerror', onErr);
	await page.goto(`${DEFAULT_BASE}/admin/content-manager/collection-types/api::${t}.${t}`, { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
	await page.waitForTimeout(1500);
	// a healthy list view shows the "N entries found" sub-title AND a table
	const rendered = (await page.getByText(/entries? found|no content|no entries/i).count().catch(() => 0)) > 0
		&& (await page.locator('table, [role="grid"]').count().catch(() => 0)) > 0;
	page.off('console', onMsg);
	page.off('pageerror', onErr);
	const labelErr = msgs.find((m) => /reading ['"]label['"]/.test(m));
	if (!rendered || labelErr) { bad.push(t); console.log(`✗ ${t}  rendered=${rendered}  ${labelErr || msgs.slice(-1)[0] || ''}`); }
	else console.log(`ok ${t}`);
}
console.log(bad.length ? `\nBROKEN LIST VIEWS: ${bad.join(', ')}` : '\nall list views render the table');
await browser.close();
