// Local (Windows) bake for the Anbennar loading-screen art: decode each vendored
// data/anbennar/loadingscreens/load_*.dds (DXT1) with the repo's dds.mjs, write it as a temporary
// 24-bit BMP, then let .NET System.Drawing (the OS's built-in JPEG codec — no reinvented encoder,
// no npm dependency) re-encode it to web/assets/loading/loading-<n>.jpg at the given quality.
//
// The .jpg outputs are committed; build.mjs just lists whatever web/assets/loading/loading-*.jpg exist into
// the bundle. Run occasionally, on this machine:  node web/bake-loading.mjs [quality]
//
// The screens are 2048x1536; they are NOT downscaled — the page shows them 1:1 and lets the viewport
// crop (see #loading CSS). This step is Windows-only by design (System.Drawing); it is separate from
// the portable, cross-platform build.mjs.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { decodeDds } from './dds.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(HERE, '../data/anbennar/loadingscreens');
const ASSETS = path.join(HERE, 'assets', 'loading');
const QUALITY = Math.max(1, Math.min(100, parseInt(process.argv[2] || '82', 10)));

// write RGBA as a 24-bit bottom-up BMP (uncompressed; System.Drawing reads it, no encoder needed)
function writeBmp(file, w, h, rgba) {
  const rowSize = (w * 3 + 3) & ~3, dataSize = rowSize * h, fileSize = 54 + dataSize;
  const buf = Buffer.alloc(fileSize);
  buf.write('BM', 0);
  buf.writeUInt32LE(fileSize, 2); buf.writeUInt32LE(54, 10);
  buf.writeUInt32LE(40, 14); buf.writeInt32LE(w, 18); buf.writeInt32LE(h, 22);
  buf.writeUInt16LE(1, 26); buf.writeUInt16LE(24, 28); buf.writeUInt32LE(dataSize, 34);
  for (let y = 0; y < h; y++) {
    const srcY = h - 1 - y; let o = 54 + y * rowSize;
    for (let x = 0; x < w; x++) {
      const s = (srcY * w + x) * 4;
      buf[o++] = rgba[s + 2]; buf[o++] = rgba[s + 1]; buf[o++] = rgba[s];   // BGR
    }
  }
  fs.writeFileSync(file, buf);
}

const dds = fs.existsSync(SRC)
  ? fs.readdirSync(SRC).filter(f => /^load_\d+\.dds$/i.test(f)).sort((a, b) => (parseInt(a.match(/\d+/)) - parseInt(b.match(/\d+/))))
  : [];
if (!dds.length) { console.log('no loading screens in', SRC, '- nothing to bake'); process.exit(0); }
fs.mkdirSync(ASSETS, { recursive: true });

const bmps = [];
dds.forEach((f, i) => {
  const img = decodeDds(fs.readFileSync(path.join(SRC, f)));
  const bmp = path.join(ASSETS, `loading-${i}.bmp`);
  writeBmp(bmp, img.width, img.height, img.rgba);
  bmps.push({ bmp, jpg: path.join(ASSETS, `loading-${i}.jpg`), src: f, w: img.width, h: img.height });
});

// hand off to .NET for the JPEG encode (built-in codec), then drop the temp BMPs
const ps = `
Add-Type -AssemblyName System.Drawing
$enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$ep = New-Object System.Drawing.Imaging.EncoderParameters 1
$ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality, [long]${QUALITY})
Get-ChildItem "${ASSETS.replace(/\\/g, '\\\\')}\\loading-*.bmp" | ForEach-Object {
  $img = [System.Drawing.Image]::FromFile($_.FullName)
  $out = [System.IO.Path]::ChangeExtension($_.FullName, '.jpg')
  $img.Save($out, $enc, $ep)
  $img.Dispose()
  Remove-Item $_.FullName
}
`;
execFileSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', ps], { stdio: 'inherit' });

let total = 0;
for (const b of bmps) {
  const kb = fs.statSync(b.jpg).size / 1024; total += kb;
  console.log(`  ${b.src} (${b.w}x${b.h}) -> assets/loading/${path.basename(b.jpg)}  ${kb.toFixed(0)} KB`);
}
console.log(`baked ${bmps.length} loading screens at q${QUALITY}, ${(total / 1024).toFixed(1)} MB total`);
