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
		double roadSpeedFactor) {

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
			1.0);   // roadSpeedFactor (hook)

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
