package com.civstudio.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.civstudio.agent.Caravan;
import com.civstudio.agent.Member;
import com.civstudio.agent.Retinue;
import com.civstudio.agent.SettlerCaravan;
import com.civstudio.bank.Bank;
import com.civstudio.bank.BankConfig;
import com.civstudio.good.Good;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

/**
 * A band that dies on the road is <b>deleted</b>, not left to march as a corpse.
 * <p>
 * The session's caravan list used to be append-only: {@code addCaravan} had no counterpart, so a
 * band whose last member starved was re-ticked every remaining day of the run and still shipped to
 * the client as a live marker with a head-count of zero. {@link Caravan#isSpent()} names the
 * condition (it was computed inside {@code tick} and thrown away) and {@code
 * GameSession.pruneSpentCaravans} buries it.
 */
class SpentCaravanPruneTest {

	private static final int DHENIJANSAR = 4411;

	// a lean band with an empty larder, registered with the session
	private static SettlerCaravan starvingBand(GameSession session, double larder) {
		SimulationConfig cfg = SimulationConfig.DEFAULT;
		Settlement muster = session.newSettlement("muster", cfg.startDate(),
				cfg.meanInitAgeYears(), cfg.targetNStock(), cfg.meanSkillMale(),
				cfg.meanSkillFemale(), 0, 0);
		Bank bank = new Bank(BankConfig.DEFAULT, muster);
		Retinue following = new Retinue(20, bank, muster);
		Member leader = following.promoteHighestSkilled();
		Good food = following.getGood("Necessity");
		double have = food.getQuantity();
		if (larder > have)
			food.increase(larder - have);
		else
			food.decrease(have - larder);
		SettlerCaravan band = new SettlerCaravan(leader, following, 1000, DHENIJANSAR, session);
		session.addCaravan(band);
		return band;
	}

	@Test
	void aBandThatStarvesOutIsSpentAndIsPrunedFromTheSession() {
		GameSession session = new GameSession(24680);
		SettlerCaravan band = starvingBand(session, 0);   // no food at all: it dies at once
		assertFalse(band.isSpent(), "a freshly mustered band has people in it");
		assertEquals(1, session.getCaravans().size(), "the band is registered with the session");

		// march it with an empty larder until the last member is gone
		Rng rng = session.getBandRng();
		LocalDate start = LocalDate.of(1445, 6, 1);
		for (int day = 0; day < 400 && !band.isSpent(); day++)
			band.tick(start.plusDays(day), rng);

		assertTrue(band.isSpent(), "an unfed band starves out");
		assertEquals(0, band.getFollowing().size(), "nobody is left in it");

		// ...and the session buries it
		var dead = session.pruneSpentCaravans();
		assertEquals(1, dead.size(), "the prune reports the band it buried");
		assertSame(band, dead.get(0), "and reports the band that actually died");
		assertTrue(session.getCaravans().isEmpty(),
				"the corpse is gone from the session — not left to be re-ticked forever");
	}

	@Test
	void thePruneSparesTheLiving() {
		GameSession session = new GameSession(24680);
		SettlerCaravan dying = starvingBand(session, 0);
		SettlerCaravan living = starvingBand(session, 5_000);   // provisioned: it marches on
		assertEquals(2, session.getCaravans().size(), "both bands are registered");

		Rng rng = session.getBandRng();
		LocalDate start = LocalDate.of(1445, 6, 1);
		for (int day = 0; day < 400 && !dying.isSpent(); day++) {
			dying.tick(start.plusDays(day), rng);
			living.tick(start.plusDays(day), rng);
		}

		assertTrue(dying.isSpent(), "the unfed band died");
		assertFalse(living.isSpent(), "the provisioned band did not");
		var dead = session.pruneSpentCaravans();
		assertEquals(1, dead.size(), "only the dead band is buried");
		assertEquals(1, session.getCaravans().size(), "the living band marches on");
		assertSame(living, session.getCaravans().get(0), "and it is the one that was fed");
	}

	@Test
	void pruningIsIdempotentAndHarmlessWithNoDead() {
		GameSession session = new GameSession(24680);
		starvingBand(session, 5_000);
		assertTrue(session.pruneSpentCaravans().isEmpty(), "nothing to bury on a healthy day");
		assertEquals(1, session.getCaravans().size(), "and the living band is untouched");
		// the drivers call this EVERY day, so the empty case is the common one
		assertTrue(session.pruneSpentCaravans().isEmpty(), "and again the next day");
		assertEquals(1, session.getCaravans().size(), "still untouched");
	}
}
