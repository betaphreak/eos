package com.civstudio.agent;

import java.util.Map;

import com.civstudio.agent.march.MarchFlavor;
import com.civstudio.skill.Skill;

/**
 * The <b>role</b> of a {@link Caravan} — what a band is <em>for</em>, mirroring a
 * Caveman2Cosmos land unit's {@code <DefaultUnitAI>} family (the discriminator C2C uses to
 * pick a unit's behaviour). Each role fields a march {@linkplain #marchFlavor() column},
 * carries a {@linkplain #signatureSkill() signature skill} that governs a band's
 * effectiveness in the role (and that acting in the role trains), and is realized by a
 * concrete {@link MarchingCaravan} subclass whose {@link
 * MarchingCaravan#arrive(java.time.LocalDate, com.civstudio.util.Rng) arrival mission}
 * carries out the role.
 * <p>
 * Nine roles, aligned with the imported C2C unit set (see {@code docs/c2c-unit-import.md}).
 * The four original land roles map onto the C2C {@code UnitAI} taxonomy:
 * <ul>
 * <li>{@link #SETTLER} — {@code UNITAI_SETTLE} (e.g. {@code UNIT_BAND}, {@code UNIT_TRIBE},
 * {@code UNIT_SETTLER}); founds a colony ({@code MISSION_FOUND}).</li>
 * <li>{@link #WORKER} — {@code UNITAI_WORKER} (e.g. {@code UNIT_WORKER}); builds map
 * infrastructure — routes and tile improvements ({@code MISSION_BUILD} → {@code
 * CIV4BuildInfos.xml}).</li>
 * <li>{@link #EXPLORER} — {@code UNITAI_EXPLORE} (e.g. {@code UNIT_SCOUT},
 * {@code UNIT_EXPLORER}); scouts the map and identifies resources.</li>
 * <li>{@link #MILITARY} — the combat AIs ({@code UNITAI_ATTACK}, {@code UNITAI_ATTACK_CITY},
 * {@code UNITAI_CITY_DEFENSE}, …); moves to project force.</li>
 * </ul>
 * The five later roles ({@link #TRADE}, {@link #MISSIONARY}, {@link #HUNTER},
 * {@link #HEALER}, {@link #COVERT}) exist as <b>catalog roles</b> — an imported unit takes
 * one, and it shows in the tech tree — but only {@code TRADE} is forecast a band class
 * ({@code docs/caravan-trade.md}); the rest muster to a no-op until a mission is designed.
 * The C2C sea/air variants ({@code *_SEA}/{@code *_AIR}) are out of scope (this model is
 * land-only).
 * <p>
 * The {@code <Combat>} (UnitCombat class) &rarr; {@code CaravanRole} fold, with
 * {@code <DefaultUnitAI>} as the fallback, is {@link #fromUnit(String, String)}; the
 * {@code <Combat>} &rarr; signature {@link Skill} fold is {@link #signatureSkillOf(String,
 * String)}. Both are committed static tables — the analogue of the buildings'
 * {@code Advisor.fromKey} reuse — shared by {@code UnitInfoExporter} (to stamp each unit's
 * {@code caravanRole}) and any engine consumer.
 */
public enum CaravanRole {

	/** Found a new colony (C2C {@code UNITAI_SETTLE}); trains {@link Skill#STEWARDSHIP}. */
	SETTLER(MarchFlavor.SETTLER, Skill.STEWARDSHIP),
	/** Build routes and improvements (C2C {@code UNITAI_WORKER}); trains {@link Skill#CONSTRUCTION}. */
	WORKER(MarchFlavor.SETTLER, Skill.CONSTRUCTION),
	/** Scout, forage and subsist (C2C {@code UNITAI_EXPLORE}); trains {@link Skill#SURVIVAL}. */
	EXPLORER(MarchFlavor.SETTLER, Skill.SURVIVAL),
	/** Project force (C2C combat AIs — {@code UNITAI_ATTACK}, …); trains {@link Skill#WARFARE}. */
	MILITARY(MarchFlavor.MILITARY, Skill.WARFARE),
	/** Carry trade (C2C {@code UNITAI_MERCHANT}); trains {@link Skill#COMMERCE}. */
	TRADE(MarchFlavor.SETTLER, Skill.COMMERCE),
	/** Proselytize (C2C {@code UNITAI_MISSIONARY}/{@code _PROPHET}); trains {@link Skill#FAITH}. */
	MISSIONARY(MarchFlavor.SETTLER, Skill.FAITH),
	/** Hunt (C2C {@code UNITAI_HUNTER}/{@code _GREAT_HUNTER}); trains {@link Skill#HUNTING}. */
	HUNTER(MarchFlavor.SETTLER, Skill.HUNTING),
	/** Heal (C2C {@code UNITAI_HEALER}); trains {@link Skill#MEDICINE}. */
	HEALER(MarchFlavor.SETTLER, Skill.MEDICINE),
	/** Spy / crime↔order (C2C {@code UNITAI_SPY}/{@code _INFILTRATOR}); trains {@link Skill#SUBTERFUGE}. */
	COVERT(MarchFlavor.SETTLER, Skill.SUBTERFUGE);

	private final MarchFlavor marchFlavor;
	private final Skill signatureSkill;

	CaravanRole(MarchFlavor marchFlavor, Skill signatureSkill) {
		this.marchFlavor = marchFlavor;
		this.signatureSkill = signatureSkill;
	}

	/**
	 * The band's <b>order of march</b> for this role — a lean admin column
	 * ({@link MarchFlavor#SETTLER}) for the civilian roles, the full order
	 * ({@link MarchFlavor#MILITARY}) for a military band (see {@code
	 * docs/caravan-march.md} §5).
	 *
	 * @return the march flavor this role fields
	 */
	public MarchFlavor marchFlavor() {
		return marchFlavor;
	}

	/**
	 * The {@link Skill} that governs a band's effectiveness in this role and that acting in
	 * the role trains (the on-the-job-training seam). One of the nine role-signature skills
	 * (the three non-role skills — {@link Skill#INTELLECTUAL}, {@link Skill#SOCIAL},
	 * {@link Skill#PRODUCTION} — belong to no role).
	 *
	 * @return this role's signature skill
	 */
	public Skill signatureSkill() {
		return signatureSkill;
	}

	// The <Combat> (UnitCombat class, sans the UNITCOMBAT_ prefix) -> role fold. Only the
	// role-bearing classes appear; the non-role classes (EXECUTIVE/PRODIGY/ENTERTAINER, whose
	// signature skill is a non-role skill) are deliberately omitted so their units resolve by
	// <DefaultUnitAI> instead (e.g. a corp EXECUTIVE is UNITAI_MERCHANT -> TRADE). Military
	// variants beyond the classical horizon (ROBOT/HITECH/…) are listed too so any that slip
	// past the tech gate still fold correctly.
	private static final Map<String, CaravanRole> COMBAT_TO_ROLE = Map.ofEntries(
			Map.entry("SETTLER", SETTLER), Map.entry("ADMINISTRATOR", SETTLER),
			Map.entry("WORKER", WORKER),
			Map.entry("RECON", EXPLORER),
			Map.entry("HUNTER", HUNTER), Map.entry("ANIMAL", HUNTER),
			Map.entry("HEALTH_CARE", HEALER),
			Map.entry("MISSIONARY", MISSIONARY),
			Map.entry("TRADE", TRADE),
			Map.entry("SPY", COVERT), Map.entry("CRIMINAL", COVERT),
			Map.entry("RUFFIAN", COVERT), Map.entry("LAW_ENFORCEMENT", COVERT),
			// military
			Map.entry("MELEE", MILITARY), Map.entry("MOUNTED", MILITARY),
			Map.entry("SIEGE", MILITARY), Map.entry("GUN", MILITARY),
			Map.entry("ARCHER", MILITARY), Map.entry("THROWING", MILITARY),
			Map.entry("HERO", MILITARY), Map.entry("CAPTAIN", MILITARY),
			Map.entry("ROBOT", MILITARY), Map.entry("HITECH", MILITARY),
			Map.entry("DOOM", MILITARY), Map.entry("STRIKE_TEAM", MILITARY),
			Map.entry("TRACKED", MILITARY), Map.entry("WHEELED", MILITARY),
			Map.entry("ROCKET_LAUNCHER", MILITARY), Map.entry("DREADNOUGHT", MILITARY),
			Map.entry("CLONES", MILITARY), Map.entry("NANITE", MILITARY),
			Map.entry("MISSILE", MILITARY), Map.entry("ASSAULT_MECH", MILITARY),
			Map.entry("HOVERCRAFT", MILITARY), Map.entry("HELICOPTER", MILITARY),
			Map.entry("BALLOON", MILITARY), Map.entry("BOMBERS", MILITARY));

	// The <DefaultUnitAI> (sans UNITAI_) -> role fallback, used when <Combat> is absent or
	// maps to no role (the non-role great-people/exec classes). Military and property-control
	// AIs fold to MILITARY; the great-people AIs fold to nearest (GENERAL->MILITARY,
	// ENGINEER->WORKER), the rest defaulting to MILITARY below.
	private static final Map<String, CaravanRole> UNITAI_TO_ROLE = Map.ofEntries(
			Map.entry("SETTLE", SETTLER),
			Map.entry("WORKER", WORKER), Map.entry("ENGINEER", WORKER),
			Map.entry("EXPLORE", EXPLORER),
			Map.entry("MERCHANT", TRADE),
			Map.entry("MISSIONARY", MISSIONARY), Map.entry("PROPHET", MISSIONARY),
			Map.entry("HUNTER", HUNTER), Map.entry("GREAT_HUNTER", HUNTER),
			Map.entry("HEALER", HEALER),
			Map.entry("SPY", COVERT), Map.entry("INFILTRATOR", COVERT));

	// The <Combat> -> signature-Skill fold for the three NON-role combat classes — the
	// analogue of a role's signatureSkill for units the role fold doesn't cover. A role-mapped
	// class derives its skill from the role (fromUnit(...).signatureSkill()); these three do
	// not correspond to a role at all (owner rulings, docs/c2c-unit-import.md).
	private static final Map<String, Skill> NON_ROLE_COMBAT_SKILL = Map.of(
			"EXECUTIVE", Skill.PRODUCTION,   // C2C corporation execs — industry, not trade
			"ENTERTAINER", Skill.SOCIAL,     // great artists — appeal/leadership
			"PRODIGY", Skill.INTELLECTUAL);  // genius/scholar — science

	/**
	 * Fold a unit's {@code <Combat>} (UnitCombat class) — with {@code <DefaultUnitAI>} as the
	 * fallback — onto a {@code CaravanRole}. Keys off the combat class primarily (the
	 * strongest signal), falling back to the AI when the combat class is absent or maps to no
	 * role (the non-role exec/great-people classes), and finally to {@link #MILITARY} (most
	 * unmapped combat classes are military variants). Both raw values ride each
	 * {@code units.json} row, so this fold can be refined later without a re-bake.
	 *
	 * @param combat
	 *            the unit's {@code <Combat>} value ({@code UNITCOMBAT_*}), or {@code null}
	 * @param defaultUnitAI
	 *            the unit's {@code <DefaultUnitAI>} value ({@code UNITAI_*}), or {@code null}
	 * @return the folded role (never {@code null})
	 */
	public static CaravanRole fromUnit(String combat, String defaultUnitAI) {
		String cKey = strip(combat, "UNITCOMBAT_");
		// NB: COMBAT_TO_ROLE / UNITAI_TO_ROLE are immutable maps, which throw on a null-key
		// get() — so guard before the lookup (a unit may carry neither <Combat> nor an AI).
		if (cKey != null) {
			CaravanRole byCombat = COMBAT_TO_ROLE.get(cKey);
			if (byCombat != null)
				return byCombat;
		}
		String aKey = strip(defaultUnitAI, "UNITAI_");
		if (aKey != null) {
			CaravanRole byAi = UNITAI_TO_ROLE.get(aKey);
			if (byAi != null)
				return byAi;
		}
		return MILITARY;
	}

	/**
	 * The signature {@link Skill} a unit's {@code <Combat>} class folds to — the role's
	 * signature skill for a role-bearing class, or one of the three non-role skills
	 * ({@link Skill#PRODUCTION}/{@link Skill#SOCIAL}/{@link Skill#INTELLECTUAL}) for the
	 * exec/entertainer/prodigy classes. This is what {@code UnitCombatExporter} stamps on each
	 * {@code unit-combats.json} row and what a band's role effectiveness will read.
	 *
	 * @param combat
	 *            the unit's {@code <Combat>} value ({@code UNITCOMBAT_*}), or {@code null}
	 * @param defaultUnitAI
	 *            the {@code <DefaultUnitAI>} fallback ({@code UNITAI_*}), or {@code null}
	 * @return the folded signature skill (never {@code null})
	 */
	public static Skill signatureSkillOf(String combat, String defaultUnitAI) {
		String cKey = strip(combat, "UNITCOMBAT_");
		if (cKey != null) {
			Skill nonRole = NON_ROLE_COMBAT_SKILL.get(cKey); // immutable map — guard null key
			if (nonRole != null)
				return nonRole;
		}
		return fromUnit(combat, defaultUnitAI).signatureSkill();
	}

	// strip a known prefix from a C2C token (null-safe), returning the bare class name
	private static String strip(String token, String prefix) {
		if (token == null)
			return null;
		return token.startsWith(prefix) ? token.substring(prefix.length()) : token;
	}
}
