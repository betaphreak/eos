package com.civstudio.simulation;

import java.util.function.IntFunction;

import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;

/**
 * Simulation (small open colony): a minimum-scale bare colony — 2 enjoyment firms,
 * 2 necessity firms, 1 capital firm and 90 laborers — run with <b>two banks</b>
 * (agents split A/B by index), <b>mortality</b> on (households age, die and are
 * succeeded), and an <b>open colony</b> that grows the population: external
 * money flows into bank A's equity each step and bankrolls a stream of immigrant
 * households. The labor-share wage rule (default {@code laborShare = 0.5}) lets
 * wages scale with the growing labor pool, so 2 consumer firms of each type keep
 * feeding a population that climbs above its starting 90.
 * <p>
 * Population dynamics here have two sources: 1:1 replacement keeps the dynasties
 * going as heads die of old age (mortality), while immigration adds net-new
 * households funded from outside — so the head count rises over the run rather
 * than holding flat. Because external money accumulates in bank A's equity
 * between immigrant admissions, bank A is <i>not</i> a zero-equity intermediary
 * here (unlike the closed default runs); its equity oscillates below the
 * immigration threshold.
 */
public class SmallOpenEconomy {

	/** Laborer households the colony is founded with (the minimum stable scale). */
	static final int NUM_LABORERS = 90;

	/** Net new money entering the colony (into bank A's equity) each step. */
	static final double EXTERNAL_INFLOW_PER_STEP = 1.0;

	/** Equity accumulated per admitted immigrant household (its opening funds). */
	static final double IMMIGRATION_THRESHOLD = 100;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks and agents
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.numEFirms(2)
				.numNFirms(2)
				.externalInflowPerStep(EXTERNAL_INFLOW_PER_STEP)
				.immigrationThreshold(IMMIGRATION_THRESHOLD)
				.build();

		SimulationHarness h = SimulationHarness.create(cfg, 7654321);
		h.createMarkets();
		Bank bankA = h.addBank(BankConfig.DEFAULT);
		Bank bankB = h.addBank(BankConfig.DEFAULT);
		IntFunction<Bank> alternate = i -> (i % 2 == 0) ? bankA : bankB;

		h.createFirms(bankA, alternate,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		h.createLaborers(NUM_LABORERS, alternate, i -> 15,
				i -> cfg.laborer().savings());
		// open the colony through bank A: external inflow + immigration grow the
		// population (a no-op only when externalInflowPerStep is 0, which it isn't)
		h.enableExternalInflow(bankA);
		h.addCommonPrinters();
		h.addBanksPrinter("Banks");
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
