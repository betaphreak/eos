package com.civstudio.agent.firm;

import java.util.Set;

import com.civstudio.tech.Sector;
import com.civstudio.bank.Bank;
import com.civstudio.settlement.Settlement;
import com.civstudio.good.Good;
import com.civstudio.good.Necessity;
import com.civstudio.skill.Skill;

/**
 * Necessity Firm
 * 
 * @author zhihongx
 * 
 */
public class NFirm extends ConsumerGoodFirm {

	// the hamlet seat this farm belongs to (city-of-hamlets V3): the village whose larder it fills
	// before selling, whose residents it hires first, and whose leader owns it. Null = a CITY farm —
	// the pre-V3 behaviour, and what every farm is while the village-firm subsystem is off. Assigned
	// (and re-assigned) by settlement.VillageFirms; a death-safe plain reference, since a plot outlives
	// the households on it.
	private com.civstudio.settlement.Plot village;

	/**
	 * Create a new necessity firm
	 * 
	 * @param initCheckingBal
	 *            initial checking account balance
	 * @param initSavingsBal
	 *            initial savings account balance
	 * @param initOutput
	 *            initial output
	 * @param initWageBudget
	 *            initial wage budget
	 * @param initCapital
	 *            initial amount of capital
	 * @param capitalProducers
	 *            array of capital good producers
	 * @param config
	 *            tunable model parameters
	 * @param bank
	 *            the bank at which this firm holds its accounts
	 * @param colony
	 *            the colony this firm belongs to
	 */
	public NFirm(double initCheckingBal, double initSavingsBal,
			double initOutput, double initWageBudget, int initCapital,
			CFirm[] capitalProducers, FirmConfig config, Bank bank,
			Settlement colony) {
		super("Necessity", initCheckingBal, initSavingsBal, initOutput,
				initWageBudget, initCapital, capitalProducers, config, bank,
				colony);
		product = new Necessity(0);
	}

	/**
	 * The <b>village</b> this farm belongs to — the seat plot of the hamlet whose larder it fills,
	 * whose residents it hires first, and whose leader owns it (city-of-hamlets V3, {@code
	 * docs/city-of-hamlets-plan.md}). {@code null} for a <b>city</b> farm: one no village has claimed,
	 * which sells its whole output on the shared market and draws from the whole workforce — the
	 * pre-V3 behaviour every farm keeps while the village-firm subsystem is off.
	 *
	 * @return the hamlet seat this farm serves, or {@code null} if it is a city farm
	 */
	public com.civstudio.settlement.Plot getVillage() {
		return village;
	}

	/**
	 * Attach this farm to a village (or detach it with {@code null}) — the assignment {@link
	 * com.civstudio.settlement.VillageFirms} makes each day, so a farm follows the villages that
	 * actually exist rather than a bundle fixed at founding.
	 *
	 * @param village the hamlet seat this farm now serves, or {@code null} to make it a city farm
	 */
	public void setVillage(com.civstudio.settlement.Plot village) {
		this.village = village;
	}

	/**
	 * {@inheritDoc} A village farm draws on <b>its own village's residents first</b> — the lord's
	 * fields are worked by the lord's own people — spilling over to the rest of the city's workforce
	 * only when the village cannot fill its slice (the shared-labor + affinity decision of {@code
	 * docs/city-of-hamlets-plan.md} §5). A city farm has no affinity.
	 */
	@Override
	public com.civstudio.settlement.Plot laborAffinity() {
		return village;
	}

	/**
	 * {@inheritDoc} A village farm <b>feeds its own village first</b>: it moves what its larder is
	 * short of its floor straight into that larder — bought by the village's leader at the going
	 * market price, since the leader owns both the farm and the duty to provision — and only the
	 * <b>surplus</b> reaches the shared market. A city farm (no village) delivers nothing locally and
	 * sells everything, as before. See {@code docs/city-of-hamlets-plan.md} V3.
	 */
	@Override
	protected double deliverLocally() {
		if (village == null)
			return 0;
		double moved = getColony().stockVillageLarder(this, village, product.getQuantity());
		if (moved > 0)
			product.decrease(moved);
		return moved;
	}

	/**
	 * Return good with name <tt>good</tt>.
	 */
	public Good getGood(String good) {
		if (good.equals("Necessity"))
			return product;
		else
			return null;
	}

	/** Necessity production is subsistence agriculture: it trains {@link
	 * Skill#SURVIVAL}. */
	@Override
	public Set<Skill> laborSkills() {
		return Set.of(Skill.SURVIVAL);
	}

	/** A necessity firm produces in the {@link Sector#NECESSITY} sector. */
	@Override
	public Sector sector() {
		return Sector.NECESSITY;
	}

	/**
	 * A necessity firm is <b>subsistence</b> agriculture the colony depends on to eat, so it keeps a
	 * wage-budget floor and does not shut down under a demand-deficient transient (see
	 * {@link ConsumerGoodFirm#isSubsistence()}).
	 */
	@Override
	protected boolean isSubsistence() {
		return true;
	}

	/**
	 * A necessity firm is subsistence agriculture — a farm on cleared land — so it
	 * <b>sits on a plot</b> (the only firm type that does, this cut): it reads its
	 * plot's terrain food yield into its TFP and its workers commute to it.
	 */
	@Override
	public boolean occupiesPlot() {
		return true;
	}

	/**
	 * A necessity firm is subsistence agriculture, so the improvement it raises on its
	 * plot is a {@code FARM} (on cleared land). Its {@code +2} food yield is folded
	 * into the plot's food TFP factor (see {@link com.civstudio.settlement.Plot}).
	 */
	@Override
	public String plotImprovement() {
		return "IMPROVEMENT_FARM";
	}

	/**
	 * Necessity is the colony's agriculture, so its effective total-factor
	 * productivity is additionally scaled by the colony's {@linkplain
	 * Settlement#getAgricultureClimateMultiplier() agricultural climate multiplier}
	 * (climate band &times; winter &times; monsoon) on top of the base {@code A}, the
	 * sector tech multiplier, and the plot's terrain yield factor (which {@code
	 * super.effectiveA()} already folds in — food is the live sector this cut). Both
	 * climate channels stack: climate acts through which terrains are generated
	 * <em>and</em> as this direct multiplier. Only necessity reads the climate
	 * multiplier — enjoyment and capital firms are climate-independent — and a colony
	 * with no province leaves both at {@code 1.0}.
	 */
	@Override
	protected double effectiveA() {
		return super.effectiveA() * getColony().getAgricultureClimateMultiplier();
	}
}
