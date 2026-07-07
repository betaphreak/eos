# Web UX — navigation, camera framing & chrome

How the WorldMap page (`web/`) behaves as an interface: URL deep links, how focusing a province
frames it, pan/zoom clamping, and the on-screen chrome (zoom readout, collapsible sidebar, floating
search, status line, theme). Pure client-side + static hosting — no server compute. The map
*rendering* pipeline (terrain, shore, sea, resource icons) lives in [`coastlines.md`](coastlines.md)
and the per-plot docs; this file is the interaction layer over it.

The relevant modules: `core.mjs` (camera/projection, `cam`, `VIEW`, `clampPan`), `main.mjs` (`draw`,
`focusProvince`/`focusProvinceFit`, deep link), `panel.mjs` (all DOM: selection, sidebar, search,
mode toggle, keyboard), `index.html` + `styles.css` (chrome markup + layout).

## Deep links — `?p=<id>&z=<zoom>` (2026-07)

Focusing a province from the URL accepts a **query string** (`?p=<id>&z=<zoom>` — the
shareable/production form) as well as the legacy `#p=…&z=…` hash; the query wins when both are
present (`readDeepLink`/`applyHash` in `main.mjs`, also re-applied on `popstate`). `z` is optional.

- **Production path.** On Azure Static Web Apps the existing `navigationFallback` (see
  `web/staticwebapp.config.json`) rewrites an unknown route to `index.html`, so a pretty
  `/worldmap?p=…&z=…` path serves the app and the query rides through untouched — **no server change,
  no Azure Function**. Use `/worldmap` with **no trailing slash** so the relative `data.js`/`js/*`
  script paths still resolve to root. A plain local `http.server` 404s on `/worldmap`, so for local
  debugging use the root form `/?p=…&z=…`.
- **Timing.** Applied after first layout (`requestAnimationFrame` in `boot`) so the camera math sees a
  sized `VIEW` — the old inline-at-boot call silently no-opped.
- **Debugging.** `tools/webverify/shot.mjs` drives it for headless province screenshots:
  `node shot.mjs <baseUrl> <p> <z> <out.png>`.

## Focus framing & pan clamp (2026-07)

- **Fit-to-frame focus.** Focusing a province (deep link with no `?z=`, or search-select) uses
  `focusProvinceFit` — the province bbox fits **0.9 of the canvas**, centred — instead of a fixed
  far-out zoom, so it fills most of the canvas. An explicit `?z=` still sets that exact zoom via
  `focusProvince`. Ring-less provinces (no bbox) fall back to a deep fixed zoom.
- **`clampPan` centring margin.** The vertical pan clamp (`clampAxis`, `core.mjs`) allows the map's
  top/bottom edge to reach the viewport **centre** (margin = `viewDim/2`), so a province at the very
  edge of the mapped latitudes can actually be centred; the polar sea gradient fills the gap beyond
  the edge. Trade-off: manual panning can now push the map half-off-screen vertically — acceptable for
  centring edge features, tighten the margin if bounded panning is wanted back.
- **Reset.** `resetView` (`panel.mjs`) returns to the world view (`cam.k=1`, centred); bound to the
  `0`/`Home` keys and the zoom-readout button.

## Chrome

The map overlays live in `.stage` and are pinned to the dark palette regardless of the UI theme
(the baked terrain is dark). Tooltips are automatic for any `[data-tip]` element inside `.stage`
(`showBtnTip`, delegated in `panel.mjs`).

- **Brand wordmark** (top-left, leftmost). `CivStudio: Anbennar` in the project serif stack (Constantia /
  Cambria / Georgia — system fonts, CSP-safe), gold + light two-tone, in a `.brandbar` flex row with the
  zoom button beside it. Replaced the old two-line title block over the canvas (removed, freeing map height;
  `setMode` no longer swaps a title).
- **Zoom readout button** (top-left, beside the wordmark). `#zoomLevel` shows the live magnification
  (`1×`…`256×`, updated each `draw`) and, when clicked, runs `resetView`. Styled like the other chrome buttons.
- **Collapsible overlay sidebar.** `.app` is no longer a two-column grid — the map (`.stage`) is
  full-bleed and the rail (`.railwrap`) is an absolute right overlay that slides in via `.open`
  (`showRail` toggles it + `.stage.rail-open`). It opens on province / journey selection and on caravan
  mode; it collapses on **Esc**, the **×** close button, or re-clicking the selected province
  (`closePanel` also restores a focused camera). The top-right controls shift left in `.stage.rail-open`
  (a `translateX(-392px)`) so they clear the open panel; disabled below 900px where the panel just
  overlays. **Note:** the old sidebar intro/overview (province count + blurb) now shows only when the
  panel is open — the default view is map-first.
- **Floating search.** The search moved out of the sidebar into `.stage` as a top-right pill styled
  like the buttons (`.searchbar`), always present as the rightmost control. Type a name or id → pick →
  `goToProvince` fits + selects it.
- **Bottom status line.** `#statusline` (fixed, slides up, colour-keyed by kind, click to dismiss,
  auto-hides) surfaces errors/warnings: `window.onerror` + `unhandledrejection` + wrapped
  `console.error`/`warn`, wired in an **early inline script** in `index.html` so it catches even
  module-load failures. `window.__status(msg, kind)` posts arbitrary app messages (`kind`:
  `info`/`warn`/`error`).
- **Dark by default.** `index.html` ships `data-theme="dark"`; the theme toggle (`themeBtn`) still
  flips light/dark.
