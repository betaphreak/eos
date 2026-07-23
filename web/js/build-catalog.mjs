"use strict";
// The BUILDING CATALOG join — one place that fetches /api/buildings and turns a bare
// `BUILDING_*` id (which is all the live feed ever sends) into a name, a hammer cost and its
// C2C button icon, for both the DOM (sprite cells in a list) and the canvas (icons on the map).
//
// This used to exist three times over: the decree modal in overlays/live.mjs, the district draw in
// districts.mjs, and — when the city screen arrived — a third copy. Each had its own fetch, its own
// gzip fallback, its own id-prettifier and its own icon-sheet arithmetic. One module, one fetch,
// one prettifier.
import { apiUrl } from "./core.mjs";

const SHEET = "assets/buildings/building-icons.webp";   // the Phase-2 button bake: 64² cells
export const sheetImg = new Image();
sheetImg.src = SHEET;

let meta = null;      // id -> { name, cost, icon:[x,y,w,h], category }
let inFlight = null;

/**
 * Fetch the catalog once (idempotent, and safe to call from anywhere at any time). Resolves to the
 * id→metadata map, or null if the server has no catalog — every consumer degrades to prettified ids
 * and plain chips rather than failing, so a missing catalog is a cosmetic loss, not a broken view.
 */
export function loadBuildCatalog() {
  if (meta) return Promise.resolve(meta);
  if (!inFlight) inFlight = fetchCatalog().then(m => (meta = m));
  return inFlight;
}
loadBuildCatalog();

async function fetchCatalog() {
  try {
    const res = await fetch(apiUrl("/api/buildings"));
    if (!res.ok) return null;
    const buf = await res.arrayBuffer();
    let arr;
    try {   // the bundle is served gzipped; a dev server may hand it over plain
      const stream = new Response(buf).body.pipeThrough(new DecompressionStream("gzip"));
      arr = JSON.parse(await new Response(stream).text());
    } catch { arr = JSON.parse(new TextDecoder().decode(buf)); }
    const out = {};
    for (const b of arr) out[b.id] = { name: b.name, cost: b.cost, icon: b.icon, category: b.category };
    return out;
  } catch { return null; }
}

/** One building's catalog row, or null before the catalog lands (or if it never does). */
export const buildMeta = id => (meta && meta[id]) || null;

/** A building's display name — the catalog's, or a prettified id until it arrives. */
export function buildingName(id) {
  const m = buildMeta(id);
  if (m && m.name) return m.name;
  return String(id || "").replace(/^BUILDING_/, "").replace(/_/g, " ").toLowerCase()
    .replace(/\b\w/g, c => c.toUpperCase());
}

/** A building's hammer cost, or 0 when unknown/costless (a costless row is unbuildable). */
export function buildingCost(id) {
  const c = parseInt(buildMeta(id)?.cost, 10);
  return Number.isFinite(c) && c > 0 ? c : 0;
}

/**
 * Stamp a building's button onto a DOM element sized `px`: the catalog's [x, y] is a pixel offset
 * into the 64²-cell sheet, scaled to the render size. Falls back to a lettered chip.
 */
export function paintBuildIcon(el, id, px) {
  const icon = buildMeta(id)?.icon;
  if (!icon) {
    el.classList.add("chip");
    el.textContent = buildingName(id)[0] || "?";
    return;
  }
  const [x, y] = icon;
  const bw = sheetImg.naturalWidth || 3200, bh = sheetImg.naturalHeight || 1344, s = px / 64;
  el.style.backgroundImage = `url(${SHEET})`;
  el.style.backgroundSize = `${bw * s}px ${bh * s}px`;
  el.style.backgroundPosition = `${-x * s}px ${-y * s}px`;
}

/** Draw a building's button icon on a canvas, centred at (cx, cy) at `s` px. */
export function drawBuildIcon(ctx, id, cx, cy, s) {
  const icon = buildMeta(id)?.icon;
  if (icon && sheetImg.complete && sheetImg.naturalWidth) {
    const [x, y, w, h] = icon;
    ctx.drawImage(sheetImg, x, y, w || 64, h || 64, cx - s / 2, cy - s / 2, s, s);
    return;
  }
  // no icon rect (yet) — a small stone chip so the building still shows
  ctx.beginPath();
  ctx.arc(cx, cy, s * 0.32, 0, Math.PI * 2);
  ctx.fillStyle = "rgba(210,200,186,0.9)";
  ctx.fill();
}
