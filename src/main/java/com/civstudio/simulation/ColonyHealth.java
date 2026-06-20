package com.civstudio.simulation;

import com.civstudio.bank.Bank;
import com.civstudio.market.ConsumerGoodMarket;

/**
 * Shared definition of a <b>healthy finished colony</b>, factored out so the
 * same stability invariants are not maintained twice. The core checks live here;
 * each caller layers its run-specific extras on top (e.g. a price-runaway ceiling,
 * or zero-profit bank equity for the closed default runs). Used by the test
 * smoke-check helper ({@code SimulationAssertions}).
 */
public final class ColonyHealth {

	private ColonyHealth() {
	}

	/**
	 * The invariants common to every healthy finished run: the laborer
	 * population is at least {@code minLaborers}, both consumer-good prices are
	 * finite and positive, and every bank keeps a finite, positive deposit pool
	 * and finite interest rates. Returns {@code null} if all hold, otherwise a
	 * short human-readable reason the run is unhealthy.
	 *
	 * @param h
	 *            a finished harness
	 * @param minLaborers
	 *            the minimum sustained laborer population a healthy run keeps
	 * @return {@code null} if healthy, otherwise why it is not
	 */
	public static String diagnose(SimulationHarness h, long minLaborers) {
		long alive = h.currentLaborerCount();
		if (alive < minLaborers)
			return "population too low (" + alive + " < " + minLaborers + ")";

		String ep = checkFinitePositivePrice(h.getEnjoymentMkt(), "enjoyment");
		if (ep != null)
			return ep;
		String np = checkFinitePositivePrice(h.getNecessityMkt(), "necessity");
		if (np != null)
			return np;

		// every bank's pools and rates must stay finite, and the banking system as a
		// whole must hold deposits (a collapse drains them). An individual
		// specialized bank may legitimately hold none — e.g. a ruler's gold bank
		// that is purely its borrowing facility while it funds peasant relief — so
		// the positive-deposit check is at the system level, not per bank.
		double totalDeposits = 0;
		for (Bank bank : h.getBanks()) {
			if (!Double.isFinite(bank.getTotalDeposit()))
				return "bank deposit not finite";
			if (!Double.isFinite(bank.getLoanIR())
					|| !Double.isFinite(bank.getDepositIR()))
				return "bank rate not finite";
			totalDeposits += bank.getTotalDeposit();
		}
		if (!(totalDeposits > 0))
			return "no bank deposits (banking collapsed)";
		return null;
	}

	/**
	 * Check a consumer-good market's last price is finite and positive.
	 *
	 * @param m
	 *            the market
	 * @param label
	 *            the good's name, for the reason string
	 * @return {@code null} if finite and positive, otherwise why it is not
	 */
	public static String checkFinitePositivePrice(ConsumerGoodMarket m,
			String label) {
		double p = m.getLastMktPrice();
		if (!(Double.isFinite(p) && p > 0))
			return label + " price not finite/positive (" + p + ")";
		return null;
	}
}
