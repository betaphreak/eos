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

### `mapshot.mjs` — screenshot the map viewport at a province/zoom (no throwaway scripts)
```bash
node mapshot.mjs <provId> [zoom=64] [out=shot.png] [WxH]
# e.g. node mapshot.mjs 412 256 p412.png 380x280
#      node mapshot.mjs 4411 256 --live=https://dev.civstudio.com
```
Serves `web/` over HTTP (with Range), points the page's bundle fetch at a live server via `?live=`
(default `http://localhost:8080`, override with `--live=` or `$LIVE`), deep-links to the province at
the given zoom, waits for the terrain to bake, and screenshots the viewport — optionally a centred
`WxH` crop. Prints console errors plus a province diagnostic (plot count, offscreen `w`/`h`, computed
`tpp`, whether the textured blend is active, snowy-plot count, and per-terrain counts + `LayerOrder`).
Use this to eyeball deep-zoom terrain/blend/feature changes instead of hand-rolling a Playwright
script each time. Needs a bundle server up (local `spring-boot:run`, or the deployed one via `--live=`).

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

### `boot-check.mjs` — server-gated bootstrap / Maintenance Mode check
```bash
node boot-check.mjs <liveBase> [waitMs]
# e.g. node boot-check.mjs http://localhost:8080 9000
```
Serves `web/` over HTTP, loads `index.html?live=<liveBase>`, and reports the boot outcome as
JSON: whether the app booted (`hasBundle`, province count, loading screen cleared) or held the
`Maintenance Mode` splash, plus console errors. Run it with the Spring Boot server **up** (expect
`maintenance:false`, bundle loaded) and **down** (expect `maintenance:true`) to verify the
health-gated bootstrap.

> The site's terrain zoom range-fetches `plots.pack`, which `file://` blocks — so
> always verify through an HTTP server (either script's server, `npx serve web`,
> or `swa start ./web`), never by opening `index.html` off disk.
