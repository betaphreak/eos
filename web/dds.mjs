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
// Supports the three classic FourCC block formats (DXT1/DXT3/DXT5), DX10
// uncompressed B8G8R8A8 / R8G8B8A8 (the format Anbennar's gfx/interface/resources.dds
// icon strip uses), and classic (non-FourCC) uncompressed surfaces decoded via the
// ddspf RGBA bit-masks — the format the Civ6 SDK depot ships (R8G8B8A8 tiles, L8 masks;
// see docs/civ6-assets.md §2a). Throws on formats none of those cover — the caller falls back.

const MAGIC = 0x20534444;        // 'DDS ' little-endian
const FLAG_FOURCC = 0x4;         // DDPF_FOURCC in ddspf.dwFlags

// FourCC codes as little-endian uint32
const fourCC = s => s.charCodeAt(0) | (s.charCodeAt(1) << 8) | (s.charCodeAt(2) << 16) | (s.charCodeAt(3) << 24);
const DXT1 = fourCC('DXT1'), DXT3 = fourCC('DXT3'), DXT5 = fourCC('DXT5'), DX10 = fourCC('DX10');

// the DXGI_FORMAT values (in the 20-byte DX10 header) this decoder handles as raw 32-bit pixels
const DXGI_B8G8R8A8_UNORM = 87, DXGI_B8G8R8A8_UNORM_SRGB = 91;   // BGRA byte order
const DXGI_R8G8B8A8_UNORM = 28, DXGI_R8G8B8A8_UNORM_SRGB = 29;   // RGBA byte order

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
  if (!(pfFlags & FLAG_FOURCC)) return decodeUncompressed(dv, buf, width, height);
  if (cc === DX10) return decodeDx10Uncompressed(dv, buf, width, height);
  if (cc !== DXT1 && cc !== DXT3 && cc !== DXT5) {
    const tag = String.fromCharCode(cc & 255, (cc >> 8) & 255, (cc >> 16) & 255, (cc >> 24) & 255);
    throw new Error(`unsupported DDS FourCC "${tag}" (only DXT1/3/5, DX10)`);
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

// Decode a DX10-extended, uncompressed 32-bit surface (header: 4 + 124 DDS + 20 DX10 = 148 bytes,
// then raw rows at pitch = width*4). Handles the B8G8R8A8 / R8G8B8A8 dxgiFormats — the only
// uncompressed layouts we bake from (Anbennar's resources.dds is B8G8R8A8_UNORM_SRGB).
function decodeDx10Uncompressed(dv, buf, width, height) {
  const dxgi = dv.getUint32(128, true);          // first field of the 20-byte DX10 header
  const bgra = dxgi === DXGI_B8G8R8A8_UNORM || dxgi === DXGI_B8G8R8A8_UNORM_SRGB;
  const rgbaOrder = dxgi === DXGI_R8G8B8A8_UNORM || dxgi === DXGI_R8G8B8A8_UNORM_SRGB;
  if (!bgra && !rgbaOrder)
    throw new Error(`unsupported DX10 dxgiFormat ${dxgi} (only B8G8R8A8 / R8G8B8A8)`);
  const data = 148;                              // 4 magic + 124 DDS header + 20 DX10 header
  const src = new Uint8Array(buf.buffer, buf.byteOffset + data, width * height * 4);
  const rgba = new Uint8Array(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    const o = i * 4;
    if (bgra) { rgba[o] = src[o + 2]; rgba[o + 1] = src[o + 1]; rgba[o + 2] = src[o]; rgba[o + 3] = src[o + 3]; }
    else { rgba[o] = src[o]; rgba[o + 1] = src[o + 1]; rgba[o + 2] = src[o + 2]; rgba[o + 3] = src[o + 3]; }
  }
  return { width, height, rgba };
}

// Decode a classic (non-FourCC) uncompressed surface via the ddspf RGBA bit-masks.
// Header layout: 4 magic + 124 DDS header = 128 bytes, then raw rows at pitch = width*bytesPP.
// ddspf fields: dwFlags@80, dwRGBBitCount@88, dw{R,G,B,A}BitMask@{92,96,100,104}. Handles 8/16/24/32
// bpp, any channel order (RGBA / BGRA / XRGB…), and single-channel luminance (only an R mask → grey).
// This is the Civ6 SDK format: SV_* tiles are R8G8B8A8 (masks R=0xFF…A=0xFF000000), *_A masks are L8.
function decodeUncompressed(dv, buf, width, height) {
  const bitCount = dv.getUint32(88, true);
  const rMask = dv.getUint32(92, true) >>> 0, gMask = dv.getUint32(96, true) >>> 0;
  const bMask = dv.getUint32(100, true) >>> 0, aMask = dv.getUint32(104, true) >>> 0;
  if (bitCount !== 8 && bitCount !== 16 && bitCount !== 24 && bitCount !== 32)
    throw new Error(`unsupported uncompressed DDS bit count ${bitCount}`);
  // trailing-zero shift + normalised max for a channel mask
  const chan = m => { if (!m) return [0, 0]; let s = 0; while (((m >>> s) & 1) === 0) s++; return [s, m >>> s]; };
  const [rs, rm] = chan(rMask), [gs, gm] = chan(gMask), [bs, bm] = chan(bMask), [as, am] = chan(aMask);
  const scale = (px, sh, mx) => mx ? Math.round((((px >>> sh) & mx) * 255) / mx) : 0;
  const luminance = gMask === 0 && bMask === 0;   // single R mask (L8) → replicate to grey
  const bytesPP = bitCount >> 3;
  const data = 128;
  const src = new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
  const rgba = new Uint8Array(width * height * 4);
  let off = data;
  for (let i = 0; i < width * height; i++, off += bytesPP) {
    let px = 0;
    for (let b = 0; b < bytesPP; b++) px |= src[off + b] << (b * 8);
    px >>>= 0;
    const o = i * 4;
    if (luminance) {
      const l = scale(px, rs, rm);
      rgba[o] = rgba[o + 1] = rgba[o + 2] = l;
    } else {
      rgba[o] = scale(px, rs, rm); rgba[o + 1] = scale(px, gs, gm); rgba[o + 2] = scale(px, bs, bm);
    }
    rgba[o + 3] = aMask ? scale(px, as, am) : 255;
  }
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
