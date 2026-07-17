package com.civstudio.server;

import java.util.Collection;
import java.util.List;

import com.civstudio.geo.Province;
import com.civstudio.geo.Realm;
import com.civstudio.settlement.Settlement;

/**
 * Where a player joining a ranked {@linkplain SessionSpec#TIMELINE Timeline} founds — the site
 * picker (see {@code docs/spectator-lobby.md} Phase 3).
 * <p>
 * <b>One province each, spread across the map.</b> The first joiner takes the Timeline's
 * {@linkplain SessionSpec#provinceId anchor}; every later joiner takes the viable province
 * <em>furthest from everyone already seated</em> — max-min distance, the same idea
 * {@code ProvincePlotPool.foundingCenter} uses to space colonies within a province, lifted to the
 * world. Rivals therefore start far apart and expansion means something.
 * <p>
 * <b>Deterministic.</b> No randomness: the picks are a pure function of the map, the anchor and the
 * join order, and ties break on province id. So a Timeline's roster replays exactly — which is what
 * lets a run be rebuilt from its spec + roster rather than a snapshot.
 * <p>
 * <b>Scoped to one {@link Realm}.</b> A Timeline is a single realm's ranked ladder (docs/realms.md
 * §Ranked is per realm): the realm is the anchor's, and every joiner founds within it — so the
 * royale never spans a boundary the UI cannot see across, and {@link Realm#NONE realm-less} land is
 * never a site. (Start-scoping is only half of it; the cross-realm fey portals are gated closed —
 * docs/realms.md §Crossing a realm on foot is gated — so no colony walks into another ladder.)
 */
public final class TimelineSites {

	private TimelineSites() {
	}

	/**
	 * The province the next joiner should found into.
	 *
	 * @param world  every province on the map
	 * @param taken  the colonies already seated in this Timeline (their provinces are excluded, and
	 *               the pick is pushed as far from them as the map allows)
	 * @param anchor the Timeline's anchor province — the first joiner's site
	 * @return the chosen province
	 * @throws IllegalStateException if the map has no viable province left
	 */
	public static Province pick(Collection<Province> world, List<Settlement> taken, Province anchor) {
		// the Timeline's realm is the anchor's; every site must sit in it (and never in Realm.NONE)
		Realm realm = anchor == null ? Realm.NONE : anchor.realm();
		if (taken.isEmpty() && viable(anchor, realm))
			return anchor;

		Province best = null;
		double bestDistance = -1;
		for (Province p : world) {
			if (!viable(p, realm) || isTaken(p, taken))
				continue;
			double d = nearestTakenDistance(p, taken);
			// strictly-greater keeps the FIRST of equally-distant candidates, and `world` iterates
			// in province-id order — so ties break on id rather than on map iteration luck
			if (d > bestDistance) {
				bestDistance = d;
				best = p;
			}
		}
		if (best == null)
			throw new IllegalStateException("no settleable province left to found into");
		return best;
	}

	/**
	 * A province worth founding into: settleable land in this Timeline's realm (never {@link
	 * Realm#NONE}) with room for a colony to grow.
	 */
	private static boolean viable(Province p, Realm realm) {
		return p != null && p.realm() == realm && p.realm() != Realm.NONE
				&& p.isSettleable() && p.plots() >= Settlement.MIN_FOUNDING_PLOTS;
	}

	private static boolean isTaken(Province p, List<Settlement> taken) {
		for (Settlement c : taken)
			if (c.getProvince() != null && c.getProvince().id() == p.id())
				return true;
		return false;
	}

	// distance to the NEAREST seated colony — the score we maximize, so the pick is the site
	// furthest from its closest rival rather than furthest from their average
	private static double nearestTakenDistance(Province p, List<Settlement> taken) {
		double nearest = Double.MAX_VALUE;
		for (Settlement c : taken) {
			Province q = c.getProvince();
			if (q == null)
				continue;
			double dx = p.longitude() - q.longitude();
			double dy = p.latitude() - q.latitude();
			nearest = Math.min(nearest, dx * dx + dy * dy); // squared: ordering is all we need
		}
		return nearest == Double.MAX_VALUE ? 0 : nearest;
	}
}
