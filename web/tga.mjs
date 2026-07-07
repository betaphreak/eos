// Minimal TGA reader — build-time only, for slicing Civ4's GameFont.tga into web icons
// (docs/bonus-sprite-bake.md). Civ4 shipped UI/font art as TGA (uncompressed load, simple 32-bit
// alpha — see docs/ported-terrain-art-system.md). GameFont.tga is RLE 32-bit BGRA, bottom-up.
//
//   import { decodeTga } from './tga.mjs';
//   const { width, height, rgba } = decodeTga(fs.readFileSync('GameFont.tga'));  // top-down RGBA
//
// Supports uncompressed (type 2) and RLE (type 10) truecolour, 24/32 bpp; no colour-map.

export function decodeTga(buf) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const idlen = dv.getUint8(0);
  if (dv.getUint8(1) !== 0) throw new Error('TGA: colour-mapped not supported');
  const type = dv.getUint8(2);                 // 2 = raw truecolour, 10 = RLE truecolour
  if (type !== 2 && type !== 10) throw new Error('TGA: unsupported image type ' + type);
  const w = dv.getUint16(12, true), h = dv.getUint16(14, true);
  const bpp = dv.getUint8(16);                  // 24 or 32
  if (bpp !== 24 && bpp !== 32) throw new Error('TGA: unsupported bpp ' + bpp);
  const topOrigin = (dv.getUint8(17) & 0x20) !== 0;   // descriptor bit 5: 0 = bottom-up
  const bpx = bpp >> 3;                          // bytes per pixel (BGRA / BGR)
  let off = 18 + idlen;                          // no colour map (type 0)
  const n = w * h, rgba = new Uint8Array(n * 4);
  const put = (di, o) => {                       // one source pixel (BGRA) → RGBA
    rgba[di] = dv.getUint8(o + 2); rgba[di + 1] = dv.getUint8(o + 1); rgba[di + 2] = dv.getUint8(o);
    rgba[di + 3] = bpx === 4 ? dv.getUint8(o + 3) : 255;
  };
  if (type === 2) {
    for (let i = 0; i < n; i++) put(i * 4, off + i * bpx);
  } else {                                       // RLE packets
    let i = 0;
    while (i < n) {
      const hdr = dv.getUint8(off++), count = (hdr & 0x7f) + 1;
      if (hdr & 0x80) {                          // run: one pixel repeated
        for (let k = 0; k < count && i < n; k++, i++) put(i * 4, off);
        off += bpx;
      } else {                                   // literal: count raw pixels
        for (let k = 0; k < count && i < n; k++, i++) { put(i * 4, off); off += bpx; }
      }
    }
  }
  if (!topOrigin) {                              // bottom-up → flip to top-down rows
    const row = w * 4, tmp = new Uint8Array(row);
    for (let y = 0; y < (h >> 1); y++) {
      const a = y * row, b = (h - 1 - y) * row;
      tmp.set(rgba.subarray(a, a + row)); rgba.copyWithin(a, b, b + row); rgba.set(tmp, b);
    }
  }
  return { width: w, height: h, rgba };
}
