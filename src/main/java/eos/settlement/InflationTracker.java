package eos.settlement;

import java.util.Collection;

import eos.market.ConsumerGoodMarket;
import eos.util.Averager;

/**
 * Tracks the colony's consumer price index (CPI) and inflation. Each step it
 * recomputes the CPI as the mean of the consumer-good market prices, derives the
 * step's inflation from the previous CPI, and keeps a rolling average over a
 * fixed window ({@link Settlement#INFLATION_TIME_WIN}).
 *
 * <p>Extracted from {@link Settlement}, which holds one and updates it once per
 * {@link Settlement#newDay()}. It reads the live consumer-good market set passed
 * at construction, so markets added after construction are included.
 */
class InflationTracker {

	private final Collection<ConsumerGoodMarket> consumerGoodMarkets;

	// CPI in the last step
	private double lastCPI;

	// inflation in the current step
	private double inflation;

	// average inflation within INFLATION_TIME_WIN
	private double avgInflation;

	// an averager used to compute average inflation
	private final Averager inflationAvger = new Averager(Settlement.INFLATION_TIME_WIN);

	InflationTracker(Collection<ConsumerGoodMarket> consumerGoodMarkets) {
		this.consumerGoodMarkets = consumerGoodMarkets;
	}

	/**
	 * Recompute the CPI (mean consumer-good price) and the step's inflation. On
	 * the first step inflation is defined as 0 (there is no previous CPI to
	 * compare against).
	 *
	 * @param firstStep
	 *            whether this is the colony's first step (time step 0)
	 */
	void update(boolean firstStep) {
		double cpi = 0;
		for (ConsumerGoodMarket mkt : consumerGoodMarkets) {
			cpi += mkt.getLastMktPrice();
		}
		cpi /= consumerGoodMarkets.size();

		if (firstStep) {
			inflation = 0;
			avgInflation = 0;
		} else {
			inflation = (cpi - lastCPI) / lastCPI;
			avgInflation = inflationAvger.update(inflation);
		}
		lastCPI = cpi;
	}

	// average inflation within INFLATION_TIME_WIN
	double getAvgInflation() {
		return avgInflation;
	}

	// the latest consumer price index (mean of consumer-good market prices)
	double getCPI() {
		return lastCPI;
	}
}
