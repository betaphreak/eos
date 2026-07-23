"use strict";
// Building FOOTPRINTS — the deep end of the city-micro spine (docs/zoom-bands.md band 6: "building
// footprints per developed plot"). Past the icon band a plot stops being a chip with a spiral of
// button icons around it and becomes a piece of ground with blocks standing on it: one block per
// building, laid out in a stable grid inside the plot cell, with anything still under construction
// drawn as a part-filled scaffold.
//
// Pure geometry, no canvas and no imports, so it unit-tests under node (see footprints.test.mjs).

/**
 * Lay `n` footprints out inside a plot cell of side `s` px whose top-left is (x0, y0).
 *
 * The grid is squarest-first (cols = ceil(sqrt(n))), so a plot with one building gets one big
 * block and a plot with nine gets a 3×3 hamlet — the visual weight of a plot reads as how built-up
 * it is, which is the whole point of the band. Blocks fill `FILL` of their cell so there is always
 * a street's worth of gap between them.
 *
 * Deterministic: the same n and cell always give the same boxes, so a plot does not shimmer as the
 * camera moves. Order is the caller's order (the feed's), so a new building appears in a free slot
 * rather than reshuffling its neighbours — provided the caller keeps its list stable.
 *
 * @returns {{x:number,y:number,w:number,h:number}[]} one box per footprint, in the given order
 */
export function footprintCells(n, s, x0 = 0, y0 = 0) {
  if (!(n > 0) || !(s > 0)) return [];
  const cols = Math.ceil(Math.sqrt(n));
  const rows = Math.ceil(n / cols);
  const pad = s * PAD;
  const inner = s - pad * 2;
  const cw = inner / cols;
  const ch = inner / rows;
  const w = cw * FILL;
  const h = ch * FILL;
  const out = [];
  for (let i = 0; i < n; i++) {
    const col = i % cols;
    const row = Math.floor(i / cols);
    out.push({
      x: x0 + pad + col * cw + (cw - w) / 2,
      y: y0 + pad + row * ch + (ch - h) / 2,
      w, h,
    });
  }
  return out;
}

const PAD = 0.12;    // margin from the plot edge — the ground the blocks sit on
const FILL = 0.74;   // block size within its grid cell — the rest is street

/**
 * The draw order for one plot's footprints: finished buildings first (they are the settled town),
 * then whatever is rising, so a scaffold always takes the last slots and the built blocks keep
 * their positions as construction comes and goes.
 *
 * @param {{id:string,owner:string}[]} buildings the finished buildings on the plot
 * @param {{id:string,cost:number,progress:number,owner:string}[]} underway constructions in flight
 * @returns {{id:string,owner:string,progress:number|null}[]} one entry per block; `progress` is
 *          null for a finished building and 0..1 for a scaffold
 */
export function plotBlocks(buildings, underway) {
  const out = [];
  for (const b of buildings || []) out.push({ id: b.id, owner: b.owner || "NONE", progress: null });
  for (const u of underway || [])
    out.push({
      id: u.id,
      owner: u.owner || "NONE",
      progress: u.cost > 0 ? Math.max(0, Math.min(1, u.progress / u.cost)) : 0,
    });
  return out;
}
