package com.civstudio.simulation;

import com.civstudio.agent.Agent;
import com.civstudio.agent.firm.CFirm;
import com.civstudio.agent.firm.ConsumerGoodFirm;
import com.civstudio.agent.firm.EFirm;
import com.civstudio.agent.firm.Firm;
import com.civstudio.agent.firm.FirmConfig;
import com.civstudio.agent.firm.FirmFactory;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;

/**
 * The {@link FirmFactory} that gives a ruler-bearing colony <b>dynamic firm
 * provisioning</b>: the ruler's monthly sector review charters and dissolves
 * consumer-good firms as demand warrants, rather than the colony carrying a
 * fixed founding count. Factored out of {@link SimulationHarness} (an anonymous
 * inner class before) so the charter/dissolve logic is testable in isolation and
 * matches how {@code docs/architecture.md} treats provisioning as its own
 * subsystem.
 * <p>
 * A new firm banks at {@code firmBank}, is built with the run's standard initial
 * parameters, has its seed capital funded out of the ruler's treasury, and is
 * granted to the least-encumbered living noble (or, if the colony has none, an
 * ablest laborer ennobled to own it — see {@link SocialMobility}); a dissolved
 * firm is detached from its owner, its plot freed and its account settled into
 * equity.
 */
class DynamicFirmProvisioner implements FirmFactory {

	private final Settlement colony;
	private final SimulationConfig cfg;
	private final CFirm[] capitalFirms;
	private final FirmConfig firmConfig;
	private final FirmConfig nFirmConfig;
	private final Bank firmBank;
	private final SocialMobility mobility;

	/** The numbers this colony runs on — its own {@code (era, race)} cell, not the run's. */
	private com.civstudio.era.Era.Economy econ() {
		return colony.getEconomy();
	}

	DynamicFirmProvisioner(Settlement colony, SimulationConfig cfg,
			CFirm[] capitalFirms, FirmConfig firmConfig, FirmConfig nFirmConfig,
			Bank firmBank, SocialMobility mobility) {
		this.colony = colony;
		this.cfg = cfg;
		this.capitalFirms = capitalFirms;
		this.firmConfig = firmConfig;
		this.nFirmConfig = nFirmConfig;
		this.firmBank = firmBank;
		this.mobility = mobility;
	}

	@Override
	public ConsumerGoodFirm charter(boolean necessity) {
		Ruler ruler = colony.getRuler();
		if (ruler == null || !ruler.isAlive())
			return null;

		double seed;
		ConsumerGoodFirm firm;
		if (necessity) {
			seed = econ().nFirm().checking() + econ().nFirm().savings();
			firm = new NFirm(econ().nFirm().checking(), econ().nFirm().savings(),
					econ().nFirm().output(), econ().nFirm().wageBudget(),
					econ().nFirm().capital(), capitalFirms, nFirmConfig,
					firmBank, colony);
		} else {
			seed = econ().eFirm().checking() + econ().eFirm().savings();
			firm = new EFirm(econ().eFirm().checking(), econ().eFirm().savings(),
					econ().eFirm().output(), econ().eFirm().wageBudget(),
					econ().eFirm().capital(), capitalFirms, firmConfig,
					firmBank, colony);
		}

		// the crown funds the firm's seed money out of its treasury, so the
		// money the firm opened with has a counterparty (the firm's account
		// was credited from nothing; this destroys an equal sum). Gold→copper
		// fires the gold bank's FX fee; a short treasury borrows.
		ruler.getBank().withdraw(ruler.getID(), seed);

		// grant it to the noble with the fewest holdings (spreading ownership);
		// if the colony has no noble, ennoble the ablest laborer to own it —
		// deferred to end of step, once that laborer's offers have cleared so
		// its account can be moved (see ennobleBestLaborer). Re-check for a
		// noble inside the deferred action, so a second charter the same step
		// (e.g. both sectors) reuses the one just raised rather than raising
		// another from the same laborer.
		Noble owner = mobility.leastLoadedNoble();
		if (owner != null)
			owner.addFirm(firm);
		else
			colony.scheduleEndOfStepAction(() -> {
				Noble raised = mobility.leastLoadedNoble();
				if (raised == null)
					raised = mobility.ennobleBestLaborer();
				if (raised != null)
					raised.addFirm(firm);
			});

		// seat the firm: an on-plot farm claims a plot (on a live colony that
		// queues a builder plot-clearance and holds it pending; a center-grouped
		// enjoyment firm consumes none) — but it is economically active from its
		// constructor (which posted a labor demand) regardless — then admit it to
		// the step loop at end of step (so the agent set is not mutated mid-iter)
		seatFirm(firm);
		colony.scheduleAddAgent(firm);
		return firm;
	}

	@Override
	public void dissolve(ConsumerGoodFirm firm) {
		// detach from its owner so no dividend is drawn next step
		for (Agent a : colony.getAgents())
			if (a instanceof Noble noble && noble.removeFirm(firm))
				break;
		// mark it dissolved and remove it at end of step; its plot is freed and
		// its account settled into equity there (its final offers clear first)
		firm.markDissolved();
		colony.scheduleRemoveAgent(firm);
	}

	// claim a plot for a firm iff it sits on the land (a farm); a center-grouped firm
	// works in town and consumes no plot.
	private void seatFirm(Firm firm) {
		if (firm.occupiesPlot())
			colony.claimPlot(firm);
	}
}
