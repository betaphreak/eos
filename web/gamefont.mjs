// Shared reader for the Civ4 GameFont sprite sheet (data/civ4/res/Fonts/GameFont_120.tga) —
// the single source of GameFont glyph geometry for the whole build. The font packs the
// game's inline symbols on a fixed grid: after the numeric/letter glyph rows, the ICON
// grid begins at y=Y0 (72) with CELL (25) px square cells, COLS (25) per row. The
// resource-bonus block is icon-row RESOURCE_ROW (17): 72 + 17·25 = 497, matching the
// value the bonus bake was calibrated to. A FontButtonIndex counts cells from there.
//
// Anything that needs a GameFont glyph goes through here: the resource icons
// (build.mjs bakeBonusIcons) and the research beaker + future commerce/yield symbols
// (build-techs.mjs). Extend SYMBOL with more named cells as they are needed.
import fs from 'node:fs';
import path from 'node:path';
import { decodeTga } from './tga.mjs';

export const CELL = 25, COLS = 25, X0 = 0, Y0 = 72, RESOURCE_ROW = 17;

// named game symbols → [col, row] in the icon grid (row 0 = first icon row), calibrated
// from GameFont_120.tga by alpha-projection + eye (see docs/tech-tree.md, cost beaker)
export const SYMBOL = {
  BEAKER: [1, 1],   // research (the green conical beaker)
  HAMMER: [1, 0],   // production (the blue hammer) — the future red-beaker source
  GOLD: [0, 1],     // treasury (the gold coin stack)
};

let _gf = null, _tried = false;

/** Decode GameFont_120.tga once (cached); null if the font isn't vendored / can't be read. */
export function loadGameFont(root) {
  if (_tried) return _gf;
  _tried = true;
  const p = path.join(root, 'data/civ4/res/Fonts/GameFont_120.tga');
  try { _gf = fs.existsSync(p) ? decodeTga(fs.readFileSync(p)) : null; } catch { _gf = null; }
  return _gf;
}

/** The CELL×CELL interleaved RGBA of icon cell (col,row); null if out of bounds / no font. */
export function cellRGBA(gf, col, row) {
  if (!gf) return null;
  const sx = X0 + col * CELL, sy = Y0 + row * CELL;
  if (sx < 0 || sy < 0 || sx + CELL > gf.width || sy + CELL > gf.height) return null;
  const out = Buffer.alloc(CELL * CELL * 4);
  for (let y = 0; y < CELL; y++)
    for (let x = 0; x < CELL; x++) {
      const so = ((sy + y) * gf.width + (sx + x)) * 4, d = (y * CELL + x) * 4;
      out[d] = gf.rgba[so]; out[d + 1] = gf.rgba[so + 1];
      out[d + 2] = gf.rgba[so + 2]; out[d + 3] = gf.rgba[so + 3];
    }
  return out;
}

/** A resource-bonus icon by its FontButtonIndex (the bonus block, icon-row RESOURCE_ROW+). */
export function resourceCellRGBA(gf, fontButtonIndex) {
  if (fontButtonIndex == null || fontButtonIndex < 0) return null;
  return cellRGBA(gf, fontButtonIndex % COLS, RESOURCE_ROW + Math.floor(fontButtonIndex / COLS));
}

/** A named symbol (see SYMBOL) as an RGBA cell; null if unknown / no font. */
export function symbolRGBA(gf, name) {
  const c = SYMBOL[name];
  return c ? cellRGBA(gf, c[0], c[1]) : null;
}

// Recolour a glyph's SATURATED pixels to a target hue while leaving its near-grey pixels
// (glass, outline, metal) untouched — so the green research beaker can be minted into the
// blue / red / yellow beaker variants from the one GameFont source. Returns a new buffer.
export function recolorHue(cell, hueDeg) {
  const out = Buffer.from(cell);
  const h = ((hueDeg % 360) + 360) % 360 / 360;
  for (let i = 0; i < out.length; i += 4) {
    if (out[i + 3] < 8) continue;                       // transparent
    const r = out[i] / 255, g = out[i + 1] / 255, b = out[i + 2] / 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2, d = max - min;
    const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
    if (s < 0.28) continue;                             // near-grey (glass/outline): keep
    const [nr, ng, nb] = hslToRgb(h, s, l);
    out[i] = nr; out[i + 1] = ng; out[i + 2] = nb;
  }
  return out;
}

function hslToRgb(h, s, l) {
  if (s === 0) { const v = Math.round(l * 255); return [v, v, v]; }
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
  const hue = t => {
    t = (t + 1) % 1;
    if (t < 1 / 6) return p + (q - p) * 6 * t;
    if (t < 1 / 2) return q;
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
    return p;
  };
  return [hue(h + 1 / 3), hue(h), hue(h - 1 / 3)].map(v => Math.round(v * 255));
}
