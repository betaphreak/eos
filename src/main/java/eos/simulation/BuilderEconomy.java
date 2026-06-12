package eos.simulation;

import java.time.LocalDate;

import eos.agent.firm.BuilderConfig;
import eos.agent.firm.BuilderFirm;
import eos.agent.firm.EFirm;
import eos.agent.firm.NFirm;
import eos.bank.Bank;
import eos.settlement.Settlement;
import eos.settlement.SlotTable;

/**
 * Simulation (a colony that <b>builds itself bigger</b>): the worked example of the
 * {@link BuilderFirm}. It is the minimum stable closed colony — {@value
 * #NUM_LABORERS} laborers, {@value #NUM_EFIRMS} enjoyment + {@value #NUM_NFIRMS}
 * necessity + one capital firm, the default export sector and a ruler, all on the
 * default tiered banking — founded at the floor size ({@link SlotTable#MIN_SIZE}),
 * <b>plus a builder</b>.
 * <p>
 * Unlike every other settlement, a live colony with a builder does not grow for
 * free: the builder is the <em>only</em> thing that can enlarge it. To show that, a
 * one-off {@link Settlement#addStepAction(Runnable) step action} expands the
 * colony's industry {@value #EXPANSION_YEARS} years in, planting {@value
 * #EXTRA_EFIRMS} enjoyment and {@value #EXTRA_NFIRMS} necessity firms (a balanced
 * batch, so it does not unbalance the consumer markets). The founding footprint is
 * nearly full, so the new firms cannot all be seated: each one that does not fit has
 * its slot <b>built</b> — the builder clears the firm's land (billed to that firm)
 * while the ruler funds the new ring's roads and walls. The colony grows from its
 * founding size {@value SlotTable#MIN_SIZE} into the next ring as the work
 * completes, and the waiting firms are seated.
 */
public class BuilderEconomy {

	/** Seed for the game session, so the run is reproducible. */
	static final long SEED = 13572468L;

	/** Laborer households — the minimum stable closed scale (see {@link ScaleSweep}). */
	static final int NUM_LABORERS = 180;

	/** Enjoyment firms at founding. */
	static final int NUM_EFIRMS = 4;

	/** Necessity firms at founding. */
	static final int NUM_NFIRMS = 6;

	/** Years after founding the colony expands its industry (and so must build to grow). */
	static final int EXPANSION_YEARS = 5;

	/** Enjoyment firms the expansion plants. */
	static final int EXTRA_EFIRMS = 2;

	/** Necessity firms the expansion plants (balanced with the enjoyment firms). */
	static final int EXTRA_NFIRMS = 2;

	/**
	 * Build and run the simulation.
	 *
	 * @return the harness, exposing the constructed markets, banks, firms (the
	 *         builder via {@code getBuilderFirm()}) and agents
	 */
	public static SimulationHarness run() {
		SimulationConfig cfg = SimulationConfig.DEFAULT.toBuilder()
				.settlementName("Vinderhoute")
				.numLaborers(NUM_LABORERS)
				.numEFirms(NUM_EFIRMS)
				.numNFirms(NUM_NFIRMS)
				.build();
		SimulationHarness h = SimulationHarness.create(cfg, SEED);
		Settlement colony = h.getColony();

		h.createMarkets();
		Bank copper = h.getCopperBank();
		h.createFirms(copper, i -> copper,
				i -> cfg.eFirm().savings(), i -> cfg.nFirm().savings());
		// the default export sector (its nobles bank copper here, as in HomogeneousEconomy)
		h.createDefaultStrategicSector(copper);
		// the builder that will grow the colony when it outgrows its footprint
		h.createBuilder(copper, BuilderConfig.DEFAULT);
		h.createLaborers(i -> copper, i -> 15, i -> cfg.laborer().savings());
		// every settlement has a ruler; here it also funds the builder's public works
		Bank gold = h.createDefaultRuler();

		// industry expansion: once, EXPANSION_YEARS in, plant a balanced batch of new
		// consumer firms. The colony is nearly full, so the ones that do not fit have
		// their slots built — the builder clears each firm's land (billed to it) and
		// the ruler funds the new ring's roads and walls.
		LocalDate expansionDate = cfg.startDate().plusYears(EXPANSION_YEARS);
		boolean[] expanded = { false };
		colony.addStepAction(() -> {
			if (expanded[0] || colony.getDate().isBefore(expansionDate))
				return;
			expanded[0] = true;
			for (int i = 0; i < EXTRA_NFIRMS; i++) {
				NFirm f = new NFirm(cfg.nFirm().checking(), cfg.nFirm().savings(),
						cfg.nFirm().output(), cfg.nFirm().wageBudget(),
						cfg.nFirm().capital(), h.getCapitalFirms(),
						h.getFirmConfig(), copper, colony);
				colony.addAgent(f);
				colony.claimSlot(f); // null when queued for the builder to build
			}
			for (int i = 0; i < EXTRA_EFIRMS; i++) {
				EFirm f = new EFirm(cfg.eFirm().checking(), cfg.eFirm().savings(),
						cfg.eFirm().output(), cfg.eFirm().wageBudget(),
						cfg.eFirm().capital(), h.getCapitalFirms(),
						h.getFirmConfig(), copper, colony);
				colony.addAgent(f);
				colony.claimSlot(f);
			}
		});

		h.addCommonPrinters();
		h.addBankPrinter("Copper", copper);
		h.addBankPrinter("Gold", gold);
		h.addBuilderPrinter("Builder");
		h.run();
		return h;
	}

	public static void main(String[] args) {
		run();
	}
}
