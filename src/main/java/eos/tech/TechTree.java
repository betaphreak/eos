package eos.tech;

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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import eos.era.Era;

/**
 * The technology graph: the set of researchable {@link Tech} nodes and the
 * prerequisite edges between them, loaded once from {@code /techs.json} (a
 * Caveman2Cosmos tech graph). Like {@link eos.settlement.SlotTable} and
 * {@link eos.calendar.LiturgicalCalendar} it is pure, immutable reference data —
 * independent of seed and location — so a single instance is shared by every colony
 * in a {@link eos.settlement.GameSession} (which loads it lazily, on first use).
 * <p>
 * Loading does three things beyond parsing: it <b>drops out-of-scope techs</b> (any
 * whose era is past the {@link Era#RENAISSANCE} — i.e. the source's lone Industrial
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

	// the highest era the tech tree models; techs beyond it (the lone Industrial node
	// and any later) are dropped at load. The scope is expressed here rather than by
	// which Era values exist, since Era is now the full ladder (see eos.era.Era).
	private static final Era MAX_TECH_ERA = Era.RENAISSANCE;

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			// PrereqTech is a single string for most techs but an array for some;
			// accept the scalar form as a one-element array so both parse uniformly
			.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

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

		// fail-fast: every overlay key must name a kept tech (so an effect authored
		// for a mistyped or out-of-scope id surfaces immediately rather than silently
		// applying to nothing)
		for (String type : effects.keySet())
			if (!byType.containsKey(type))
				throw new IllegalStateException(
						"tech-effect overlay names an unknown tech: " + type);
		this.effects = Map.copyOf(effects);
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
		return load(EFFECTS_RESOURCE);
	}

	/**
	 * Load the tech tree with a specific effect overlay resource (instead of the
	 * shipped {@code /tech-effects.json}). Package-private — used by tests to load a
	 * tree whose techs carry effects, since the shipped overlay is empty.
	 *
	 * @param effectsResource
	 *            the effect-overlay classpath resource
	 * @return the loaded tech tree with that overlay
	 */
	static TechTree load(String effectsResource) {
		try (InputStream in = TechTree.class.getResourceAsStream(RESOURCE)) {
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
			return new TechTree(kept, TechEffects.load(effectsResource));
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
