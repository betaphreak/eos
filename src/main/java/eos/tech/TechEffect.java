package eos.tech;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a researched {@link Tech} <em>does</em> in the eos economy. The source
 * {@code techs.json} carries no eos-native effects, so effects are authored
 * separately in an overlay ({@code /tech-effects.json}) keyed by tech id and loaded
 * alongside the tree (see {@link TechEffects} / {@link TechTree#effectsOf(String)}).
 * <p>
 * Three kinds cover the three roles the tech tree plays (see {@code docs/tech-tree.md}),
 * discriminated in JSON by a {@code "kind"} property:
 * <ul>
 * <li>{@link SectorProductivity} — multiply a {@link Sector}'s total-factor
 *     productivity (the only kind with a runtime effect today: it raises the colony's
 *     per-sector tech multiplier);</li>
 * <li>{@link Unlock} — enable new content (a good, firm or building) by an
 *     eos-native id;</li>
 * <li>{@link SocialGate} — enable a social/political capability (a class, rank or
 *     feature).</li>
 * </ul>
 * {@code Unlock} and {@code SocialGate} are recorded as granted tokens on the colony
 * but read by nothing yet — they are the seam their future consumers (the rank
 * ladder, {@code SocialClass}, new content) will plug into.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
		property = "kind")
@JsonSubTypes({
		@JsonSubTypes.Type(value = TechEffect.SectorProductivity.class,
				name = "SECTOR_PRODUCTIVITY"),
		@JsonSubTypes.Type(value = TechEffect.Unlock.class, name = "UNLOCK"),
		@JsonSubTypes.Type(value = TechEffect.SocialGate.class,
				name = "SOCIAL_GATE") })
public sealed interface TechEffect
		permits TechEffect.SectorProductivity, TechEffect.Unlock,
		TechEffect.SocialGate {

	/**
	 * Multiply a sector's total-factor productivity by {@code factor} (e.g. 1.05 for
	 * +5%). Cumulative: completing several such effects on a sector multiplies its
	 * colony tech multiplier in turn.
	 *
	 * @param sector
	 *            the sector whose productivity is raised
	 * @param factor
	 *            the multiplicative factor (1.0 is a no-op; &gt;1 raises output)
	 */
	record SectorProductivity(@JsonProperty("sector") Sector sector,
			@JsonProperty("factor") double factor) implements TechEffect {
	}

	/**
	 * Unlock a content element by its eos-native id (e.g. {@code "GOOD_PAPER"},
	 * {@code "FIRM_BANKING_HOUSE"}).
	 *
	 * @param target
	 *            the id of the content unlocked
	 */
	record Unlock(@JsonProperty("target") String target) implements TechEffect {
	}

	/**
	 * Enable a social/political capability by token (e.g. {@code "CLASS_BURGHER"},
	 * {@code "RANK_CITY"}, {@code "FEATURE_CARAVAN_TRADE"}).
	 *
	 * @param capability
	 *            the capability token granted
	 */
	record SocialGate(@JsonProperty("capability") String capability)
			implements TechEffect {
	}
}
