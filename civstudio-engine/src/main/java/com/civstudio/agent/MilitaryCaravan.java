package com.civstudio.agent;

import java.time.LocalDate;

import com.civstudio.settlement.GameSession;
import com.civstudio.util.Rng;

/**
 * A <b>military</b> band — an army on the march: a {@link MarchingCaravan} whose goal is to
 * <b>move to project force</b>, the C2C combat-AI flavor ({@code UNITAI_ATTACK},
 * {@code UNITAI_ATTACK_CITY}, {@code UNITAI_CITY_DEFENSE}, …). Unlike the civilian bands it
 * fields the <b>full order of march</b> ({@link com.civstudio.agent.march.MarchFlavor#MILITARY}
 * — scouts, vanguard, surveyors, command, main body, baggage, rear guard; see {@code
 * docs/caravan-march.md} §5), so a large army marches slower for its size. Its following is
 * conceptually soldiers rather than settlers; it marches and forages like any band.
 * <p>
 * <b>Scaffold.</b> The army marches to its {@link #setDestination(int) destination} and, on
 * arrival, would engage — but there is no combat model yet (no soldiers, no battles, no
 * {@code CasusBelli}/conquest triggers — the {@code Rank} military register is dormant, see
 * {@code docs/caravan.md} §Phase 5). So the arrival {@linkplain #arrive(LocalDate, Rng)
 * mission} is a <b>stub</b> that simply halts the army on arrival. Realizing it needs a
 * combat/army subsystem.
 */
public final class MilitaryCaravan extends MarchingCaravan {

	// set once the army has reached its target; from then the band is idle (no combat yet).
	private boolean done;

	/**
	 * Create an on-graph military band anchored at a province.
	 *
	 * @param leader     the band's leader (its commander)
	 * @param following  the band's following (its soldiers and carried larder)
	 * @param hoard      the band's carried money, in copper, held outside any bank
	 * @param provinceId the band's starting node on the province graph
	 * @param session    the session the band belongs to
	 */
	public MilitaryCaravan(Member leader, Retinue following, double hoard, int provinceId,
			GameSession session) {
		super(leader, following, hoard, provinceId, session);
	}

	@Override
	public CaravanRole role() {
		return CaravanRole.MILITARY;
	}

	@Override
	protected boolean journeyComplete() {
		return done;
	}

	@Override
	protected boolean arrive(LocalDate date, Rng rng) {
		if (!atDestination())
			return false;
		// TODO(military): engage / garrison / raid on arrival. No combat or army model
		// exists yet (docs/caravan.md §Phase 5 — the Rank military register is dormant), so
		// this is a no-op stub — the army simply halts on arrival.
		done = true;
		return true;
	}
}
