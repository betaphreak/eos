"use strict";
// The district view (docs/district-buildout.md D5): at deep zoom, each city's urban core plots
// show a small Civ6 NEIGHBORHOOD chip — the default district every urban plot carries (the other
// district types emerge from the buildings actually raised on a plot, during gameplay). A plot
// that isn't linked to a live settlement reads as ABANDONED (a ruined-neighborhood variant); the
// spectated colony's own province renders active neighborhoods, and its built buildings ring its
// center as flat button icons on little plinths. The chips are small ICONS centred on each plot,
// not full-hex tiles blanketing the cell. This layer supersedes the interim pip (city.mjs) as you
// zoom into a city.
//
// Two sources: the map's urban plots (BUNDLE, geographic — every city) and the live session
// snapshot (the POV colony's placed buildings, from the D3 district feed). Both fade in deep.
import { P, ctx, pxr, pyr, px, py, provOnScreen, isPolitical, BUNDLE, apiUrl } from "./core.mjs";
import { bandAlpha } from "./bands.mjs";
import { liveColony } from "./overlays/live.mjs";
import { nearestPlots } from "./district-plots.mjs";

// --- Civ6 district-hex chips (D4a): {TYPE: {src,w,h}} → loaded Images. We draw NEIGHBORHOOD
// (+ its baked ABANDONED variant); CITY_CENTER is a last-ditch fallback. ---
const TILES = (BUNDLE && BUNDLE.districtTiles) || null;
const tileImg = {};
if (TILES) for (const [type, a] of Object.entries(TILES)) { const im = new Image(); im.src = a.src; tileImg[type] = im; }

// the neighborhood chip is drawn small — a fraction of the plot, capped — so it reads as a marker
// centred on the plot, not a tile blanketing the cell (the old full-hex D_HEX_SCALE pile-up).
function iconSize(plotPx) { return Math.max(10, Math.min(plotPx * 0.55, 46)); }

// --- building button icons (Phase 2 sheet) + the /api/buildings icon rects (D3 join) ---
const BSHEET = "assets/buildings/building-icons.webp";
const bsheetImg = new Image(); bsheetImg.src = BSHEET;
let bmeta = null;               // BUILDING_* id -> { icon:[x,y,w,h], category }
async function loadBuildingMeta() {
  if (bmeta) return;
  bmeta = {};
  try {
    const res = await fetch(apiUrl("/api/buildings"));
    if (!res.ok) return;
    const buf = await res.arrayBuffer();
    let arr;
    try {
      const stream = new Response(buf).body.pipeThrough(new DecompressionStream("gzip"));
      arr = JSON.parse(await new Response(stream).text());
    } catch { arr = JSON.parse(new TextDecoder().decode(buf)); }
    for (const b of arr) bmeta[b.id] = { icon: b.icon, category: b.category };
  } catch { /* the tree works without it; icons just fall back to a dot */ }
}
loadBuildingMeta();

// The colony's CITY CENTER in screen px — the centre of its `centerX`/`centerY` plot, the
// water-first plot the engine actually founded on. Falls back to the colony's lat/lon, which is its
// PROVINCE's anchor and can sit a plot or two off the true centre (docs/urban-plots.md) — only
// reached for a colony whose centre plot isn't laid yet, or an older server that omits the fields.
function centerPx(colony, plotPx) {
  if (Number.isFinite(colony.centerX) && Number.isFinite(colony.centerY))
    return { x: pxr(colony.centerX) + plotPx / 2, y: pyr(colony.centerY) + plotPx / 2 };
  return { x: px(colony.longitude), y: py(colony.latitude) };
}

// the province that hosts the live colony (so its urban plots read as ACTIVE, not abandoned): the
// on-screen province whose plot bounds contain the colony's map point. The district view only fades
// in at deep zoom, where one city province fills the view, so a plot-bbox containment test is exact
// enough (and cheap — only loaded provinces are scanned). Null when the colony's province is off
// screen (every visible urban plot is then correctly an unlinked, abandoned site).
function colonyProvince(colony, plotPx) {
  const { x: cx, y: cy } = centerPx(colony, plotPx);
  for (const p of P) {
    if (!p._plots || !p._plots.length || !provOnScreen(p)) continue;
    let urban = false, x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    for (const q of p._plots) {
      if (q.urban) urban = true;
      if (q.x < x0) x0 = q.x; if (q.x > x1) x1 = q.x;
      if (q.y < y0) y0 = q.y; if (q.y > y1) y1 = q.y;
    }
    if (!urban) continue;
    if (cx >= pxr(x0) && cx <= pxr(x1 + 1) && cy >= pyr(y0) && cy <= pyr(y1 + 1)) return p;
  }
  return null;
}

// The set of the live colony's urban plots that are actually BUILT — a city of N districts lights N
// of its province's urban plots; the rest of the urban core is unclaimed ground and still reads as
// abandoned. The lit plots are the N nearest the city center, so the core is live and the outskirts
// are ruins. Null means "every urban plot is live" (the core is fully built out).
function livePlots(prov, colony, plotPx) {
  const n = Math.max(0, colony.startingDistricts | 0);
  const urban = prov._plots.filter(q => q.urban);
  const c = centerPx(colony, plotPx);
  return nearestPlots(urban, n, c.x, c.y, q => pxr(q.x) + plotPx / 2, q => pyr(q.y) + plotPx / 2);
}

// is `q` the colony's city-center plot?
const isCenter = (q, colony) => q.x === colony.centerX && q.y === colony.centerY;

// draw a small district chip centred at (cx, cy), sized to `s` px. `active` picks the live art;
// otherwise the ABANDONED (ruined) variant — its own baked webp when present, else the live tile
// drawn desaturated/darkened so an unlinked site still reads as forsaken. A live colony's own
// centre plot draws the CITY_CENTER chip rather than a generic neighborhood, so the city reads as
// having a seat; an abandoned site is ruins either way.
function drawNeighborhood(active, cx, cy, s, center = false) {
  const live = (center && tileImg.CITY_CENTER) || tileImg.NEIGHBORHOOD || tileImg.CITY_CENTER;
  let im = active ? live : (tileImg.NEIGHBORHOOD_ABANDONED || live);
  if (!im || !im.complete || !im.naturalWidth) return;
  const fake = !active && !tileImg.NEIGHBORHOOD_ABANDONED;  // no baked variant → fake the ruin look
  if (fake) { ctx.save(); ctx.filter = "grayscale(0.9) brightness(0.62)"; ctx.globalAlpha *= 0.85; }
  ctx.drawImage(im, cx - s / 2, cy - s / 2, s, s);
  if (fake) ctx.restore();
}

// draw one building's button icon on a small plinth centred at (cx, cy), sized to `s` px
function drawBuildingIcon(id, cx, cy, s) {
  // plinth: a soft shadow ellipse so the icon reads as sitting in the district
  ctx.beginPath(); ctx.ellipse(cx, cy + s * 0.42, s * 0.5, s * 0.2, 0, 0, Math.PI * 2);
  ctx.fillStyle = "rgba(30,24,18,0.35)"; ctx.fill();
  const m = bmeta && bmeta[id];
  if (m && m.icon && bsheetImg.complete && bsheetImg.naturalWidth) {
    const [x, y, w, h] = m.icon;
    ctx.drawImage(bsheetImg, x, y, w || 64, h || 64, cx - s / 2, cy - s / 2, s, s);
  } else {
    // no icon rect yet — a small stone chip so the building still shows
    ctx.beginPath(); ctx.arc(cx, cy, s * 0.32, 0, Math.PI * 2);
    ctx.fillStyle = "rgba(210,200,186,0.9)"; ctx.fill();
  }
}

/** The district view — a small district chip on every city's urban plots (abandoned unless the plot
 *  is one the live colony's districts occupy; its centre plot draws the CITY_CENTER art) + the POV
 *  colony's built buildings ringing that centre. Fades in at deep zoom (reading a city's plots). */
export function drawDistricts() {
  const a = bandAlpha([4.5, 5.5]);   // fade in past the pip, when zoomed into a city
  if (a <= 0.01 || isPolitical()) return;
  const plotPx = pxr(1) - pxr(0);
  if (plotPx < 2) return;            // too small to read
  ctx.save();
  ctx.globalAlpha = a;

  const colony = liveColony();
  const anchored = colony && (Number.isFinite(colony.latitude) || Number.isFinite(colony.centerX));
  const liveProv = anchored ? colonyProvince(colony, plotPx) : null;
  const built = liveProv ? livePlots(liveProv, colony, plotPx) : null;
  const s = iconSize(plotPx);

  // (1) geographic: a small district chip on every city's urban core plots. Abandoned by default
  // (an unlinked map site); active on the live colony's province — but only on the plots its
  // districts actually occupy, the rest of that core being unbuilt ground.
  for (const p of P) {
    if (!p._plots || !p._plots.length || !provOnScreen(p)) continue;
    const live = p === liveProv;
    for (const q of p._plots) {
      if (!q.urban) continue;
      const active = live && (!built || built.has(q));
      drawNeighborhood(active, pxr(q.x) + plotPx / 2, pyr(q.y) + plotPx / 2, s, live && isCenter(q, colony));
    }
  }

  // (2) live: the POV colony's built buildings, ringed on its city center over that plot's chip
  if (anchored && Array.isArray(colony.districts) && colony.districts.length) {
    const { x: cx, y: cy } = centerPx(colony, plotPx);
    const ids = [];
    for (const dist of colony.districts) for (const id of (dist.buildings || [])) ids.push(id);
    if (ids.length) {
      const bs = Math.max(8, Math.min(plotPx * 0.4, 20));  // icon size (a fraction of a plot)
      // ring (then spiral) the icons out from the center
      for (let i = 0; i < ids.length; i++) {
        const ring = Math.floor(i / 8), slot = i % 8;
        const rad = plotPx * (0.45 + ring * 0.42);
        const ang = (slot / 8) * Math.PI * 2 + ring * 0.4;
        drawBuildingIcon(ids[i], cx + Math.cos(ang) * rad, cy + Math.sin(ang) * rad, bs);
      }
    }
  }
  ctx.restore();
}
