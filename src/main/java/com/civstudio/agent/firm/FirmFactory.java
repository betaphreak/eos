package com.civstudio.agent.firm;

import com.civstudio.agent.ruler.Ruler;
import com.civstudio.settlement.Settlement;

/**
 * The colony's <b>dynamic firm provisioning</b> service: how a running settlement
 * charters a new consumer-good firm and dissolves an existing one. The {@link
 * Ruler}'s monthly sector review decides <em>whether</em> to act
 * (reading the demand and firm-level signals); this factory encapsulates the
 * <em>how</em> — the construction, funding, ownership assignment, slot placement
 * and teardown that need the run's full context (firm configs, the capital
 * producers, the firm bank) which the harness holds but the ruler does not.
 * <p>
 * Installed on the {@link Settlement} by the simulation harness
 * (see {@code SimulationHarness.enableDynamicFirmProvisioning}); absent it, the
 * ruler's review is a no-op (it logs nothing it cannot carry out). A colony
 * without a factory simply keeps its founding firm count.
 */
public interface FirmFactory {

	/**
	 * Charter a new consumer-good firm: build it (with the run's standard initial
	 * parameters), fund its seed capital out of the ruler's treasury (so the new
	 * money has a counterparty), grant it to a noble owner, claim it a build slot
	 * (queuing a builder growth ring when the colony is full), and schedule it to
	 * join the step loop. The firm is economically active immediately (its
	 * constructor posts a labor demand); the slot is pure bookkeeping.
	 *
	 * @param necessity
	 *            {@code true} to charter a necessity (food) firm, {@code false} an
	 *            enjoyment firm
	 * @return the chartered firm, or {@code null} if it could not be created (e.g.
	 *         no ruler to fund it)
	 */
	ConsumerGoodFirm charter(boolean necessity);

	/**
	 * Dissolve <tt>firm</tt>: detach it from its owner, vacate its slot, settle its
	 * account (its net worth folds into the bank's equity, debt absorbed, as for a
	 * deceased estate) and remove it from the colony. Its workers are freed simply
	 * by its ceasing to bid for labor.
	 *
	 * @param firm
	 *            the firm to dissolve
	 */
	void dissolve(ConsumerGoodFirm firm);
}
