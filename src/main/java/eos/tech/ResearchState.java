package eos.tech;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import eos.era.Era;
import eos.era.Era.EraModifiers;
import eos.settlement.Settlement;

/**
 * A colony's research progress: what it already knows, the single technology it is
 * currently researching, and the points accumulated toward it. Research is produced
 * by the colony's {@link eos.agent.firm.ScienceFirm science firm}, which converts the
 * scholarly labor of the nobles (and the ruler) into research points (RP) and
 * delivers them here each step (see {@link #accrue(double)}); when the focus's cost is
 * met it <b>completes</b>, its {@link TechEffect effects} are applied to the colony
 * (raising a {@link Sector}'s tech multiplier, or recording an unlock/gate token),
 * and the colony is left without a focus until the ruler picks the next one.
 * <p>
 * Selection is <b>monthly</b>: the ruler picks the cheapest researchable tech in its
 * monthly review (see {@link #review()} and {@code Ruler.act}). RP is <b>never
 * wasted</b> — points produced while there is no focus (or beyond a focus's cost)
 * <b>buffer</b> in {@link #getProgress() progress} and carry over to the next focus,
 * so the monthly cadence only delays <em>which</em> tech the points advance, not
 * whether they count. The colony starts knowing every tech up to a configured start
 * era (see {@link TechTree#preKnownThrough(Era)}) and researches the frontier beyond
 * it; a fresh colony may also be given a <b>warm-start</b> focus part way to completion
 * (see {@link #seedInitialFocus}).
 * <p>
 * The whole state {@linkplain #snapshot() snapshots} onto a wandering band when a
 * colony is abandoned and {@linkplain #restore restores} onto the colony it re-founds,
 * so the tech tree survives the settle/unsettle hinge (see {@code docs/caravan.md}).
 * No randomness is used (cheapest-cost, ties broken by the tree's source order), so a
 * run's research path is deterministic for a given seed.
 */
public final class ResearchState {

	private final TechTree tree;
	private final Settlement colony;

	// a multiplier on each tech's authored cost — the pacing knob (see
	// SimulationConfig); the research-point yield itself is set by the science firm's
	// production curve (ScienceConfig)
	private final double costScale;

	// techs already known (seeded with the pre-known era set, grows on completion)
	private final Set<String> known;

	// the techs this colony researched itself (a subset of known, excluding the
	// pre-known baseline) — carried in the snapshot so a re-founded colony re-applies
	// their effects and recovers its productivity
	private final Set<String> completed = new HashSet<>();

	// the tech currently being researched (null when none — before the first monthly
	// pick, or between a completion and the next monthly review)
	private Tech focus;

	// RP accumulated toward the current focus; persists (buffers) across the gaps when
	// there is no focus and carries the overflow past a completion, so RP is never lost
	private double progress;

	// research points delivered last step and over the colony's life (for reporting)
	private double lastResearchPoints;
	private double totalResearchPoints;

	/**
	 * Create a colony's research state, knowing every tech up to {@code startEra}.
	 *
	 * @param tree
	 *            the shared tech graph
	 * @param colony
	 *            the colony whose effects completed techs apply to
	 * @param startEra
	 *            the latest era known for free at the start
	 * @param costScale
	 *            multiplier on each tech's authored research cost
	 */
	public ResearchState(TechTree tree, Settlement colony, Era startEra,
			double costScale) {
		this(tree, colony, costScale, new HashSet<>(tree.preKnownThrough(startEra)));
	}

	// shared constructor over an explicit known set (the era seed, or a restored one)
	private ResearchState(TechTree tree, Settlement colony, double costScale,
			Set<String> known) {
		this.tree = tree;
		this.colony = colony;
		this.costScale = costScale;
		this.known = known;
	}

	/**
	 * Restore a colony's research from a band's {@link ResearchSnapshot} (a re-founded
	 * colony resuming the tech tree). The known set, researched set, focus and buffered
	 * progress are taken from the snapshot, and the researched techs' {@link TechEffect
	 * effects} are <b>re-applied</b> to {@code colony} so its tech-derived productivity
	 * is recovered.
	 *
	 * @param tree
	 *            the shared tech graph
	 * @param colony
	 *            the re-founded colony
	 * @param snapshot
	 *            the band's carried research
	 * @param costScale
	 *            the re-founded colony's cost multiplier
	 * @return the restored research state
	 */
	public static ResearchState restore(TechTree tree, Settlement colony,
			ResearchSnapshot snapshot, double costScale) {
		ResearchState rs = new ResearchState(tree, colony, costScale,
				new HashSet<>(snapshot.known()));
		rs.completed.addAll(snapshot.completed());
		rs.progress = snapshot.progress();
		rs.focus = snapshot.focusType() == null ? null
				: tree.get(snapshot.focusType());
		// re-apply the researched techs' effects so the re-founded colony recovers the
		// productivity (and unlock/gate tokens) its research had earned
		for (String type : rs.completed)
			for (TechEffect effect : tree.effectsOf(type))
				colony.applyTechEffect(effect);
		return rs;
	}

	/**
	 * Give a fresh colony a <b>warm-start</b>: begin researching {@code techType} with
	 * {@code fraction} of its (scaled) cost already accumulated — e.g. a band founds
	 * already 90% of the way through the era's entry tech. A no-op if the tech is
	 * unknown to the tree or its prerequisites are not yet satisfied, or if a focus is
	 * already set.
	 *
	 * @param techType
	 *            the tech to begin part way through (e.g. {@code "TECH_MEDIEVAL_LIFESTYLE"})
	 * @param fraction
	 *            the fraction of its cost already accumulated (e.g. 0.9)
	 */
	public void seedInitialFocus(String techType, double fraction) {
		if (focus != null || techType == null)
			return;
		Tech t = tree.get(techType);
		if (t == null || known.contains(techType) || !prereqsSatisfied(t, known))
			return;
		focus = t;
		progress = fraction * effectiveCost();
	}

	/**
	 * Accrue {@code rp} research points (produced by the colony's science firm this
	 * step). The points always accumulate in {@link #getProgress() progress} — even
	 * with no focus, so they buffer and carry to the next focus — and complete the
	 * current focus when its (scaled) cost is reached.
	 *
	 * @param rp
	 *            the research points produced this step
	 */
	public void accrue(double rp) {
		lastResearchPoints = rp;
		if (rp <= 0)
			return;
		totalResearchPoints += rp;
		progress += rp; // buffers even when there is no focus
		if (focus != null && progress >= effectiveCost())
			complete();
	}

	private void complete() {
		progress -= effectiveCost(); // carry the overflow toward the next focus
		known.add(focus.type());
		completed.add(focus.type());
		for (TechEffect effect : tree.effectsOf(focus.type()))
			colony.applyTechEffect(effect);
		// leave the colony focus-less; the next monthly review picks a successor, and
		// the buffered overflow advances it
		focus = null;
	}

	/**
	 * The ruler's monthly research pick: if the colony has no current focus, choose
	 * the <b>cheapest researchable</b> tech (ties broken by the tree's source order).
	 * The buffered {@link #getProgress() progress} is kept (it carries onto the new
	 * focus). Leaves an in-progress focus untouched, and is a no-op when nothing is
	 * researchable. Call once a month from the ruler.
	 */
	public void review() {
		if (focus != null)
			return;
		Tech cheapest = null;
		for (Tech t : tree.researchableFrontier(known))
			if (cheapest == null || t.cost() < cheapest.cost())
				cheapest = t;
		focus = cheapest;
	}

	/**
	 * The current focus's effective cost — its authored cost scaled by its era's
	 * {@linkplain EraModifiers#researchPercent() research modifier} (research grows
	 * costlier in later eras) and the global {@code costScale} — or 0 when there is no
	 * focus.
	 *
	 * @return the research points needed to complete the current focus
	 */
	public double effectiveCost() {
		if (focus == null)
			return 0;
		EraModifiers m = focus.era().modifiers();
		double eraFactor = (m != null ? m.researchPercent() : 100) / 100.0;
		return focus.cost() * eraFactor * costScale;
	}

	/**
	 * Snapshot this research state for a band to carry across abandonment (see
	 * {@link #restore}).
	 *
	 * @return an immutable snapshot of the known/researched sets, focus and progress
	 */
	public ResearchSnapshot snapshot() {
		return new ResearchSnapshot(known, completed,
				focus == null ? null : focus.type(), progress);
	}

	// whether tech's prereqs are satisfied given known (all AND-prereqs known, and any
	// OR-prereq known or none required)
	private boolean prereqsSatisfied(Tech tech, Set<String> known) {
		return tree.prereqsSatisfied(tech, known);
	}

	/** @return the tech currently being researched, or {@code null} if none */
	public Tech getFocus() {
		return focus;
	}

	/** @return research points accumulated toward the current focus (buffered) */
	public double getProgress() {
		return progress;
	}

	/** @return the number of techs this colony has researched (completed) itself */
	public int getCompletedCount() {
		return completed.size();
	}

	/** @return the number of techs the colony knows (pre-known plus researched) */
	public int getKnownCount() {
		return known.size();
	}

	/** @return an unmodifiable view of the known tech ids */
	public Set<String> getKnown() {
		return Collections.unmodifiableSet(known);
	}

	/** @return research points delivered to the colony last step */
	public double getLastResearchPoints() {
		return lastResearchPoints;
	}

	/** @return research points delivered over the colony's life */
	public double getTotalResearchPoints() {
		return totalResearchPoints;
	}
}
