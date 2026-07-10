package com.civstudio.settlement;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.civstudio.tech.Sector;
import com.civstudio.tech.TechEffect;

/**
 * The <b>technology state</b> of a {@link Settlement}: the per-sector productivity
 * multipliers researched {@link TechEffect.SectorProductivity} effects accumulate,
 * and the capability tokens {@link TechEffect.Unlock} / {@link TechEffect.SocialGate}
 * effects grant. Extracted from {@code Settlement} so its tech bookkeeping is one
 * cohesive unit; the colony delegates to it (see {@code docs/tech-tree.md}).
 * <p>
 * Every sector starts at the neutral {@code 1.0} and no tokens are granted, so this
 * is behaviour-neutral until a completed tech applies an effect.
 */
class TechState {

	// per-sector technology multiplier: the live total-factor-productivity scaling a
	// researched SectorProductivity tech effect raises. Every sector starts at 1.0.
	private final Map<Sector, Double> techMultiplier = new EnumMap<>(Sector.class);

	// tokens granted by researched Unlock / SocialGate tech effects (e.g. GOOD_PAPER,
	// CLASS_BURGHER). Read by future consumers (new content, the rank ladder,
	// SocialClass); nothing reads them yet.
	private final Set<String> grantedTokens = new LinkedHashSet<>();

	/**
	 * The multiplier for a given sector (1.0 if unset, or for a {@code null} sector —
	 * a firm without one, e.g. the builder).
	 */
	double multiplier(Sector sector) {
		if (sector == null)
			return 1.0;
		return techMultiplier.getOrDefault(sector, 1.0);
	}

	/** Apply a researched {@link TechEffect} — accumulate a multiplier or grant a token. */
	void apply(TechEffect effect) {
		switch (effect) {
		case TechEffect.SectorProductivity sp -> techMultiplier.merge(sp.sector(),
				sp.factor(), (cur, f) -> cur * f);
		case TechEffect.Unlock u -> grantedTokens.add(u.target());
		case TechEffect.SocialGate g -> grantedTokens.add(g.capability());
		}
	}

	/** An unmodifiable view of the granted capability tokens. */
	Set<String> grantedTokens() {
		return Collections.unmodifiableSet(grantedTokens);
	}
}
