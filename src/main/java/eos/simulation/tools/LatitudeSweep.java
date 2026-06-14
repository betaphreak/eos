package eos.simulation.tools;

import java.util.ArrayList;
import java.util.List;

import eos.bank.Bank;
import eos.simulation.HomogeneousEconomy;
import eos.simulation.SimulationConfig;
import eos.simulation.SimulationHarness;

/**
 * Simulation (latitude sweep): finds the <b>highest latitude at which a colony
 * can still feed itself</b>. Laborer output scales with the length of the day
 * (see {@link eos.market.LaborMarket}), so the further a colony sits from the
 * equator the deeper its winter labor shortfall — at some latitude the shortfall
 * starves the necessity sector and the colony collapses. This sweep places the
 * standard closed colony of {@link HomogeneousEconomy} at a range of latitudes,
 * runs each to the full horizon, and reports where survival gives out.
 * <p>
 * Everything but the latitude is the calibrated default: 10 enjoyment firms, 10
 * necessity firms (+1 capital firm), 450 laborers, one zero-profit bank. Only the
 * colony's {@code latitude} varies (longitude is irrelevant to day <i>length</i>,
 * so it is held at 0). A run is judged to have <b>survived</b> by the same
 * standard the {@link ScaleSweep} uses ({@link ScaleSweep#diagnose}): the laborer
 * population is sustained, both consumer prices stay finite, positive and below a
 * runaway ceiling, and the bank stays finite — anything else (a depopulated
 * colony, a price spiralling to infinity, a thrown {@code -ea} invariant) is a
 * colony that starved.
 * <p>
 * Like {@link ScaleSweep}, this entry point's deliverable is the printed table on
 * stdout, not CSV files, so the per-latitude runs register no printers. {@link
 * #main} runs the sweep, bisects the survivable/starving boundary and prints the
 * maximum survivable latitude; {@link #run()} is the convention hook returning one
 * default-latitude harness.
 */
public class LatitudeSweep {

	/** Seed shared by every latitude, so the runs are comparable and reproducible. */
	static final long SEED = 7654321L;

	/** Lowest latitude swept (the equator). */
	static final double MIN_LATITUDE = 0;

	/** Highest latitude swept (just past the Arctic Circle at ~66.5°). */
	static final double MAX_LATITUDE = 70;

	/** Latitude step of the coarse scan, in degrees. */
	static final double STEP = 5;

	/** Bisection stops once the survivable/starving boundary is this tight (degrees). */
	static final double BISECTION_PRECISION = 0.25;

	/** One latitude's outcome: whether the colony survived, and its final state. */
	record LatResult(double latitude, boolean survives, String detail,
			long aliveLaborers, double ePrice, double nPrice) {
	}

	/**
	 * Build and run a single standard closed colony placed at <tt>latitude</tt>
	 * (mirrors {@link HomogeneousEconomy#run()} but with the colony's latitude set
	 * and no printers registered).
	 *
	 * @param latitude
	 *            the colony's latitude in decimal degrees
	 * @return the finished harness
	 */
	static SimulationHarness runAtLatitude(double latitude) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.latitude(latitude)
				.build();
		SimulationHarness h = SimulationHarness.create(cfg, SEED);
		h.createMarkets();
		Bank bank = h.getCopperBank();
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createLaborers(ScaleSweep.DEFAULT_LABORERS, i -> bank, i -> 15,
				i -> cfg.laborer().savings());
		h.enableExternalInflow(bank);
		// no printers: the sweep evaluates the final state in memory and reports a
		// table on stdout (and many latitudes would otherwise collide on filenames)
		h.run();
		return h;
	}

	/**
	 * Whether the colony at <tt>latitude</tt> survives to the horizon. A run that
	 * throws (e.g. an {@code -ea} invariant tripping as a starving colony breaks
	 * down) counts as not surviving, not as a crash of the sweep.
	 *
	 * @param latitude
	 *            the colony's latitude in decimal degrees
	 * @return true if the colony survived
	 */
	static boolean survivesAt(double latitude) {
		try {
			return ScaleSweep.diagnose(runAtLatitude(latitude)) == null;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Sweep the latitude from the equator up, running each colony and scoring
	 * survival, and print a table of the outcomes.
	 *
	 * @return the per-latitude results, ordered from the equator upward
	 */
	public static List<LatResult> sweep() {
		System.out.printf(
				"Latitude sweep (standard %d-laborer closed colony, daylight-scaled "
						+ "labor):%n%n",
				ScaleSweep.DEFAULT_LABORERS);
		System.out.printf("%-8s %-9s %-10s %-10s %-10s %s%n",
				"lat", "survives", "laborers", "ePrice", "nPrice", "detail");

		List<LatResult> results = new ArrayList<>();
		for (double lat = MIN_LATITUDE; lat <= MAX_LATITUDE; lat += STEP) {
			LatResult r;
			try {
				SimulationHarness h = runAtLatitude(lat);
				String why = ScaleSweep.diagnose(h);
				r = new LatResult(lat, why == null, why == null ? "ok" : why,
						h.currentLaborerCount(),
						h.getEnjoymentMkt().getLastMktPrice(),
						h.getNecessityMkt().getLastMktPrice());
			} catch (Throwable t) {
				r = new LatResult(lat, false,
						"threw " + t.getClass().getSimpleName() + ": "
								+ t.getMessage(),
						0, Double.NaN, Double.NaN);
			}
			results.add(r);
			System.out.printf("%-8.2f %-9s %-10d %-10.4g %-10.4g %s%n",
					r.latitude(), r.survives() ? "yes" : "NO",
					r.aliveLaborers(), r.ePrice(), r.nPrice(), r.detail());
		}
		return results;
	}

	/**
	 * The maximum latitude at which the colony still survives, found by bisecting
	 * the boundary between the survivable band rising from the equator and the
	 * first latitude that starves. Returns {@link Double#NaN} if even the equator
	 * starves, or {@link #MAX_LATITUDE} if no swept latitude starved.
	 *
	 * @param results
	 *            the coarse sweep results, ordered from the equator upward
	 * @return the maximum survivable latitude (degrees), or NaN if none survive
	 */
	static double maxSurvivableLatitude(List<LatResult> results) {
		// walk up from the equator to the first starving latitude: the boundary lies
		// between the last surviving latitude and that one
		double lastSurvived = Double.NaN, firstStarved = Double.NaN;
		for (LatResult r : results) {
			if (r.survives()) {
				lastSurvived = r.latitude();
			} else {
				firstStarved = r.latitude();
				break;
			}
		}
		if (Double.isNaN(lastSurvived))
			return Double.NaN; // even the equator starved
		if (Double.isNaN(firstStarved))
			return MAX_LATITUDE; // nothing starved within the swept range

		// bisect the [survives, starves] bracket down to the target precision
		double lo = lastSurvived, hi = firstStarved;
		while (hi - lo > BISECTION_PRECISION) {
			double mid = (lo + hi) / 2;
			if (survivesAt(mid))
				lo = mid;
			else
				hi = mid;
		}
		return lo;
	}

	/**
	 * Convention hook: build and run a single default-latitude colony and return
	 * its harness. The sweep itself runs from {@link #main}.
	 *
	 * @return the finished default-latitude harness
	 */
	public static SimulationHarness run() {
		return runAtLatitude(SimulationConfig.DEFAULT.latitude());
	}

	public static void main(String[] args) {
		List<LatResult> results = sweep();
		double maxLat = maxSurvivableLatitude(results);
		System.out.println();
		if (Double.isNaN(maxLat)) {
			System.out.println(
					"Even a colony on the equator starved — no latitude survives.");
		} else if (maxLat >= MAX_LATITUDE) {
			System.out.printf(
					"No latitude up to %.0f° starved; the survivable limit is beyond "
							+ "the swept range.%n",
					MAX_LATITUDE);
		} else {
			System.out.printf(
					"Maximum latitude at which the settlement does not starve: "
							+ "~%.2f°.%n",
					maxLat);
		}
	}
}
