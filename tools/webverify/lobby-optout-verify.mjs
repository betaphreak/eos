// Local check: `?lobby=0` suppresses the auto-opening Spectator Lobby (web/index.html
// openLobbyDuringLoad). The Strapi admin embeds the viewer, where the lobby is not a choosing but a
// modal in someone else's panel — see docs/studio-control-plane-plan.md §D4.
//
// Serves web/ over HTTP (the terrain zoom range-fetches plots.pack, which file:// blocks) and loads
// the page twice: without the flag the lobby must open, with it the map must be clear.
//
// Usage: node lobby-optout-verify.mjs [liveBase]
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const live = process.argv[2] || 'https://dev.civstudio.com';
const webDir = fileURLToPath(new URL('../../web/', import.meta.url));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.webp': 'image/webp',
  '.woff2': 'font/woff2', '.pack': 'application/octet-stream', '.svg': 'image/svg+xml' };

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    let p = join(webDir, normalize(decodeURIComponent(url.pathname)).replace(/^([/\\])+/, ''));
    if ((await stat(p).catch(() => null))?.isDirectory?.()) p = join(p, 'index.html');
    const body = await readFile(p);
    res.writeHead(200, { 'Content-Type': MIME[extname(p)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404).end('not found');
  }
});
await new Promise((r) => server.listen(0, r));
const base = `http://localhost:${server.address().port}`;

const browser = await chromium.launch({ channel: 'msedge', headless: true });

async function lobbyOpen(query) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await page.goto(`${base}/index.html?live=${encodeURIComponent(live)}${query}#none`,
    { waitUntil: 'load', timeout: 60000 });
  await page.waitForTimeout(9000);   // the lobby opens while the bundle downloads
  // #lobby exists in the markup and starts [hidden]; openLobby clears that
  const open = await page.evaluate(() => {
    const el = document.getElementById('lobby');
    return !!el && !el.hidden;
  });
  await page.close();
  return open;
}

const withoutFlag = await lobbyOpen('');
const withFlag = await lobbyOpen('&lobby=0');
console.log('lobby open (no flag):   ', withoutFlag, '(expected true)');
console.log('lobby open (?lobby=0):  ', withFlag, '(expected false)');

await browser.close();
server.close();
const ok = withoutFlag === true && withFlag === false;
console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL');
process.exit(ok ? 0 : 1);
