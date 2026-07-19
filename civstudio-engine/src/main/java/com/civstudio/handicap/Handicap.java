package com.civstudio.handicap;

import java.util.Locale;
import java.util.Map;

/**
 * One Civ4/C2C <b>handicap</b> (difficulty level) — the numeric challenge scaling imported from
 * {@code CIV4HandicapInfo.xml} (see {@code docs/session-management.md} §Two difficulty selectors).
 * This is the <em>how hard</em> axis; the <em>who you are</em> axis is the alignment grid.
 * <p>
 * Reference data only for now: a run records its chosen {@link #key()} and the founding validates it
 * against the {@link HandicapCatalog}, but the {@link #modifiers()} are <b>not yet applied</b> to the
 * simulation — wiring the multipliers is a later, separate feature. The modifiers are carried so that
 * feature has them ready.
 *
 * @param type        the Civ4 type constant (e.g. {@code "HANDICAP_NOBLE"})
 * @param key         the stable short key a session stores (e.g. {@code "noble"}) — the type with its
 *                    {@code HANDICAP_} prefix stripped, lower-cased
 * @param description the Civ4 {@code TXT_KEY_*} display-name key (unresolved; GameText lookup is a
 *                    later concern)
 * @param modifiers   the handicap's integer {@code i*} fields (gold, upkeep/inflation percents, AI
 *                    bonuses, barbarian tuning, …), keyed by their XML tag — the raw scaling table
 */
public record Handicap(String type, String key, String description, Map<String, Integer> modifiers) {

	/** The short key for a Civ4 handicap {@code type} — {@code "HANDICAP_NOBLE"} → {@code "noble"}. */
	public static String keyOf(String type) {
		if (type == null)
			return null;
		String t = type.trim();
		if (t.regionMatches(true, 0, "HANDICAP_", 0, "HANDICAP_".length()))
			t = t.substring("HANDICAP_".length());
		return t.toLowerCase(Locale.ROOT);
	}
}
