package eos.simulation;

import java.util.ArrayList;
import java.util.List;

import eos.bank.Bank;
import eos.bank.BankConfig;
import eos.settlement.Settlement;
import eos.settlement.GameSession;
import eos.io.SimLog;

/**
 * Simulation (scale sweep): explores <b>how small the colony can be and still
 * be stable</b>. It does not model a single fixed population like
 * {@link HomogeneousEconomy}; instead it scales the consumer-firm and laborer counts
 * <i>down together</i> at the default firms:laborers ratio and reports, for each
 * scale, whether the run stayed healthy.
 * <p>
 * The default run has 10 enjoyment firms, 10 necessity firms (+1 capital firm)
 * and 450 laborers — a ratio of {@value #LABORERS_PER_FIRM} laborers per
 * consumer firm of each type. A "scale" {@code k} here means {@code k} enjoyment
 * firms, {@code k} necessity firms (plus the single capital firm) and
 * {@code k * }{@value #LABORERS_PER_FIRM} laborers, so the composition matches
 * the calibrated default at every size. The sweep walks {@code k} from the
 * default down to 1, runs each as a homogeneous single-bank colony (like
 * {@link HomogeneousEconomy}), and scores stability.
 * <p>
 * A run is judged <b>stable</b> when, after the full horizon, (1) its laborer
 * population is sustained (at least {@value #MIN_SURVIVAL_PCT}% of the initial
 * count — in a closed run replacement keeps this pinned, so a shortfall means
 * the run blew up), (2) both consumer-good prices stay finite, positive and
 * below {@value #PRICE_RUNAWAY_FACTOR}x their initial ceiling (the real
 * discriminator: a too-small colony drives persistent excess demand and the
 * price compounds toward infinity — and a runaway stays a finite double for a
 * long time, so the bound, not mere finiteness, is what catches it), and (3) the
 * bank's pools and rates stay finite. A run that throws (e.g. an {@code -ea}
 * invariant tripping) is unstable.
 * <p>
 * Unlike the other simulations this entry point's deliverable is the printed
 * sweep table on stdout, not CSV files, so the per-scale runs register no
 * printers (which also avoids many runs colliding on the same output filenames).
 * {@link #main} runs the sweep and prints the minimum stable scale;
 * {@link #run()} is the convention hook returning one default-scale harness.
 */
public class ScaleSweep {

	/** Seed shared by every scale, so the runs are comparable and reproducible. */
	static final long SEED = 7654321L;

	/** Number of enjoyment firms (== necessity firms) at the default scale. */
	static final int DEFAULT_FIRMS = SimulationConfig.DEFAULT.numEFirms();

	/** Number of laborers at the default scale. */
	static final int DEFAULT_LABORERS = SimulationConfig.DEFAULT.numLaborers();

	/**
	 * Laborers per consumer firm of each type, fixed across the sweep (default
	 * 450 / 10 = 45). Scale {@code k} uses {@code k * LABORERS_PER_FIRM} laborers.
	 */
	static final int LABORERS_PER_FIRM = DEFAULT_LABORERS / DEFAULT_FIRMS;

	/** A run keeping at least this fraction of its initial laborers is alive. */
	static final double MIN_SURVIVAL = 0.85;

	private static final int MIN_SURVIVAL_PCT = (int) (MIN_SURVIVAL * 100);

	/**
	 * A consumer-good price above this multiple of its market's initial price
	 * ceiling is treated as a runaway (the colony is collapsing into
	 * hyperinflation even though the price is still a finite, positive double).
	 * Healthy runs sit a few times the initial ceiling; a broken one compounds to
	 * many orders of magnitude beyond it — so any factor between the two
	 * separates them, and 1000x leaves wide margin against a nominally-inflating
	 * but healthy run.
	 */
	static final double PRICE_RUNAWAY_FACTOR = 1000;

	/** One scale's outcome: its composition, whether it was stable, and why. */
	record ScaleResult(int firmsPerType, int numLaborers, boolean stable,
			String detail, long aliveLaborers, double ePrice, double nPrice) {
	}

	/**
	 * Build and run a single homogeneous, single-bank colony at the given scale
	 * (mirrors {@link HomogeneousEconomy#run()} but with the firm/laborer counts varied
	 * and no printers registered). Returns the finished harness so the caller can
	 * inspect the final state.
	 *
	 * @param firmsPerType
	 *            number of enjoyment firms and of necessity firms
	 * @param numLaborers
	 *            number of laborer households
	 * @return the finished harness
	 */
	static SimulationHarness runScale(int firmsPerType, int numLaborers) {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.numEFirms(firmsPerType)
				.numNFirms(firmsPerType)
				.numLaborers(numLaborers)
				.build();
		GameSession session = new GameSession(SEED);
		Settlement colony = session.newSettlement(cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock());
		SimLog.init(colony);

		SimulationHarness h = new SimulationHarness(cfg, colony);
		h.createMarkets();
		Bank bank = h.addBank(BankConfig.DEFAULT);
		h.createFirms(bank, i -> bank,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createLaborers(i -> bank, i -> 15, i -> cfg.laborer().savings());
		h.enableExternalInflow(bank);
		// no printers: the sweep evaluates the final state in memory and reports a
		// table on stdout (and many scales would otherwise collide on filenames)
		h.run();
		return h;
	}

	/**
	 * Judge a finished run. Returns {@code null} if the colony is stable, else a
	 * short human-readable reason it is not.
	 *
	 * @param h
	 *            a finished harness
	 * @return {@code null} if stable, otherwise why it is not
	 */
	static String diagnose(SimulationHarness h) {
		long alive = h.currentLaborerCount();
		int n0 = h.getCfg().numLaborers();
		if (alive < MIN_SURVIVAL * n0)
			return "population collapsed (" + alive + "/" + n0 + " alive)";

		double ep = h.getEnjoymentMkt().getLastMktPrice();
		double np = h.getNecessityMkt().getLastMktPrice();
		double eCeil = PRICE_RUNAWAY_FACTOR * h.getCfg().ePrice().max();
		double nCeil = PRICE_RUNAWAY_FACTOR * h.getCfg().nPrice().max();
		if (!(Double.isFinite(ep) && ep > 0))
			return "enjoyment price not finite/positive (" + ep + ")";
		if (!(Double.isFinite(np) && np > 0))
			return "necessity price not finite/positive (" + np + ")";
		if (ep > eCeil)
			return String.format("enjoyment price runaway (%.3g > %.0f)", ep,
					eCeil);
		if (np > nCeil)
			return String.format("necessity price runaway (%.3g > %.0f)", np,
					nCeil);

		for (Bank bank : h.getBanks()) {
			if (!(Double.isFinite(bank.getTotalDeposit())
					&& bank.getTotalDeposit() > 0))
				return "bank deposit not finite/positive";
			if (!Double.isFinite(bank.getLoanIR())
					|| !Double.isFinite(bank.getDepositIR()))
				return "bank rate not finite";
		}
		return null;
	}

	/**
	 * Sweep the scale from the default down to 1, running each and scoring
	 * stability, and print a table of the outcomes. Each scale {@code k} uses
	 * {@code k} enjoyment firms, {@code k} necessity firms (+1 capital firm) and
	 * {@code k * }{@value #LABORERS_PER_FIRM} laborers.
	 *
	 * @return the per-scale results, ordered from the largest scale to the
	 *         smallest
	 */
	public static List<ScaleResult> sweep() {
		System.out.printf(
				"Scale sweep (k enjoyment + k necessity firms + 1 capital firm, "
						+ "%d laborers per firm-type):%n%n",
				LABORERS_PER_FIRM);
		System.out.printf("%-3s %-9s %-9s %-7s %-9s %-9s %s%n",
				"k", "firms", "laborers", "stable", "ePrice", "nPrice",
				"detail");

		List<ScaleResult> results = new ArrayList<>();
		for (int k = DEFAULT_FIRMS; k >= 1; k--) {
			int labs = k * LABORERS_PER_FIRM;
			ScaleResult r;
			try {
				SimulationHarness h = runScale(k, labs);
				String why = diagnose(h);
				r = new ScaleResult(k, labs, why == null,
						why == null ? "ok" : why, h.currentLaborerCount(),
						h.getEnjoymentMkt().getLastMktPrice(),
						h.getNecessityMkt().getLastMktPrice());
			} catch (Throwable t) {
				// a thrown error (e.g. an -ea invariant tripping in a broken
				// small colony) counts as unstable, not a crash of the sweep
				r = new ScaleResult(k, labs, false,
						"threw " + t.getClass().getSimpleName() + ": "
								+ t.getMessage(),
						0, Double.NaN, Double.NaN);
			}
			results.add(r);
			System.out.printf("%-3d %-9d %-9d %-7s %-10.4g %-10.4g %s%n",
					r.firmsPerType(), 2 * r.firmsPerType() + 1, r.numLaborers(),
					r.stable() ? "yes" : "NO", r.ePrice(), r.nPrice(),
					r.detail());
		}
		return results;
	}

	/**
	 * The smallest stable scale among {@code results}, or {@code null} if none
	 * were stable. Stability need not be monotonic in the scale, so this scans
	 * all stable outcomes rather than assuming the last one.
	 *
	 * @param results
	 *            the sweep results
	 * @return the stable result with the fewest firms, or {@code null}
	 */
	static ScaleResult minimumStable(List<ScaleResult> results) {
		ScaleResult min = null;
		for (ScaleResult r : results)
			if (r.stable() && (min == null || r.firmsPerType() < min.firmsPerType()))
				min = r;
		return min;
	}

	/**
	 * Convention hook: build and run a single default-scale colony and return
	 * its harness. The sweep itself runs from {@link #main}.
	 *
	 * @return the finished default-scale harness
	 */
	public static SimulationHarness run() {
		return runScale(DEFAULT_FIRMS, DEFAULT_LABORERS);
	}

	public static void main(String[] args) {
		List<ScaleResult> results = sweep();
		ScaleResult min = minimumStable(results);
		System.out.println();
		if (min == null) {
			System.out.println(
					"No scale in the sweep was stable (even the default).");
		} else {
			System.out.printf(
					"Minimum stable scale: k=%d -> %d enjoyment + %d necessity "
							+ "+ 1 capital firm, %d laborers.%n",
					min.firmsPerType(), min.firmsPerType(), min.firmsPerType(),
					min.numLaborers());
		}
	}
}
