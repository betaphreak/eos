// Minimal TGA decoder — build-time only, for the Civ4 coastscalemask blend masks
// (heightmap/coastblendmasks/*.tga), which are tiny uncompressed 8-bit colour-mapped
// greyscale ramps. Handles image type 1 (colour-mapped) and 3 (greyscale), the only
// forms these masks use; returns the per-pixel luminance in top-down row order. Kept
// dependency-free and out of the shipped page, like dds.mjs (docs/coastlines.md §B).
export function decodeTga(b) {
  const idLen = b[0], cmType = b[1], imgType = b[2];
  const cmLen = b.readUInt16LE(5), cmEntry = b[7];
  const w = b.readUInt16LE(12), h = b.readUInt16LE(14), bpp = b[16], desc = b[17];
  if (imgType !== 1 && imgType !== 3)
    throw new Error('unsupported TGA image type ' + imgType);
  if (bpp !== 8) throw new Error('unsupported TGA bpp ' + bpp);
  let o = 18 + idLen;
  // colour map (image type 1): entries are BGR(A); reduce each to a luminance
  let pal = null;
  if (cmType === 1) {
    const es = cmEntry / 8;
    pal = new Uint8Array(cmLen);
    for (let i = 0; i < cmLen; i++) { const p = o + i * es; pal[i] = (b[p] + b[p + 1] + b[p + 2]) / 3; }
    o += cmLen * es;
  }
  const topDown = (desc & 0x20) !== 0;   // bit 5: 1 = top-to-bottom, 0 = bottom-up
  const gray = new Uint8Array(w * h);
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const idx = b[o + y * w + x];
      const ry = topDown ? y : (h - 1 - y);   // normalise to top-down
      gray[ry * w + x] = pal ? pal[idx] : idx;
    }
  return { width: w, height: h, gray };
}
