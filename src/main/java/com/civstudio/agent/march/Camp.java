package com.civstudio.agent.march;

import com.civstudio.settlement.PlotOccupant;

/**
 * A band's <b>transient nightly camp</b>: a lightweight {@link PlotOccupant} pitched on
 * one plot at dusk and struck at dawn when the band moves on (see {@code
 * docs/caravan-march.md}, <i>The nightly camp</i>). It carries no banks, markets or rank —
 * it is infrastructure the band raises and tears down, not a settlement. On the settle
 * decision the camp plot becomes the founding {@code HOLDING} seat (a later cut).
 */
public final class Camp implements PlotOccupant {

	@Override
	public String toString() {
		return "Camp";
	}
}
