# tools/webverify

Headless-browser checks for the `web/` static site, driven through the system
**Edge** (`msedge`) via `playwright-core`. Read-only — it never touches the engine
or the map resources, just loads the page like a visitor would.

## Setup (once)

```bash
cd tools/webverify
npm install          # pulls playwright-core; uses the already-installed system Edge
```

`node_modules/` is gitignored (regenerable via `npm install`).

## Scripts

### `verify.mjs` — screenshot + console-error report
```bash
node verify.mjs <url> <outPng> [waitMs] [w] [h]
# e.g. node verify.mjs http://localhost:8080/index.html shot.png 1600 1400 900
```
Loads a URL, waits for network idle, screenshots it, and prints any console/page
errors. Works against any served page (deep-link with `#p=<id>&z=<zoom>`).

### `verify-pack.mjs` — end-to-end `plots.pack` range-fetch check
```bash
node verify-pack.mjs <webDir> <provId> <outPng>
# e.g. node verify-pack.mjs ../../web 4411 shot.png
```
Serves `<webDir>` over HTTP **with Range support** (mirroring how the deployed
Static Web App serves `plots.pack`), deep-links to `<provId>` at deep zoom, and
reports: how many `plots.pack` requests returned **206**, whether the province's
plots populated (`_plots.length`), the index size, any real-asset 404s, and any
console errors. Use it after `node web/build.mjs` to confirm the per-plot terrain
zoom still resolves through the packed/range-fetched path.
```

> The site's terrain zoom range-fetches `plots.pack`, which `file://` blocks — so
> always verify through an HTTP server (either script's server, `npx serve web`,
> or `swa start ./web`), never by opening `index.html` off disk.
