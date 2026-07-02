package com.civstudio.good;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>per-good inventory</b>: quantities keyed by a {@link com.civstudio.geo.Bonus
 * bonus} type key (e.g. {@code BONUS_TIN_ORE}). The general form of the carried food
 * larder — the larder is the {@code NECESSITY} special case, kept separate because it
 * is eaten daily (and food, unlike cargo, is divisible); the cargo holds everything
 * else a band gathers off the land and, later, the goods a trade caravan moves between
 * markets (see {@code docs/manufactured-bonuses.md}, <i>Caravans carry, forage, and
 * trade</i>).
 * <p>
 * Quantities are <b>whole units</b> — these are discrete goods (an elephant, a gem, an
 * ingot's worth of ore), not divisible bulk; a gatherer accumulates fractional
 * <i>progress</i> on its own side and deposits only full units.
 * <p>
 * Deliberately dumb: it neither identifies goods (the tech gate lives on the carrier)
 * nor caps itself (carrying capacity is the carrier's, sized by its head-count) — it
 * just conserves quantities. Insertion order is preserved (first-gathered first).
 */
public final class Cargo {

	// per-good whole-unit quantities in first-added order; every value > 0 (a good
	// drawn down to nothing is removed, so goods() lists only what is actually carried)
	private final Map<String, Integer> goods = new LinkedHashMap<>();

	/**
	 * Add whole units of a good to the cargo.
	 *
	 * @param type  the good's bonus type key (e.g. {@code BONUS_TIN_ORE})
	 * @param units whole units to add (non-negative)
	 */
	public void add(String type, int units) {
		if (units < 0)
			throw new IllegalArgumentException("cannot add a negative quantity: " + units);
		if (units > 0)
			goods.merge(type, units, Integer::sum);
	}

	/**
	 * Remove up to {@code requested} units of a good — the seam a trade caravan sells
	 * across. Never over-draws: returns the units actually removed (less than
	 * requested only if the cargo holds less).
	 *
	 * @param type      the good's bonus type key
	 * @param requested whole units to remove (non-negative)
	 * @return the units actually removed
	 */
	public int draw(String type, int requested) {
		if (requested < 0)
			throw new IllegalArgumentException("cannot draw a negative quantity: " + requested);
		int have = quantity(type);
		int drawn = Math.min(have, requested);
		if (drawn >= have)
			goods.remove(type);
		else if (drawn > 0)
			goods.put(type, have - drawn);
		return drawn;
	}

	/** @return the carried units of one good (0 if not carried) */
	public int quantity(String type) {
		return goods.getOrDefault(type, 0);
	}

	/** @return the total units carried, over all goods */
	public int total() {
		int t = 0;
		for (int q : goods.values())
			t += q;
		return t;
	}

	/** @return whether the cargo carries nothing */
	public boolean isEmpty() {
		return goods.isEmpty();
	}

	/** @return an unmodifiable view of the per-good units, in first-added order */
	public Map<String, Integer> goods() {
		return Collections.unmodifiableMap(goods);
	}

	/**
	 * A compact human-readable manifest — the carried goods in descending quantity,
	 * prefix-stripped ({@code BONUS_TIN_ORE} &rarr; {@code tin_ore}), e.g. {@code
	 * "sapphires 12; tin_ore 4 (+3 more)"}. Used by the march journal's
	 * {@code Carrying} column (semicolon-separated — commas are the CSV delimiter).
	 *
	 * @param limit the most-carried goods to list before folding the rest into a count
	 * @return the manifest label, or {@code "-"} when the cargo is empty
	 */
	public String manifest(int limit) {
		if (goods.isEmpty())
			return "-";
		List<Map.Entry<String, Integer>> byQty = new ArrayList<>(goods.entrySet());
		byQty.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < byQty.size() && i < limit; i++) {
			if (i > 0)
				s.append("; ");
			s.append(shortName(byQty.get(i).getKey())).append(' ')
					.append(byQty.get(i).getValue());
		}
		if (byQty.size() > limit)
			s.append(" (+").append(byQty.size() - limit).append(" more)");
		return s.toString();
	}

	@Override
	public String toString() {
		return "Cargo[" + manifest(3) + "]";
	}

	// drop the BONUS_ prefix and lower-case the rest ("BONUS_TIN_ORE" -> "tin_ore"),
	// matching the march journal's compact bonus labels
	private static String shortName(String type) {
		int us = type.indexOf('_');
		return (us >= 0 ? type.substring(us + 1) : type).toLowerCase();
	}
}
