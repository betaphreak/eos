package com.civstudio.agent;

import java.time.LocalDate;

import com.civstudio.settlement.GameSession;
import com.civstudio.util.Rng;
import lombok.Getter;
import lombok.Setter;

/**
 * A <b>worker</b> band: a {@link MarchingCaravan} whose goal is to <b>build map
 * infrastructure</b> — the C2C {@code UNITAI_WORKER} flavor (e.g. {@code UNIT_WORKER}),
 * whose mission is {@code MISSION_BUILD}: laying a <b>route</b> or a tile
 * <b>improvement</b> (a {@code BuildType} in {@code CIV4BuildInfos.xml} — roads, mines,
 * farms, forts…). It marches and forages exactly like any band (that machinery is on
 * {@link MarchingCaravan}); only its arrival mission differs.
 * <p>
 * <b>Scaffold.</b> The band marches to its {@link #setDestination(int) destination} and, on
 * arrival, would lay its {@link #getBuild() build} on the province's plots. That mission is
 * a <b>stub</b> today: there is no persisted plot route/improvement state for it to write —
 * the corridor {@code moveCost} road discount is a dormant hook (see {@code
 * docs/caravan-march.md} §Accepted limitations), so the arrival {@linkplain
 * #arrive(LocalDate, Rng) mission} records intent and completes without changing the map.
 * Realizing it needs a plot-infrastructure subsystem (routes/improvements that persist and
 * feed the march speed and farm/mine yields).
 */
public final class WorkerCaravan extends MarchingCaravan {

	/** The default build a worker lays if none is set — a road (C2C {@code BUILD_ROAD}). */
	public static final String DEFAULT_BUILD = "BUILD_ROAD";

	// the C2C BuildType this worker intends to lay on arrival (a route or improvement);
	// inert until a plot-infrastructure subsystem exists (see class doc).
	@Getter
	@Setter
	private String build = DEFAULT_BUILD;

	// set once the worker has reached its destination and done its (stubbed) build; from
	// then the band is idle.
	private boolean done;

	/**
	 * Create an on-graph worker band anchored at a province.
	 *
	 * @param leader     the band's leader
	 * @param following  the band's following (its people and carried larder)
	 * @param hoard      the band's carried money, in copper, held outside any bank
	 * @param provinceId the band's starting node on the province graph
	 * @param session    the session the band belongs to
	 */
	public WorkerCaravan(Member leader, Retinue following, double hoard, int provinceId,
			GameSession session) {
		super(leader, following, hoard, provinceId, session);
	}

	@Override
	public CaravanRole role() {
		return CaravanRole.WORKER;
	}

	@Override
	protected boolean journeyComplete() {
		return done;
	}

	@Override
	protected boolean arrive(LocalDate date, Rng rng) {
		if (!atDestination())
			return false;
		// TODO(worker): lay `build` (a route/improvement — C2C MISSION_BUILD →
		// CIV4BuildInfos) on the arrival province's plots. No persisted plot
		// route/improvement state exists yet (docs/caravan-march.md: the corridor moveCost
		// road discount is a dormant hook), so this is a no-op stub — the band records
		// intent and idles.
		done = true;
		return true;
	}
}
