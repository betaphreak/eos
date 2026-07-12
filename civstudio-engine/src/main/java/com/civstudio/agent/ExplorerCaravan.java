package com.civstudio.agent;

import java.time.LocalDate;

import com.civstudio.settlement.GameSession;
import com.civstudio.util.Rng;

/**
 * An <b>explorer</b> band: a {@link MarchingCaravan} whose goal is to <b>scout the map and
 * identify resources</b> — the C2C {@code UNITAI_EXPLORE} flavor (e.g. {@code UNIT_SCOUT},
 * {@code UNIT_EXPLORER}), a recon unit that reveals unseen ground and pops what it finds.
 * It marches and forages like any band; as it goes, the shared march already
 * {@linkplain #identifies(com.civstudio.geo.Bonus) identifies} (tech-gated) the resource
 * bonuses on the plots it crosses and lists them in the journal — the "identify resources"
 * half of the role, for free.
 * <p>
 * <b>Scaffold.</b> Unlike a settler, an explorer never settles — it marches toward its
 * {@link #setDestination(int) destination} scouting as it goes, then stops. The distinctive
 * <em>exploration</em> half — persistently <b>revealing</b> provinces/resources into a
 * session-level discovered-map (fog of war) — is a <b>stub</b>: no map-visibility state
 * exists yet, so the arrival {@linkplain #arrive(LocalDate, Rng) mission} simply ends the
 * scout. Realizing it needs a discovered-map subsystem (per-owner revealed provinces + the
 * resources identified on them). Note the C2C {@code <iBaseDiscover>} tag is <em>not</em>
 * map scouting (it is the great-person tech pop, {@code MISSION_DISCOVER}); this role rides
 * {@code UNITAI_EXPLORE} + sight/recon behaviour instead.
 */
public final class ExplorerCaravan extends MarchingCaravan {

	// set once the scout has reached its destination; from then the band is idle.
	private boolean done;

	/**
	 * Create an on-graph explorer band anchored at a province.
	 *
	 * @param leader     the band's leader
	 * @param following  the band's following (its people and carried larder)
	 * @param hoard      the band's carried money, in copper, held outside any bank
	 * @param provinceId the band's starting node on the province graph
	 * @param session    the session the band belongs to
	 */
	public ExplorerCaravan(Member leader, Retinue following, double hoard, int provinceId,
			GameSession session) {
		super(leader, following, hoard, provinceId, session);
	}

	@Override
	public CaravanRole role() {
		return CaravanRole.EXPLORER;
	}

	@Override
	protected boolean journeyComplete() {
		return done;
	}

	@Override
	protected boolean arrive(LocalDate date, Rng rng) {
		if (!atDestination())
			return false;
		// TODO(explorer): reveal the scouted provinces + the resources identified on them
		// into a session-level discovered-map (fog of war). No map-visibility state exists
		// yet, so this is a no-op stub — the scout simply stops on arrival. (Resource
		// identification along the way already happens via the shared march's
		// identifies()/journal.)
		done = true;
		return true;
	}
}
