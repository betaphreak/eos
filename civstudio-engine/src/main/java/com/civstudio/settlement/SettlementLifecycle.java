package com.civstudio.settlement;

import java.time.LocalDate;

import com.civstudio.agent.Agent;
import com.civstudio.agent.Household;
import com.civstudio.agent.SettlerCaravan;
import com.civstudio.agent.Retinue;

import lombok.extern.java.Log;

/**
 * The <b>lifecycle</b> of a {@link Settlement}: whether it has {@link #start()
 * started}, whether it has {@link #isDead() died}, and — for a ruler-bearing colony
 * that can form a band — its dissolution into a wandering {@link SettlerCaravan} once
 * its workforce drains. Extracted from {@code Settlement} so the birth/death/departure
 * state machine is one cohesive unit; the colony delegates its lifecycle queries here
 * and calls {@link #update()} once the population settles each step.
 * <p>
 * A colony is "started" once it begins running and ends its settled life ("dies") when
 * its workforce drains. A ruler-bearing colony that can form a band does not simply
 * vanish: once its workforce falls below {@link Settlement#DISSOLUTION_WORKFORCE_FLOOR}
 * it crosses the HOLDING → CARAVAN hinge and the survivors depart as a wandering band
 * (see {@code docs/caravan.md}); a pool-less colony still dies terminally when its last
 * laborer is gone.
 */
@Log
class SettlementLifecycle {

	// the owning colony, for the agents/ruler/session/plots state a transition reads
	private final Settlement colony;

	private boolean started = false;
	private boolean died = false;
	private LocalDate deathDate;

	// the wandering band a dissolved colony departed as (null until then; only a
	// ruler-bearing colony produces one)
	private SettlerCaravan departedBand;
	// flagged by update() when the workforce floor is crossed, so finishRun() performs
	// the dissolution after the step's market clearing/printing (the dissolution drains
	// banks and folds households, so it must not run mid-step)
	private boolean dissolving = false;

	SettlementLifecycle(Settlement colony) {
		this.colony = colony;
	}

	/** Mark the colony founded (idempotent); logs the founding once. */
	void start() {
		if (started)
			return;
		started = true;
		log.info(colony.getName() + " was founded on " + colony.getDate() + ".");
	}

	/** Whether {@link #start()} has been called (distinct from {@link #isAlive()}). */
	boolean isStarted() {
		return started;
	}

	/** Whether the colony is alive: it has started and has not died. */
	boolean isAlive() {
		return started && !died;
	}

	/** Whether the colony has died (lost its last laborer). Terminal. */
	boolean isDead() {
		return died;
	}

	/** The date the colony died, or null if still alive. */
	LocalDate getDeathDate() {
		return deathDate;
	}

	/** The wandering band a dissolved colony departed as, or null. */
	SettlerCaravan getDepartedBand() {
		return departedBand;
	}

	// detect the end of the colony's settled life, called each newDay once the
	// population settles. A ruler-bearing colony that can form a band dissolves into
	// a Caravan once its workforce falls below DISSOLUTION_WORKFORCE_FLOOR (the
	// HOLDING -> CARAVAN hinge): it is flagged here and the band departs in finishRun,
	// after the step's market clearing. A pool-less colony (no ruler/Retinue) instead
	// dies terminally when its last laborer is gone.
	void update() {
		if (!started || died)
			return;
		long workforce = livingLaborerCount();
		if (canDissolve()) {
			if (workforce < Settlement.DISSOLUTION_WORKFORCE_FLOOR) {
				died = true;
				dissolving = true;
				deathDate = colony.getDate();
				log.info(colony.getName() + " is dissolving into a Caravan on " + deathDate
						+ " (workforce " + workforce + " < floor "
						+ Settlement.DISSOLUTION_WORKFORCE_FLOOR + ")");
			}
		} else if (workforce == 0) {
			died = true;
			deathDate = colony.getDate();
			log.info(colony.getName() + " died on " + deathDate
					+ " (its last laborer is gone)");
		}
		if (died)
			colony.releasePlotsToPool();
	}

	// number of living workforce households in this colony (the laborers whose
	// labor sustains the colony; see Household.isWorkforce)
	private long livingLaborerCount() {
		long n = 0;
		for (Agent agent : colony.getAgents())
			if (agent instanceof Household h && h.isWorkforce())
				n++;
		return n;
	}

	// whether this colony can dissolve into a Caravan rather than dying terminally:
	// it needs a living ruler to lead the band and a Retinue to be its following.
	private boolean canDissolve() {
		if (colony.getRuler() == null || !colony.getRuler().isAlive())
			return false;
		for (Agent a : colony.getAgents())
			if (a instanceof Retinue)
				return true;
		return false;
	}

	/**
	 * Finalize a finished run: a colony that crossed the workforce floor departs as a
	 * {@link SettlerCaravan} (the survivors take to the road) rather than vanishing —
	 * done here, after the last step's clearing/printing, since the dissolution drains
	 * the banks and folds the households (it must not run mid-step).
	 */
	void finishRun() {
		if (dissolving && departedBand == null)
			dissolveIntoCaravan();
	}

	// cross the HOLDING -> CARAVAN hinge: dissolve this colony into a wandering band
	// (money -> hoard, households -> following) and, if the colony belongs to a
	// session, register the band there (colony-less bands live at the session level —
	// see docs/caravan.md). The settlement is now gone; its people persist in the band.
	private void dissolveIntoCaravan() {
		departedBand = SettlerCaravan.dissolve(colony);
		if (colony.getSession() != null)
			colony.getSession().addCaravan(departedBand);
		log.info(colony.getName() + " departed as a Caravan ("
				+ departedBand.getFollowing().size() + " in the following, hoard "
				+ (long) departedBand.getHoard() + " copper)");
	}
}
