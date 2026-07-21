package com.civstudio.wiki.export;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Name-normalization + fuzzy name→key matching shared by the wiki exporters. Wiki articles key on
 * <em>display names</em> ("Grand Duchy of Wex", "Ayarallen Queendom") while the engine's canonical
 * entities key on stable keys/tags; correlation is a normalized name-join with the government
 * prefix/suffix stripped so "Kingdom of Lorent" → "Lorent" and "Ayarallen Queendom" → "Ayarallen".
 */
public final class WikiNames {

	private WikiNames() {
	}

	// leading government/organisational words to strip ("Kingdom of Lorent" → Lorent)
	private static final List<String> PREFIXES = List.of(
			"kingdom of", "empire of", "grand duchy of", "archduchy of", "duchy of", "county of",
			"barony of", "principality of", "republic of", "free city of", "hold of", "command of",
			"march of", "lordship of", "confederation of", "dominion of", "theocracy of", "sultanate of",
			"emirate of", "khanate of", "great clan of", "clan of", "tribe of", "city of", "state of",
			"union of", "league of", "akalate of", "jaddari", "the");
	// trailing government/organisational words to strip ("Ayarallen Queendom" → Ayarallen)
	private static final List<String> SUFFIXES = List.of(
			"empire", "queendom", "kingdom", "overclan", "confederation", "republic", "clans", "clan",
			"horde", "dominion", "khaganate", "akalate", "legion", "remnant", "state", "league", "union",
			"hold", "command", "march", "principality", "duchy", "county");

	/** Lowercased, diacritic-folded, alphanumeric-collapsed form of a name (š→s, "Rüng"→"rung"). */
	public static String norm(String s) {
		if (s == null)
			return "";
		String x = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return x.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	/** Candidate normalized forms of a title: the whole thing, then with a government prefix/suffix off. */
	public static List<String> candidates(String title) {
		String n = norm(title);
		List<String> out = new ArrayList<>();
		out.add(n);
		for (String pre : PREFIXES)
			if (n.startsWith(pre + " "))
				out.add(n.substring(pre.length() + 1));
		for (String suf : SUFFIXES)
			if (n.endsWith(" " + suf))
				out.add(n.substring(0, n.length() - suf.length() - 1));
		return out;
	}

	/**
	 * The canonical key a title correlates to, or {@code null} (no match = lore-only, no engine entity).
	 * First candidate that hits the {@code norm(name) → key} index wins.
	 */
	public static String match(String title, Map<String, String> normNameToKey) {
		for (String cand : candidates(title)) {
			String key = normNameToKey.get(cand);
			if (key != null)
				return key;
		}
		return null;
	}
}
