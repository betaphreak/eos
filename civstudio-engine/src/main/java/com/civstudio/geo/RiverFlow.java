package com.civstudio.geo;

import java.util.Arrays;

/**
 * Derives the <b>drainage network</b> of a river raster — every cell's downstream flow
 * direction and its accumulation (see {@code docs/river-rendering.md} §3–4). It answers "which
 * way does the water leave here, and how much of the network drains through here", so the
 * renderer can taper a river from thread to trunk and gameplay (caravan river-navigation,
 * downstream effects) has real flow.
 * <p>
 * <b>The water is rooted at the sea, not guessed from the ground.</b> Every river cell touching
 * open water is a <em>mouth</em>; flow is the shortest path through the river network back to
 * one. This is derived by a multi-source breadth-first sweep out from all mouths at once, which
 * yields a spanning forest whose every edge points seaward.
 * <p>
 * <b>Why not steepest-descent or width-following</b> (both were tried — see the doc's §4
 * post-mortem): a D8 descent leans on the heightmap, which is near-flat and noisy exactly on the
 * valley floors where rivers live, so it drowns in pits; and following the mod's authored width
 * downhill assumes width grows monotonically toward the mouth, which the authored data does not
 * honour — it plateaus and wobbles, stranding a sink at every local width maximum. Measured on
 * the real map, those two produced <b>7,294–13,630 roots for 465 actual river systems</b>,
 * shattering the forest into ~9-cell fragments and making accumulation meaningless. Rooting at
 * the coast instead gives exactly one root per system that reaches the sea, and — because a
 * river network is essentially a tree — the BFS path is the <em>only</em> path, so neither pits
 * nor width noise can corrupt it. Elevation and width survive only as tie-breaks for picking a
 * fallback root on an endorheic system, where there is no sea to root at.
 * <p>
 * Pure and global: run once over the whole river raster, no per-province seams (a cell's true
 * downstream neighbour may sit in the next province, which a per-province pass could not see).
 */
public final class RiverFlow {

	// 8-neighbour offsets, in the order the returned direction code 1..8 names them:
	// 1 E, 2 NE, 3 N, 4 NW, 5 W, 6 SW, 7 S, 8 SE (y grows downward, so N is -y).
	private static final int[] DX = { 1, 1, 0, -1, -1, -1, 0, 1 };
	private static final int[] DY = { 0, -1, -1, -1, 0, 1, 1, 1 };

	/**
	 * A derived river network.
	 *
	 * @param dir per-cell downstream direction: {@code 0} on a non-river cell <em>or a mouth</em>
	 *            (the water leaves the network there), else {@code 1..8} pointing at the
	 *            neighbour the water flows to
	 * @param acc per-cell drainage accumulation: how many river cells drain through this one,
	 *            counting itself — {@code 0} off-river, {@code 1} at a headwater, growing
	 *            downstream, peaking at a mouth
	 */
	public record Network(byte[] dir, int[] acc) {
	}

	private RiverFlow() {
	}

	/**
	 * Derive the drainage network of a river raster.
	 *
	 * @param w         raster width
	 * @param h         raster height
	 * @param widthGrid per-cell authored river width ({@code 0} = no river, {@code 1..} narrow→
	 *                  wide), row-major, length {@code w*h} (read as unsigned bytes). Used only
	 *                  to pick a fallback root on a system that never reaches the sea.
	 * @param elev      per-cell elevation (0..255), row-major length {@code w*h}, or {@code null}.
	 *                  Likewise fallback-root tie-break only — never a flow signal.
	 * @param isSea     per-cell open-water flag, row-major length {@code w*h}. A river cell
	 *                  8-adjacent to one of these is a mouth.
	 * @return the network; {@code acc} over all mouths sums to the river-cell count, since every
	 *         cell drains to exactly one mouth
	 */
	public static Network derive(int w, int h, byte[] widthGrid, int[] elev, boolean[] isSea) {
		int n = w * h;
		boolean[] isRiver = new boolean[n];
		for (int i = 0; i < n; i++)
			isRiver[i] = (widthGrid[i] & 0xFF) != 0;

		byte[] dir = new byte[n];
		int[] parent = new int[n];
		Arrays.fill(parent, -1);
		boolean[] seen = new boolean[n];
		int[] queue = new int[n];
		int[] order = new int[n]; // BFS visit order — a parent always precedes its children
		int head = 0, tail = 0, cnt = 0;

		// every river cell touching open water is a mouth, and they all seed the sweep at once —
		// so a cell reached by the wavefront is reached via a shortest path to the NEAREST mouth
		for (int i = 0; i < n; i++)
			if (isRiver[i] && touchesSea(w, h, i, isSea)) {
				seen[i] = true;
				queue[tail++] = i;
				order[cnt++] = i;
			}
		int[] st = sweep(w, h, isRiver, seen, parent, queue, order, head, tail, cnt);
		head = st[0];
		tail = st[1];
		cnt = st[2];

		// endorheic systems — a river network that never reaches the sea (an inland basin, or a
		// chain the raster leaves stranded). No mouth to root at, so root at the cell most likely
		// to be its terminus: widest, then lowest, then lowest index for determinism.
		int[] stamp = new int[n];
		Arrays.fill(stamp, -1);
		int[] scratch = new int[n], stack = new int[n];
		for (int s = 0; s < n; s++) {
			if (!isRiver[s] || seen[s] || stamp[s] >= 0)
				continue;
			int size = collect(w, h, s, isRiver, stamp, scratch, stack);
			int root = scratch[0];
			for (int c = 0; c < size; c++)
				if (better(scratch[c], root, widthGrid, elev))
					root = scratch[c];
			seen[root] = true;
			queue[tail++] = root;
			order[cnt++] = root;
			st = sweep(w, h, isRiver, seen, parent, queue, order, head, tail, cnt);
			head = st[0];
			tail = st[1];
			cnt = st[2];
		}

		// flow points at the parent — the neighbour the wavefront arrived from, i.e. seaward
		for (int i = 0; i < n; i++)
			if (parent[i] >= 0)
				dir[i] = (byte) code(w, i, parent[i]);

		// accumulation = subtree size. Walking the BFS order backwards guarantees every child is
		// totalled before its parent reads it, so one pass suffices.
		int[] acc = new int[n];
		for (int i = 0; i < n; i++)
			if (isRiver[i])
				acc[i] = 1; // every river cell drains at least itself
		for (int k = cnt - 1; k >= 0; k--) {
			int i = order[k];
			if (parent[i] >= 0)
				acc[parent[i]] += acc[i];
		}
		return new Network(dir, acc);
	}

	// drain the BFS queue, recording each newly reached cell's parent; returns {head, tail, cnt}
	private static int[] sweep(int w, int h, boolean[] isRiver, boolean[] seen, int[] parent,
			int[] queue, int[] order, int head, int tail, int cnt) {
		while (head < tail) {
			int i = queue[head++];
			int x = i % w, y = i / w;
			for (int d = 0; d < 8; d++) {
				int nx = x + DX[d], ny = y + DY[d];
				if (nx < 0 || nx >= w || ny < 0 || ny >= h)
					continue;
				int j = ny * w + nx;
				if (!isRiver[j] || seen[j])
					continue;
				seen[j] = true;
				parent[j] = i; // reached from i, so j's water flows to i
				queue[tail++] = j;
				order[cnt++] = j;
			}
		}
		return new int[] { head, tail, cnt };
	}

	// flood the 8-connected river component containing `s` into `out` (stamping each cell so it is
	// only ever collected once); returns the component's size. `stack` is scratch — it must be a
	// SEPARATE array from `out`, which is being filled with the result as we go.
	private static int collect(int w, int h, int s, boolean[] isRiver, int[] stamp, int[] out, int[] stack) {
		int top = 0, size = 0;
		stack[top++] = s;
		stamp[s] = s;
		while (top > 0) {
			int i = stack[--top];
			out[size++] = i;
			int x = i % w, y = i / w;
			for (int d = 0; d < 8; d++) {
				int nx = x + DX[d], ny = y + DY[d];
				if (nx < 0 || nx >= w || ny < 0 || ny >= h)
					continue;
				int j = ny * w + nx;
				if (isRiver[j] && stamp[j] < 0) {
					stamp[j] = s;
					stack[top++] = j;
				}
			}
		}
		return size;
	}

	// is `a` a likelier terminus than `b` for an endorheic system: widest wins, then lowest
	// ground, then lowest index (so the choice never depends on iteration accidents)
	private static boolean better(int a, int b, byte[] widthGrid, int[] elev) {
		int wa = widthGrid[a] & 0xFF, wb = widthGrid[b] & 0xFF;
		if (wa != wb)
			return wa > wb;
		int ea = elev == null ? 0 : elev[a], eb = elev == null ? 0 : elev[b];
		if (ea != eb)
			return ea < eb;
		return a < b;
	}

	private static boolean touchesSea(int w, int h, int i, boolean[] isSea) {
		int x = i % w, y = i / w;
		for (int d = 0; d < 8; d++) {
			int nx = x + DX[d], ny = y + DY[d];
			if (nx < 0 || nx >= w || ny < 0 || ny >= h)
				continue;
			if (isSea[ny * w + nx])
				return true;
		}
		return false;
	}

	// the direction code 1..8 pointing from cell `from` to its 8-neighbour `to`
	private static int code(int w, int from, int to) {
		int dx = to % w - from % w, dy = to / w - from / w;
		for (int d = 0; d < 8; d++)
			if (DX[d] == dx && DY[d] == dy)
				return d + 1;
		throw new IllegalArgumentException("cells " + from + " and " + to + " are not 8-adjacent");
	}
}
