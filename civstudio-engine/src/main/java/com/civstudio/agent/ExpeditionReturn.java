package com.civstudio.agent;

import java.util.List;

import com.civstudio.settlement.Settlement;

/**
 * The <b>reward handler</b> invoked when an {@link ExplorerCaravan} returns home alive — the
 * renewal seam of {@code docs/explorer-caravan.md}. The caravan (in {@code com.civstudio.agent})
 * knows only the returnees and its haul; the actual reward — selling the gathered cargo, the
 * ruler's tax, ennobling the ablest returnee, and founding {@code Laborer} households from the
 * proceeds so each landless peasant <b>becomes banked</b> — is colony-side social mobility
 * (implemented by {@code simulation.SocialMobility}). This one-method interface lets the caravan
 * hand its return off across the package boundary without depending on that machinery. Wired at
 * muster by {@link ExplorerProvisioner}; a directly-driven band (e.g. a test) leaves it unset and
 * the caravan just undrafts its people.
 */
@FunctionalInterface
public interface ExpeditionReturn {

	/**
	 * Reward an expedition that has arrived home alive: distribute the value of its haul to its
	 * people (founding households / ennobling the ablest) and restore them to the colony. The
	 * handler is responsible for <b>undrafting</b> each returnee (its founding/promotion removes
	 * it from the pool). Runs from the caravan's tick (end of {@code newDay}, after market
	 * clearing); the handler defers any agent/account changes to end of step itself.
	 *
	 * @param home       the colony the expedition returned to
	 * @param returnees  the surviving drafted people, still in the pool (flagged drafted)
	 * @param cargoUnits the total whole units of non-food cargo the band gathered (valued and
	 *                   sold by the handler; food is deposited separately into the granary)
	 */
	void rewardReturn(Settlement home, List<Member> returnees, int cargoUnits);
}
