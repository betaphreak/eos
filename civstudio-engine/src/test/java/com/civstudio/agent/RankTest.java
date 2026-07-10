package com.civstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.civstudio.name.Gender;

/**
 * Verifies the {@link Rank} ladder: the levels are the declaration order, the
 * {@link Rank#promoted()}/{@link Rank#demoted()} walk steps to the adjacent rank
 * and is empty at the ends, the two are exact inverses in the interior, the
 * singular/plural kinds alternate, and every rank carries its typed titles and
 * casus belli. The 16-rung taxonomy (Household … Hegemony).
 */
class RankTest {

	@Test
	void levelIsTheDeclarationOrder() {
		Rank[] all = Rank.values();
		assertEquals(16, all.length);
		for (int i = 0; i < all.length; i++)
			assertEquals(i, all[i].level(), all[i] + " level");
		// the rungs the model realizes today, by number
		assertEquals(0, Rank.HOUSEHOLD.level());
		assertEquals(1, Rank.CARAVAN.level());
		assertEquals(2, Rank.HOLDING.level());
		assertEquals(3, Rank.VILLAGE.level());
		assertEquals(4, Rank.CITY.level());
		assertEquals(15, Rank.HEGEMONY.level());
	}

	@Test
	void promotedStepsOneRankUp() {
		assertSame(Rank.CARAVAN, Rank.HOUSEHOLD.promoted().orElseThrow());
		assertSame(Rank.HOLDING, Rank.CARAVAN.promoted().orElseThrow());
		assertSame(Rank.VILLAGE, Rank.HOLDING.promoted().orElseThrow());
		assertSame(Rank.CITY, Rank.VILLAGE.promoted().orElseThrow());
	}

	@Test
	void demotedStepsOneRankDown() {
		assertSame(Rank.VILLAGE, Rank.CITY.demoted().orElseThrow());
		assertSame(Rank.HOLDING, Rank.VILLAGE.demoted().orElseThrow());
		assertSame(Rank.CARAVAN, Rank.HOLDING.demoted().orElseThrow());
		assertSame(Rank.HOUSEHOLD, Rank.CARAVAN.demoted().orElseThrow());
	}

	@Test
	void laddersAreEmptyAtTheEnds() {
		assertEquals(Optional.empty(), Rank.HOUSEHOLD.demoted(),
				"nothing below HOUSEHOLD");
		assertEquals(Optional.empty(), Rank.HEGEMONY.promoted(),
				"nothing above HEGEMONY");
		// and the ends still step the other way
		assertSame(Rank.CARAVAN, Rank.HOUSEHOLD.promoted().orElseThrow());
		assertSame(Rank.EMPIRE, Rank.HEGEMONY.demoted().orElseThrow());
	}

	@Test
	void promoteAndDemoteAreInversesInTheInterior() {
		for (Rank r : Rank.values()) {
			r.promoted().ifPresent(up -> assertSame(r, up.demoted().orElseThrow(),
					r + " promoted then demoted"));
			r.demoted().ifPresent(down -> assertSame(r,
					down.promoted().orElseThrow(), r + " demoted then promoted"));
		}
	}

	@Test
	void singularAndPluralKindsAlternate() {
		// even levels are single consolidated entities, odd levels are collectives
		// of the rank below (see the class note)
		for (Rank r : Rank.values())
			assertEquals(r.level() % 2 == 1, r.isPlural(),
					r + " plural iff odd level");
		assertFalse(Rank.CITY.isPlural(), "a City is a single urban center");
		assertTrue(Rank.VILLAGE.isPlural(), "a Village is a network of holdings");
		assertTrue(Rank.LEAGUE.isPlural(), "a League is a bloc of cities");
	}

	@Test
	void everyRankCarriesTitlesInAllThreeRegisters() {
		for (Rank r : Rank.values()) {
			assertNotNull(r.displayName(), r + " name");
			assertFalse(r.displayName().isBlank(), r + " name");
			for (TitleMode mode : TitleMode.values()) {
				TitleSet t = r.titles(mode);
				assertNotNull(t, r + " " + mode + " titles");
				assertFalse(t.male().isBlank(), r + " " + mode + " male title");
				assertFalse(t.female().isBlank(), r + " " + mode + " female title");
				// the convenience accessor agrees with the title set
				assertEquals(t.male(), r.title(mode, Gender.MALE));
				assertEquals(t.female(), r.title(mode, Gender.FEMALE));
			}
		}
		// gendered feudal titles, ungendered commoner ones
		assertEquals("Baroness",
				Rank.BARONY.title(TitleMode.ADMINISTRATIVE, Gender.FEMALE));
		assertEquals("Mayoress",
				Rank.CITY.title(TitleMode.ADMINISTRATIVE, Gender.FEMALE));
		assertEquals("Captain",
				Rank.CARAVAN.title(TitleMode.ADMINISTRATIVE, Gender.FEMALE));
		// the military register is the rebel form
		assertEquals("Demagogue",
				Rank.CITY.title(TitleMode.MILITARY, Gender.MALE));
	}

	@Test
	void casusBelliPresentExceptAtTheLadderEnds() {
		// every rank can wage war against a peer
		for (Rank r : Rank.values())
			assertTrue(r.casusBelli(Relation.EQUAL).isPresent(),
					r + " has an equal-tier casus belli");
		// the bottom rank has nothing below it, the top nothing above
		assertTrue(Rank.HOUSEHOLD.casusBelli(Relation.LOWER).isEmpty(),
				"HOUSEHOLD has no lower target");
		assertTrue(Rank.HEGEMONY.casusBelli(Relation.HIGHER).isEmpty(),
				"HEGEMONY has no higher target");
		// a defined one carries its named pretext
		assertEquals("League Defection",
				Rank.CITY.casusBelli(Relation.HIGHER).orElseThrow().name());
	}
}
