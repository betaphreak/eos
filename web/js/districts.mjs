"use strict";
// The district view (docs/district-buildout.md D5): at deep zoom, each city's urban core plots
// show their Civ6 flat district-hex tile (D4a — the composite's *ground*), and the spectated
// colony's actually-built buildings ring its center as their flat button icons on little plinths
// (D4b's 3D sprites were deferred — button icons give full 1,270-building coverage). This layer
// supersedes the interim pip (city.mjs) as you zoom into a city.
//
// Two sources: the map's urban plots (BUNDLE, geographic — every city) and the live session
// snapshot (the POV colony's placed buildings, from the D3 district feed). Both fade in deep.
import { P, ctx, pxr, pyr, px, py, provOnScreen, isPolitical, BUNDLE, apiUrl } from "./core.mjs";
import { bandAlpha } from "./bands.mjs";
import { liveColony } from "./overlays/live.mjs";

// --- Civ6 district-hex ground tiles (D4a): {TYPE: {src,w,h}} → loaded Images ---
const TILES = (BUNDLE && BUNDLE.districtTiles) || null;
const tileImg = {};
if (TILES) for (const [type, a] of Object.entries(TILES)) { const im = new Image(); im.src = a.src; tileImg[type] = im; }

// district types assigned to a city's non-primary urban plots (the primary is CITY_CENTER)
const OTHER_TYPES = ["NEIGHBORHOOD", "COMMERCIAL_HUB", "CAMPUS", "HOLY_SITE", "ENCAMPMENT", "THEATER"];

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

// map a building's Advisor category → its DistrictType (mirrors engine DistrictType.fromCategory)
const CAT_TO_TYPE = {
  SCIENCE: "CAMPUS", RELIGION: "HOLY_SITE", MILITARY: "ENCAMPMENT",
  ECONOMY: "COMMERCIAL_HUB", CULTURE: "THEATER", GROWTH: "NEIGHBORHOOD",
};

// per-province urban-plot district-type assignment, cached on the plot (primary=CITY_CENTER)
function assignTypes(prov) {
  if (prov._dtypes) return;
  prov._dtypes = true;
  let i = 0;
  for (const q of prov._plots) {
    if (!q.urban) continue;
    q._dtype = i === 0 ? "CITY_CENTER" : OTHER_TYPES[(i - 1) % OTHER_TYPES.length];
    i++;
  }
}

// draw a district hex tile centred at (cx, cy), sized to `d` px (its alpha is the hex cutout)
function drawTile(type, cx, cy, d) {
  const im = tileImg[type] || tileImg.CITY_CENTER;
  if (!im || !im.complete || !im.naturalWidth) return false;
  ctx.drawImage(im, cx - d / 2, cy - d / 2, d, d);
  return true;
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

/** The district view — Civ6 hex tiles on every city's urban plots + the POV colony's built
 *  buildings ringing its center. Fades in at deep zoom (reading a city's plots). */
export function drawDistricts() {
  const a = bandAlpha([4.5, 5.5]);   // fade in past the pip, when zoomed into a city
  if (a <= 0.01 || isPolitical()) return;
  const plotPx = pxr(1) - pxr(0);
  if (plotPx < 2) return;            // too small to read
  ctx.save();
  ctx.globalAlpha = a;

  // (1) geographic: the Civ6 district-hex tile on every city's urban core plots
  const d = plotPx * 1.6;            // a district hex covers a little more than one plot
  for (const p of P) {
    if (!p._plots || !p._plots.length || !provOnScreen(p)) continue;
    assignTypes(p);
    for (const q of p._plots) {
      if (!q.urban) continue;
      drawTile(q._dtype || "CITY_CENTER", pxr(q.x) + plotPx / 2, pyr(q.y) + plotPx / 2, d);
    }
  }

  // (2) live: the POV colony's built buildings, ringed on its center over a CITY_CENTER tile
  const colony = liveColony();
  if (colony && Number.isFinite(colony.latitude) && Array.isArray(colony.districts) && colony.districts.length) {
    const cx = px(colony.longitude), cy = py(colony.latitude);
    const ids = [];
    for (const dist of colony.districts) for (const id of (dist.buildings || [])) ids.push(id);
    if (ids.length) {
      drawTile("CITY_CENTER", cx, cy, plotPx * 2.4);         // the ground the icons sit on
      const s = Math.max(10, Math.min(plotPx * 0.7, 26));    // icon size
      // ring (then spiral) the icons out from the center
      for (let i = 0; i < ids.length; i++) {
        const ring = Math.floor(i / 8), slot = i % 8;
        const rad = plotPx * (0.55 + ring * 0.5);
        const ang = (slot / 8) * Math.PI * 2 + ring * 0.4;
        drawBuildingIcon(ids[i], cx + Math.cos(ang) * rad, cy + Math.sin(ang) * rad, s);
      }
    }
  }
  ctx.restore();
}
