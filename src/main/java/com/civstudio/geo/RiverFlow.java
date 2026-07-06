package com.civstudio.geo;

/**
 * Derives the <b>downstream flow direction</b> of a river raster — the Phase 2 data
 * product of the river pipeline (see {@code docs/river-rendering.md} §3). It answers, for
 * every river cell, "which way does the water leave here?", so gameplay (caravan
 * river-navigation, downstream/upstream effects) and a future faithful edge-tile renderer
 * have real flow, not just presence.
 * <p>
 * <b>Signal priority (deliberate):</b> the mod's <b>authored width</b> is primary — a river
 * grows wider toward its mouth, so a cell flows to its <em>widest</em> river neighbour;
 * <b>elevation</b> (from the heightmap) only breaks ties between equal-width neighbours, so
 * the noisy, near-flat valley-floor heightmap — where a pure D8 steepest-descent misbehaves
 * — never drives the direction, it only nudges it. A deterministic cell-index tie-break
 * settles the rest, which also guarantees the directed graph is <b>acyclic</b> (every edge
 * points to a strictly greater cell in the {@code (score, index)} total order), i.e. a flow
 * forest rooted at the local width maxima (mouths).
 * <p>
 * Pure and global: run once over the whole river raster, no per-province seams (a cell's
 * true downstream neighbour may sit in the next province, which a per-province pass could
 * not see). No pit-filling / flat-resolution is needed precisely because width, not
 * elevation, leads.
 */
public final class RiverFlow {

	// 8-neighbour offsets, in the order the returned direction code 1..8 names them:
	// 1 E, 2 NE, 3 N, 4 NW, 5 W, 6 SW, 7 S, 8 SE (y grows downward, so N is -y).
	private static final int[] DX = { 1, 1, 0, -1, -1, -1, 0, 1 };
	private static final int[] DY = { 0, -1, -1, -1, 0, 1, 1, 1 };

	private RiverFlow() {
	}

	/**
	 * The downstream direction of every cell of a river raster.
	 *
	 * @param w          raster width
	 * @param h          raster height
	 * @param widthGrid  per-cell river width ({@code 0} = no river, {@code 1..} narrow→wide),
	 *                   row-major, length {@code w*h} (read as unsigned bytes)
	 * @param elev       per-cell elevation (0..255), row-major length {@code w*h}, or
	 *                   {@code null} to treat all ground as flat (width + index decide)
	 * @return a row-major {@code byte[]} of direction codes: {@code 0} for a non-river cell
	 *         or a local sink/mouth (no strictly-downstream neighbour), else {@code 1..8}
	 */
	public static byte[] direction(int w, int h, byte[] widthGrid, int[] elev) {
		byte[] dir = new byte[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				int wc = widthGrid[i] & 0xFF;
				if (wc == 0)
					continue; // not a river cell
				long best = score(wc, elev == null ? 0 : elev[i]);
				int bestIdx = i, bestDir = 0; // compared against the cell itself → sink if nothing beats it
				for (int d = 0; d < 8; d++) {
					int nx = x + DX[d], ny = y + DY[d];
					if (nx < 0 || nx >= w || ny < 0 || ny >= h)
						continue;
					int j = ny * w + nx;
					int wn = widthGrid[j] & 0xFF;
					if (wn == 0)
						continue; // neighbour is not a river
					long sn = score(wn, elev == null ? 0 : elev[j]);
					// downstream = strictly greater in the (score, index) total order; the
					// index tie-break makes the whole flow graph acyclic
					if (sn > best || (sn == best && j > bestIdx)) {
						best = sn;
						bestIdx = j;
						bestDir = d + 1;
					}
				}
				dir[i] = (byte) bestDir;
			}
		}
		return dir;
	}

	// a cell's "downstream-ness": authored width dominates (×100000 ≫ any elevation), and a
	// lower elevation reads as further downstream — so equal-width neighbours are settled by
	// going downhill, but width alone decides wherever the widths differ.
	private static long score(int width, int elev) {
		return width * 100000L - elev;
	}
}
