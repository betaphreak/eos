package com.civstudio.agent.firm;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.civstudio.agent.AbstractHousehold;
import com.civstudio.agent.Agent;
import com.civstudio.agent.Member;
import com.civstudio.bank.Bank;
import com.civstudio.good.Good;
import com.civstudio.settlement.Settlement;
import lombok.Getter;

/**
 * The colony's <b>civic school</b> — where children grow their skills before
 * working age, so a child comes of age already capable (see {@code docs/births.md}).
 * It is a "firm" by name and civic role only: unlike every production firm it
 * <b>produces no good, earns no revenue, and pays no wages</b>. Its sole effect is
 * advancing the enrolled children's skills.
 * <p>
 * Each step it gathers the colony's <b>children</b> — the sub-working-age members
 * across all of its households (laborer, noble and ruler alike) — and enrolls up to
 * {@link ChildrenFirmConfig#capacity()}
 * of them, <b>the oldest first</b> (those closest to working age, so their freshly-grown
 * skills land just before they enter the workforce); any overflow waits and is
 * enrolled in later years as it ages up. Each enrolled child gains
 * {@link ChildrenFirmConfig#xpPerTick()} experience in <b>one randomly chosen skill</b>,
 * applied through the existing learn curve so the gain is passion-scaled. The
 * random-skill draw runs on the demographic skill RNG (never the economic stream),
 * so schooling does not perturb the economy's random draws.
 * <p>
 * It is an automatic civic institution: it is not chartered or dissolved by the
 * dynamic firm provisioning and occupies no build plot. A colony with no children
 * simply enrolls no one (and draws no randomness), so it is inert until births fill it.
 */
public class ChildrenFirm extends Agent {

	private final ChildrenFirmConfig config;

	// children enrolled (trained) in the latest act(), for the ChildrenPrinter
	@Getter
	private int lastEnrolled;

	/**
	 * Create the school with the {@link ChildrenFirmConfig#DEFAULT default parameters}.
	 *
	 * @param bank
	 *            a bank reference (the school holds no account and moves no money)
	 * @param colony
	 *            the colony this school belongs to
	 */
	public ChildrenFirm(Bank bank, Settlement colony) {
		this(bank, colony, ChildrenFirmConfig.DEFAULT);
	}

	/**
	 * Create the school with explicit {@code config}.
	 *
	 * @param bank
	 *            a bank reference (the school holds no account and moves no money)
	 * @param colony
	 *            the colony this school belongs to
	 * @param config
	 *            the school's tunable parameters
	 */
	public ChildrenFirm(Bank bank, Settlement colony, ChildrenFirmConfig config) {
		super(bank, colony);
		this.config = config;
		setName("School");
	}

	/** Called by Settlement.newDay() in each step. */
	@Override
	public void act() {
		Settlement colony = getColony();
		LocalDate today = colony.getDate();

		// gather every child in the colony — the sub-working-age members of every
		// living household, laborer, noble or ruler alike (births are universal, so a
		// noble's or the ruler's children are schooled like any other)
		List<Member> children = new ArrayList<>();
		for (Agent a : colony.getAgents())
			if (a instanceof AbstractHousehold household && household.isAlive())
				for (Member m : household.getMembers())
					if (!m.isAdult(today))
						children.add(m);

		// enroll up to capacity, oldest first (earliest birth date); the rest wait
		children.sort(Comparator.comparing(Member::getBirthDate));
		int places = Math.min(config.capacity(), children.size());
		for (int i = 0; i < places; i++)
			colony.getDemography()
					.trainRandomSkill(children.get(i).skills(), config.xpPerTick());
		lastEnrolled = places;
	}

	/** The number of places the school offers each step. */
	public int getCapacity() {
		return config.capacity();
	}

	/** The school produces no good. */
	@Override
	public Good getGood(String goodName) {
		return null;
	}
}
