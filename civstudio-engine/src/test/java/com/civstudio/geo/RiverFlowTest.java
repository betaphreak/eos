package com.civstudio.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RiverFlow} — the river flow-direction derivation (Phase 2 of the
 * river pipeline, docs/river-rendering.md §3). Direction codes: 1 E, 2 NE, 3 N, 4 NW,
 * 5 W, 6 SW, 7 S, 8 SE (y grows downward); 0 = no river or a sink/mouth.
 */
class RiverFlowTest {

	@Test
	void widthLeads_flowsTowardTheWiderNeighbour() {
		// a row narrowing→widening left→right: each cell flows E to the wider one; the
		// widest (mouth) is a sink
		byte[] widths = { 1, 2, 3, 4 };
		byte[] dir = RiverFlow.direction(4, 1, widths, null);
		assertEquals(1, dir[0]); // E
		assertEquals(1, dir[1]);
		assertEquals(1, dir[2]);
		assertEquals(0, dir[3]); // widest → sink
	}

	@Test
	void equalWidth_elevationBreaksTheTie_downhill() {
		// uniform authored width, elevation falling left→right: flow follows the ground down
		byte[] widths = { 2, 2, 2, 2 };
		int[] elev = { 3, 2, 1, 0 };
		byte[] dir = RiverFlow.direction(4, 1, widths, elev);
		assertEquals(1, dir[0]); // E, downhill
		assertEquals(1, dir[1]);
		assertEquals(1, dir[2]);
		assertEquals(0, dir[3]); // lowest → sink
	}

	@Test
	void widthDominatesElevation_evenUphill() {
		// the downstream cell is wider but HIGHER: width must still win (river flows "up" the
		// noisy heightmap toward the authored-wide channel, which is the whole point)
		byte[] widths = { 1, 3 };
		int[] elev = { 0, 255 }; // the wide cell is the highest ground
		byte[] dir = RiverFlow.direction(2, 1, widths, elev);
		assertEquals(1, dir[0]); // still flows E toward the wider cell
		assertEquals(0, dir[1]);
	}

	@Test
	void diagonalNeighbourIsFound() {
		// grid (w=2,h=2): a width-1 cell at (0,1) with its only river neighbour, a width-3
		// cell, diagonally NE at (1,0) → flows NE (code 2)
		byte[] widths = { 0, 3, 1, 0 }; // (0,0)=0 (1,0)=3 (0,1)=1 (1,1)=0
		byte[] dir = RiverFlow.direction(2, 2, widths, null);
		assertEquals(2, dir[2]); // (0,1) → NE
		assertEquals(0, dir[1]); // the width-3 cell is the sink
	}

	@Test
	void allEqual_isAcyclic_viaIndexTieBreak() {
		// a fully flat, equal-width run must still be a forest (no 2-cycle): the deterministic
		// index tie-break sends flow toward the higher index, leaving exactly one sink
		byte[] widths = { 1, 1, 1, 1 };
		byte[] dir = RiverFlow.direction(4, 1, widths, null);
		int sinks = 0;
		for (byte d : dir)
			if (d == 0)
				sinks++;
		assertEquals(1, sinks, "exactly one sink in a single connected run");
		assertEquals(0, dir[3], "flow runs toward the higher index, so the last cell sinks");

		// and following the flow from any cell must terminate (acyclic) at a sink
		for (int start = 0; start < 4; start++) {
			int c = start, steps = 0;
			while (dir[c] != 0 && steps++ < 8)
				c += (dir[c] == 1) ? 1 : -1; // this run only ever flows E (1) here
			assertTrue(dir[c] == 0, "flow from " + start + " terminates at a sink");
		}
	}

	@Test
	void nonRiverCellsHaveNoDirection() {
		byte[] widths = { 0, 0, 2, 0 };
		byte[] dir = RiverFlow.direction(4, 1, widths, null);
		assertEquals(0, dir[0]);
		assertEquals(0, dir[1]);
		assertEquals(0, dir[2]); // lone river cell → sink
		assertEquals(0, dir[3]);
	}
}
