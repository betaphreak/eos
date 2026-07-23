package com.civstudio.simulation;

/**
 * Dev probe: the <b>smallest founding band that still survives</b> a full run under the
 * default (build-economy) configuration — a bisection over {@code retinueSize}, each
 * step one full headless {@code CampFoundingEconomy}-shaped run (no printers, seconds
 * each), survival = the colony alive at the end. The boundary is then sanity-checked
 * across extra seeds (mortality draws matter at small N). Run from the project root:
 *
 * <pre>
 * mvn -q -pl civstudio-engine compile exec:exec -Dsim.main=com.civstudio.simulation.SmallestBandProbe
 * </pre>
 */
public final class SmallestBandProbe {

	private static final int DHENIJANSAR = 4411;
	private static final long[] SEEDS = { 7654321, 1234567, 42424242 };

	private SmallestBandProbe() {
	}

	private static boolean survives(int band, long seed) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder().foundAtCamp(true).build();
		SimulationHarness h = SimulationHarness.create(cfg, seed, DHENIJANSAR);
		h.tuneEconomy(e -> e.toBuilder().retinueSize(band).build());
		h.foundStandardColony();
		h.run();
		return h.getColony().isAlive();
	}

	public static void main(String[] args) {
		long seed = SEEDS[0];
		int lo = 5, hi = 60; // 60 is the known-surviving HammerEconomy band
		if (!survives(hi, seed))
			throw new IllegalStateException("band 60 no longer survives — recalibrate first");
		// bisect the smallest surviving band on the primary seed
		while (hi - lo > 1) {
			int mid = (lo + hi) / 2;
			boolean ok = survives(mid, seed);
			System.out.println("band " + mid + " seed " + seed + " -> "
					+ (ok ? "SURVIVES" : "dies"));
			if (ok)
				hi = mid;
			else
				lo = mid;
		}
		System.out.println("smallest surviving band on seed " + seed + ": " + hi);
		// the boundary across the other seeds (threshold is seed-sensitive at small N)
		for (int i = 1; i < SEEDS.length; i++)
			for (int band : new int[] { hi - 1, hi, hi + 2 })
				if (band > 0)
					System.out.println("  seed " + SEEDS[i] + " band " + band + " -> "
							+ (survives(band, SEEDS[i]) ? "SURVIVES" : "dies"));
	}
}
