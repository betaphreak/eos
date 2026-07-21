package com.civstudio.tech;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.civstudio.calendar.LiturgicalCalendar;
import com.civstudio.geo.TerrainRegistry;
import com.civstudio.settlement.GameSession;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.civstudio.era.Era;

/**
 * The technology graph: the set of researchable {@link Tech} nodes and the
 * prerequisite edges between them, loaded once from {@code /techs.json} (a
 * Caveman2Cosmos tech graph). Like {@link TerrainRegistry} and
 * {@link LiturgicalCalendar} it is pure, immutable reference data —
 * independent of seed and location — so a single instance is shared by every colony
 * in a {@link GameSession} (which loads it lazily, on first use).
 * <p>
 * Loading does three things beyond parsing: it <b>drops out-of-scope techs</b> (any
 * whose era is past {@link Era#ATOMIC} — i.e. the source's Information
 * node — see {@link Era}), discards every Civ4-specific effect/asset field, and
 * <b>validates the graph</b> (fail-fast): every prerequisite must resolve to a tech
 * that is itself kept, or {@link #load()} throws. Nothing else in the model reads
 * this yet — Phase 1 is the data structure and its graph queries (see
 * {@code docs/tech-tree.md}).
 * <p>
 * The graph queries — {@link #preKnownThrough(Era)}, {@link #prereqsSatisfied(Tech,
 * Set)} and {@link #researchableFrontier(Set)} — are pure functions of a "known"
 * set; the per-colony research <em>state</em> that will drive them is a later phase.
 */
public final class TechTree {

	private static final String RESOURCE = "/techs.json";
	private static final String EFFECTS_RESOURCE = "/tech-effects.json";
	// the generated building-unlock overlay (BuildingInfoExporter, Phase 4): per kept tech, an
	// UNLOCK effect for each building it unlocks. Kept separate from the hand-authored
	// /tech-effects.json so regenerating it never clobbers hand-authored effects; the two overlays
	// are merged (effect lists concatenated per tech) at load. Absent → no building unlocks.
	private static final String BUILDING_UNLOCKS_RESOURCE = "/building-unlocks.json";
	// the generated unit-unlock overlay (UnitInfoExporter, docs/c2c-unit-import.md): per kept
	// tech, an UNLOCK effect for each UNIT_* it unlocks. Merged alongside the building overlay,
	// on the same footing, so researching a tech grants its unit tokens. Absent → no unit unlocks.
	private static final String UNIT_UNLOCKS_RESOURCE = "/unit-unlocks.json";

	// the highest era the tech tree models; techs beyond it (the Information node and any
	// later) are dropped at load. The scope is expressed here rather than by which Era
	// values exist, since Era is now the full ladder (see eos.era.Era).
	private static final Era MAX_TECH_ERA = Era.ATOMIC;

	/**
	 * The eos <b>tech cap</b>: the single tech the tree ends on. It sits one step past the
	 * modeled ceiling ({@link #MAX_TECH_ERA}) and is <b>always kept</b> in {@code techs.json} as
	 * the tree's visual end-cap — so the whole modeled tree converges on one ending node — while
	 * the engine still drops it at load (its era is past {@code MAX_TECH_ERA}). The single global
	 * home for the horizon tech: the tech exporter caps the tree here, and the building/improvement
	 * importers gate their data to the kept set this defines (see {@code
	 * com.civstudio.tech.export.TechInfoExporter}, {@code geo.export.ImprovementExporter}).
	 */
	public static final String CAP_TECH = "TECH_INFORMATION_LIFESTYLE";

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			// PrereqTech is a single string for most techs but an array for some;
			// accept the scalar form as a one-element array so both parse uniformly
			.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
			.build();

	// the kept techs, in source order
	private final List<Tech> techs;

	// fast lookup by type id; also the set of kept ids the validator checks against
	private final Map<String, Tech> byType;

	// eos-native effects per tech id, from the overlay (empty list when a tech has
	// none); see TechEffects and effectsOf
	private final Map<String, List<TechEffect>> effects;

	private TechTree(List<Tech> techs, Map<String, List<TechEffect>> effects) {
		this.techs = List.copyOf(techs);

		Map<String, Tech> byType = new LinkedHashMap<>();
		for (Tech t : this.techs)
			if (byType.put(t.type(), t) != null)
				throw new IllegalStateException(
						"duplicate tech type: " + t.type());
		this.byType = byType;

		// fail-fast: every prerequisite must resolve to a kept tech. Dropping the
		// out-of-scope (Industrial) node cannot dangle here — nothing depends on it —
		// but a future scope change that drops a depended-on era is caught immediately.
		for (Tech t : this.techs) {
			validatePrereqs(t, t.orPrereqs());
			validatePrereqs(t, t.andPrereqs());
		}

		// Drop overlay entries whose key isn't a kept tech: the building-/unit-unlock overlays are
		// machine-generated (the inverse of prereqTech) and can name a tech that survives the exporter's
		// horizon but is dropped here by the era filter — its unlock would apply to nothing. Committed
		// overlays name only kept techs, so nothing is dropped on the classpath (behavior-neutral); this
		// makes the tree tolerant of overlay/tree drift, e.g. the reconstructed unlock overlays the
		// Strapi world bundle serves. (tech-effects is a hand-authored stub today, so a typo there no-ops
		// rather than failing fast — an accepted trade for that robustness.)
		Map<String, List<TechEffect>> keptEffects = new LinkedHashMap<>();
		int droppedOverlay = 0;
		for (Map.Entry<String, List<TechEffect>> e : effects.entrySet()) {
			if (byType.containsKey(e.getKey()))
				keptEffects.put(e.getKey(), e.getValue());
			else
				droppedOverlay++;
		}
		if (droppedOverlay > 0)
			System.err.println("TechTree: dropped " + droppedOverlay
					+ " overlay entr(y/ies) naming non-kept techs");
		this.effects = Map.copyOf(keptEffects);
	}

	private void validatePrereqs(Tech t, List<String> prereqs) {
		for (String p : prereqs)
			if (!byType.containsKey(p))
				throw new IllegalStateException("tech " + t.type()
						+ " has an unresolved prerequisite: " + p);
	}

	/**
	 * Load the tech tree from its classpath resource ({@code /techs.json}), dropping
	 * out-of-scope techs and validating that every prerequisite resolves.
	 *
	 * @return the loaded tech tree
	 * @throws IllegalStateException
	 *             if the resource is missing, a tech has an unknown advisor, or a
	 *             prerequisite does not resolve to a kept tech
	 */
	public static TechTree load() {
		// merge the hand-authored overlay with the generated building- and unit-unlock overlays
		return loadWith(mergeEffects(mergeEffects(TechEffects.load(EFFECTS_RESOURCE),
				TechEffects.load(BUILDING_UNLOCKS_RESOURCE)),
				TechEffects.load(UNIT_UNLOCKS_RESOURCE)));
	}

	/**
	 * Load the tech tree over a single, <b>isolated</b> effect overlay — no
	 * building-unlock merge — so overlay-parsing tests can assert exact effect lists.
	 * For a race's tree (which does get the universal building unlocks) use
	 * {@link #loadWithRaceOverlay(String)}.
	 *
	 * @param effectsResource
	 *            the effect-overlay classpath resource
	 * @return the loaded tech tree with only that overlay
	 */
	public static TechTree load(String effectsResource) {
		return loadWith(TechEffects.load(effectsResource));
	}

	/**
	 * Load the tech tree under a race's effect overlay, merged with the universal
	 * building-unlock overlay ({@code /building-unlocks.json}). Buildings are
	 * race-independent content, so their {@link TechEffect.Unlock}s apply to every race;
	 * only the race's own effects overlay differs. Mirrors the default {@link #load()}
	 * (which merges building unlocks onto the hand-authored {@code /tech-effects.json}).
	 *
	 * @param raceOverlayResource
	 *            the race's effect-overlay classpath resource (e.g.
	 *            {@code "/tech-effects-harimari.json"})
	 * @return the loaded tech tree with that race overlay plus the building unlocks
	 */
	public static TechTree loadWithRaceOverlay(String raceOverlayResource) {
		return loadWith(mergeEffects(mergeEffects(TechEffects.load(raceOverlayResource),
				TechEffects.load(BUILDING_UNLOCKS_RESOURCE)),
				TechEffects.load(UNIT_UNLOCKS_RESOURCE)));
	}

	// merge two effect overlays, concatenating the effect lists of any tech present in both
	private static Map<String, List<TechEffect>> mergeEffects(
			Map<String, List<TechEffect>> a, Map<String, List<TechEffect>> b) {
		Map<String, List<TechEffect>> merged = new LinkedHashMap<>();
		a.forEach((k, v) -> merged.put(k, new ArrayList<>(v)));
		b.forEach((k, v) -> merged.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
		return merged;
	}

	// parse the tech graph and build the tree over an already-resolved effect overlay
	private static TechTree loadWith(Map<String, List<TechEffect>> effects) {
		try (InputStream in = com.civstudio.data.WorldSources.current().open(RESOURCE)) {
			if (in == null)
				throw new IllegalStateException(
						"Tech tree resource not found: " + RESOURCE);
			List<Row> rows = MAPPER.readValue(in,
					new TypeReference<List<Row>>() {
					});
			List<Tech> kept = new ArrayList<>();
			for (Row r : rows) {
				Optional<Era> era = Era.fromTechKey(r.era());
				if (era.isEmpty() || !era.get().isAtOrBefore(MAX_TECH_ERA))
					continue; // unknown era, or past the modeled ceiling (e.g. Industrial)
				Advisor advisor = Advisor.fromKey(r.advisor())
						.orElseThrow(() -> new IllegalStateException(
								"tech " + r.type() + " has an unknown advisor: "
										+ r.advisor()));
				kept.add(new Tech(r.type(), era.get(), advisor,
						Integer.parseInt(r.cost()), prereqList(r.orPreReqs()),
						prereqList(r.andPreReqs())));
			}
			return new TechTree(kept, effects);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Failed to load tech tree resource: " + RESOURCE, e);
		}
	}

	// flatten a prereq group to its tech-id list (empty when the group or its list
	// is absent)
	private static List<String> prereqList(PrereqGroup group) {
		if (group == null || group.prereqTech() == null)
			return List.of();
		return group.prereqTech();
	}

	/**
	 * The tech with the given id, or {@code null} if no such (in-scope) tech exists.
	 *
	 * @param type
	 *            the tech id (e.g. {@code "TECH_MERCANTILISM"})
	 * @return the tech, or {@code null}
	 */
	public Tech get(String type) {
		return byType.get(type);
	}

	/**
	 * All kept techs, in source order. The returned list is unmodifiable.
	 *
	 * @return the techs
	 */
	public List<Tech> getAll() {
		return techs;
	}

	/** @return the number of kept techs */
	public int size() {
		return techs.size();
	}

	/**
	 * The eos-native effects a tech grants when researched, from the overlay — an
	 * empty list if the tech has no authored effects (the common case while the
	 * overlay is unpopulated).
	 *
	 * @param type
	 *            the tech id
	 * @return the tech's effects (never {@code null}; empty if none)
	 */
	public List<TechEffect> effectsOf(String type) {
		return effects.getOrDefault(type, List.of());
	}

	/**
	 * The ids of every tech in {@code era} or earlier (chronological order). This is
	 * the colony's <b>pre-known set</b> for a start at the given era: a colony that
	 * begins Medieval-complete knows {@code preKnownThrough(Era.MEDIEVAL)} for free
	 * and researches only what lies beyond it.
	 *
	 * @param era
	 *            the latest era known at the start
	 * @return the ids of all techs at or before that era (a fresh mutable set)
	 */
	public Set<String> preKnownThrough(Era era) {
		Set<String> known = new LinkedHashSet<>();
		for (Tech t : techs)
			if (t.era().isAtOrBefore(era))
				known.add(t.type());
		return known;
	}

	/**
	 * Whether {@code tech}'s prerequisites are satisfied given the set of {@code
	 * known} tech ids: <b>all</b> of its {@linkplain Tech#andPrereqs() AND-prereqs}
	 * are known <b>and</b> (it has no {@linkplain Tech#orPrereqs() OR-prereqs} or at
	 * least one is known). The tree's root tech, which has neither, is trivially
	 * satisfied.
	 *
	 * @param tech
	 *            the tech to test
	 * @param known
	 *            the ids of techs already known
	 * @return true if the tech can be researched next
	 */
	public boolean prereqsSatisfied(Tech tech, Set<String> known) {
		for (String p : tech.andPrereqs())
			if (!known.contains(p))
				return false;
		if (tech.orPrereqs().isEmpty())
			return true;
		for (String p : tech.orPrereqs())
			if (known.contains(p))
				return true;
		return false;
	}

	/**
	 * The <b>research frontier</b> for a given known set: every tech not yet known
	 * whose {@linkplain #prereqsSatisfied(Tech, Set) prerequisites are satisfied} —
	 * i.e. the techs a colony with that knowledge could research next.
	 *
	 * @param known
	 *            the ids of techs already known
	 * @return the researchable techs (a fresh set, in source order)
	 */
	public Set<Tech> researchableFrontier(Set<String> known) {
		Set<Tech> frontier = new LinkedHashSet<>();
		for (Tech t : techs)
			if (!known.contains(t.type()) && prereqsSatisfied(t, known))
				frontier.add(t);
		return frontier;
	}

	// --- JSON binding DTOs (the raw techs.json shape; mapped to Tech at load) ---

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Row(@JsonProperty("Type") String type,
			@JsonProperty("Era") String era,
			@JsonProperty("Advisor") String advisor,
			@JsonProperty("iCost") String cost,
			@JsonProperty("OrPreReqs") PrereqGroup orPreReqs,
			@JsonProperty("AndPreReqs") PrereqGroup andPreReqs) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record PrereqGroup(@JsonProperty("PrereqTech") List<String> prereqTech) {
	}
}
