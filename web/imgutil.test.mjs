// Tests for the shared raw-RGBA pixel helpers — run with `node --test`. Hermetic (synthesized buffers).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resampleRGBA, octagonBacking, compositeCentered } from './imgutil.mjs';

test('resampleRGBA downscale box-averages 2×2 → 1×1', () => {
  // four pixels: R = 0,100,200,100 (mean 100); all opaque
  const src = Buffer.from([0,0,0,255, 100,0,0,255, 200,0,0,255, 100,0,0,255]);
  const out = resampleRGBA(src, 2, 2, 1, 1);
  assert.equal(out[0], 100); assert.equal(out[3], 255);
});

test('resampleRGBA upscale 1×1 → 2×2 replicates (no black/NaN — the Grass_Dark_B bug)', () => {
  const src = Buffer.from([40, 65, 8, 255]);   // a small source, smaller than the target
  const out = resampleRGBA(src, 1, 1, 2, 2);
  for (let i = 0; i < 4; i++) {
    assert.deepEqual([...out.subarray(i * 4, i * 4 + 4)], [40, 65, 8, 255], `pixel ${i} not black`);
  }
});

test('octagonBacking: opaque class colour in the centre, transparent corners', () => {
  const S = 32, col = [200, 150, 40];
  const bg = octagonBacking(S, col);
  const ctr = ((S / 2) * S + S / 2) * 4;
  assert.equal(bg[ctr + 3], 255, 'centre opaque');
  assert.ok(Math.abs(bg[ctr] - 200) < 60 && bg[ctr] > bg[ctr + 2], 'centre ~class colour (reddish-yellow)');
  assert.equal(bg[3], 0, 'top-left corner transparent');       // (0,0) is outside the octagon
  assert.equal(bg[(S - 1) * 4 + 3], 0, 'top-right corner transparent');
});

test('compositeCentered places an opaque sprite in the middle, leaves edges as backing', () => {
  const S = 32;
  const dst = octagonBacking(S, [200, 150, 40]);
  const sprite = Buffer.alloc(8 * 8 * 4);       // solid blue 8×8
  for (let i = 0; i < 64; i++) { sprite[i * 4 + 2] = 255; sprite[i * 4 + 3] = 255; }
  compositeCentered(dst, S, sprite, 8, 8, 0.5);
  const ctr = ((S / 2) * S + S / 2) * 4;
  assert.ok(dst[ctr + 2] > 200 && dst[ctr] < 60, 'centre is the blue sprite');
  const edge = ((S / 2) * S + 2) * 4;           // far-left, still inside octagon → backing colour
  assert.ok(dst[edge] > dst[edge + 2], 'edge kept the yellow backing');
});
