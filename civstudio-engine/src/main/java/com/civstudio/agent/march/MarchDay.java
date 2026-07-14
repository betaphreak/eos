package com.civstudio.agent.march;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * The computed <b>physics of one day's march</b> for a band (see {@code
 * docs/caravan-march.md} §1–§5): the daylight budget, the marching speed, the column's
 * length, the net camp-relocation distance {@code D}, and the per-{@link MarchElement}
 * departure/arrival {@link Stage schedule}. It is the aggregate the band spends over its
 * route; the movement itself (which provinces/plots the {@code D} carries it across, and
 * where it camps) is layered on top by the band and the reporting (see {@link
 * com.civstudio.io.printer.CaravanMarchPrinter}).
 *
 * @param date          the in-game date this was computed for
 * @param bandSize      the band's head-count (following size)
 * @param daylightHours the daylight length {@code H} at the band's position (0 when the
 *                      band is daylight-starved and halts)
 * @param speedKmh      the marching speed {@code v}
 * @param columnKm      the column length {@code L} — the coil/uncoil tax
 * @param netMarchKm    the net daily distance {@code D = max(0, v·(H − H_camp) − L)} — kept
 *                      for <b>reporting</b> (the plots-crossed estimate and the surplus-
 *                      daylight scaling); since Phase 3 the movement <i>decision</i> spends
 *                      {@link #movePoints} instead ({@code docs/explorer-caravan.md} §5)
 * @param movePoints    the day's <b>Civ4 move-point budget</b> {@code M = max(
 *                      minDailyMovePoints, baseMovePoints · usableFraction − columnOverhead)}
 *                      — the points the band lays onto plot corridors priced in Civ4 move-cost
 *                      units. <b>Floored to one plot/day</b> (the Civ4 min-one-move rule), so
 *                      it is never zero: a marching band always advances, even on a polar or
 *                      huge-column day when {@link #netMarchKm} (reporting) is 0
 * @param firstDepart   the first element's departure (sunrise + prep), or {@code null}
 *                      when the band does not march
 * @param campMade      when the far camp is fully built (the last element's tail is in),
 *                      or {@code null} when the band does not march
 * @param stages        the per-element schedule, front &rarr; back (empty when {@code D == 0})
 */
public record MarchDay(
		LocalDate date,
		int bandSize,
		double daylightHours,
		double speedKmh,
		double columnKm,
		double netMarchKm,
		double movePoints,
		LocalTime firstDepart,
		LocalTime campMade,
		List<Stage> stages) {

	/**
	 * One block's schedule line: when it {@link #depart() departs} camp, when its tail
	 * {@link #clearsCamp() clears} the old camp, and when its head {@link #headArrives()
	 * reaches} the new camp (see {@code docs/caravan-march.md} §5).
	 *
	 * @param element     the column block
	 * @param size        its head-count
	 * @param depart      its departure time
	 * @param clearsCamp  when its tail has filed out of the old camp
	 * @param headArrives when its head reaches the new camp
	 */
	public record Stage(MarchElement element, int size, LocalTime depart,
			LocalTime clearsCamp, LocalTime headArrives) {
	}

	/** Whether the band actually advances today ({@code D > 0}). */
	public boolean marches() {
		return netMarchKm > 0;
	}
}
