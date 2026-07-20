package com.civstudio.era;

import java.util.Optional;

import com.civstudio.settlement.GameSession;
import com.civstudio.simulation.SimulationConfig.CFirmInit;
import com.civstudio.simulation.SimulationConfig.FirmInit;
import com.civstudio.simulation.SimulationConfig.LaborerInit;
import com.civstudio.simulation.SimulationConfig.PriceRange;
import com.civstudio.tech.ResearchState;
import com.civstudio.tech.TechTree;
import com.civstudio.race.Race;

import lombok.Builder;

/**
 * The technological eras a colony advances through — the single, canonical era
 * ladder, bridging the two data files keyed on it: {@code eras.json} (by {@code
 * eraId}, equal to the ordinal) and {@code techs.json} (by the {@code C2C_ERA_*}
 * string, via {@link #fromTechKey}). It is also the home of the per-era data:
 * <ul>
 * <li>an {@link Economy} bundling the run-level economic tuning that {@code
 *     SimulationConfig.DEFAULT} seeds from (only {@link #MEDIEVAL} is calibrated;
 *     the rest are {@code null} pending calibration);</li>
 * <li>{@link EraModifiers} — the Civ-style per-era percentage modifiers from the
 *     prototype {@code eras.json} (growth, research, build, …). Only {@code
 *     researchPercent} is wired today (it scales {@linkplain
 *     ResearchState research} cost per era); the rest are carried for
 *     future mechanics. Defined for {@link #PREHISTORIC} through {@link #RENAISSANCE}
 *     (the modeled span); {@code null} beyond.</li>
 * </ul>
 * The {@link GameSession} owns a single {@code Era} — the era every
 * colony in the session founds in (Medieval by default) — and the tech tree's scope
 * ceiling is {@link #RENAISSANCE} (see {@link TechTree}). Era-advancement
 * during a run is future work; for now {@code Era} is the static home of these
 * per-era values.
 */
public enum Era {

	PREHISTORIC(0, null, new EraModifiers(150, 100, 100, 100, 150, 140, 100, 230, 40)),
	ANCIENT(1, null, new EraModifiers(140, 100, 100, 100, 150, 130, 100, 220, 80)),
	CLASSICAL(2, null, new EraModifiers(130, 100, 100, 100, 200, 120, 100, 210, 80)),
	MEDIEVAL(3, new Economy(
			new PriceRange(0.1, 5),                // ePrice
			new PriceRange(0.1, 5),                // nPrice
			new FirmInit(100, -1000, 40, 100, 30), // eFirm
			new FirmInit(100, -1000, 50, 100, 30), // nFirm
			new CFirmInit(500, 500, 0),            // cFirm
			new LaborerInit(0, 0, 100, 0.9),       // laborer
			26,                                    // targetNStock
			0,                                     // externalInflowPerStep (closed)
			100,                                   // immigrationThreshold
			0.5,                                   // laborShare
			0.05,                                  // bankProfitTaxRate
			0.02,                                  // nobleIncomeTaxRate
			900,                                   // retinueSize
			0.45,                                  // promotionRatio
			5),                                    // targetNobles
			new EraModifiers(120, 100, 100, 100, 250, 110, 100, 200, 80)),
	RENAISSANCE(4, null, new EraModifiers(110, 100, 100, 100, 300, 100, 100, 190, 80)),
	INDUSTRIAL(5, null, null),
	ATOMIC(6, null, null),
	INFORMATION(7, null, null),
	NANOTECH(8, null, null),
	TRANSHUMAN(9, null, null);

	// the C2C era-key prefix used in techs.json (the suffix is the constant name)
	private static final String TECH_KEY_PREFIX = "C2C_ERA_";

	private final int eraId;
	private final Economy economy;
	private final EraModifiers modifiers;

	Era(int eraId, Economy economy, EraModifiers modifiers) {
		this.eraId = eraId;
		this.economy = economy;
		this.modifiers = modifiers;
	}

	/** The stable numeric id this era is keyed on in {@code eras.json}. */
	public int eraId() {
		return eraId;
	}

	/**
	 * The economic tuning for this era as the <b>human</b> race founds it — equivalent to
	 * {@link #economy(Race) economy(Race.HUMAN)}. {@code null} if the era is not yet calibrated
	 * (currently only {@link #MEDIEVAL} is).
	 */
	public Economy economy() {
		return economy(Race.HUMAN);
	}

	/**
	 * The economic tuning for this era <b>as {@code race} founds it</b>.
	 * <p>
	 * Economy is a <b>two-axis authored matrix</b>: an era sets the technological/commercial epoch, a
	 * race sets who is living through it. The values on the constants above are the <em>human</em>
	 * column — they were authored before race was a lever and read as universal, which they are not:
	 * a race that matures at 9 or lives for centuries ({@link Race#minInitAgeYears()},
	 * {@link Race#lifeTable()}) does not plausibly share humanity's pool size, promotion ratio or
	 * savings behaviour.
	 * <p>
	 * Only {@code (MEDIEVAL, HUMAN)} is authored today, so every race falls back to the human column —
	 * the same shape race already takes everywhere else in the engine: its own name tables and life
	 * table where they exist, the human liturgical calendar and tech overlay where they do not
	 * (see {@code docs/race.md}). Per-race economies are the next lever to be authored, and they will
	 * arrive as content rather than as constants here.
	 *
	 * @param race the founding race; {@code null} is read as {@link Race#HUMAN}
	 * @return the tuning to found with, or {@code null} for an uncalibrated era
	 */
	public Economy economy(Race race) {
		// authored content wins; the compiled constant is the floor under it. Until the matrix is
		// seeded, find() returns null for every cell and this is exactly the old behaviour.
		Economy authored = EconomyCatalog.get().find(this, race);
		return authored != null ? authored : economy;
	}

	/**
	 * This era's economy <b>as compiled in</b>, ignoring the authored {@link EconomyCatalog} — the
	 * human column, and the source the catalog's seed content is generated from. Use
	 * {@link #economy(Race)} to found a colony; this is for producing and checking content, not for
	 * running on.
	 *
	 * @return the compiled economy, or {@code null} for an uncalibrated era
	 */
	Economy compiledEconomy() {
		return economy;
	}

	/**
	 * The era's Civ-style percentage modifiers (research/growth/build/…), or
	 * {@code null} beyond the modeled span ({@link #INDUSTRIAL} and later).
	 */
	public EraModifiers modifiers() {
		return modifiers;
	}

	/**
	 * The era with the given {@code eraId}.
	 *
	 * @throws IllegalArgumentException if no era has that id
	 */
	public static Era fromId(int eraId) {
		for (Era era : values())
			if (era.eraId == eraId)
				return era;
		throw new IllegalArgumentException("no era with eraId " + eraId);
	}

	/**
	 * Resolve a raw {@code techs.json} era key (e.g. {@code "C2C_ERA_MEDIEVAL"}) to
	 * an {@link Era}, or {@link Optional#empty()} if the key is malformed or names no
	 * era. The key is {@value #TECH_KEY_PREFIX} followed by the constant name.
	 *
	 * @param key
	 *            the raw era key from {@code techs.json}
	 * @return the matching era, or empty
	 */
	public static Optional<Era> fromTechKey(String key) {
		if (key == null || !key.startsWith(TECH_KEY_PREFIX))
			return Optional.empty();
		try {
			return Optional.of(valueOf(key.substring(TECH_KEY_PREFIX.length())));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/**
	 * Whether this era is no later than {@code other} (chronological order, by
	 * {@link #ordinal()}).
	 *
	 * @param other
	 *            the era to compare against
	 * @return true if this era is at or before {@code other}
	 */
	public boolean isAtOrBefore(Era other) {
		return ordinal() <= other.ordinal();
	}

	/**
	 * The era just below this one (one lower {@link #eraId()}), or this era itself if
	 * it is the lowest ({@link #PREHISTORIC}). A colony founding <em>in</em> an era
	 * knows every tech up to the era below it (and researches this era's frontier).
	 *
	 * @return the preceding era, or this era if already the lowest
	 */
	public Era below() {
		return ordinal() == 0 ? this : values()[ordinal() - 1];
	}

	/**
	 * The economic parameters that vary by era — the run-level economic tuning
	 * previously hard-wired into {@code SimulationConfig.DEFAULT}. Immutable;
	 * reuses {@code SimulationConfig}'s value records for the structured fields.
	 *
	 * @param ePrice               initial price bounds for the enjoyment market
	 * @param nPrice               initial price bounds for the necessity market
	 * @param eFirm                initial state of each enjoyment firm
	 * @param nFirm                initial state of each necessity firm
	 * @param cFirm                initial state of the capital firm
	 * @param laborer              initial state of each laborer
	 * @param targetNStock         target necessity stock every laborer accumulates
	 * @param externalInflowPerStep money entering the colony from outside each step
	 *                             (0 leaves the colony closed)
	 * @param immigrationThreshold equity level at which the bank funds one immigrant
	 * @param laborShare           fraction of revenue each consumer-good firm budgets
	 *                             for wages
	 * @param bankProfitTaxRate    fraction of each bank's distributable profit the
	 *                             Ruler taxes each step
	 * @param nobleIncomeTaxRate   fraction of each noble's per-step income the Ruler
	 *                             taxes each step
	 * @param retinueSize      number of peasants a pooled colony is seeded with
	 * @param promotionRatio       fraction of the pool promoted into laborer households
	 * @param targetNobles         size of the aristocracy maintained by ennoblement
	 */
	@Builder(toBuilder = true)
	public record Economy(
			PriceRange ePrice,
			PriceRange nPrice,
			FirmInit eFirm,
			FirmInit nFirm,
			CFirmInit cFirm,
			LaborerInit laborer,
			double targetNStock,
			double externalInflowPerStep,
			double immigrationThreshold,
			double laborShare,
			double bankProfitTaxRate,
			double nobleIncomeTaxRate,
			int retinueSize,
			double promotionRatio,
			int targetNobles) {
	}

	/**
	 * The Civ-style per-era percentage modifiers carried over from the prototype
	 * {@code eras.json} — each a percent (100 = unmodified). Most are <b>forward-looking</b>:
	 * eos wires only {@link #researchPercent()} today (it scales research cost per era,
	 * see {@link ResearchState}); the rest are carried until their mechanics
	 * exist (e.g. {@link #buildPercent()} for the builder, {@link #growthPercent()} for
	 * population growth). The fields mirror the prototype's: only {@code growth},
	 * {@code research}, {@code build} and {@code gp} actually vary across the modeled
	 * eras; the others are a flat 100.
	 *
	 * @param growthPercent    population/growth cost modifier
	 * @param trainPercent     unit-training cost modifier (no eos mechanic yet)
	 * @param constructPercent building-construction cost modifier (no eos mechanic yet)
	 * @param createPercent    wonder/project creation cost modifier (no eos mechanic yet)
	 * @param researchPercent  <b>research cost modifier</b> — the per-era scaling on a
	 *                         tech's cost (150 at Prehistoric rising to 300 at Renaissance)
	 * @param buildPercent     worker/builder output cost modifier
	 * @param improvePercent   tile-improvement cost modifier (no eos mechanic yet)
	 * @param gpPercent        great-person cost modifier (no eos mechanic yet)
	 * @param anarchyPercent   anarchy/revolt length modifier (no eos mechanic yet)
	 */
	@Builder(toBuilder = true)
	public record EraModifiers(
			int growthPercent,
			int trainPercent,
			int constructPercent,
			int createPercent,
			int researchPercent,
			int buildPercent,
			int improvePercent,
			int gpPercent,
			int anarchyPercent) {
	}
}
