package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RiverFlow} — the mouth-rooted drainage derivation (docs/river-rendering.md
 * §3–4). Direction codes: 1 E, 2 NE, 3 N, 4 NW, 5 W, 6 SW, 7 S, 8 SE (y grows downward); 0 = no
 * river, or a mouth (the water leaves the network there).
 */
class RiverFlowTest {

	private static boolean[] mask(int len, int... cells) {
		boolean[] m = new boolean[len];
		for (int c : cells)
			m[c] = true;
		return m;
	}

	@Test
	void flowsToTheSea_notTowardTheWiderCell() {
		// a row [sea][1][4][1] — the WIDEST cell sits in the middle. The old width-following
		// derivation made it a sink (a local width maximum); rooting at the sea makes the whole
		// run flow W toward the mouth regardless of how the authored width wobbles.
		byte[] widths = { 0, 1, 4, 1 };
		boolean[] isSea = mask(4, 0);
		RiverFlow.Network net = RiverFlow.derive(4, 1, widths, null, isSea);
		assertEquals(0, net.dir()[1], "the cell touching the sea is the mouth");
		assertEquals(5, net.dir()[2], "W, toward the mouth — not stranded at a width maximum");
		assertEquals(5, net.dir()[3], "W, toward the mouth");
	}

	@Test
	void accumulationGrowsSeaward_headwaterIsOne() {
		// [sea][r][r][r] — accumulation counts what drains through each cell, so it grows toward
		// the mouth and the mouth carries the whole run
		byte[] widths = { 0, 1, 1, 1 };
		RiverFlow.Network net = RiverFlow.derive(4, 1, widths, null, mask(4, 0));
		assertEquals(3, net.acc()[1], "the mouth drains the whole run");
		assertEquals(2, net.acc()[2]);
		assertEquals(1, net.acc()[3], "the headwater drains only itself");
	}

	@Test
	void confluence_sumsEveryBranch() {
		// A Y: three headwaters meeting at an inland junction, draining out through one mouth.
		// NB the sea is a full column — with 8-adjacency a single sea pixel would make BOTH of its
		// diagonal river neighbours mouths, which is correct but not what this test is probing.
		//   w=5,h=3    x: 0    1    2    3    4
		//     row 0    sea   .    .    r    .        indices  0  1  2  3  4
		//     row 1    sea   r    r    r    .                 5  6  7  8  9
		//     row 2    sea   .    .    r    .                10 11 12 13 14
		byte[] widths = new byte[15];
		for (int c : new int[] { 3, 6, 7, 8, 13 })
			widths[c] = 1;
		RiverFlow.Network net = RiverFlow.derive(5, 3, widths, null, mask(15, 0, 5, 10));
		assertEquals(0, net.dir()[6], "only cell 6 touches the sea — the system's one mouth");
		assertEquals(5, net.acc()[6], "the mouth drains every cell of the system");
		assertEquals(4, net.acc()[7], "the junction carries all three branches plus itself");
		for (int c : new int[] { 3, 8, 13 })
			assertEquals(1, net.acc()[c], "cell " + c + " is a headwater");
	}

	@Test
	void mouthsAccumulationSumsToTheRiverCellCount() {
		// THE invariant: every river cell drains to exactly one mouth, so the mouths' accumulations
		// partition the network. This is what caught the old derivation shattering the forest.
		byte[] widths = { 0, 1, 2, 1, 0, 1, 0, 3, 1, 1, 1, 0 }; // w=4,h=3, an irregular network
		boolean[] isSea = mask(12, 0, 4);
		RiverFlow.Network net = RiverFlow.derive(4, 3, widths, null, isSea);

		int cells = 0, drained = 0;
		for (int i = 0; i < widths.length; i++) {
			if (widths[i] == 0) {
				assertEquals(0, net.acc()[i], "a non-river cell accumulates nothing");
				continue;
			}
			cells++;
			assertTrue(net.acc()[i] >= 1, "every river cell drains at least itself");
			if (net.dir()[i] == 0)
				drained += net.acc()[i];
		}
		assertEquals(cells, drained, "the mouths' accumulations sum to every river cell");
	}

	@Test
	void everyCellReachesAMouth_soTheForestIsAcyclic() {
		byte[] widths = { 0, 1, 2, 1, 0, 1, 0, 3, 1, 1, 1, 0 };
		RiverFlow.Network net = RiverFlow.derive(4, 3, widths, null, mask(12, 0, 4));
		int[] dx = { 1, 1, 0, -1, -1, -1, 0, 1 }, dy = { 0, -1, -1, -1, 0, 1, 1, 1 };
		for (int start = 0; start < widths.length; start++) {
			if (widths[start] == 0)
				continue;
			int c = start, steps = 0;
			while (net.dir()[c] != 0) {
				int d = net.dir()[c] - 1;
				c = (c / 4 + dy[d]) * 4 + (c % 4 + dx[d]);
				assertTrue(steps++ < widths.length, "flow from " + start + " must terminate, not loop");
			}
			assertEquals(0, net.dir()[c], "flow from " + start + " ends at a mouth");
		}
	}

	@Test
	void accumulationIsMonotonicDownstream() {
		// water never shrinks going downstream: a cell's downstream neighbour carries it plus itself
		byte[] widths = { 0, 1, 2, 1, 0, 1, 0, 3, 1, 1, 1, 0 };
		RiverFlow.Network net = RiverFlow.derive(4, 3, widths, null, mask(12, 0, 4));
		int[] dx = { 1, 1, 0, -1, -1, -1, 0, 1 }, dy = { 0, -1, -1, -1, 0, 1, 1, 1 };
		for (int i = 0; i < widths.length; i++) {
			if (widths[i] == 0 || net.dir()[i] == 0)
				continue;
			int d = net.dir()[i] - 1;
			int j = (i / 4 + dy[d]) * 4 + (i % 4 + dx[d]);
			assertTrue(net.acc()[j] > net.acc()[i],
					"cell " + i + " (" + net.acc()[i] + ") → " + j + " (" + net.acc()[j] + ")");
		}
	}

	@Test
	void endorheicSystem_rootsAtTheWidestCell_whenThereIsNoSea() {
		// a landlocked run [1][1][4] with no sea anywhere: there is no mouth to root at, so the
		// fallback roots at the widest cell and the rest drains into it
		byte[] widths = { 1, 1, 4 };
		RiverFlow.Network net = RiverFlow.derive(3, 1, widths, null, new boolean[3]);
		assertEquals(0, net.dir()[2], "the widest cell is the terminus");
		assertEquals(3, net.acc()[2], "and it drains the whole basin");
		assertEquals(1, net.acc()[0], "the far headwater drains only itself");
	}

	@Test
	void endorheicTieBreak_prefersLowerGround() {
		// equal authored width and no sea → the lowest ground is the basin's terminus
		byte[] widths = { 1, 1, 1 };
		int[] elev = { 80, 40, 90 };
		RiverFlow.Network net = RiverFlow.derive(3, 1, widths, elev, new boolean[3]);
		assertEquals(0, net.dir()[1], "the lowest cell is the terminus");
		assertEquals(3, net.acc()[1]);
	}

	@Test
	void nonRiverCellsHaveNoFlowOrAccumulation() {
		byte[] widths = { 0, 0, 2, 0 };
		RiverFlow.Network net = RiverFlow.derive(4, 1, widths, null, new boolean[4]);
		assertEquals(0, net.dir()[0]);
		assertEquals(0, net.acc()[0]);
		assertEquals(0, net.dir()[2], "a lone landlocked river cell is its own terminus");
		assertEquals(1, net.acc()[2]);
	}

	@Test
	void separateSystemsEachRootAtTheirOwnMouth() {
		// two independent rivers, one per row, each touching its own sea cell — neither may leak
		// accumulation into the other
		// w=4,h=2:  sea r r .      indices 0..3
		//           sea r r .              4..7
		byte[] widths = { 0, 1, 1, 0, 0, 1, 1, 0 };
		RiverFlow.Network net = RiverFlow.derive(4, 2, widths, null, mask(8, 0, 4));
		assertEquals(0, net.dir()[1], "row 0's mouth");
		assertEquals(0, net.dir()[5], "row 1's mouth");
		// the two rows are 8-adjacent, so they form one component with two mouths; every cell
		// still drains to exactly one of them and the totals partition the 4 river cells
		assertEquals(4, net.acc()[1] + net.acc()[5], "the two mouths partition the network");
	}
}
