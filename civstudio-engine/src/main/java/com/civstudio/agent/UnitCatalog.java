package com.civstudio.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The in-memory index of the imported C2C unit catalog ({@code generated/units.json}) — the
 * engine-side reader of what {@code UnitInfoExporter} baked, so a {@link MarchingCaravan} can
 * pick and <em>embody</em> a {@link UnitInfo unit} for its role. Loaded once from the classpath
 * (the {@code TechTree}/{@code TechEffects} resource-load pattern) and shared, immutable.
 * <p>
 * The load is <b>lenient</b>: a missing/broken {@code /units.json} yields an empty catalog (a
 * warning, not a crash) — embodiment is an optional identity overlay, so a band simply keeps its
 * default identity rather than the whole caravan system failing. See {@code docs/c2c-unit-import.md}.
 */
public final class UnitCatalog {

	private static final String RESOURCE = "/units.json";
	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
			.build();

	private static final UnitCatalog INSTANCE = load();

	// units grouped by role, each list in a stable (document) order
	private final Map<CaravanRole, List<UnitInfo>> byRole;

	private UnitCatalog(Map<CaravanRole, List<UnitInfo>> byRole) {
		this.byRole = byRole;
	}

	/** The shared catalog. */
	public static UnitCatalog get() {
		return INSTANCE;
	}

	/**
	 * The catalog's units of a role, in document order (empty if none). An unmodifiable view — the
	 * catalog is immutable.
	 *
	 * @param role the caravan role
	 * @return the role's units
	 */
	public List<UnitInfo> forRole(CaravanRole role) {
		return byRole.getOrDefault(role, List.of());
	}

	private static UnitCatalog load() {
		Map<CaravanRole, List<UnitInfo>> byRole = new EnumMap<>(CaravanRole.class);
		try (InputStream in = com.civstudio.data.WorldSources.current().open(RESOURCE)) {
			if (in == null) {
				System.err.println("UnitCatalog: " + RESOURCE + " not on classpath — empty catalog");
				return new UnitCatalog(byRole);
			}
			List<UnitInfo> rows = MAPPER.readValue(in, new TypeReference<List<UnitInfo>>() {
			});
			for (UnitInfo u : rows)
				if (u.role() != null)
					byRole.computeIfAbsent(u.role(), r -> new java.util.ArrayList<>()).add(u);
		} catch (IOException | RuntimeException e) {
			// lenient: embodiment is cosmetic, so a bad resource must not break caravans
			System.err.println("UnitCatalog: failed to load " + RESOURCE + " — " + e.getMessage());
			return new UnitCatalog(new EnumMap<>(CaravanRole.class));
		}
		return new UnitCatalog(byRole);
	}

	/**
	 * The <b>best available</b> unit a colony can currently field for a role: among the role's
	 * units that are <b>unlocked</b> (their {@code UNIT_*} token is in {@code grantedTokens}) and
	 * <b>not obsolete</b> (their {@code obsoleteTech} is not among {@code knownTechs}), the most
	 * advanced — the interim stand-in for a player/AI build choice (decision 9). "Most advanced" is
	 * ordered by build cost ({@code iCost}) as the era proxy, with the id as a deterministic
	 * tie-break; no RNG. Returns {@code null} when the colony can field no unit of the role yet (the
	 * band then keeps its default identity).
	 *
	 * @param role          the caravan role to field
	 * @param grantedTokens the colony's granted tech tokens ({@link Settlement#getGrantedTechTokens})
	 * @param knownTechs    the colony's known tech ids (for the obsolescence check), never {@code null}
	 * @return the best available unit, or {@code null}
	 */
	public UnitInfo pickBest(CaravanRole role, Set<String> grantedTokens, Set<String> knownTechs) {
		UnitInfo best = null;
		for (UnitInfo u : byRole.getOrDefault(role, List.of())) {
			if (!grantedTokens.contains(u.id()))
				continue; // not unlocked
			if (u.obsoleteTech() != null && knownTechs.contains(u.obsoleteTech()))
				continue; // obsoleted by a researched successor tech
			if (best == null || moreAdvanced(u, best))
				best = u;
		}
		return best;
	}

	// higher build cost ranks as more advanced (the interim era proxy); id breaks ties deterministically
	private static boolean moreAdvanced(UnitInfo a, UnitInfo b) {
		int ac = a.iCost() == null ? -1 : a.iCost();
		int bc = b.iCost() == null ? -1 : b.iCost();
		if (ac != bc)
			return ac > bc;
		return a.id().compareTo(b.id()) > 0;
	}
}
