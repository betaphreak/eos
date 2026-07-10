package com.civstudio.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies the colony's fixed 15th-century exchange rate: copper is the base
 * unit, silver is worth 100 copper and gold 1200 copper (gold:silver ≈ 12:1,
 * silver:copper ≈ 100:1), and the conversions round-trip.
 */
class CurrencyTypeTest {

	private static final double EPS = 1e-9;

	@Test
	void copperValuesMatchTheFixedRate() {
		assertEquals(1, CurrencyType.COPPER.copperValue(), EPS);
		assertEquals(100, CurrencyType.SILVER.copperValue(), EPS);
		assertEquals(1200, CurrencyType.GOLD.copperValue(), EPS);
	}

	@Test
	void toAndFromCopperAreInverses() {
		assertEquals(200, CurrencyType.SILVER.toCopper(2), EPS, "2 silver = 200 copper");
		assertEquals(2, CurrencyType.SILVER.fromCopper(200), EPS, "200 copper = 2 silver");
		assertEquals(1200, CurrencyType.GOLD.toCopper(1), EPS, "1 gold = 1200 copper");
		assertEquals(1, CurrencyType.GOLD.fromCopper(1200), EPS, "1200 copper = 1 gold");
	}

	@Test
	void crossConversionsUseTheFixedRatios() {
		// gold:silver = 12:1
		assertEquals(12, CurrencyType.convert(1, CurrencyType.GOLD, CurrencyType.SILVER), EPS);
		// silver:copper = 100:1
		assertEquals(100, CurrencyType.convert(1, CurrencyType.SILVER, CurrencyType.COPPER), EPS);
		// 1200 copper = 1 gold
		assertEquals(1, CurrencyType.convert(1200, CurrencyType.COPPER, CurrencyType.GOLD), EPS);
		// same currency is identity
		assertEquals(7.5, CurrencyType.convert(7.5, CurrencyType.SILVER, CurrencyType.SILVER), EPS);
	}
}
