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
	 * Necessity is the colony's agriculture, so its effective total-factor
	 * productivity is additionally scaled by the colony's {@linkplain
	 * Settlement#getAgricultureClimateMultiplier() agricultural climate multiplier}
	 * (climate band &times; winter &times; monsoon) on top of the base {@code A}
	 * and the sector tech multiplier. Only necessity reads this — enjoyment and
	 * capital firms are climate-independent — and a colony with no province leaves
	 * it at {@code 1.0}.
	 */
	@Override
	protected double effectiveA() {
		return super.effectiveA() * getColony().getAgricultureClimateMultiplier();
	}
}
