package com.civstudio.agent.firm;

import java.util.Set;

import com.civstudio.tech.Sector;
import com.civstudio.bank.Bank;
import com.civstudio.calendar.DayType;
import com.civstudio.settlement.Settlement;
import com.civstudio.good.Enjoyment;
import com.civstudio.good.Good;
import com.civstudio.skill.Skill;

/**
 * Enjoyment Firm
 * 
 * @author zhihongx
 * 
 */
public class EFirm extends ConsumerGoodFirm {

	/**
	 * Create a new enjoyment firm
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
	public EFirm(double initCheckingBal, double initSavingsBal,
			double initOutput, double initWageBudget, int initCapital,
			CFirm[] capitalProducers, FirmConfig config, Bank bank,
			Settlement colony) {
		super("Enjoyment", initCheckingBal, initSavingsBal, initOutput,
				initWageBudget, initCapital, capitalProducers, config, bank,
				colony);
		product = new Enjoyment(0);
	}

	/**
	 * Return good with name <tt>good</tt>.
	 */
	public Good getGood(String good) {
		if (good.equals("Enjoyment"))
			return product;
		else
			return null;
	}

	/** Producing enjoyment goods trains a mix of {@link Skill#PRODUCTION}
	 * (the making) and {@link Skill#SOCIAL} (the performance/appeal). */
	@Override
	public Set<Skill> laborSkills() {
		return Set.of(Skill.PRODUCTION, Skill.SOCIAL);
	}

	/** An enjoyment firm produces in the {@link Sector#ENJOYMENT} sector. */
	@Override
	public Sector sector() {
		return Sector.ENJOYMENT;
	}

	/**
	 * Enjoyment firms run on workdays <b>and</b> on the weekly day of rest
	 * (Sunday) — the leisure trade keeps going on the day off — but not on feast
	 * days.
	 */
	@Override
	public boolean operatesOn(DayType day) {
		return day == DayType.WORKDAY || day == DayType.WEEKEND;
	}
}
