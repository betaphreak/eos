package eos.tech;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The advisor "branch" a {@link Tech} belongs to — the source {@code techs.json}'s
 * coarse categorization of what a technology is about (military, economy, growth,
 * culture, religion, science). In eos these branches are the axis that the tech
 * tree's productivity effects will map onto the firm sectors (Growth→necessity,
 * Economy→capital, Science→export, Culture/Religion→enjoyment, Military deferred).
 * <p>
 * That advisor&rarr;sector mapping is <b>not</b> modelled here yet — it is Phase 2
 * of the tech tree (see {@code docs/tech-tree.md}); Phase 1 only classifies each
 * tech by its branch.
 */
public enum Advisor {

	MILITARY,
	ECONOMY,
	GROWTH,
	CULTURE,
	RELIGION,
	SCIENCE;

	private static final String PREFIX = "ADVISOR_";

	// reverse lookup from the raw JSON key (e.g. "ADVISOR_ECONOMY") to the constant
	private static final Map<String, Advisor> BY_KEY = new HashMap<>();
	static {
		for (Advisor a : values())
			BY_KEY.put(PREFIX + a.name(), a);
	}

	/**
	 * Resolve a raw {@code techs.json} advisor key (e.g. {@code "ADVISOR_ECONOMY"})
	 * to an {@link Advisor}, or {@link Optional#empty()} if the key is unrecognized.
	 *
	 * @param key
	 *            the raw advisor key
	 * @return the matching advisor, or empty if unknown
	 */
	public static Optional<Advisor> fromKey(String key) {
		return Optional.ofNullable(BY_KEY.get(key));
	}
}
