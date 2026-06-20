package com.civstudio.agent;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import com.civstudio.agent.noble.Noble;
import com.civstudio.agent.ruler.Ruler;
import com.civstudio.name.Gender;

/**
 * The rank of an entity in the realm's social and political hierarchy — the scope
 * of what it commands, from a single household up through a caravan, a holding, a
 * settlement and on to a continent-spanning hegemony. The ladder maps onto this
 * model's existing concepts at its lower rungs ({@link #HOUSEHOLD} a family,
 * {@link #HOLDING} the firms/banks a {@link Noble noble} owns,
 * {@link #VILLAGE} a settlement a {@link Ruler ruler} leads); the
 * higher rungs ({@link #CITY} through {@link #HEGEMONY}) are larger polities the
 * model reserves a place for.
 * <p>
 * The ranks <b>alternate</b> between two structural kinds, captured by
 * {@link #isPlural()}: the even {@link #level() levels} are <em>singular</em>,
 * consolidated entities (a {@link #HOLDING}, a {@link #CITY}, a {@link #DUCHY}…),
 * and the odd levels are <em>plural</em> collectives of the rank just below it (a
 * {@link #CARAVAN} of households, a {@link #VILLAGE} of holdings, a {@link #LEAGUE}
 * of cities…). So going up two rungs is "gather peers into a collective, then
 * consolidate that collective into a single larger entity".
 * <p>
 * Beyond its {@link #level()} (its identity and sort order) and {@link #displayName()},
 * each rank carries the typed styling and diplomacy vocabulary the future
 * diplomacy/warfare system will read (nothing consumes them yet):
 * <ul>
 * <li>a {@link TitleSet} in each of the three {@link TitleMode registers} —
 *     administrative (legitimate), military (rebel) and diplomatic (envoy) — each
 *     gendered (see {@link #title(TitleMode, Gender)});</li>
 * <li>a {@link CasusBelli} per {@link Relation} (a war pretext against an equal, a
 *     lesser, or a higher polity), where defined (see {@link #casusBelli(Relation)}).</li>
 * </ul>
 * The data is English-only for now; localizing it (the {@code locale} dimension of
 * the source prototype) is future work.
 */
public enum Rank {

	/** A single household — the foundational unit. */
	HOUSEHOLD(0, false, "Household",
			new TitleSet("Player", "Player",
					"The foundational unit of administration..."),
			new TitleSet("Exile", "Exile", "Cast out from the family unit..."),
			new TitleSet("Emissary", "Emissary",
					"Negotiates personal contracts..."),
			new CasusBelli("Blood Feud", "A violently personal grievance..."),
			null,
			new CasusBelli("Desertion",
					"A household breaking their contract to violently depart or overthrow the caravan captain.")),

	/** A structured collective of households — a wandering band. */
	CARAVAN(1, true, "Caravan",
			new TitleSet("Captain", "Captain",
					"A structured collective of households..."),
			new TitleSet("Mutineer", "Mutineer",
					"A captain turning against their own..."),
			new TitleSet("Commander", "Commander",
					"Leverages collective strength..."),
			new CasusBelli("Breach of Contract",
					"A retaliatory strike for unpaid wages..."),
			new CasusBelli("Press Gang",
					"Forcing an independent household into servitude."),
			new CasusBelli("Mutiny",
					"An armed mercenary caravan seizing the physical holding they were hired to protect.")),

	/** A centralized physical asset — what a {@link Noble noble} owns. */
	HOLDING(2, false, "Holding",
			new TitleSet("Builder", "Builder",
					"A centralized physical asset..."),
			new TitleSet("Squatter", "Squatter",
					"Unlawfully seizing and defending..."),
			new TitleSet("Steward", "Steward",
					"Handles immediate territorial borders..."),
			new CasusBelli("Land Dispute",
					"A localized conflict to violently adjust..."),
			new CasusBelli("Eviction", "Clearing out an armed caravan..."),
			new CasusBelli("Rural Secession",
					"A single holding violently rejecting the village council authority.")),

	/** An interconnected network of holdings — what a {@link Ruler ruler} leads. */
	VILLAGE(3, true, "Village",
			new TitleSet("Leader", "Leader",
					"An interconnected network of holdings..."),
			new TitleSet("Outlaw", "Outlaw",
					"Operating outside the local community laws..."),
			new TitleSet("Elder", "Elder", "Represents communal interests..."),
			new CasusBelli("Resource War",
					"A communal clash over shared survival assets..."),
			new CasusBelli("Communal Annexation",
					"Forcing an independent holding to submit..."),
			new CasusBelli("Peasant Revolt",
					"A coalition of rural villages rising up to throw off oppressive urban taxation.")),

	/** A complex urban center. */
	CITY(4, false, "City",
			new TitleSet("Mayor", "Mayoress",
					"A complex urban center demanding..."),
			new TitleSet("Demagogue", "Demagogue", "Riling up the urban mob..."),
			new TitleSet("Magistrate", "Magistrate",
					"Projects economic influence..."),
			new CasusBelli("Commercial Monopoly",
					"A war to crush economic rivals..."),
			new CasusBelli("Urban Expansion",
					"A city extending its jurisdiction..."),
			new CasusBelli("League Defection",
					"A free city breaking syndicate treaties to exit the economic bloc.")),

	/** A cooperative administrative bloc of cities. */
	LEAGUE(5, true, "League",
			new TitleSet("Legate", "Legate",
					"A cooperative administrative bloc..."),
			new TitleSet("Dissident", "Dissident",
					"Breaking the allied pact of cities..."),
			new TitleSet("Consul", "Consul",
					"Negotiates as a unified economic bloc..."),
			new CasusBelli("Tariff Enforcement",
					"A punitive campaign against cities..."),
			new CasusBelli("Syndicate Coercion",
					"An economic league forcing a free city..."),
			new CasusBelli("Burghers' Rebellion",
					"A powerful trade league taking up arms to throw off feudal lordship.")),

	/** A formalized feudal fiefdom. */
	BARONY(6, false, "Barony",
			new TitleSet("Baron", "Baroness",
					"A formalized feudal fiefdom..."),
			new TitleSet("Robber Baron", "Robber Baroness",
					"A rogue feudal lord..."),
			new TitleSet("Lord", "Lady", "Engages in formal feudal oaths..."),
			new CasusBelli("De Jure Claim", "A formal feudal declaration..."),
			new CasusBelli("Revoke Charters",
					"A feudal lord crushing a free trade league..."),
			new CasusBelli("Factional Betrayal",
					"A single baron turning against their local political bloc to dismantle the Viscount authority.")),

	/** A regional administrative hub. */
	VISCOUNTY(7, true, "Viscounty",
			new TitleSet("Viscount", "Viscountess",
					"A regional administrative hub..."),
			new TitleSet("Factioneer", "Factioneer",
					"Scheming to tear apart or usurp..."),
			new TitleSet("Arbiter", "Arbiter", "Brokers regional power blocs..."),
			new CasusBelli("Factional Intervention",
					"A justified military action to step into..."),
			new CasusBelli("Factional Coercion",
					"An alliance of minor lords forcing a stubbornly independent baron..."),
			new CasusBelli("Vassal Uprising",
					"An alliance of lesser lords uniting to overthrow their centralized Count.")),

	/** A major regional power center. */
	COUNTY(8, false, "County",
			new TitleSet("Count", "Countess",
					"A major regional power center..."),
			new TitleSet("Defector", "Defector",
					"A Count turning their regional power..."),
			new TitleSet("Peer", "Peer", "Maintains a formal court..."),
			new CasusBelli("Dynastic Dispute",
					"A large-scale regional war sparked by..."),
			new CasusBelli("Dissolve Faction",
					"A Count shattering a lordly alliance..."),
			new CasusBelli("Refuse Levies",
					"An inner county refusing border taxation and military drafts from the March.")),

	/** A heavily fortified border territory. */
	MARCH(9, true, "March",
			new TitleSet("Margrave", "Margravine",
					"A heavily fortified border territory..."),
			new TitleSet("Warlord", "Warlord", "A border-lord going rogue..."),
			new TitleSet("Warden", "Warden",
					"Acts as a militarized buffer state..."),
			new CasusBelli("Frontier Pacification",
					"A preemptive or retaliatory military campaign..."),
			new CasusBelli("Border Annexation",
					"A militarized march forcing a peaceful inner county..."),
			new CasusBelli("Marcher Treason",
					"A militarized border estate turning its veteran armies inward to depose the Duke.")),

	/** A grand territory. */
	DUCHY(10, false, "Duchy",
			new TitleSet("Duke", "Duchess",
					"A grand territory characterized by..."),
			new TitleSet("Usurper", "Usurper",
					"Illegally seizing a massive territory..."),
			new TitleSet("Sovereign", "Sovereign",
					"Wields grand sovereign-adjacent power..."),
			new CasusBelli("Ducal Conquest",
					"A massive territorial war aimed at entirely subjugating..."),
			new CasusBelli("Revoke March",
					"A Duke stripping a border march of its military autonomy..."),
			new CasusBelli("Defy Sovereign",
					"A grand Duke rejecting the high council vote and violently seceding from the Principate.")),

	/** A high-level political coalition. */
	PRINCIPATE(11, true, "Principate",
			new TitleSet("Prince", "Princess",
					"A high-level political coalition..."),
			new TitleSet("Antiprince", "Antiprincess",
					"Setting up a rival court to fracture..."),
			new TitleSet("Elector", "Elector",
					"A high council of lords collectively wielding..."),
			new CasusBelli("Council Mandate",
					"A legally sanctioned war voted upon by a high council..."),
			new CasusBelli("Coalition Ultimatum",
					"A high council of lords forcing an independent Duke..."),
			new CasusBelli("Aristocratic Revolt",
					"A massive coalition of high nobility declaring war to depose the monarch.")),

	/** A fully sovereign nation. */
	KINGDOM(12, false, "Kingdom",
			new TitleSet("King", "Queen",
					"A fully sovereign nation operating a supreme bureaucratic apparatus..."),
			new TitleSet("False King", "False Queen",
					"Claiming the sovereign crown illegitimately..."),
			new TitleSet("Monarch", "Monarch",
					"Conducts full international diplomacy..."),
			new CasusBelli("War of Succession",
					"A sovereign-level conflict to claim a recognized crown..."),
			new CasusBelli("Crown Centralization",
					"A monarch shattering a powerful ducal coalition..."),
			new CasusBelli("Nullify Treaty",
					"A sovereign nation violently breaking continental alliances to assert complete independence.")),

	/** A supranational administrative body. */
	FEDERATION(13, true, "Federation",
			new TitleSet("High King", "High Queen",
					"A supranational administrative body dedicated to standardizing..."),
			new TitleSet("Oathbreaker", "Oathbreaker",
					"Shattering the high treaty between kingdoms..."),
			new TitleSet("Chancellor", "Chancellor",
					"A supranational alliance coordinating continental treaties..."),
			new CasusBelli("Enforce Treaty",
					"A supranational declaration to force a sovereign kingdom..."),
			new CasusBelli("Federal Intervention",
					"A supranational alliance using military force to compel a kingdom..."),
			new CasusBelli("Coalition War",
					"A massive alliance of kingdoms banding together to throw off the yoke of an Empire.")),

	/** A massive, consolidated superpower. */
	EMPIRE(14, false, "Empire",
			new TitleSet("Emperor", "Empress",
					"A massive, consolidated superpower employing an immense bureaucratic machine..."),
			new TitleSet("Shadow Emperor", "Shadow Empress",
					"A rival claiming continental dominance..."),
			new TitleSet("Autocrat", "Autocrat",
					"A supreme superpower demanding tribute..."),
			new CasusBelli("Imperial Reconquest",
					"An absolute declaration of war driven by manifest destiny..."),
			new CasusBelli("Dissolve Federation",
					"An Emperor crushing a coalition of allied kings..."),
			new CasusBelli("Hegemonic Challenge",
					"An ambitious Empire launching a world war to dismantle the ultimate global authority.")),

	/** A continent-spanning hegemony — the highest rank. */
	HEGEMONY(15, true, "Hegemony",
			new TitleSet("Hegemon", "Hegemon",
					"The absolute apex of administration..."),
			new TitleSet("Anarch", "Anarch",
					"One who seeks to completely dismantle the established world order..."),
			new TitleSet("Suzerain", "Suzerain",
					"The ultimate diplomatic authority, dictating universal law..."),
			new CasusBelli("World War",
					"The ultimate sanction, a universal war to completely neutralize an entity..."),
			new CasusBelli("Hegemonic Compliance",
					"The world order forcing a rogue, independent empire to submit..."),
			null);

	// the rank's level — its identity and position in the hierarchy (lower is
	// humbler). Currently equal to ordinal(), but kept as its own field so the
	// declaration order can change later without shifting each rank's level.
	private final int level;
	// whether this rank is a plural collective of the rank below (odd levels) vs. a
	// single consolidated entity (even levels) — see the class note.
	private final boolean isPlural;
	private final String displayName;
	private final Map<TitleMode, TitleSet> titles;
	private final Map<Relation, CasusBelli> casusBelli;

	Rank(int level, boolean isPlural, String displayName, TitleSet administrative,
			TitleSet military, TitleSet diplomatic, CasusBelli vsEqual,
			CasusBelli vsLower, CasusBelli vsHigher) {
		this.level = level;
		this.isPlural = isPlural;
		this.displayName = displayName;

		Map<TitleMode, TitleSet> t = new EnumMap<>(TitleMode.class);
		t.put(TitleMode.ADMINISTRATIVE, administrative);
		t.put(TitleMode.MILITARY, military);
		t.put(TitleMode.DIPLOMATIC, diplomatic);
		this.titles = t;

		// a null pretext means "no casus belli for that relation" — the bottom rank
		// can wage none against a lower, the top none against a higher
		Map<Relation, CasusBelli> cb = new EnumMap<>(Relation.class);
		if (vsEqual != null)
			cb.put(Relation.EQUAL, vsEqual);
		if (vsLower != null)
			cb.put(Relation.LOWER, vsLower);
		if (vsHigher != null)
			cb.put(Relation.HIGHER, vsHigher);
		this.casusBelli = cb;
	}

	// ranks indexed by level, for the adjacency walk. level == ordinal() today, so the
	// declaration order is the hierarchy order; kept as its own array so that, if a
	// level is ever renumbered out of declaration order, only this initializer changes.
	private static final Rank[] BY_LEVEL = values();

	/**
	 * The next rank up the hierarchy (one higher {@link #level()}), or
	 * {@link Optional#empty()} at the top ({@link #HEGEMONY}). One step of a
	 * promotion up the ladder.
	 *
	 * @return the adjacent higher rank, or empty if this is the highest
	 */
	public Optional<Rank> promoted() {
		return level + 1 < BY_LEVEL.length ? Optional.of(BY_LEVEL[level + 1])
				: Optional.empty();
	}

	/**
	 * The next rank down the hierarchy (one lower {@link #level()}), or
	 * {@link Optional#empty()} at the bottom ({@link #HOUSEHOLD}). One step of a
	 * demotion down the ladder.
	 *
	 * @return the adjacent lower rank, or empty if this is the lowest
	 */
	public Optional<Rank> demoted() {
		return level > 0 ? Optional.of(BY_LEVEL[level - 1]) : Optional.empty();
	}

	/**
	 * This rank's level — its identity and its position in the hierarchy (lower is
	 * humbler), independent of declaration order.
	 *
	 * @return the rank's level
	 */
	public int level() {
		return level;
	}

	/**
	 * Whether this rank is a <em>plural</em> collective of the rank just below it
	 * (the odd levels — a {@link #CARAVAN}, {@link #VILLAGE}, {@link #LEAGUE}…) as
	 * opposed to a single, consolidated entity (the even levels — a {@link #HOLDING},
	 * {@link #CITY}, {@link #DUCHY}…). See the class note.
	 *
	 * @return {@code true} if this rank is a collective of the rank below
	 */
	public boolean isPlural() {
		return isPlural;
	}

	/**
	 * This rank's display name (e.g. {@code "Viscounty"}).
	 *
	 * @return the rank's name
	 */
	public String displayName() {
		return displayName;
	}

	/**
	 * The {@link TitleSet} this rank's holder bears in the given {@link TitleMode
	 * register} (administrative, military or diplomatic) — both gendered titles and
	 * the role's description.
	 *
	 * @param mode
	 *            the register
	 * @return the title set for that register (never {@code null})
	 */
	public TitleSet titles(TitleMode mode) {
		return titles.get(mode);
	}

	/**
	 * The title this rank's holder bears in the given register and gender — a
	 * convenience over {@link #titles(TitleMode)} and {@link TitleSet#forGender}.
	 *
	 * @param mode
	 *            the register (administrative, military, diplomatic)
	 * @param gender
	 *            the holder's gender
	 * @return the gender-appropriate title in that register
	 */
	public String title(TitleMode mode, Gender gender) {
		return titles.get(mode).forGender(gender);
	}

	/**
	 * The {@link CasusBelli war pretext} this rank may invoke against a target of the
	 * given {@link Relation relative standing}, or {@link Optional#empty()} if it has
	 * none for that relation (the bottom rank has no {@link Relation#LOWER}, the top
	 * no {@link Relation#HIGHER}).
	 *
	 * @param relation
	 *            the target's standing relative to this rank
	 * @return the available casus belli, or empty
	 */
	public Optional<CasusBelli> casusBelli(Relation relation) {
		return Optional.ofNullable(casusBelli.get(relation));
	}
}
