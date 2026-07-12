// Small dependency-free raw-RGBA pixel helpers shared by the bakers (build.mjs) — decode caching,
// box-resampling, a class-backing octagon, and centred alpha compositing. Kept out of build.mjs so
// they can be unit-tested (imgutil.test.mjs) and reused as the Civ6 bake grows (terrain, icons,
// features). All operate on raw RGBA Buffers so they slot into build.mjs's sync-buffer→async-flush
// pipeline (no async sharp). See docs/civ6-art-replacement.md.
import fs from 'node:fs';
import { decodeDds } from './dds.mjs';

// decode-once cache — a Civ6 ground/atlas (e.g. Grass_B 2k, Resources256 2k) is shared by many
// terrains/bonuses, so decode each source file at most once. Keyed by absolute path; null on failure.
const _cache = new Map();
export function decodeCached(file) {
  if (_cache.has(file)) return _cache.get(file);
  let img = null;
  try { img = decodeDds(fs.readFileSync(file)); } catch { /* leave null */ }
  _cache.set(file, img);
  return img;
}

// box-resample an RGBA source (sw×sh) to dw×dh. Handles up- AND down-scale: the sample region is
// clamped to ≥1 source pixel, so upscaling a source SMALLER than the target never divides by zero
// (the bug that turned Civ6's 128² Grass_Dark_B into black tiles). Returns a dw·dh·4 Buffer.
export function resampleRGBA(src, sw, sh, dw, dh) {
  const out = Buffer.alloc(dw * dh * 4);
  const bx = sw / dw, by = sh / dh;
  for (let j = 0; j < dh; j++)
    for (let i = 0; i < dw; i++) {
      let r = 0, g = 0, b = 0, a = 0, n = 0;
      const y0 = Math.min(sh - 1, Math.floor(j * by)), y1 = Math.max(y0 + 1, Math.floor((j + 1) * by));
      const x0 = Math.min(sw - 1, Math.floor(i * bx)), x1 = Math.max(x0 + 1, Math.floor((i + 1) * bx));
      for (let y = y0; y < y1 && y < sh; y++)
        for (let x = x0; x < x1 && x < sw; x++) {
          const o = (y * sw + x) * 4; r += src[o]; g += src[o + 1]; b += src[o + 2]; a += src[o + 3]; n++;
        }
      const d = (j * dw + i) * 4; out[d] = r / n | 0; out[d + 1] = g / n | 0; out[d + 2] = b / n | 0; out[d + 3] = a / n | 0;
    }
  return out;
}

// a filled rounded-octagon backing (class colour `col=[r,g,b]`) with a darker rim + subtle top light,
// as an S×S RGBA Buffer. Mirrors Civ6's class-coloured resource octagon so C2C glyphs composited onto
// it read consistently with the real Civ6 atlas cells.
export function octagonBacking(S, col) {
  const out = Buffer.alloc(S * S * 4);
  const c = (S - 1) / 2, h = S * 0.46, cut = h * 1.42, rim = S * 0.06;   // square clipped by a diamond
  for (let y = 0; y < S; y++)
    for (let x = 0; x < S; x++) {
      const dx = Math.abs(x - c), dy = Math.abs(y - c), d = (y * S + x) * 4;
      if (dx > h || dy > h || dx + dy > cut) { out[d + 3] = 0; continue; }
      const edge = Math.min(h - dx, h - dy, (cut - dx - dy) * 0.72);
      const k = (edge < rim ? 0.6 : 1) * (1 + (c - y) / S * 0.32);       // darker border, lighter top
      out[d] = Math.min(255, col[0] * k) | 0; out[d + 1] = Math.min(255, col[1] * k) | 0;
      out[d + 2] = Math.min(255, col[2] * k) | 0; out[d + 3] = 255;
    }
  return out;
}

// alpha-over composite `src` (sw×sh RGBA) scaled to `frac` of the S×S cell, centred, onto `dst`
// (S×S RGBA, mutated in place).
export function compositeCentered(dst, S, src, sw, sh, frac) {
  const scale = Math.min(S * frac / sw, S * frac / sh);
  const dw = Math.max(1, Math.round(sw * scale)), dh = Math.max(1, Math.round(sh * scale));
  const rs = resampleRGBA(src, sw, sh, dw, dh);
  const ox = Math.round((S - dw) / 2), oy = Math.round((S - dh) / 2);
  for (let y = 0; y < dh; y++)
    for (let x = 0; x < dw; x++) {
      const so = (y * dw + x) * 4, a = rs[so + 3] / 255;
      if (a <= 0) continue;
      const d = ((oy + y) * S + (ox + x)) * 4;
      dst[d] = (rs[so] * a + dst[d] * (1 - a)) | 0; dst[d + 1] = (rs[so + 1] * a + dst[d + 1] * (1 - a)) | 0;
      dst[d + 2] = (rs[so + 2] * a + dst[d + 2] * (1 - a)) | 0; dst[d + 3] = Math.max(dst[d + 3], rs[so + 3]);
    }
}
