// Minimal DDS reader + hand-rolled DXT1/DXT3/DXT5 block decoder — no dependencies.
//
// The Civ4/C2C terrain textures the build bakes (data/civ4/assets) are DXT-compressed .dds
// (DirectDraw Surface), which browsers can't read and Node has no codec for. This
// decodes the top mip level to raw RGBA so the build can bake web imagery from it.
// Kept deliberately dependency-free, in the spirit of build.mjs's hand-rolled PNG
// encoder. See docs/ported-terrain-art-system.md §10.
//
//   import { decodeDds } from './dds.mjs';
//   const { width, height, rgba } = decodeDds(fs.readFileSync('x.dds'));
//
// Supports the three classic FourCC block formats (DXT1/DXT3/DXT5). Throws on
// uncompressed or DX10-extended-header .dds — the caller falls back gracefully.

const MAGIC = 0x20534444;        // 'DDS ' little-endian
const FLAG_FOURCC = 0x4;         // DDPF_FOURCC in ddspf.dwFlags

// FourCC codes as little-endian uint32
const fourCC = s => s.charCodeAt(0) | (s.charCodeAt(1) << 8) | (s.charCodeAt(2) << 16) | (s.charCodeAt(3) << 24);
const DXT1 = fourCC('DXT1'), DXT3 = fourCC('DXT3'), DXT5 = fourCC('DXT5');

/**
 * Decode a DDS buffer's top mip to RGBA.
 * @param {Buffer|Uint8Array} buf
 * @returns {{width:number, height:number, rgba:Uint8Array}}
 */
export function decodeDds(buf) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (dv.getUint32(0, true) !== MAGIC) throw new Error('not a DDS file');
  const height = dv.getUint32(12, true);
  const width = dv.getUint32(16, true);
  const pfFlags = dv.getUint32(80, true);
  const cc = dv.getUint32(84, true);
  if (!(pfFlags & FLAG_FOURCC)) throw new Error('DDS is uncompressed (no FourCC) — unsupported');
  if (cc !== DXT1 && cc !== DXT3 && cc !== DXT5) {
    const tag = String.fromCharCode(cc & 255, (cc >> 8) & 255, (cc >> 16) & 255, (cc >> 24) & 255);
    throw new Error(`unsupported DDS FourCC "${tag}" (only DXT1/3/5)`);
  }
  const data = 128;                            // classic DDS header is 4 + 124 bytes
  const rgba = new Uint8Array(width * height * 4);
  const bw = Math.max(1, (width + 3) >> 2), bh = Math.max(1, (height + 3) >> 2);
  const blockBytes = cc === DXT1 ? 8 : 16;
  let off = data;
  for (let by = 0; by < bh; by++)
    for (let bx = 0; bx < bw; bx++, off += blockBytes)
      decodeBlock(dv, off, cc, rgba, width, height, bx * 4, by * 4);
  return { width, height, rgba };
}

// unpack an RGB565 uint16 into [r,g,b] bytes
function rgb565(v) {
  const r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, b = v & 0x1f;
  return [(r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)];
}

// decode one 4x4 block into rgba at pixel (px,py), clamped to (w,h)
function decodeBlock(dv, off, cc, rgba, w, h, px, py) {
  // --- alpha (DXT3: explicit 4-bit; DXT5: interpolated; DXT1: from colour block) ---
  let alpha = null;               // filled below for DXT3/DXT5
  let colourOff = off;
  if (cc === DXT3) {
    alpha = new Uint8Array(16);
    for (let i = 0; i < 4; i++) {
      const bits = dv.getUint16(off + i * 2, true);
      for (let j = 0; j < 4; j++) { const a = (bits >> (j * 4)) & 0xf; alpha[i * 4 + j] = (a << 4) | a; }
    }
    colourOff = off + 8;
  } else if (cc === DXT5) {
    alpha = new Uint8Array(16);
    const a0 = dv.getUint8(off), a1 = dv.getUint8(off + 1);
    const av = new Array(8);
    av[0] = a0; av[1] = a1;
    if (a0 > a1) for (let i = 1; i < 7; i++) av[i + 1] = ((7 - i) * a0 + i * a1 + 3) / 7 | 0;
    else { for (let i = 1; i < 5; i++) av[i + 1] = ((5 - i) * a0 + i * a1 + 2) / 5 | 0; av[6] = 0; av[7] = 255; }
    // 16 pixels × 3-bit indices packed into 6 bytes (two 24-bit little-endian runs)
    for (let g = 0; g < 2; g++) {
      let bits = dv.getUint8(off + 2 + g * 3) | (dv.getUint8(off + 3 + g * 3) << 8) | (dv.getUint8(off + 4 + g * 3) << 16);
      for (let j = 0; j < 8; j++) { alpha[g * 8 + j] = av[bits & 0x7]; bits >>= 3; }
    }
    colourOff = off + 8;
  }

  // --- colour (DXT1-style 4-colour block; the c0<=c1 3-colour+transparent case) ---
  const c0 = dv.getUint16(colourOff, true), c1 = dv.getUint16(colourOff + 2, true);
  const e0 = rgb565(c0), e1 = rgb565(c1);
  const col = [e0, e1, [0, 0, 0], [0, 0, 0]];
  const punch = cc === DXT1 && c0 <= c1;        // 3-colour mode with a transparent index
  if (punch) {
    for (let k = 0; k < 3; k++) col[2][k] = (e0[k] + e1[k]) >> 1;
    col[3] = [0, 0, 0];                          // index 3 = transparent black
  } else {
    for (let k = 0; k < 3; k++) { col[2][k] = (2 * e0[k] + e1[k]) / 3 | 0; col[3][k] = (e0[k] + 2 * e1[k]) / 3 | 0; }
  }
  const idx = dv.getUint32(colourOff + 4, true);
  for (let j = 0; j < 16; j++) {
    const ci = (idx >> (j * 2)) & 0x3;
    const c = col[ci];
    const x = px + (j & 3), y = py + (j >> 2);
    if (x >= w || y >= h) continue;
    const o = (y * w + x) * 4;
    rgba[o] = c[0]; rgba[o + 1] = c[1]; rgba[o + 2] = c[2];
    rgba[o + 3] = alpha ? alpha[j] : (punch && ci === 3 ? 0 : 255);
  }
}
