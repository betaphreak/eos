package com.civstudio.agent;

/**
 * A monthly snapshot of a colony's <b>explorer-expedition activity</b> — the renewal loop of
 * {@code docs/explorer-caravan.md} — for the {@code ExpeditionsPrinter}. A plain reporting DTO
 * assembled by {@code SimulationHarness.expeditionStats()} from the colony's {@link
 * ExplorerProvisioner} (musters) and its {@code SocialMobility} return handler (the reward tallies),
 * so the printer can read the figures across the package boundary without touching that machinery.
 *
 * @param out       explorer bands currently out (in flight)
 * @param mustered  bands mustered over the run so far (cumulative)
 * @param returns   bands returned home and rewarded over the run (cumulative)
 * @param founded   laborer households founded from returned peasants (cumulative)
 * @param ennobled  returnees ennobled to lead an expedition — no abler noble existed (cumulative)
 * @param nobleLed  returns an existing abler noble led, so no new noble was minted (cumulative)
 */
public record ExpeditionStats(int out, int mustered, long returns, long founded, long ennobled,
		long nobleLed) {

	/** The all-zero snapshot for a colony that musters no expeditions (a Village, or a bare sim). */
	public static final ExpeditionStats NONE = new ExpeditionStats(0, 0, 0, 0, 0, 0);
}
