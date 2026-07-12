// Tests for the DDS decoder — run with `node --test` (built-in test runner, no deps).
// Focus: the classic uncompressed (non-FourCC) path added for the Civ6 SDK art
// (docs/civ6-assets.md §2a). Buffers are synthesized in-memory so the test is hermetic
// and does not touch the gitignored .civ6-cache.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeDds } from './dds.mjs';

// Build a minimal classic (non-FourCC) uncompressed DDS: 128-byte header + raw pixel rows.
// `pixels` is a flat byte array already in the surface's native little-endian channel order.
function makeUncompressedDds({ width, height, bitCount, rMask, gMask, bMask, aMask, pixels }) {
  const buf = Buffer.alloc(128 + pixels.length);
  buf.writeUInt32LE(0x20534444, 0);      // 'DDS '
  buf.writeUInt32LE(124, 4);             // dwSize
  buf.writeUInt32LE(0x1007, 8);          // dwFlags: CAPS|HEIGHT|WIDTH|PIXELFORMAT
  buf.writeUInt32LE(height, 12);
  buf.writeUInt32LE(width, 16);
  buf.writeUInt32LE(32, 76);             // ddspf.dwSize
  buf.writeUInt32LE(aMask ? 0x41 : 0x40, 80); // DDPF_RGB (+ALPHAPIXELS if alpha)
  buf.writeUInt32LE(0, 84);              // dwFourCC = 0 → uncompressed classic path
  buf.writeUInt32LE(bitCount, 88);
  buf.writeUInt32LE(rMask >>> 0, 92);
  buf.writeUInt32LE(gMask >>> 0, 96);
  buf.writeUInt32LE(bMask >>> 0, 100);
  buf.writeUInt32LE(aMask >>> 0, 104);
  Buffer.from(pixels).copy(buf, 128);
  return buf;
}

test('R8G8B8A8 (Civ6 SV_* tile format) decodes channels in order', () => {
  // one pixel R=10 G=20 B=30 A=40, stored little-endian as bytes [10,20,30,40]
  const dds = makeUncompressedDds({
    width: 1, height: 1, bitCount: 32,
    rMask: 0x000000ff, gMask: 0x0000ff00, bMask: 0x00ff0000, aMask: 0xff000000,
    pixels: [10, 20, 30, 40],
  });
  const { width, height, rgba } = decodeDds(dds);
  assert.equal(width, 1);
  assert.equal(height, 1);
  assert.deepEqual([...rgba], [10, 20, 30, 40]);
});

test('A8R8G8B8 (BGRA byte order) is unshuffled to RGBA', () => {
  // masks say byte0=B, byte1=G, byte2=R, byte3=A. Bytes [30,20,10,40] → RGBA 10,20,30,40
  const dds = makeUncompressedDds({
    width: 1, height: 1, bitCount: 32,
    rMask: 0x00ff0000, gMask: 0x0000ff00, bMask: 0x000000ff, aMask: 0xff000000,
    pixels: [30, 20, 10, 40],
  });
  const { rgba } = decodeDds(dds);
  assert.deepEqual([...rgba], [10, 20, 30, 40]);
});

test('X8R8G8B8 (no alpha mask) yields opaque alpha', () => {
  const dds = makeUncompressedDds({
    width: 1, height: 1, bitCount: 32,
    rMask: 0x00ff0000, gMask: 0x0000ff00, bMask: 0x000000ff, aMask: 0,
    pixels: [30, 20, 10, 255],
  });
  const { rgba } = decodeDds(dds);
  assert.deepEqual([...rgba], [10, 20, 30, 255]);
});

test('L8 single-channel (Civ6 *_A mask) replicates to grey, opaque', () => {
  const dds = makeUncompressedDds({
    width: 2, height: 1, bitCount: 8,
    rMask: 0xff, gMask: 0, bMask: 0, aMask: 0,
    pixels: [128, 255],
  });
  const { width, rgba } = decodeDds(dds);
  assert.equal(width, 2);
  assert.deepEqual([...rgba.slice(0, 4)], [128, 128, 128, 255]);
  assert.deepEqual([...rgba.slice(4, 8)], [255, 255, 255, 255]);
});

test('multi-pixel row is laid out left-to-right', () => {
  const dds = makeUncompressedDds({
    width: 2, height: 1, bitCount: 32,
    rMask: 0x000000ff, gMask: 0x0000ff00, bMask: 0x00ff0000, aMask: 0xff000000,
    pixels: [1, 2, 3, 255, 4, 5, 6, 255],
  });
  const { rgba } = decodeDds(dds);
  assert.deepEqual([...rgba], [1, 2, 3, 255, 4, 5, 6, 255]);
});
