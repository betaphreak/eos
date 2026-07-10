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
 *                          from the province raster's degrees-per-pixel — used only for the
 *                          reported plot estimate until Level-2 corridors land)
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
		double cargoCapacityPerHead) {

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
			10.0);  // cargoCapacityPerHead (cargo units one member can carry)

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
