package com.civstudio.agent.march;

import lombok.Builder;

/**
 * The calibration constants of the daylight-bounded march (see {@code
 * docs/caravan-march.md} §2–§4 and its <i>Calibration constants</i> table). All values
 * are <b>placeholders pending tuning</b>. Immutable; {@link #DEFAULT} holds the canonical
 * set, and the Lombok {@code toBuilder} builder lets a run derive a variant.
 *
 * @param paceLengthM       stride length in metres (Vegetius-derived 0.76)
 * @param cadencePerMin     march cadence in paces/min (100 regular &rarr; ~4.6&nbsp;km/h)
 * @param rowSpacingM       gap between marching ranks in metres
 * @param abreast           how many march shoulder-to-shoulder (road-width dependent)
 * @param baggageFootprint  the per-head length multiplier for the baggage block (wagons
 *                          and animals pack less efficiently than marching people)
 * @param hCampBaseHours    daylight reserved to make/break camp before a size term
 * @param hCampPerThousand  extra camp hours per 1000 band members (a bigger camp is
 *                          slower to build and strike)
 * @param prepSpanHours     sunrise &rarr; first departure (strike camp, meals, form ranks)
 * @param elementBufferMin  head-start/breathing room granted after an element clears camp
 * @param scoutBufferMin    the larger head-start the scouts are given before the vanguard
 * @param kmPerPlot         ground km one plot represents (a placeholder; to be derived
 *                          from the province raster's degrees-per-pixel). Since Phase 3
 *                          ({@code docs/explorer-caravan.md} §5) the movement <i>decision</i>
 *                          is denominated in Civ4 move-points, not km, so this survives for
 *                          <b>reporting distance only</b> (the estimated plots-crossed column
 *                          and the surplus-daylight scaling), never the spend.
 * @param roadSpeedFactor   terrain/road speed multiplier (1.0 hook; roads &gt; 1, rough
 *                          &lt; 1 — a later cut fed by {@code data/civ4/Civ4RouteInfos.xml})
 * @param maxDailyKm        the practical daily march ceiling — a real foot force sustains
 *                          ~20–30&nbsp;km/day well below the daylight ceiling ({@code
 *                          docs/caravan-march.md} §<i>Motivation</i>). Capping the day here
 *                          is what leaves <b>surplus daylight</b> for foraging on long days.
 * @param forageRatePerHour food a single forager gathers per surplus-daylight hour when the
 *                          corridor crossed a food resource (a placeholder)
 * @param forageCapFraction the daily forage ceiling as a fraction of the band's daily
 *                          ration — kept &lt; 1 so foraging only slows the larder's decline
 *                          (the band stays a decaying asset; see {@code docs/caravan.md})
 * @param gatherRatePerHour non-food goods (ore, gems, luxuries…) a single gatherer collects
 *                          into the band's cargo per surplus-daylight hour left after food
 *                          foraging, when the corridor crossed such a resource — slower than
 *                          foraging, since digging ore or picking spices yields less per hour
 *                          than gathering food (a placeholder)
 * @param cargoCapacityPerHead the cargo units one band member can carry — the band's total
 *                          carrying capacity is this × its head-count; a full band gathers
 *                          nothing more (a placeholder)
 * @param baseMovePoints    the Civ4 move-points a band spends in a day at the reference
 *                          daylight length, before the column-overhead term — the daily
 *                          budget the band lays down onto plot corridors priced in Civ4
 *                          move-cost units ({@code docs/explorer-caravan.md} §5). A
 *                          placeholder pending tuning (§10 calibration).
 * @param referenceDaylightHours the usable daylight at which the band earns its full {@code
 *                          baseMovePoints}; the daily budget scales with the usable-daylight
 *                          fraction against this, so a short winter day buys proportionally
 *                          fewer moves (the daylight coupling, in move-points)
 * @param columnOverheadPerThousand the move-points a 1000-strong column forfeits to its own
 *                          coil/uncoil each day — the size coupling in move-points (a big
 *                          band moves fewer plots than a lean one on the same daylight)
 * @param boundaryHopCost   the move-points to cross one province edge (the centroid-to-
 *                          centroid boundary hop, a per-hop unit) — added to the province's
 *                          plot-corridor cost to price a leg
 * @param minDailyMovePoints the daily move-point <b>floor</b> — the Civ4 min-one-move rule:
 *                          a marching band always advances at least this (one flat plot's
 *                          worth) per day and <b>never has a zero-progress day</b>, even in
 *                          polar winter or as a huge column, so it is never frozen (it creeps
 *                          on its larder rather than halting). One flat plot ≈ 1.0
 */
@Builder(toBuilder = true)
public record MarchConfig(
		double paceLengthM,
		double cadencePerMin,
		double rowSpacingM,
		int abreast,
		double baggageFootprint,
		double hCampBaseHours,
		double hCampPerThousand,
		double prepSpanHours,
		double elementBufferMin,
		double scoutBufferMin,
		double kmPerPlot,
		double roadSpeedFactor,
		double maxDailyKm,
		double forageRatePerHour,
		double forageCapFraction,
		double gatherRatePerHour,
		double cargoCapacityPerHead,
		double baseMovePoints,
		double referenceDaylightHours,
		double columnOverheadPerThousand,
		double boundaryHopCost,
		double minDailyMovePoints) {

	/** The canonical placeholder constants (see {@code docs/caravan-march.md}). */
	public static final MarchConfig DEFAULT = new MarchConfig(
			0.76,   // paceLengthM
			100.0,  // cadencePerMin -> v ~ 4.56 km/h
			1.2,    // rowSpacingM
			4,      // abreast
			2.5,    // baggageFootprint (wagons/animals ~2.5x a marcher's length per head)
			3.5,    // hCampBaseHours
			0.5,    // hCampPerThousand
			1.0,    // prepSpanHours
			1.0,    // elementBufferMin
			20.0,   // scoutBufferMin
			7.0,    // kmPerPlot (placeholder; see docs/land-routing.md open question)
			1.0,    // roadSpeedFactor (hook)
			30.0,   // maxDailyKm (practical daily march ceiling)
			0.03,   // forageRatePerHour (food per forager per surplus hour)
			0.8,    // forageCapFraction (< 1: foraging only slows the larder's decline)
			0.01,   // gatherRatePerHour (goods per gatherer per surplus hour left)
			10.0,   // cargoCapacityPerHead (cargo units one member can carry)
			6.0,    // baseMovePoints (Civ4 move-points/day at reference daylight, pre-overhead)
			12.0,   // referenceDaylightHours (usable daylight buying the full base points)
			1.0,    // columnOverheadPerThousand (move-points a 1000-strong column loses/day)
			2.0,    // boundaryHopCost (move-points to cross one province edge)
			1.0);   // minDailyMovePoints (min-one-move floor: one flat plot/day, never zero)

	/**
	 * The march speed in km/h — pace length &times; cadence, converted from m/min. With
	 * the defaults, {@code 0.76 m &times; 100/min = 76 m/min = 4.56 km/h} (the regular
	 * Roman march).
	 *
	 * @return the marching speed in km/h
	 */
	public double speedKmh() {
		return paceLengthM * cadencePerMin * 60.0 / 1000.0;
	}
}
