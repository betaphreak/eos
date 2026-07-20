package com.civstudio.era;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

import com.civstudio.race.Race;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authored <b>era × race economy matrix</b>, read from content rather than compiled in — the
 * delivery mechanism for the decision in {@code docs/studio-control-plane-plan.md} §A1: an era sets
 * the technological epoch, a race sets who is living through it, and the constants on {@link Era}
 * are merely the <em>human</em> column authored before race was a lever.
 *
 * <p>The resource is a two-level map keyed by enum name, {@code era → race → economy}:
 * <pre>{@code
 * { "MEDIEVAL": { "HUMAN": { "retinueSize": 900, ... }, "DWARVEN": { ... } } }
 * }</pre>
 *
 * <h2>Failure contract — deliberately unlike {@code UnitCatalog}</h2>
 * That catalog is lenient because embodiment is cosmetic: a broken resource costs a band its
 * portrait. An economy is <b>load-bearing</b> — it sets prices, starting balances, tax rates and the
 * size of the peasant pool, so a silently-wrong one is a simulation that quietly ran different
 * numbers than it reported. Therefore:
 * <ul>
 * <li><b>absent</b> resource → empty catalog, and every lookup falls back to the {@link Era}
 *     constants. This is the offline/pre-cutover path and is behaviour-neutral by construction.</li>
 * <li><b>present but malformed</b> → {@link IllegalStateException}, loudly, at load. Never a silent
 *     fallback: "the file is there but we ignored it" is exactly how a run ends up irreproducible
 *     with no way to detect it.</li>
 * </ul>
 *
 * <p><b>Reproducibility.</b> Once economies ride the content bundle, a content edit changes
 * simulation behaviour, so a run is reproducible only as {@code seed + contentVersion + command
 * log}. {@code SessionRecord.contentVersion} already records the second (see
 * {@code docs/architecture.md} §Where world data comes from).
 */
public final class EconomyCatalog {

	/** The content path; served by whichever {@link com.civstudio.data.WorldSource} is installed. */
	public static final String RESOURCE = "/balance/economies.json";

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
			.build();

	// built on first use and rebuilt if the active WorldSource changes — never captured at class-load
	// (see WorldSourceCache for the ordering hazard this avoids)
	private static final com.civstudio.data.WorldSourceCache<EconomyCatalog> CACHE =
			new com.civstudio.data.WorldSourceCache<>(EconomyCatalog::load);

	private final Map<Era, Map<Race, Era.Economy>> byEra;

	private EconomyCatalog(Map<Era, Map<Race, Era.Economy>> byEra) {
		this.byEra = byEra;
	}

	/** The shared catalog. */
	public static EconomyCatalog get() {
		return CACHE.get();
	}

	/** Whether any economy at all was authored (false on the absent-resource path). */
	public boolean isEmpty() {
		return byEra.isEmpty();
	}

	/**
	 * The authored economy for a cell, or {@code null} when the matrix says nothing about it (the
	 * caller then keeps the {@link Era} constant).
	 *
	 * <p>A race with no column of its own falls back to {@link Race#HUMAN}'s <em>within the
	 * matrix</em> — the same shape race takes everywhere else in the engine (its own name tables and
	 * life table where they exist, the human calendar and tech overlay where they do not; see
	 * {@code docs/race.md}). So authoring only a human column keeps every race on it, and authoring
	 * a dwarven one moves dwarves alone.
	 *
	 * @param era  the era to found in
	 * @param race the founding race; {@code null} is read as {@link Race#HUMAN}
	 * @return the authored economy, or {@code null} if this cell is unauthored
	 */
	public Era.Economy find(Era era, Race race) {
		Map<Race, Era.Economy> byRace = byEra.get(era);
		if (byRace == null)
			return null;
		Era.Economy own = byRace.get(race == null ? Race.HUMAN : race);
		return own != null ? own : byRace.get(Race.HUMAN);
	}

	/** Read the matrix from a source. Package-private and parameterised so a test can drive it. */
	static EconomyCatalog load(com.civstudio.data.WorldSource source) {
		Map<Era, Map<Race, Era.Economy>> byEra = new EnumMap<>(Era.class);
		try (InputStream in = source.open(RESOURCE)) {
			if (in == null)
				return new EconomyCatalog(byEra); // unauthored: the Era constants stand
			Map<String, Map<String, Era.Economy>> raw =
					MAPPER.readValue(in, new TypeReference<Map<String, Map<String, Era.Economy>>>() {
					});
			for (Map.Entry<String, Map<String, Era.Economy>> era : raw.entrySet()) {
				Map<Race, Era.Economy> byRace = new EnumMap<>(Race.class);
				for (Map.Entry<String, Era.Economy> race : era.getValue().entrySet())
					byRace.put(Race.valueOf(race.getKey()), race.getValue());
				byEra.put(Era.valueOf(era.getKey()), byRace);
			}
		} catch (IOException | RuntimeException e) {
			// LOUD, not lenient — see the class javadoc. An economy that silently reverts to the
			// compiled constants is a run that reports numbers it did not use.
			throw new IllegalStateException(
					"EconomyCatalog: " + RESOURCE + " is present but unreadable — refusing to fall"
							+ " back to the compiled constants, since that would silently change"
							+ " what the simulation runs on: " + e.getMessage(), e);
		}
		return new EconomyCatalog(byEra);
	}

	/**
	 * The canonical JSON for the matrix as currently compiled in — how the seed content for the
	 * content store is produced (and what a round-trip test checks against). Emits every calibrated
	 * era's human column, which is all that is authored today.
	 *
	 * @return the matrix as pretty-printed JSON
	 */
	public static String canonicalJson() {
		Map<String, Map<String, Era.Economy>> out = new java.util.LinkedHashMap<>();
		for (Era era : Era.values()) {
			Era.Economy econ = era.compiledEconomy();
			if (econ != null)
				out.put(era.name(), Map.of(Race.HUMAN.name(), econ));
		}
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
	}
}
