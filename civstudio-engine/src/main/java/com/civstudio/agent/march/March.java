package com.civstudio.agent.march;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes the {@link MarchDay physics of a day's march} from the band's size, flavor,
 * and the day's daylight — the model of {@code docs/caravan-march.md} §1–§5. Stateless;
 * the caller supplies the daylight length and sunrise (from a {@link
 * com.civstudio.settlement.SolarClock} at the band's moving position) and the band size,
 * and gets back the net distance {@code D} and the per-element schedule.
 * <p>
 * The headline coupling: a lean band in a long summer day covers ground; a big band, or a
 * short winter day, makes little or no progress ({@code D → 0}) because the column's own
 * length and the camp overhead eat the daylight.
 */
public final class March {

	private March() {
	}

	/**
	 * Compute the day's march for a band.
	 *
	 * @param date          the in-game date
	 * @param bandSize      the band's head-count
	 * @param flavor        which {@link MarchElement}s the band fields
	 * @param daylightHours the daylight length {@code H} (NaN/&le;0 &rArr; a forced halt,
	 *                      {@code D = 0} — high-latitude winter or undefined solar events)
	 * @param sunrise       the day's sunrise (the schedule's anchor), or {@code null} when
	 *                      undefined (polar day/night) — then no timed schedule is produced
	 * @param cfg           the march calibration constants
	 * @return the computed day
	 */
	public static MarchDay compute(LocalDate date, int bandSize, MarchFlavor flavor,
			double daylightHours, LocalTime sunrise, MarchConfig cfg) {
		int size = Math.max(0, bandSize);
		double v = cfg.speedKmh();

		// column length L = Σ over fielded elements of ceil(size(e)/abreast)·rowSpacing,
		// the baggage block counted at its heavier per-head footprint (§3)
		double columnKm = 0;
		int[] elemSize = new int[MarchElement.values().length];
		double[] elemLenKm = new double[MarchElement.values().length];
		for (MarchElement e : MarchElement.values()) {
			int s = (int) Math.round(size * e.share(flavor));
			elemSize[e.ordinal()] = s;
			if (s <= 0)
				continue;
			int rows = (int) Math.ceil(s / (double) cfg.abreast());
			double footprint = e.isBaggage() ? cfg.baggageFootprint() : 1.0;
			double lenKm = rows * cfg.rowSpacingM() * footprint / 1000.0;
			elemLenKm[e.ordinal()] = lenKm;
			columnKm += lenKm;
		}

		// net daily distance D = max(0, v·(H − H_camp) − L); a daylight-starved day
		// (H NaN/≤0) or a column longer than the usable window makes zero progress. The
		// day is then capped at the practical march ceiling (a real force sustains far less
		// than the daylight ceiling), which is what leaves surplus daylight for foraging.
		double hCamp = cfg.hCampBaseHours() + cfg.hCampPerThousand() * size / 1000.0;
		double usable = Double.isNaN(daylightHours) ? 0 : daylightHours - hCamp;
		double d = Math.min(cfg.maxDailyKm(), Math.max(0, v * Math.max(0, usable) - columnKm));

		// the day's Civ4 move-point budget M (the Phase-3 movement driver — the band spends
		// this onto plot corridors priced in Civ4 move-cost units, docs/explorer-caravan.md
		// §5): base points scaled by the usable-daylight fraction, less the column overhead a
		// big band forfeits to its coil, then FLOORED to minDailyMovePoints — the Civ4
		// min-one-move rule, so a marching band always advances at least one plot/day and
		// never has a zero-progress day (polar winter or a huge column creeps on its larder
		// rather than freezing). Unlike netMarchKm (which still zeroes on a daylight halt, for
		// reporting), the move-point budget never falls to zero.
		double usableFraction = cfg.referenceDaylightHours() <= 0 ? 0
				: Math.max(0, usable) / cfg.referenceDaylightHours();
		double columnOverhead = cfg.columnOverheadPerThousand() * size / 1000.0;
		double movePoints = Math.max(cfg.minDailyMovePoints(),
				cfg.baseMovePoints() * usableFraction - columnOverhead);

		// the timed per-element schedule (only when the band marches and sunrise is
		// defined): each element leaves once the previous has cleared camp plus a buffer
		List<MarchDay.Stage> stages = new ArrayList<>();
		LocalTime firstDepart = null;
		LocalTime campMade = null;
		if (d > 0 && sunrise != null) {
			double cursor = toHours(sunrise) + cfg.prepSpanHours(); // T0
			firstDepart = toLocalTime(cursor);
			double marchHours = d / v;
			double tailArriveMax = 0;
			for (MarchElement e : MarchElement.values()) {
				int s = elemSize[e.ordinal()];
				if (s <= 0)
					continue;
				double fHours = elemLenKm[e.ordinal()] / v; // file-out time
				double depart = cursor;
				double clears = depart + fHours;
				double headArrives = depart + marchHours;
				double tailArrives = depart + fHours + marchHours;
				stages.add(new MarchDay.Stage(e, s, toLocalTime(depart),
						toLocalTime(clears), toLocalTime(headArrives)));
				tailArriveMax = Math.max(tailArriveMax, tailArrives);
				double buffer = (e == MarchElement.SCOUTS ? cfg.scoutBufferMin()
						: cfg.elementBufferMin()) / 60.0;
				cursor = clears + buffer;
			}
			campMade = toLocalTime(tailArriveMax);
		}

		return new MarchDay(date, size, Double.isNaN(daylightHours) ? 0 : daylightHours,
				v, columnKm, d, movePoints, firstDepart, campMade, stages);
	}

	// LocalTime -> decimal hours since midnight
	private static double toHours(LocalTime t) {
		return t.getHour() + t.getMinute() / 60.0;
	}

	// decimal hours since midnight -> LocalTime, clamped to a single day [00:00, 23:59]
	// (a schedule running past midnight is capped rather than wrapping — a day that long
	// is itself the signal the march does not fit)
	private static LocalTime toLocalTime(double hours) {
		if (hours <= 0)
			return LocalTime.MIDNIGHT;
		if (hours >= 24)
			return LocalTime.of(23, 59);
		int h = (int) hours;
		int m = (int) Math.round((hours - h) * 60);
		if (m == 60) {
			h += 1;
			m = 0;
		}
		return h >= 24 ? LocalTime.of(23, 59) : LocalTime.of(h, m);
	}
}
