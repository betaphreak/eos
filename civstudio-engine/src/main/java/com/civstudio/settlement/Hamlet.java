package com.civstudio.settlement;

import java.util.List;

import com.civstudio.agent.laborer.Laborer;

/**
 * A <b>hamlet</b> — a city's plot made a first-class place: one {@linkplain #seat() seat plot}, the
 * peasant {@linkplain #households() households} resident on it, and the {@linkplain #leaderId()
 * leader} that holds it as a fief (a {@link com.civstudio.agent.noble.Noble}, or the Crown when the
 * plot is unenfeoffed). The city-of-hamlets V0 entity ({@code docs/city-of-hamlets-plan.md}): pure
 * <b>grouping</b> over what the shipped vassalage already gives — the plot's {@linkplain
 * Plot#ownerId() fief-holder}, the households' {@linkplain Laborer#getHomePlot() home plots}, and the
 * plot's real-world {@linkplain Plot#placeName() place name}. It carries no state of its own and
 * drives no behaviour; a {@link Settlement} recomputes its hamlets on demand ({@link
 * Settlement#hamlets()}), so a hamlet never drifts from the agents behind it.
 * <p>
 * A hamlet sits on the {@link SettlementTier} ladder like any settlement, but <b>capped at {@link
 * SettlementTier#HAMLET}</b> — a dependent cell never boots its own Ruler (that is the {@link
 * SettlementTier#SMALLHOLDING} rung an independent settlement reaches). Its {@linkplain #tier() tier}
 * is derived from its household count. The later storeys that give a hamlet its own larder, Necessity
 * firms and per-village tick are V2+; here it is a read-only view.
 *
 * @param seat       the seat plot — the fief plot the households live on
 * @param name       the seat's real-world place name (GeoNames), or {@code null} if unnamed
 * @param leaderId   the agent id of the fief-holder that leads this hamlet (a noble, or the ruler),
 *                   or {@code null} when the seat is Crown demesne — a death-safe id, exactly like
 *                   {@link Plot#ownerId()}
 * @param households the resident peasant households (each with {@code homePlot == seat}); never empty
 * @param tier       the hamlet's tier, derived from its household count and capped at {@link
 *                   SettlementTier#HAMLET}
 */
public record Hamlet(Plot seat, String name, Integer leaderId, List<Laborer> households,
		SettlementTier tier) {

	/**
	 * Project a hamlet from its seat plot and the households resident on it — reads the name and
	 * leader off the plot, and rates the tier from the household count.
	 *
	 * @param seat       the seat plot
	 * @param households the households whose home plot is {@code seat} (non-empty)
	 * @return the hamlet
	 */
	static Hamlet of(Plot seat, List<Laborer> households) {
		return new Hamlet(seat, seat.placeName(), seat.ownerId(), List.copyOf(households),
				tierFor(households.size()));
	}

	/**
	 * This hamlet's <b>territory</b> — the plots it works. For V0 that is only its seat; the
	 * organic spread that claims nearby empty plots as fields is a later phase ({@code
	 * docs/city-of-hamlets-plan.md} "territory grows by proximity").
	 *
	 * @return the hamlet's plots (the seat alone, this cut)
	 */
	public List<Plot> territory() {
		return List.of(seat);
	}

	/**
	 * The number of households living in this hamlet — its population in households (the city-screen
	 * "N households"). People-per-hamlet (summing members) is a later concern.
	 *
	 * @return the resident household count
	 */
	public int size() {
		return households.size();
	}

	/**
	 * Whether this hamlet is <b>Crown demesne</b> — held by the crown directly rather than a noble
	 * ({@link #leaderId()} is {@code null}).
	 *
	 * @return {@code true} if the seat is unenfeoffed
	 */
	public boolean crownDemesne() {
		return leaderId == null;
	}

	/**
	 * The tier a hamlet of {@code households} households sits at — the highest rung up to {@link
	 * SettlementTier#HAMLET} whose {@linkplain SettlementTier#minHouseholds() household floor} it
	 * meets ({@code CAMP} 1&ndash;3, {@code COTTAGE} 4&ndash;8, {@code HAMLET} 9+). Capped at
	 * {@code HAMLET}: a hamlet is a dependent cell, so it never climbs into the self-governing
	 * {@link SettlementTier#SMALLHOLDING} band.
	 *
	 * @param households the resident household count
	 * @return the derived tier (never above {@link SettlementTier#HAMLET})
	 */
	static SettlementTier tierFor(int households) {
		SettlementTier best = SettlementTier.CAMP;
		for (SettlementTier t : new SettlementTier[] { SettlementTier.CAMP, SettlementTier.COTTAGE,
				SettlementTier.HAMLET })
			if (households >= t.minHouseholds())
				best = t;
		return best;
	}
}
