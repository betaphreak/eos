package eos.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link Rank} ladder: the sequence numbers are the declaration
 * order, the {@link Rank#promoted()}/{@link Rank#demoted()} walk steps to the
 * adjacent rank and is empty at the ends, and the two are exact inverses in the
 * interior. Phase 1 of the rank-ladder design (see {@code docs/rank-ladder.md}).
 */
class RankTest {

	@Test
	void seqIsTheDeclarationOrder() {
		Rank[] all = Rank.values();
		for (int i = 0; i < all.length; i++)
			assertEquals(i, all[i].seq(), all[i] + " seq");
		// the rungs the design names, by number
		assertEquals(0, Rank.SPECTATOR.seq());
		assertEquals(1, Rank.HOUSEHOLD.seq());
		assertEquals(2, Rank.RETINUE.seq());
		assertEquals(3, Rank.HOLDING.seq());
		assertEquals(4, Rank.VILLAGE.seq());
		assertEquals(16, Rank.HEGEMONY.seq());
	}

	@Test
	void promotedStepsOneRankUp() {
		assertSame(Rank.HOUSEHOLD, Rank.SPECTATOR.promoted().orElseThrow());
		assertSame(Rank.RETINUE, Rank.HOUSEHOLD.promoted().orElseThrow());
		assertSame(Rank.HOLDING, Rank.RETINUE.promoted().orElseThrow());
		assertSame(Rank.VILLAGE, Rank.HOLDING.promoted().orElseThrow());
	}

	@Test
	void demotedStepsOneRankDown() {
		assertSame(Rank.HOLDING, Rank.VILLAGE.demoted().orElseThrow());
		assertSame(Rank.RETINUE, Rank.HOLDING.demoted().orElseThrow());
		assertSame(Rank.HOUSEHOLD, Rank.RETINUE.demoted().orElseThrow());
		assertSame(Rank.SPECTATOR, Rank.HOUSEHOLD.demoted().orElseThrow());
	}

	@Test
	void laddersAreEmptyAtTheEnds() {
		assertEquals(Optional.empty(), Rank.SPECTATOR.demoted(), "nothing below SPECTATOR");
		assertEquals(Optional.empty(), Rank.HEGEMONY.promoted(), "nothing above HEGEMONY");
		// and the ends still step the other way
		assertSame(Rank.HOUSEHOLD, Rank.SPECTATOR.promoted().orElseThrow());
		assertSame(Rank.EMPIRE, Rank.HEGEMONY.demoted().orElseThrow());
	}

	@Test
	void promoteAndDemoteAreInversesInTheInterior() {
		for (Rank r : Rank.values()) {
			r.promoted().ifPresent(up -> assertSame(r, up.demoted().orElseThrow(),
					r + " promoted then demoted"));
			r.demoted().ifPresent(down -> assertSame(r, down.promoted().orElseThrow(),
					r + " demoted then promoted"));
		}
	}

	@Test
	void everyRankCarriesItsLabelAndTitle() {
		for (Rank r : Rank.values()) {
			assertTrue(r.label().startsWith("TXT_RANK_"), r + " label key");
			assertTrue(r.title() != null && !r.title().isBlank(), r + " title");
		}
		// the one short code in the spec
		assertEquals("1", Rank.RETINUE.shortCode());
		assertEquals(null, Rank.HOUSEHOLD.shortCode(), "only RETINUE has a short code");
	}
}
