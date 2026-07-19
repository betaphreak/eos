package com.civstudio.handicap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The catalog of Civ4/C2C {@linkplain Handicap handicaps} (difficulty levels), loaded from the
 * committed {@code /handicaps.json} — baked from {@code CIV4HandicapInfo.xml} by
 * {@link com.civstudio.handicap.export.HandicapInfoExporter}. See {@code docs/session-management.md}.
 * <p>
 * The catalog's job today is <b>validation</b>: a session's chosen difficulty must be one of these
 * keys. The scaling {@link Handicap#modifiers() modifiers} are carried but not yet applied to the sim.
 * <p>
 * The {@link #DEFAULT} instance loads the committed resource once; construct a catalog directly from a
 * list for tests or an exporter round-trip.
 */
public final class HandicapCatalog {

	/** The classpath resource baked by the exporter. */
	public static final String RESOURCE = "/handicaps.json";

	/** Civ4's balanced middle rung — the standard difficulty when none is chosen. */
	public static final String DEFAULT_KEY = "noble";

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The catalog loaded from the committed resource (loaded once, lazily). */
	public static final HandicapCatalog DEFAULT = fromResource(RESOURCE);

	private final Map<String, Handicap> byKey = new LinkedHashMap<>();
	private final String defaultKey;

	/**
	 * @param handicaps  the handicaps, in ladder order
	 * @param defaultKey the default difficulty key (must be one of {@code handicaps}), or {@code null}
	 *                   to fall back to {@link #DEFAULT_KEY}
	 */
	public HandicapCatalog(List<Handicap> handicaps, String defaultKey) {
		for (Handicap h : handicaps)
			byKey.put(h.key(), h);
		this.defaultKey = defaultKey != null && byKey.containsKey(defaultKey) ? defaultKey
				: (byKey.containsKey(DEFAULT_KEY) ? DEFAULT_KEY
						: (handicaps.isEmpty() ? null : handicaps.get(0).key()));
	}

	/** Load a catalog from a classpath JSON resource. */
	public static HandicapCatalog fromResource(String resource) {
		try (InputStream in = HandicapCatalog.class.getResourceAsStream(resource)) {
			if (in == null)
				throw new IllegalStateException("handicap catalog resource not found: " + resource);
			JsonNode root = JSON.readTree(in);
			List<Handicap> out = new java.util.ArrayList<>();
			for (JsonNode n : root.path("handicaps")) {
				Map<String, Integer> mods = new LinkedHashMap<>();
				for (Map.Entry<String, JsonNode> e : n.path("modifiers").properties())
					mods.put(e.getKey(), e.getValue().asInt());
				out.add(new Handicap(n.path("type").asString(), n.path("key").asString(),
						n.path("description").asString(""), mods));
			}
			String def = root.path("default").asString("");
			return new HandicapCatalog(out, def);
		} catch (IOException e) {
			throw new UncheckedIOException("failed reading " + resource, e);
		}
	}

	/** The handicaps, in ladder order (easiest first). */
	public List<Handicap> all() {
		return List.copyOf(byKey.values());
	}

	/** The default difficulty key — the standard rung a run takes when none is chosen. */
	public String defaultKey() {
		return defaultKey;
	}

	/** Whether {@code key} is a known handicap key (already canonical — see {@link #resolve}). */
	public boolean has(String key) {
		return key != null && byKey.containsKey(key);
	}

	/** The handicap for a canonical key, or empty. */
	public Optional<Handicap> byKey(String key) {
		return Optional.ofNullable(key == null ? null : byKey.get(key));
	}

	/**
	 * Resolve a caller-supplied difficulty to a canonical key, tolerantly: {@code null}/blank returns
	 * {@code null} (meaning "unspecified — the standard rung"), and a value is matched case-insensitively
	 * with or without the {@code HANDICAP_} prefix ({@code "Noble"}, {@code "HANDICAP_NOBLE"} and
	 * {@code "noble"} all resolve). An unrecognised non-blank value is rejected.
	 *
	 * @param input the caller's difficulty, or {@code null}
	 * @return the canonical key, or {@code null} if {@code input} was blank
	 * @throws IllegalArgumentException if {@code input} is non-blank but not a known handicap
	 */
	public String resolve(String input) {
		if (input == null || input.isBlank())
			return null;
		String key = Handicap.keyOf(input.trim().toLowerCase(Locale.ROOT).startsWith("handicap_")
				? input.trim() : "HANDICAP_" + input.trim());
		if (!byKey.containsKey(key))
			throw new IllegalArgumentException("unknown difficulty '" + input + "' — known: " + byKey.keySet());
		return key;
	}
}
