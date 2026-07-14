// Zero-dependency static file server for local development of the web/ site.
//
// The site is a dependency-free static folder that must be served over HTTP (the per-plot
// terrain zoom byte-range-fetches assets/plots.pack, which file:// blocks) and pointed at a
// reachable spectator server for window.BUNDLE (?live=<url>). This server does the HTTP part —
// no npm install, no `npx serve` (which needs the network), so it runs with no internet at all.
//
// It supports HTTP byte-range requests (required for plots.pack range-fetches), sends the right
// content types (notably application/octet-stream + no compression for *.pack so ranges work),
// and prints a single machine-readable ready line the DevFrontendLauncher waits for before it
// opens the browser:  DEV-SERVER-READY http://localhost:<port>/
//
// Usage:  node web/dev-server.mjs [--port 3000] [--host 127.0.0.1] [--root web]
//         (PORT / HOST / WEB_ROOT env vars are honored as fallbacks)

import { createServer } from 'node:http';
import { createReadStream, promises as fs } from 'node:fs';
import { extname, join, normalize, resolve, sep } from 'node:path';

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  if (i !== -1 && i + 1 < process.argv.length) return process.argv[i + 1];
  return fallback;
}

const PORT = Number(arg('port', process.env.PORT || 3000));
const HOST = arg('host', process.env.HOST || '127.0.0.1');
// Default root is the web/ folder next to this script, resolved from CWD so it works whether the
// launcher runs it from the repo root (the spring-boot:run working dir) or from web/ itself.
const ROOT = resolve(arg('root', process.env.WEB_ROOT || 'web'));

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
  '.wasm': 'application/wasm',
  '.txt': 'text/plain; charset=utf-8',
  // plots.pack (and any *.pack): raw bytes the browser gunzips itself. Octet-stream so no proxy
  // recompresses it and byte-range requests stay valid — same as staticwebapp.config.json in prod.
  '.pack': 'application/octet-stream',
};

function contentType(path) {
  return TYPES[extname(path).toLowerCase()] || 'application/octet-stream';
}

// Resolve a request path to an on-disk file inside ROOT, or null if it escapes the root.
function resolveSafe(urlPath) {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(urlPath, 'http://x').pathname);
  } catch {
    return null;
  }
  if (pathname === '/' || pathname === '') pathname = '/index.html';
  const abs = normalize(join(ROOT, pathname));
  // reject path traversal outside ROOT
  if (abs !== ROOT && !abs.startsWith(ROOT + sep)) return null;
  return abs;
}

const server = createServer(async (req, res) => {
  const method = req.method || 'GET';
  if (method !== 'GET' && method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD' }).end('Method Not Allowed');
    return;
  }

  const file = resolveSafe(req.url || '/');
  if (!file) {
    res.writeHead(400).end('Bad Request');
    return;
  }

  let stat;
  try {
    stat = await fs.stat(file);
    if (stat.isDirectory()) {
      stat = await fs.stat(join(file, 'index.html'));
      return respond(join(file, 'index.html'), stat);
    }
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
      .end(`404 Not Found: ${req.url}`);
    return;
  }
  respond(file, stat);

  function respond(path, st) {
    const type = contentType(path);
    const total = st.size;
    const baseHeaders = {
      'Content-Type': type,
      // ranges are what plots.pack relies on; advertise support for every asset
      'Accept-Ranges': 'bytes',
      // local dev: never cache, so an edit-reload shows the change immediately
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*',
    };

    const range = req.headers.range;
    if (range) {
      const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
      if (m) {
        let start = m[1] === '' ? null : Number(m[1]);
        let end = m[2] === '' ? null : Number(m[2]);
        if (start === null) {
          // suffix range: last N bytes
          start = Math.max(0, total - (end ?? 0));
          end = total - 1;
        } else if (end === null || end >= total) {
          end = total - 1;
        }
        if (Number.isNaN(start) || Number.isNaN(end) || start > end || start >= total) {
          res.writeHead(416, { 'Content-Range': `bytes */${total}` }).end();
          return;
        }
        res.writeHead(206, {
          ...baseHeaders,
          'Content-Range': `bytes ${start}-${end}/${total}`,
          'Content-Length': end - start + 1,
        });
        if (method === 'HEAD') return res.end();
        createReadStream(path, { start, end }).pipe(res);
        return;
      }
    }

    res.writeHead(200, { ...baseHeaders, 'Content-Length': total });
    if (method === 'HEAD') return res.end();
    createReadStream(path).pipe(res);
  }
});

server.on('error', (err) => {
  console.error(`[dev-server] failed to start on ${HOST}:${PORT}: ${err.message}`);
  process.exit(1);
});

// Verify the root exists before binding, so a misconfigured root fails loudly (not as 404s).
try {
  await fs.access(join(ROOT, 'index.html'));
} catch {
  console.error(`[dev-server] no index.html under root ${ROOT} — pass --root <web dir>`);
  process.exit(1);
}

server.listen(PORT, HOST, () => {
  // The single line DevFrontendLauncher parses to know it's safe to open the browser.
  console.log(`DEV-SERVER-READY http://localhost:${PORT}/`);
  console.log(`[dev-server] serving ${ROOT} at http://${HOST}:${PORT}/`);
});

// Clean shutdown when the parent (the Spring launcher) closes our stdin or signals us.
process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
process.stdin.on('close', () => server.close(() => process.exit(0)));
