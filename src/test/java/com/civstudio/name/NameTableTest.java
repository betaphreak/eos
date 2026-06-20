package com.civstudio.name;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.civstudio.util.Rng;

/**
 * Verifies {@link NameTable#pickAtRarity(Rng, double)}: a higher target rarity
 * draws rarer names (later in the common&rarr;rare order) than a lower one.
 */
class NameTableTest {

	@Test
	void higherRarityDrawsRarerNames() {
		NameTable male = NameTable.load("/male-human.json");
		// index each name in the table's common -> rare order
		String[] order = male.namesCopy();
		Map<String, Integer> rank = new HashMap<>();
		for (int i = 0; i < order.length; i++)
			rank.put(order[i], i);

		Rng rng = new Rng(7);
		int n = 4000;
		long sumCommon = 0;
		long sumRare = 0;
		for (int i = 0; i < n; i++)
			sumCommon += rank.get(male.pickAtRarity(rng, 0.0));
		for (int i = 0; i < n; i++)
			sumRare += rank.get(male.pickAtRarity(rng, 1.0));

		assertTrue(sumRare > sumCommon,
				"rarity 1 should draw rarer (later-ranked) names than rarity 0: "
						+ "meanRank rare=" + (sumRare / n) + " common="
						+ (sumCommon / n));
	}

	@Test
	void midRaritySitsBetweenExtremes() {
		NameTable male = NameTable.load("/male-human.json");
		String[] order = male.namesCopy();
		Map<String, Integer> rank = new HashMap<>();
		for (int i = 0; i < order.length; i++)
			rank.put(order[i], i);

		Rng rng = new Rng(31);
		int n = 4000;
		long low = 0;
		long mid = 0;
		long high = 0;
		for (int i = 0; i < n; i++)
			low += rank.get(male.pickAtRarity(rng, 0.0));
		for (int i = 0; i < n; i++)
			mid += rank.get(male.pickAtRarity(rng, 0.5));
		for (int i = 0; i < n; i++)
			high += rank.get(male.pickAtRarity(rng, 1.0));

		assertTrue(low < mid && mid < high,
				"mean rank should rise with rarity: low=" + (low / n) + " mid="
						+ (mid / n) + " high=" + (high / n));
	}
}
