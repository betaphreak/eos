package com.civstudio.settlement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Household;
import com.civstudio.agent.firm.NFirm;
import com.civstudio.agent.noble.Noble;

/**
 * The <b>village-firm subsystem</b> (city-of-hamlets V3, {@code docs/city-of-hamlets-plan.md}): the
 * daily pass that makes each of the colony's necessity farms belong to a <b>village</b> rather than to
 * the city at large — attaching it to a hamlet and putting it in that hamlet's leader's hands. Present
 * only when {@code SimulationConfig.villageFirms} is on ({@link Settlement#enableVillageFirms()}); a
 * flag-off colony never builds one and its farms stay city farms, selling their whole output on the
 * shared market and drawing from the whole workforce exactly as before.
 * <p>
 * The three consequences of belonging to a village all ride the one {@link NFirm#getVillage()} link
 * this pass sets:
 * <ul>
 * <li><b>It feeds its own village first.</b> The farm delivers into its village's {@link Larder} up to
 * the floor — bought by the leader at the going market price — and offers only the <b>surplus</b> for
 * sale ({@link VillageLarders#deposit}). A village with a productive farm exports; one without imports.
 * <li><b>It hires its own village's people first.</b> The shared labor market fills the farm's slice of
 * the workforce with its village's residents before outsiders (the shared-labor + affinity decision).
 * <li><b>Its leader owns it.</b> The fief-holder noble draws the farm's dividend, which is what funds
 * the provisioning duty it owes the same village — the feudal loop: the lord's fields, worked by the
 * lord's peasants, feed the lord's larder.
 * </ul>
 * <p>
 * <b>Assignment is balanced, not spatial.</b> Farms are handed to the villages with the fewest, in
 * plot-claim order — so the food capacity spreads over the villages that exist, and a colony with more
 * villages than farms leaves the tail as importers. (The plan's richer "territory grows by proximity"
 * rule is a later storey; the plot field is claim-ordered, not a spatial grid, so proximity would be a
 * fiction here.) The pass is idempotent and consumes no randomness: it re-runs every day, so farms
 * follow the villages that actually exist as villages are founded, emptied, granted and inherited.
 * <p>
 * <b>A crown-demesne village keeps its farm's existing owner.</b> The {@link
 * com.civstudio.agent.ruler.Ruler} is a treasury, not a rentier — it draws no dividends — so there is
 * no crown hand to move the holding into; the crown's stake in a demesne village is its provisioning
 * duty (it pays for that village's larder) and its taxes.
 */
public final class VillageFirms {

	// the owning colony, for the hamlets, farms and leaders the pass reads
	private final Settlement colony;

	VillageFirms(Settlement colony) {
		this.colony = colony;
	}

	/**
	 * Run the day's assignment: attach every living necessity farm to a village and put it in that
	 * village's leader's hands. Called by {@link Settlement#newDay()} <em>before</em> the agents act,
	 * so a farm produces, delivers and hires as the village it belongs to today.
	 */
	void assign() {
		List<Hamlet> hamlets = colony.hamlets();
		List<NFirm> farms = farms();
		if (farms.isEmpty())
			return;

		// which seats are villages today, and how many farms each already holds. Detach any farm whose
		// village has emptied out (its households gone) — it becomes a city farm until re-assigned
		// below, so it never feeds a larder no one eats from.
		Map<Plot, Integer> load = new IdentityHashMap<>();
		for (Hamlet ham : hamlets)
			load.put(ham.seat(), 0);
		for (NFirm farm : farms) {
			Plot village = farm.getVillage();
			if (village == null)
				continue;
			if (load.containsKey(village))
				load.merge(village, 1, Integer::sum);
			else
				farm.setVillage(null);
		}

		// hand each unattached farm to the village holding the fewest — spreading the colony's food
		// capacity over its villages; ties break on plot-claim order, so the choice is seed-stable
		for (NFirm farm : farms) {
			if (farm.getVillage() != null)
				continue;
			Plot leanest = leanestVillage(hamlets, load);
			if (leanest == null)
				break; // no villages yet (a colony before its households take land): all farms are city farms
			farm.setVillage(leanest);
			load.merge(leanest, 1, Integer::sum);
		}

		rebalance(hamlets, farms, load);

		for (NFirm farm : farms)
			if (farm.getVillage() != null)
				grantToLeader(farm, farm.getVillage());
	}

	// the village holding the fewest farms, ties broken on plot-claim order; null when the colony has
	// no villages yet
	private Plot leanestVillage(List<Hamlet> hamlets, Map<Plot, Integer> load) {
		Plot leanest = null;
		for (Hamlet ham : hamlets)
			if (leanest == null || load.get(ham.seat()) < load.get(leanest))
				leanest = ham.seat();
		return leanest;
	}

	// move ONE farm a day from the most-farmed village to a village with none, so the food capacity
	// keeps tracking the villages that exist rather than staying with whichever were founded first.
	// One move a day makes the drift gentle (a farm is a place, not a token to shuffle) and cannot
	// oscillate: it only ever fires while some village has two farms and another has none.
	private void rebalance(List<Hamlet> hamlets, List<NFirm> farms, Map<Plot, Integer> load) {
		Plot leanest = leanestVillage(hamlets, load);
		if (leanest == null || load.get(leanest) > 0)
			return;
		Plot richest = null;
		for (Hamlet ham : hamlets)
			if (richest == null || load.get(ham.seat()) > load.get(richest))
				richest = ham.seat();
		if (load.get(richest) < 2)
			return;
		for (NFirm farm : farms)
			if (farm.getVillage() == richest) {
				farm.setVillage(leanest);
				load.merge(richest, -1, Integer::sum);
				load.merge(leanest, 1, Integer::sum);
				return;
			}
	}

	/** The colony's living necessity farms, in agent order (stable, so the assignment is too). */
	private List<NFirm> farms() {
		List<NFirm> out = new ArrayList<>();
		for (Agent a : colony.getAgents())
			if (a.isAlive() && a instanceof NFirm f)
				out.add(f);
		return out;
	}

	// put the farm in its village leader's hands, if that leader is a noble and does not already hold
	// it: the fief-holder draws the dividend from the fields its own village works. A crown-demesne
	// village (no fief-holder) leaves ownership where it stands — the crown draws no dividends.
	private void grantToLeader(NFirm farm, Plot seat) {
		if (seat.ownerId() == null)
			return;
		Household leader = colony.getHouseholdById(seat.ownerId());
		if (!(leader instanceof Noble lord) || lord.owns(farm))
			return;
		for (Agent a : colony.getAgents())
			if (a instanceof Noble n && n.removeFirm(farm))
				break;
		lord.addFirm(farm);
	}
}
