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
	 * Return good with name <tt>good</tt>.
	 */
	public Good getGood(String good) {
		if (good.equals("Necessity"))
			return product;
		else
			return null;
	}

	/** Necessity production is subsistence agriculture: it trains {@link
	 * Skill#PLANTS}. */
	@Override
	public Set<Skill> laborSkills() {
		return Set.of(Skill.PLANTS);
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
