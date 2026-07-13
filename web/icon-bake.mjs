// Shared helpers for baking Civ4 DDS button icons into a sprite sheet — used by
// build-techs.mjs (tech buttons) and build-buildings.mjs (building buttons).
import fs from 'node:fs';
import { decodeDds } from './dds.mjs';

// The standalone individual-icon path from a Civ4 `Button` field, normalized to forward
// slashes. A Button is either a single DDS path or the atlas form
// ",<individual>.dds,<atlas>.dds,col,row" — take the standalone <individual> icon in both
// cases (no atlas slicing). C2C's CIV4ArtDefines_Building.xml mixes in back-slashed paths
// (e.g. "Art\Interface\Buttons\Buildings\beliefkarma.dds"), so normalize `\` → `/` for
// civ4.mjs resolveArt (which strips a leading forward-slash "Art/" and splits on "/").
export function iconPath(button) {
  if (!button) return null;
  const parts = button.split(',');
  return (parts.length >= 5 ? parts[1] : button).trim().replace(/\\/g, '/');
}

// Decode a DDS icon to a `cell`×`cell` RGBA buffer (box-average when the source is larger,
// nearest-ish when smaller); null if it can't be read.
export function iconCell(file, cell) {
  let img;
  try { img = decodeDds(fs.readFileSync(file)); } catch { return null; }
  const { width: w, height: h, rgba } = img;
  if (w === cell && h === cell) return rgba;
  const out = new Uint8Array(cell * cell * 4);
  const bx = w / cell, by = h / cell;
  for (let j = 0; j < cell; j++)
    for (let i = 0; i < cell; i++) {
      let r = 0, g = 0, b = 0, a = 0, n = 0;
      const y0 = Math.floor(j * by), y1 = Math.max(y0 + 1, Math.floor((j + 1) * by));
      const x0 = Math.floor(i * bx), x1 = Math.max(x0 + 1, Math.floor((i + 1) * bx));
      for (let y = y0; y < y1 && y < h; y++)
        for (let x = x0; x < x1 && x < w; x++) {
          const o = (y * w + x) * 4;
          r += rgba[o]; g += rgba[o + 1]; b += rgba[o + 2]; a += rgba[o + 3]; n++;
        }
      const d = (j * cell + i) * 4;
      out[d] = r / n; out[d + 1] = g / n; out[d + 2] = b / n; out[d + 3] = a / n;
    }
  return out;
}

// Pack a list of `cell`×`cell` RGBA buffers into a `cols`-wide sheet, returning
// { buffer, width, height }. Placement is row-major in list order.
export function packSheet(cells, cell, cols) {
  const rows = Math.max(1, Math.ceil(cells.length / cols));
  const width = cols * cell, height = rows * cell;
  const sheet = Buffer.alloc(width * height * 4);
  cells.forEach((c, i) => {
    const ox = (i % cols) * cell, oy = Math.floor(i / cols) * cell;
    for (let j = 0; j < cell; j++) {
      const srcRow = j * cell * 4;
      const dstRow = ((oy + j) * width + ox) * 4;
      c.copy ? c.copy(sheet, dstRow, srcRow, srcRow + cell * 4)
             : sheet.set(c.subarray(srcRow, srcRow + cell * 4), dstRow);
    }
  });
  return { buffer: sheet, width, height };
}
